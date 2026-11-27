// Lõi của cầu nối: máy trạng thái phiên, cầu dao phía cầu nối, nối lại có backoff, thêm MỘT người vào MỘT nhóm.
// Việc được làm: nghe sự kiện kết bạn, tra tên và ảnh của MỘT số điện thoại để xác nhận mẹ (D19), thêm MỘT người vào MỘT nhóm, và (từ D18) gửi MỘT tin văn bản cho MỘT khách đã mua. Từ 23/11/2026 (chủ shop
// chốt "tài khoản phụ chủ động kết bạn") còn gửi MỘT lời mời kết bạn cho MỘT người ĐÃ nhận Tin 1. Không có liệt kê bạn bè, không nhận lời mời, không gửi hàng loạt (ADR-0002 mục 3.1, 4).
// Không bao giờ ghi uid, số điện thoại, tên vào log.

import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { InboxBuffer } from './events.mjs';
import { AddLedger, MessageLedger, ZALO_ID, inWindow, vnDayStart } from './guard.mjs';
import { classifyError, classifyResult, isSessionDead } from './classify.mjs';

const CLOSE_DUPLICATE = 3000; // cùng tài khoản mở Zalo Web/PC nơi khác: kết nối nghe bị đá
const CLOSE_KICK = 3003;
const MAX_STARTS = 5; // quá 5 lần khởi động trong 10 phút là vòng sập-bật lại
const START_WINDOW_MS = 10 * 60 * 1000;
const MAX_GROUP_NAME = 100;
const MAX_GROUP_WELCOME = 1500;
const MAX_GROUP_MEMBERS = 5;
/** Số lần thử lại đặt phó nhóm tối đa mỗi ngày (nút "Thử lại phó nhóm"). */
const MAX_DEPUTY_TRIES_PER_DAY = 10;
const REPLY_WINDOW_MS = 24 * 3600 * 1000; // WELCOME và REPLY chỉ trả lời người vừa nhắn trong khoảng này
const MAX_REPLYABLE = 500;
const MAX_FRIEND_TEXT = 150; // lời mời kết bạn của Zalo chỉ nhận câu ngắn
const FRIEND_REQUEST_READ_GAP_MS = 3000;
const MAX_PARTS = 4; // Tin 1 tối đa bấy nhiêu tin ngắn gửi liền (phần dư gộp vào phần cuối)
const MAX_TEXT = 1000; // ký tự; tin mời của shop chỉ vài trăm ký tự

/** Tách Tin 1 thành các tin ngắn theo dòng (bỏ dòng trống); quá MAX_PARTS thì gộp phần dư vào phần cuối. Không có xuống dòng thì là một tin. */
function splitParts(text) {
  const lines = String(text).split(/\r?\n/).map((l) => l.trim()).filter((l) => l.length > 0);
  if (lines.length === 0) return [String(text)];
  if (lines.length <= MAX_PARTS) return lines;
  return [...lines.slice(0, MAX_PARTS - 1), lines.slice(MAX_PARTS - 1).join('\n')];
}

function withTimeout(promise, ms) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      const e = new Error('hết giờ chờ');
      e.name = 'TimeoutError';
      reject(e);
    }, ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** Tệp trạng thái nhỏ (không có dữ liệu cá nhân): cầu dao nhảy thì khởi động lại KHÔNG được xóa. */
class StateFile {
  constructor(file) {
    this.file = file;
  }

  read() {
    try {
      return existsSync(this.file) ? JSON.parse(readFileSync(this.file, 'utf8')) : {};
    } catch {
      return { tripped: { reason: 'STATE_CORRUPT', at: new Date().toISOString() } };
    }
  }

  write(o) {
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(o), { mode: 0o600 });
    renameSync(tmp, this.file);
  }
}

/** Lỗi thường (hạn mức, khung giờ, lỗi lạ): vẫn nhận tin được. Lỗi phiên, tài khoản, Zalo hạn chế, dừng tay hay sổ hỏng: đóng hẳn. */
export function isSoftTrip(reason) {
  return typeof reason === 'string' && reason !== '' && !/^(STOP|SESSION_LOST|RESTRICTED|ACCOUNT_MISMATCH|DUPLICATE_CONNECTION|KICKED|RECONNECT_FAILED|LEDGER_CORRUPT|STATE_CORRUPT|CRASH_LOOP|INTERRUPTED)(:|$)/.test(reason);
}

export class Bridge {
  constructor({ config, adapter, store, ledger, msgLedger = null, friendLedger = null, undoLedger = null, acceptLedger = null, groupLedger = null, events, inbox = null, now = () => Date.now(), log = () => {} }) {
    this.config = config;
    this.adapter = adapter;
    this.store = store;
    this.ledger = ledger;
    this.msgLedger = msgLedger ?? new MessageLedger(null, Buffer.alloc(32));
    this.friendLedger = friendLedger ?? new MessageLedger(null, Buffer.alloc(32));
    this.undoLedger = undoLedger ?? new MessageLedger(null, Buffer.alloc(32)); // lời mời đã thu hồi (băm), giờ các lần thu hồi
    this.acceptLedger = acceptLedger ?? new MessageLedger(null, Buffer.alloc(32)); // lời mời đã nhận (băm), giờ các lần nhận
    this.incoming = new Map(); // mã Zalo -> giờ: người ĐÃ nhận Tin 1 vừa tự gửi lời mời kết bạn tới (chỉ trong bộ nhớ, không log)
    this.groupLedger = groupLedger ?? new AddLedger(null);
    this.events = events;
    this.inbox = inbox ?? new InboxBuffer({ now: () => this.now() });
    this.lastFriend = null; // { uid, at } của lời mời kết bạn gần nhất
    this.lastInvite = null; // { uid, at } của Tin 1 gần nhất (chỉ trong bộ nối)
    this.replyable = new Map(); // mã Zalo -> giờ tin riêng gần nhất (chỉ trong bộ nhớ); căn cứ duy nhất cho phép trả lời (WELCOME, REPLY)
    this.now = now;
    this.log = log;
    this.stateFile = new StateFile(path.join(config.dataDir, 'bridge-state.json'));
    this.state = 'LOGIN_REQUIRED';
    this.tripReason = null;
    this.accountOk = null;
    this.lastEventAt = null;
    this.sessionSince = null;
    this.detail = null;
    this.attempts = 0;
    this.timer = null;
    this.busy = false;
    this.reactable = new Map(); // seq tin đến -> mã tin Zalo (chỉ trong bộ nhớ), căn cứ duy nhất để thả tim
    this.reactTimes = []; // giờ các lần thả tim (trong bộ nhớ) để đếm trần ngày và khoảng nghỉ cố định
    this.friendSnapshot = null; // bản danh sách bạn đã đọc gần nhất (chỉ trong bộ nhớ, không ghi đĩa, không ghi log)
    this.friendReads = { day: null, n: 0 };
    this.friendRequestReads = { day: null, n: 0, lastAt: null };
    this.login = { state: 'IDLE', qr: null };
    this.loginHandle = null;
    this.generation = 0; // tăng mỗi lần dừng/đổi phiên để bỏ qua sự kiện của kết nối cũ
    this.listenSeq = 0; // mã của lần đăng ký nghe; activeListen khác 0 nghĩa là đang NHẬN tin (nhận không phải gửi: cầu dao chỉ chặn gửi)
    this.activeListen = 0;
    this.readOnlyConnected = false;
    this.rxAttempts = 0;
    this.rxBusy = false;
  }

  // ---------- khởi động ----------

  async start() {
    if (this.ledger.corrupt) {
      this.trip('LEDGER_CORRUPT');
      return;
    }
    if (this.msgLedger.corrupt || this.groupLedger.corrupt) {
      this.trip('LEDGER_CORRUPT');
      return;
    }
    if (this.recordStart() > MAX_STARTS) {
      // Khởi động lại liên tục (sập rồi Docker bật lại) sẽ đăng nhập Zalo lặp lại: dừng, chờ người xem.
      this.trip('CRASH_LOOP');
      return;
    }
    const saved = this.stateFile.read();
    if (saved.tripped) {
      // cầu dao đã nhảy trước khi khởi động lại: giữ nguyên, chỉ người bấm "Mở lại" mới thoát
      this.tripReason = saved.tripped.reason;
      this.state = saved.tripped.reason === 'STOP' ? 'STOPPED' : 'TRIPPED';
      this.startReceiveOnly(); // vẫn NHẬN tin riêng của khách (không gửi gì), để cầu dao hay khởi động lại không làm mất tin
      return;
    }
    if (saved.inflight) {
      // Sập khi đang thêm người: không biết Zalo đã nhận hay chưa, nên dừng thay vì đoán (thất bại kín).
      this.trip('INTERRUPTED');
      return;
    }
    const { session, corrupt } = this.store.load();
    if (corrupt) {
      this.state = 'LOGIN_REQUIRED';
      this.detail = 'SESSION_CORRUPT';
      return;
    }
    if (!session) {
      this.state = 'LOGIN_REQUIRED';
      return;
    }
    await this.connect(session);
  }

  /** Ghi lần khởi động (chỉ thời điểm) và trả số lần trong cửa sổ gần nhất. */
  recordStart() {
    const file = path.join(this.config.dataDir, 'starts.json');
    const t = this.now();
    let times = [];
    try {
      if (existsSync(file)) times = JSON.parse(readFileSync(file, 'utf8')).filter((x) => Number.isFinite(x));
    } catch {
      times = [];
    }
    times = times.filter((x) => x >= t - START_WINDOW_MS);
    times.push(t);
    try {
      const tmp = `${file}.tmp`;
      writeFileSync(tmp, JSON.stringify(times), { mode: 0o600 });
      renameSync(tmp, file);
    } catch {
      return Number.POSITIVE_INFINITY; // không ghi được thì không đếm được: dừng
    }
    return times.length;
  }

  // ---------- kết nối ----------

  async connect(session, { loggedIn = false, ownId = null } = {}) {
    const gen = ++this.generation;
    this.stopListen();
    this.state = 'CONNECTING';
    try {
      if (!loggedIn) {
        ({ ownId } = await this.adapter.restore(session));
      }
    } catch (e) {
      if (gen !== this.generation) return false;
      if (isSessionDead(e)) {
        this.state = 'LOGIN_REQUIRED';
        this.detail = 'SESSION_EXPIRED';
        this.log('phiên hết hạn');
        return false;
      }
      this.scheduleReconnect();
      return false;
    }
    if (gen !== this.generation) return false;
    if (this.config.mode === 'zca' && String(ownId) !== this.config.accountId) {
      // không phải tài khoản phụ đã khai báo: từ chối và đăng xuất (ADR-0002 mục 3.6)
      this.accountOk = false;
      this.detail = 'ACCOUNT_MISMATCH';
      await this.dropSession();
      this.log('sai tài khoản');
      return false;
    }
    this.accountOk = true;
    this.detail = null;
    const lg = ++this.listenSeq;
    this.activeListen = lg;
    this.rxAttempts = 0;
    const handlers = {
      onConnected: () => this.onConnected(gen),
      onFriendAdd: (uid) => this.onFriendAdd(gen, uid),
      onFriendRequest: (uid) => this.onFriendRequest(gen, uid),
      onDisconnected: (code) => this.onDisconnected(gen, code, lg),
    };
    // Chỉ đăng ký nghe tin nhắn khi bật cờ riêng (tắt mặc định).
    if (this.config.inboxEnabled) handlers.onMessage = (uid, text, ref) => this.onMessage(lg, uid, text, ref);
    this.adapter.listen(handlers);
    return true;
  }

  onConnected(gen) {
    if (gen !== this.generation) return;
    this.state = 'LISTENING';
    this.attempts = 0;
    this.sessionSince = new Date(this.now()).toISOString();
    this.log('đang nghe');
  }

  /**
   * Người khác gửi lời mời kết bạn tới tài khoản phụ. Chỉ nhớ MÃ của người đã nhận Tin 1 từ chính cầu nối này (khách của hệ thống); người lạ bỏ ngay, không lưu,
   * không log. Không tự nhận ở đây: việc nhận do Adh yêu cầu qua {@link Bridge#acceptFriendRequest} với đủ chốt chặn.
   */
  onFriendRequest(gen, uid) {
    if (gen !== this.generation || !ZALO_ID.test(String(uid))) return;
    const id = String(uid);
    if (!this.msgLedger.has('INVITE', id) && !this.msgLedger.has('INVITE_BLOCKED', id)) return;
    if (this.acceptLedger.has('ACCEPT', id)) return;
    if (this.incoming.size >= 500 && !this.incoming.has(id)) return;
    this.incoming.set(id, this.now());
    this.lastEventAt = new Date(this.now()).toISOString();
  }

  /** Mã Zalo của khách đang chờ được nhận lời mời (chỉ người đã nhận Tin 1 từ cầu nối này, chưa được nhận). */
  incomingRequests() {
    return [...this.incoming.keys()];
  }

  async onFriendAdd(gen, uid) {
    if (gen !== this.generation || !ZALO_ID.test(String(uid))) return;
    this.lastEventAt = new Date(this.now()).toISOString();
    let phone = null;
    const t = this.now();
    this.lookups = (this.lookups ?? []).filter((x) => x > t - 3600000);
    if (this.lookups.length >= this.config.maxLookupsPerHour) {
      this.events.push({ uid: String(uid), phone: null });
      this.log('sự kiện kết bạn (không hỏi số: quá hạn mức giờ)');
      return;
    }
    this.lookups.push(t);
    try {
      // Chỉ hỏi số của ĐÚNG người vừa thành bạn, không dò ai khác.
      const raw = await this.adapter.getPhone(uid);
      const digits = String(raw ?? '').replace(/\D/g, '');
      phone = digits.length >= 8 ? digits : null;
    } catch (e) {
      const c = classifyError(e, this.config.personErrorCodes);
      if (c.outcome === 'SESSION_LOST' || c.outcome === 'RESTRICTED') {
        this.trip(c.outcome);
        return;
      }
      phone = null; // không lấy được số: để Adh đưa vào hàng đợi tay
    }
    this.events.push({ uid: String(uid), phone });
    this.log('sự kiện kết bạn');
  }

  /**
   * Tin nhắn riêng (1-1) tới tài khoản. Chỉ giữ tin của người mà CHÍNH bộ nối đã gửi Tin 1 hoặc Tin 2 (sổ gửi tin); của người lạ bỏ ngay,
   * không lưu, không ghi log. Nội dung chỉ nằm trong bộ nhớ tới khi Adh lấy đi.
   */
  onMessage(lg, uid, text, ref = null) {
    if (lg !== this.activeListen || !this.config.inboxEnabled || !ZALO_ID.test(String(uid)) || typeof text !== 'string') return;
    const body = text.trim();
    if (body.length === 0) return;
    if (!this.msgLedger.has('INVITE', String(uid)) && !this.msgLedger.has('LINK', String(uid))) return;
    const seq = this.inbox.push({ uid: String(uid), text: body });
    const t = this.now();
    // Mã tin để (có thể) thả tim đúng tin này sau; chỉ trong bộ nhớ, bỏ khi quá hạn, không ghi log.
    if (ref && ZALO_ID.test(String(ref.msgId ?? '')) && ZALO_ID.test(String(ref.cliMsgId ?? ''))) {
      this.reactable.set(seq, { uid: String(uid), msgId: String(ref.msgId), cliMsgId: String(ref.cliMsgId), at: t });
      if (this.reactable.size > MAX_REPLYABLE) {
        for (const [k, v] of this.reactable) if (t - v.at > REPLY_WINDOW_MS) this.reactable.delete(k);
        while (this.reactable.size > MAX_REPLYABLE) this.reactable.delete(this.reactable.keys().next().value);
      }
    }
    this.replyable.set(String(uid), t);
    if (this.replyable.size > MAX_REPLYABLE) {
      for (const [k, v] of this.replyable) if (t - v > REPLY_WINDOW_MS) this.replyable.delete(k);
      while (this.replyable.size > MAX_REPLYABLE) this.replyable.delete(this.replyable.keys().next().value);
    }
  }

  onDisconnected(gen, code, lg = 0) {
    if (gen !== this.generation) {
      // Đang dừng gửi (cầu dao) mà đường NHẬN tin bị đứt: nối lại chỉ để nhận, không đổi trạng thái dừng.
      if (lg !== 0 && lg === this.activeListen && this.state === 'TRIPPED') {
        if (code === CLOSE_DUPLICATE || code === CLOSE_KICK) {
          this.trip(code === CLOSE_DUPLICATE ? 'DUPLICATE_CONNECTION' : 'KICKED');
          return;
        }
        this.activeListen = 0;
        this.readOnlyConnected = false;
        this.log('mất kết nối nhận tin khi đang dừng gửi');
        this.startReceiveOnly();
      }
      return;
    }
    if (code === CLOSE_DUPLICATE) {
      this.trip('DUPLICATE_CONNECTION');
      return;
    }
    if (code === CLOSE_KICK) {
      this.trip('KICKED');
      return;
    }
    // đứt bất thường (đo thật: mã 1006 sau khoảng 40 phút): nối lại bằng phiên đã lưu, có backoff, có giới hạn số lần
    this.log(`mất kết nối nghe (mã ${Number.isFinite(code) ? code : '?'})`);
    this.scheduleReconnect();
  }

  scheduleReconnect() {
    this.clearTimer();
    this.stopListen();
    if (this.attempts >= this.config.reconnectMaxAttempts) {
      this.trip('RECONNECT_FAILED');
      return;
    }
    const delays = this.config.reconnectDelaysMs;
    const delay = delays[Math.min(this.attempts, delays.length - 1)];
    this.attempts += 1;
    this.state = 'CONNECTING';
    this.timer = setTimeout(async () => {
      this.timer = null;
      if (this.state !== 'CONNECTING') return;
      const { session } = this.store.load();
      if (!session) {
        this.state = 'LOGIN_REQUIRED';
        return;
      }
      await this.connect(session);
    }, delay);
    this.timer.unref?.();
  }

  clearTimer() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  // ---------- cầu dao phía cầu nối ----------

  stopListen() {
    this.activeListen = 0;
    this.readOnlyConnected = false;
    this.adapter.stopListening();
  }

  canReconcileTrip(reason) {
    return this.config.friendReconcileEnabled && /^SYSTEM_ERROR:(TIMEOUT|NO_ACK)$/.test(reason ?? '');
  }

  canKeepReceiving(reason) {
    return (this.config.inboxEnabled && isSoftTrip(reason)) || this.canReconcileTrip(reason);
  }

  friendReconcileAvailable() {
    return Boolean(this.config.friendReconcileEnabled && this.accountOk === true && this.activeListen !== 0
      && (this.state === 'LISTENING' || (this.state === 'TRIPPED' && this.readOnlyConnected && this.canReconcileTrip(this.tripReason))));
  }

  /**
   * Chỉ NHẬN tin riêng khi đang dừng gửi (cầu dao nhảy vì lỗi thường, hoặc cầu nối khởi động lại giữa lúc dừng). Không gửi gì, không đổi trạng
   * thái TRIPPED, không đọc sự kiện kết bạn. Cần cờ nghe tin riêng và phiên còn dùng được; đứt thì thử nối lại có backoff và giới hạn số lần.
   */
  async startReceiveOnly() {
    if (this.rxBusy || this.state !== 'TRIPPED' || !this.canKeepReceiving(this.tripReason)) return;
    const gen = this.generation;
    if (this.rxAttempts >= this.config.reconnectMaxAttempts) {
      this.log('không nối lại được đường nhận tin khi đang dừng gửi');
      return;
    }
    const delays = this.config.reconnectDelaysMs;
    const delay = this.rxAttempts === 0 ? 0 : delays[Math.min(this.rxAttempts - 1, delays.length - 1)];
    this.rxAttempts += 1;
    this.rxBusy = true;
    try {
      if (delay > 0) await new Promise((r) => { const t = setTimeout(r, delay); t.unref?.(); });
      if (gen !== this.generation || this.state !== 'TRIPPED') return;
      const { session, corrupt } = this.store.load();
      if (!session || corrupt) return;
      let ownId;
      try {
        ({ ownId } = await this.adapter.restore(session));
      } catch (e) {
        if (gen !== this.generation || isSessionDead(e)) return;
        this.rxBusy = false;
        this.startReceiveOnly();
        return;
      }
      if (gen !== this.generation || this.state !== 'TRIPPED') {
        this.adapter.stopListening();
        return;
      }
      if (this.config.mode === 'zca' && String(ownId) !== this.config.accountId) {
        this.accountOk = false;
        this.trip('ACCOUNT_MISMATCH');
        return; // sai tài khoản: không nghe (người dùng xử ở trang quản trị)
      }
      this.accountOk = true;
      const lg = ++this.listenSeq;
      this.activeListen = lg;
      this.adapter.listen({
        onConnected: () => {
          if (lg !== this.activeListen || this.state !== 'TRIPPED') return;
          this.readOnlyConnected = true;
          this.rxAttempts = 0;
          this.log('đang nhận khi dừng gửi');
        },
        onFriendAdd: () => {},
        onMessage: (uid, text) => this.onMessage(lg, uid, text),
        onDisconnected: (code) => this.onDisconnected(-1, code, lg), // -1 khác generation hiện tại: nối lại chỉ để nhận
      });
    } finally {
      this.rxBusy = false;
    }
  }

  /** Dừng ngay: đóng kết nối nghe, ngừng mọi thao tác. KHÔNG tự thử lại; chỉ resume() từ trang quản trị mới thoát. */
  trip(reason) {
    this.generation += 1;
    this.clearTimer();
    // Cầu dao chỉ chặn GỬI. Lỗi thường (hạn mức, khung giờ, lỗi lạ) thì giữ đường NHẬN tin riêng; lỗi liên quan phiên/tài khoản/Zalo hạn chế thì đóng hẳn.
    if (this.activeListen !== 0 && this.canKeepReceiving(reason)) {
      this.readOnlyConnected ||= this.state === 'LISTENING';
    } else {
      this.stopListen();
    }
    this.tripReason = reason;
    this.state = reason === 'STOP' ? 'STOPPED' : 'TRIPPED';
    this.stateFile.write({ tripped: { reason, at: new Date(this.now()).toISOString() } });
    this.log(`dừng: ${reason}`);
  }

  async resume() {
    if (this.state === 'LISTENING' || this.state === 'CONNECTING') return true;
    if (this.state !== 'TRIPPED' && this.state !== 'STOPPED') return false;
    if (this.ledger.corrupt) return false;
    this.stateFile.write({});
    this.tripReason = null;
    const { session, corrupt } = this.store.load();
    if (!session || corrupt) {
      this.state = 'LOGIN_REQUIRED';
      this.detail = corrupt ? 'SESSION_CORRUPT' : null;
      return false;
    }
    this.attempts = 0;
    return this.connect(session);
  }

  stop() {
    this.trip('STOP');
    return true;
  }

  async dropSession() {
    this.generation += 1;
    this.clearTimer();
    this.stopListen();
    try {
      await this.adapter.logout();
    } catch {
      // đăng xuất phía Zalo thất bại thì vẫn xóa phiên ở đây
    }
    this.store.clear();
    this.state = 'LOGIN_REQUIRED';
    this.sessionSince = null;
  }

  async logout() {
    this.accountOk = null;
    this.detail = null;
    this.stateFile.write({});
    this.tripReason = null;
    await this.dropSession();
    return true;
  }

  // ---------- việc thứ ba: thêm MỘT người vào MỘT nhóm ----------

  async addMember(groupId, uid) {
    if (!ZALO_ID.test(String(groupId)) || !ZALO_ID.test(String(uid))) return { outcome: 'PERSON_ERROR', code: 'BAD_ID' };
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) {
      this.trip('WINDOW');
      return { outcome: 'RESTRICTED', code: 'WINDOW' };
    }
    if (this.ledger.countSince(vnDayStart(t)) >= this.config.hardDaily) {
      this.trip('LIMIT');
      return { outcome: 'RESTRICTED', code: 'LIMIT' };
    }
    this.busy = true;
    try {
      this.ledger.record(t); // ghi trước khi gọi, tính cả lần lỗi
      this.stateFile.write({ inflight: true }); // sập giữa chừng thì lần khởi động sau biết mà dừng
      let c;
      try {
        const result = await withTimeout(this.adapter.addUserToGroup(String(uid), String(groupId)), this.config.addTimeoutMs);
        c = classifyResult(result, uid, this.config.groupErrorCodes);
      } catch (e) {
        c = classifyError(e, this.config.personErrorCodes, this.config.groupErrorCodes);
      }
      if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR' && c.outcome !== 'GROUP_ERROR') {
        this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
      } else {
        this.stateFile.write({});
      }
      this.log(`thêm người: ${c.outcome}`);
      return c.code ? { outcome: c.outcome, code: c.code } : { outcome: c.outcome };
    } finally {
      this.busy = false;
    }
  }

  // ---------- việc thứ tư (D18): gửi MỘT tin văn bản cho MỘT khách đã mua ----------

  /**
   * Gửi đúng MỘT tin văn bản cho MỘT người, theo số điện thoại (tin mời đầu) hoặc theo mã Zalo (tin gửi link sau khi thành bạn).
   * Chốt chặn, tất cả công khai và cố định, không ngẫu nhiên hóa: khung giờ, trần mỗi ngày (vượt thì dừng cầu dao), khoảng nghỉ tối
   * thiểu CỐ ĐỊNH giữa hai tin (gửi sớm hơn thì chỉ bị từ chối RATE_LIMITED, không dừng), mỗi loại tin một lần cho một người, một lúc
   * một việc, lỗi lạ thì dừng. Văn bản, số điện thoại, mã Zalo không vào log.
   * Loại WELCOME (D19, khách từng từ chối nhắn lại) và REPLY (D20, câu trả lời do trợ lý AI soạn, Adh đã kiểm cứng): chỉ theo mã Zalo, chỉ cho người VỪA tự nhắn tới (trong 24 giờ), MỖI tin đến chỉ được trả lời
   * MỘT lần, và cần bật cờ đọc tin riêng. Không có đường nào để nhắn người chưa nhắn trước. Vẫn chịu trần ngày, khoảng nghỉ và cầu dao chung.
   * Loại FOLLOWUP2 và FOLLOWUP3 (D21, tin nhắc của chiến dịch nhiều lần gửi): chỉ theo mã Zalo, chỉ cho người ĐÃ nhận Tin 1 qua chính cầu nối này
   * (sổ có INVITE), FOLLOWUP3 còn cần FOLLOWUP2 đã gửi; mỗi loại một lần cho một người; người đang nhắn tới (trong 24 giờ, chưa được trả lời) thì không nhắc.
   * Cần bật cờ đọc tin riêng (không nghe được khách xin ngừng thì không nhắc). Vẫn chịu khung giờ, trần ngày, khoảng nghỉ và cầu dao chung; không có đường nào nhắn người chưa từng nhận Tin 1.
   * Loại ASK_FRIEND (25/11/2026, Zalo báo danh sách lời mời đầy Z223): MỘT tin nhờ người ĐÃ nhận Tin 1 chủ động kết bạn lại; chỉ theo mã Zalo, mỗi người một lần,
   * không gửi cho người đã được gửi lời mời kết bạn hoặc đang nhắn tới; cùng khung giờ, trần ngày, khoảng nghỉ và cầu dao như tin khác.
   * @param {{kind: 'INVITE'|'LINK'|'WELCOME'|'REPLY'|'FOLLOWUP2'|'FOLLOWUP3'|'ASK_FRIEND', phone?: string, uid?: string, text: string}} m
   */
  async sendMessage(m) {
    const kind = m?.kind;
    const text = typeof m?.text === 'string' ? m.text : '';
    if (!['INVITE', 'LINK', 'WELCOME', 'REPLY', 'FOLLOWUP2', 'FOLLOWUP3', 'ASK_FRIEND'].includes(kind) || text.trim().length === 0 || text.length > MAX_TEXT) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    const isReply = kind === 'WELCOME' || kind === 'REPLY'; // trả lời người vừa nhắn tới
    const isFollow = kind === 'FOLLOWUP2' || kind === 'FOLLOWUP3'; // tin nhắc cho người đã nhận Tin 1
    const isAsk = kind === 'ASK_FRIEND'; // tin nhờ người đã nhận Tin 1 chủ động kết bạn lại (lời mời kết bạn không gửi được vì Zalo báo danh sách đầy, Z223)
    const phone = m.phone === undefined || m.phone === null ? null : String(m.phone).replace(/\D/g, '');
    const uidIn = m.uid === undefined || m.uid === null ? null : String(m.uid);
    if ((phone === null) === (uidIn === null)) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' }; // đúng một trong hai
    if (phone !== null && !/^\d{8,15}$/.test(phone)) return { outcome: 'PERSON_ERROR', code: 'BAD_PHONE' };
    if (uidIn !== null && !ZALO_ID.test(uidIn)) return { outcome: 'PERSON_ERROR', code: 'BAD_ID' };
    if (isReply && (uidIn === null || !this.config.inboxEnabled)) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    if (isAsk && uidIn === null) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    if (isFollow && (uidIn === null || !this.config.inboxEnabled)) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' }; // chỉ nhắc khi đang nghe tin riêng, để nghe được khách xin ngừng
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) {
      this.trip('WINDOW');
      return { outcome: 'RESTRICTED', code: 'WINDOW' };
    }
    if (this.msgLedger.countSince(vnDayStart(t)) >= this.config.msgDaily) {
      this.trip('MSG_LIMIT');
      return { outcome: 'RESTRICTED', code: 'MSG_LIMIT' };
    }
    const last = this.msgLedger.last();
    // Gửi lại Tin 1 cho người VỪA được gửi lời mời kết bạn: cùng một người nên chỉ chờ khoảng cố định ngắn (samePersonGapMs), không phải khoảng nghỉ giữa các khách.
    const ownFriend = kind === 'INVITE' && uidIn !== null && last !== null && this.lastFriend && this.lastFriend.at === last && this.lastFriend.uid === String(uidIn);
    // Tin nhờ kết bạn lại đi ngay sau Tin 1 hoặc sau lần thử gửi lời mời (Z223) CỦA CHÍNH người đó: cũng chỉ chờ khoảng cố định ngắn.
    const ownAsk = isAsk && last !== null && ((this.lastFriend && this.lastFriend.at === last && this.lastFriend.uid === String(uidIn))
      || (this.lastInvite && this.lastInvite.at === last && this.lastInvite.uid === String(uidIn)));
    const gapMs = ownFriend || ownAsk ? Math.min(this.config.samePersonGapMs, this.config.msgGapMs)
      : isReply ? Math.min(this.config.replyGapMs, this.config.msgGapMs) : this.config.msgGapMs; // tin trả lời có khoảng nghỉ cố định ngắn hơn
    if (last !== null && t - last < gapMs) {
      return { outcome: 'RATE_LIMITED', retryAfterSec: Math.ceil((gapMs - (t - last)) / 1000) };
    }
    if (isReply) {
      // chỉ trả lời người vừa nhắn tới và chưa được trả lời cho tin đó; kiểm trước khi chạm Zalo
      const at = this.replyable.get(uidIn);
      if (at === undefined || t - at > REPLY_WINDOW_MS) return { outcome: 'PERSON_ERROR', code: 'NOT_REPLYABLE' };
    } else if (uidIn !== null && this.msgLedger.has(kind, uidIn)) return { outcome: 'PERSON_ERROR', code: 'DUPLICATE' };
    if (isFollow) {
      // chỉ nhắc người đã nhận Tin 1 từ chính cầu nối này; lần nhắc sau cần lần nhắc trước đã gửi; người đang nhắn tới thì để người thật xem
      if (!this.msgLedger.has('INVITE', uidIn)) return { outcome: 'PERSON_ERROR', code: 'NO_INVITE' };
      if (kind === 'FOLLOWUP3' && !this.msgLedger.has('FOLLOWUP2', uidIn)) return { outcome: 'PERSON_ERROR', code: 'NO_PREVIOUS' };
      const said = this.replyable.get(uidIn);
      if (said !== undefined && t - said <= REPLY_WINDOW_MS) return { outcome: 'PERSON_ERROR', code: 'CUSTOMER_REPLIED' };
    }
    if (isAsk) {
      // chỉ nhờ người đã nhận Tin 1 từ chính cầu nối này; đã được gửi lời mời kết bạn thì không nhờ nữa (lời nói sẽ sai); người đang nhắn tới thì để người thật xem
      if (!this.msgLedger.has('INVITE', uidIn)) return { outcome: 'PERSON_ERROR', code: 'NO_INVITE' };
      // lời mời đã được GỬI rồi thì không nhờ nữa (lời nói sẽ sai), trừ khi lời mời đó đã được THU HỒI (khách không đồng ý trong nhiều ngày): lúc đó nhờ khách tự kết bạn.
      if (this.friendLedger.has('FRIEND', uidIn) && !this.undoLedger.has('UNDO', uidIn)) return { outcome: 'PERSON_ERROR', code: 'FRIEND_REQUESTED' };
      const said = this.replyable.get(uidIn);
      if (said !== undefined && t - said <= REPLY_WINDOW_MS) return { outcome: 'PERSON_ERROR', code: 'CUSTOMER_REPLIED' };
    }
    this.busy = true;
    try {
      this.msgLedger.record(t); // ghi trước khi gọi, tính cả lần lỗi và lần không tìm thấy
      if (isReply) this.replyable.delete(uidIn); // mỗi tin đến chỉ được trả lời một lần, kể cả khi lần gửi lỗi
      this.stateFile.write({ inflight: true });
      let c;
      let uid = uidIn;
      try {
        if (uid === null) {
          uid = await withTimeout(this.adapter.findUid(phone), this.config.addTimeoutMs);
          if (!uid || !ZALO_ID.test(String(uid))) {
            this.stateFile.write({});
            this.log('gửi tin: không tìm thấy người nhận');
            return { outcome: 'PERSON_ERROR', code: 'NOT_FOUND' };
          }
          uid = String(uid);
          if (this.msgLedger.has(kind, uid)) {
            this.stateFile.write({});
            this.log('gửi tin: đã gửi loại tin này');
            return { outcome: 'PERSON_ERROR', code: 'DUPLICATE', uid };
          }
        }
        // Tin 1 (INVITE) có nhiều dòng thì gửi thành vài tin ngắn liền nhau cho CÙNG khách, cách nhau khoảng CỐ ĐỊNH partGapMs; đây vẫn là MỘT
        // lần Tin 1 (một dòng sổ, một lần tính hạn mức và khoảng nghỉ giữa các khách). Các loại tin khác luôn là một tin.
        if (kind === 'INVITE') this.lastInvite = { uid: String(uid), at: t }; // chỉ trong bộ nhớ: để lời mời kết bạn của chính người này đi sớm hơn
        const parts = kind === 'INVITE' ? splitParts(text) : [text];
        const result = await withTimeout(this.adapter.sendText(uid, parts[0]), this.config.addTimeoutMs);
        if (!result?.message) {
          // Zalo không xác nhận có tin: không biết đã gửi hay chưa, dừng thay vì đoán
          c = { outcome: 'SYSTEM_ERROR', code: 'NO_ACK' };
        } else {
          if (!isReply) this.msgLedger.markSent(kind, uid); // WELCOME và REPLY có thể lặp ở lần nhắn sau (Adh giữ quy tắc 30 ngày)
          c = { outcome: 'OK' };
          // Phần đầu đã tới tay khách và đã ghi sổ: các phần sau lỗi thì dừng cầu dao nhưng KHÔNG báo lỗi gửi (không bao giờ gửi lại phần đầu).
          for (let i = 1; i < parts.length; i++) {
            try {
              if (this.config.partGapMs > 0) await new Promise((r) => setTimeout(r, this.config.partGapMs));
              const more = await withTimeout(this.adapter.sendText(uid, parts[i]), this.config.addTimeoutMs);
              if (!more?.message) {
                c = { outcome: 'OK', code: 'PARTIAL', trip: 'SYSTEM_ERROR:NO_ACK' };
                break;
              }
            } catch (e) {
              const k = classifyError(e, new Set([...this.config.personErrorCodes, ...this.config.partErrorCodes]));
              c = { outcome: 'OK', code: 'PARTIAL', trip: k.outcome === 'PERSON_ERROR' ? null : (k.code ? `${k.outcome}:${k.code}` : k.outcome) };
              break;
            }
          }
        }
      } catch (e) {
        c = classifyError(e, new Set([...this.config.personErrorCodes, ...this.config.strangerBlockedCodes, ...this.config.notFoundCodes]));
        // Z212: không tìm thấy số này trên Zalo. Lỗi riêng số đó, cùng nghĩa với NOT_FOUND (Adh đã biết xử lý, không dừng).
        if (c.outcome === 'PERSON_ERROR' && /^Z\d+$/.test(c.code ?? '') && this.config.notFoundCodes.has(c.code.slice(1))) c = { outcome: 'PERSON_ERROR', code: 'NOT_FOUND' };
        // Người nhận không nhận tin của người lạ: chỉ riêng Tin 1. Nhớ lại để được gửi lời mời kết bạn (chưa từng nhận Tin 1 nên không có dấu INVITE).
        if (kind === 'INVITE' && uid !== null && c.outcome === 'PERSON_ERROR' && /^Z\d+$/.test(c.code ?? '') && this.config.strangerBlockedCodes.has(c.code.slice(1))) {
          this.msgLedger.markSent('INVITE_BLOCKED', uid);
          c = { ...c, code: 'STRANGER_BLOCKED' };
        }
      }
      if (c.trip) {
        this.trip(c.trip); // lỗi ở phần sau của Tin 1 (phần đầu đã gửi)
      } else if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR') {
        this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
      } else {
        this.stateFile.write({});
      }
      this.log(`gửi tin: ${c.outcome}`);
      const out = { outcome: c.outcome };
      if (c.code) out.code = c.code;
      if (uid) out.uid = uid;
      return out;
    } finally {
      this.busy = false;
    }
  }

  // ---------- thả tim tin khách (24/11/2026, chủ shop duyệt) ----------

  /**
   * Thả MỘT biểu tượng tim vào MỘT tin riêng mà khách vừa gửi (tin mà chính cầu nối đã nhận, qua {@code seq} của /inbox), thay vì im lặng khi
   * khách chỉ nhắn lời xác nhận kiểu "ok". Không gửi chữ, không gửi cho ai khác, chỉ thả được vào tin đến trong 24 giờ, mỗi tin một lần.
   * Chốt chặn cố định, công khai: cần bật cờ đọc tin riêng, khung giờ, cầu dao, một việc một lúc, khoảng nghỉ CỐ ĐỊNH reactGapMs giữa hai lần
   * thả tim, trần reactDaily mỗi ngày. Không ngẫu nhiên hóa. Không tính vào sổ tin gửi khách (không làm trễ tin kế tiếp), nhưng lỗi lạ vẫn dừng cầu dao.
   * @param {{seq: number}} m
   */
  async react(m) {
    const seq = Number(m?.seq);
    if (!Number.isSafeInteger(seq) || seq <= 0) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    if (!this.config.inboxEnabled) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) return { outcome: 'RESTRICTED', code: 'WINDOW' }; // không dừng cầu dao: chỉ là chưa tới giờ
    const ref = this.reactable.get(seq);
    if (!ref || t - ref.at > REPLY_WINDOW_MS) return { outcome: 'PERSON_ERROR', code: 'NOT_REACTABLE' };
    this.reactTimes = this.reactTimes.filter((x) => x >= t - 48 * 3600 * 1000);
    if (this.reactTimes.filter((x) => x >= vnDayStart(t)).length >= this.config.reactDaily) return { outcome: 'RESTRICTED', code: 'REACT_LIMIT' };
    const last = this.reactTimes.length ? Math.max(...this.reactTimes) : null;
    if (last !== null && t - last < this.config.reactGapMs) return { outcome: 'RATE_LIMITED', retryAfterSec: Math.ceil((this.config.reactGapMs - (t - last)) / 1000) };
    this.busy = true;
    try {
      this.reactTimes.push(t); // tính cả lần lỗi
      this.reactable.delete(seq); // mỗi tin chỉ thả một lần, kể cả khi lỗi
      let c;
      try {
        await withTimeout(this.adapter.addHeart(ref.uid, ref.msgId, ref.cliMsgId), this.config.addTimeoutMs);
        c = { outcome: 'OK' };
      } catch (e) {
        c = classifyError(e, this.config.personErrorCodes);
      }
      if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR') this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
      this.log(`thả tim: ${c.outcome}`);
      return c.code ? { outcome: c.outcome, code: c.code } : { outcome: c.outcome };
    } finally {
      this.busy = false;
    }
  }

  // ---------- lời mời kết bạn (chủ shop chốt 23/11/2026: tài khoản phụ chủ động kết bạn) ----------

  /**
   * Gửi MỘT lời mời kết bạn kèm câu ngắn cho MỘT người ĐÃ nhận Tin 1 từ chính cầu nối này (không bao giờ người lạ): mỗi người một lời mời.
   * Chốt chặn công khai và cố định, dùng chung với phần gửi tin: khung giờ, khoảng nghỉ cố định sau tin hoặc lời mời liền trước, phanh
   * khẩn cấp đếm mọi lần gọi, cầu dao; thêm trần lời mời mỗi ngày riêng (friendDaily). Lỗi lạ, không rõ kết quả thì dừng. Không ngẫu nhiên hóa.
   * @param {{uid: string, text: string}} m
   */
  async sendFriendRequest(m) {
    const text = typeof m?.text === 'string' ? m.text.trim() : '';
    const uid = m?.uid === undefined || m?.uid === null ? null : String(m.uid);
    if (uid === null || !ZALO_ID.test(uid) || text.length === 0 || text.length > MAX_FRIEND_TEXT) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) {
      this.trip('WINDOW');
      return { outcome: 'RESTRICTED', code: 'WINDOW' };
    }
    const day = vnDayStart(t);
    if (this.friendLedger.countSince(day) >= this.config.friendDaily) {
      this.trip('FRIEND_LIMIT');
      return { outcome: 'RESTRICTED', code: 'FRIEND_LIMIT' };
    }
    if (this.msgLedger.countSince(day) >= this.config.msgDaily) {
      this.trip('MSG_LIMIT');
      return { outcome: 'RESTRICTED', code: 'MSG_LIMIT' };
    }
    const last = this.msgLedger.last();
    // Tin gần nhất chính là Tin 1 gửi cho người này: khoảng nghỉ cố định ngắn hơn (kể cả Tin 1 bị chặn người lạ); khác thì giữ khoảng nghỉ chung.
    const own = last !== null && this.lastInvite && this.lastInvite.at === last && this.lastInvite.uid === String(uid);
    const gapMs = own ? Math.min(this.config.samePersonGapMs, this.config.msgGapMs) : this.config.msgGapMs;
    if (last !== null && t - last < gapMs) {
      return { outcome: 'RATE_LIMITED', retryAfterSec: Math.ceil((gapMs - (t - last)) / 1000) };
    }
    // chỉ người đã nhận Tin 1 từ chính cầu nối này; mỗi người một lời mời
    if (!this.msgLedger.has('INVITE', uid) && !this.msgLedger.has('INVITE_BLOCKED', uid)) return { outcome: 'PERSON_ERROR', code: 'NO_INVITE' };
    if (this.friendLedger.has('FRIEND', uid)) return { outcome: 'PERSON_ERROR', code: 'DUPLICATE' };
    this.busy = true;
    try {
      this.msgLedger.record(t); // ghi trước khi gọi, tính vào phanh khẩn cấp và khoảng nghỉ chung
      this.lastFriend = { uid, at: t };
      this.friendLedger.record(t);
      this.stateFile.write({ inflight: true });
      let c;
      try {
        await withTimeout(this.adapter.sendFriendRequest(uid, text), this.config.addTimeoutMs);
        this.friendLedger.markSent('FRIEND', uid); // thư viện không ném lỗi nghĩa là Zalo đã nhận (thành công trả chuỗi rỗng)
        c = { outcome: 'OK' };
      } catch (e) {
        c = classifyError(e, new Set([...this.config.personErrorCodes, ...this.config.friendListFullCodes]));
        // Z223: danh sách lời mời đang chờ của tài khoản phụ đã đầy. Zalo từ chối, lời mời chưa đi: không dừng cầu dao (Adh tạm dừng riêng phần kết bạn).
        if (c.outcome === 'PERSON_ERROR' && /^Z\d+$/.test(c.code ?? '') && this.config.friendListFullCodes.has(c.code.slice(1))) {
          c = { outcome: 'PERSON_ERROR', code: 'FRIEND_LIST_FULL' };
        }
      }
      if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR') {
        this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
      } else {
        this.stateFile.write({});
      }
      this.log(`kết bạn: ${c.outcome}`);
      const out = { outcome: c.outcome };
      if (c.code) out.code = c.code;
      return out;
    } finally {
      this.busy = false;
    }
  }

  /**
   * Nhận MỘT lời mời kết bạn mà MỘT khách đã nhận Tin 1 từ chính cầu nối này vừa tự gửi tới (25/11/2026, chủ dự án duyệt: "làm cả cơ chế chấp nhận lời mời kết
   * bạn của khách"). Chỉ người trong {@code incoming} (đã nhận Tin 1, lời mời tới trong phiên này), mỗi người một lần, khung giờ, trần mỗi ngày {@code acceptDaily},
   * khoảng nghỉ CỐ ĐỊNH {@code acceptGapMs}, một việc một lúc, cầu dao. Không nhận người lạ, không nhận hàng loạt, không đọc gì thêm. Thành công thì Zalo bắn
   * sự kiện kết bạn như thường (đường thêm vào nhóm không đổi). Lỗi có mã riêng của Zalo là lỗi riêng người, không dừng; lỗi lạ khác vẫn dừng. Không ngẫu nhiên hóa.
   * @param {{uid: string}} m
   */
  async acceptFriendRequest(m) {
    const uid = m?.uid === undefined || m?.uid === null ? null : String(m.uid);
    if (uid === null || !ZALO_ID.test(uid)) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) {
      this.trip('WINDOW');
      return { outcome: 'RESTRICTED', code: 'WINDOW' };
    }
    if (!this.incoming.has(uid) || !this.msgLedger.has('INVITE', uid) && !this.msgLedger.has('INVITE_BLOCKED', uid)) return { outcome: 'PERSON_ERROR', code: 'NO_REQUEST' };
    if (this.acceptLedger.has('ACCEPT', uid)) return { outcome: 'PERSON_ERROR', code: 'DUPLICATE' };
    const last = this.acceptLedger.last();
    if (this.acceptLedger.countSince(vnDayStart(t)) >= this.config.acceptDaily) return { outcome: 'RATE_LIMITED', retryAfterSec: 3600 };
    if (last !== null && t - last < this.config.acceptGapMs) {
      return { outcome: 'RATE_LIMITED', retryAfterSec: Math.ceil((this.config.acceptGapMs - (t - last)) / 1000) };
    }
    this.busy = true;
    try {
      this.acceptLedger.record(t); // ghi trước khi gọi, tính cả lần lỗi
      this.stateFile.write({ inflight: true });
      let c;
      try {
        await withTimeout(this.adapter.acceptFriendRequest(uid), this.config.addTimeoutMs);
        this.acceptLedger.markSent('ACCEPT', uid);
        c = { outcome: 'OK' };
      } catch (e) {
        c = classifyError(e, new Set());
        if (c.outcome === 'SYSTEM_ERROR' && /^Z\d+$/.test(c.code ?? '')) c = { outcome: 'PERSON_ERROR', code: c.code };
      }
      if (c.outcome === 'OK' || c.outcome === 'PERSON_ERROR') this.incoming.delete(uid); // dù nhận được hay Zalo báo lỗi riêng, không thử lại lời mời này
      if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR') {
        this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
      } else {
        this.stateFile.write({});
      }
      this.log(`nhận lời mời kết bạn: ${c.outcome}`);
      const out = { outcome: c.outcome };
      if (c.code) out.code = c.code;
      return out;
    } finally {
      this.busy = false;
    }
  }

  /**
   * Thu hồi MỘT lời mời kết bạn mà chính cầu nối này đã gửi cho MỘT người nhưng họ chưa đồng ý (25/11/2026, chủ dự án duyệt), để danh sách lời mời đang chờ
   * của Zalo (có hạn, báo Z223 khi đầy) còn chỗ. Không nhắn gì cho người đó, không đọc gì. Chốt chặn công khai và cố định: chỉ người đã được gửi lời mời qua
   * cầu nối này (sổ FRIEND), mỗi người một lần, khung giờ, trần mỗi ngày {@code undoDaily}, khoảng nghỉ CỐ ĐỊNH {@code undoGapMs}, một việc một lúc, cầu dao.
   * Lỗi có mã riêng của Zalo (thường là lời mời không còn, người đó đã đồng ý hoặc từ chối) là lỗi riêng người, không dừng; lỗi lạ khác vẫn dừng. Không ngẫu nhiên hóa.
   * @param {{uid: string}} m
   */
  async undoFriendRequest(m) {
    const uid = m?.uid === undefined || m?.uid === null ? null : String(m.uid);
    if (uid === null || !ZALO_ID.test(uid)) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) {
      this.trip('WINDOW');
      return { outcome: 'RESTRICTED', code: 'WINDOW' };
    }
    if (!this.friendLedger.has('FRIEND', uid)) return { outcome: 'PERSON_ERROR', code: 'NOT_REQUESTED' }; // chỉ lời mời do chính cầu nối đã gửi
    if (this.undoLedger.has('UNDO', uid)) return { outcome: 'PERSON_ERROR', code: 'DUPLICATE' };
    const last = this.undoLedger.last();
    if (this.undoLedger.countSince(vnDayStart(t)) >= this.config.undoDaily) return { outcome: 'RATE_LIMITED', retryAfterSec: 3600 };
    if (last !== null && t - last < this.config.undoGapMs) {
      return { outcome: 'RATE_LIMITED', retryAfterSec: Math.ceil((this.config.undoGapMs - (t - last)) / 1000) };
    }
    this.busy = true;
    try {
      this.undoLedger.record(t); // ghi trước khi gọi, tính cả lần lỗi
      this.stateFile.write({ inflight: true });
      let c;
      try {
        await withTimeout(this.adapter.undoFriendRequest(uid), this.config.addTimeoutMs);
        this.undoLedger.markSent('UNDO', uid);
        c = { outcome: 'OK' };
      } catch (e) {
        c = classifyError(e, new Set());
        // mã lỗi riêng của Zalo (không phải bị hạn chế hay mất phiên): lỗi riêng lời mời này, không dừng cầu dao
        if (c.outcome === 'SYSTEM_ERROR' && /^Z\d+$/.test(c.code ?? '')) c = { outcome: 'PERSON_ERROR', code: c.code };
      }
      if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR') {
        this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
      } else {
        this.stateFile.write({});
      }
      this.log(`thu hồi lời mời: ${c.outcome}`);
      const out = { outcome: c.outcome };
      if (c.code) out.code = c.code;
      return out;
    } finally {
      this.busy = false;
    }
  }

  // ---------- tra MỘT người theo số (D19: để chủ shop xác nhận đúng mẹ trước khi làm phó nhóm) ----------

  /**
   * Tra tên và ảnh đại diện của MỘT số điện thoại (số của mẹ, chủ shop nhập tay) để chủ shop xác nhận đúng người. Chỉ đọc, không nhắn,
   * không kết bạn. Chốt chặn công khai và cố định: tối đa profileLookupsPerHour lần mỗi giờ (mặc định 5; quá thì chỉ bị từ chối RATE_LIMITED),
   * một lúc một việc, lỗi lạ thì dừng cầu dao. Số điện thoại, mã Zalo, tên không vào log.
   * @param {{phone: string}} m
   */
  async lookupPerson(m) {
    const phone = m?.phone === undefined || m?.phone === null ? '' : String(m.phone).replace(/\D/g, '');
    if (!/^\d{8,15}$/.test(phone)) return { outcome: 'PERSON_ERROR', code: 'BAD_PHONE' };
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) return { outcome: 'RESTRICTED', code: 'WINDOW' };
    this.profileLookups = (this.profileLookups ?? []).filter((x) => x > t - 3600000);
    if (this.profileLookups.length >= this.config.profileLookupsPerHour) {
      return { outcome: 'RATE_LIMITED', retryAfterSec: Math.max(1, Math.ceil((this.profileLookups[0] + 3600000 - t) / 1000)) };
    }
    this.busy = true;
    try {
      this.profileLookups.push(t); // tính cả lần lỗi và lần không tìm thấy
      let c;
      let found = null;
      try {
        found = await withTimeout(this.adapter.findProfile(phone), this.config.addTimeoutMs);
        if (!found || !ZALO_ID.test(String(found.uid ?? ''))) {
          this.log('tra người: không tìm thấy');
          return { outcome: 'PERSON_ERROR', code: 'NOT_FOUND' };
        }
        c = { outcome: 'OK' };
      } catch (e) {
        c = classifyError(e, this.config.personErrorCodes);
      }
      if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR') {
        this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
        this.log(`tra người: ${c.outcome}`);
        const out = { outcome: c.outcome };
        if (c.code) out.code = c.code;
        return out;
      }
      if (c.outcome === 'PERSON_ERROR') {
        this.log('tra người: PERSON_ERROR');
        return { outcome: 'PERSON_ERROR', code: c.code ?? 'NOT_FOUND' };
      }
      let avatar = null;
      try {
        avatar = await withTimeout(this.adapter.fetchAvatar(found.avatarUrl), 8000); // lỗi ảnh không làm hỏng việc tra
      } catch {
        avatar = null;
      }
      this.log('tra người: OK');
      return { outcome: 'OK', uid: String(found.uid), name: String(found.name ?? '').slice(0, 120), avatar };
    } finally {
      this.busy = false;
    }
  }

  // ---------- việc thứ năm (D19): tạo MỘT nhóm theo khu vực ----------

  /**
   * Tạo đúng MỘT nhóm Zalo với vài khách đã đồng ý vào nhóm, bật link mời, và (nếu có) thêm mẹ làm phó nhóm. Chốt chặn công khai và
   * cố định, không ngẫu nhiên hóa: khung giờ, khoảng nghỉ tối thiểu giữa hai lần tạo (sớm hơn thì
   * chỉ bị từ chối RATE_LIMITED), và KHÔNG có trần số nhóm mỗi ngày (chủ dự án chốt 24/11/2026), một lúc một việc, lỗi lạ hoặc không rõ kết quả thì dừng và không tự thử lại. Tên nhóm, mã Zalo không vào log.
   * Bật link hoặc thêm phó nhóm lỗi thì KHÔNG dừng: nhóm đã tạo, báo lại để Adh ghi và nhắc mẹ làm tay.
   * Tin chào đầu nhóm (tùy chọn `welcome`, 24/11/2026, chủ dự án chốt: không giới hạn khoảng nghỉ hay hạn mức): MỘT tin văn bản vào chính nhóm
   * vừa tạo, gửi một lần ngay sau khi tạo (không có đường gửi lại); lỗi không làm hỏng việc tạo nhóm. Không tính vào sổ tin riêng.
   * @param {{name: string, members: string[], deputy?: string, welcome?: string}} m
   */
  async createGroup(m) {
    const name = typeof m?.name === 'string' ? m.name.trim() : '';
    const members = Array.isArray(m?.members) ? m.members.map(String) : [];
    const deputy = m?.deputy === undefined || m?.deputy === null ? null : String(m.deputy);
    // eslint-disable-next-line no-control-regex
    if (name.length === 0 || name.length > MAX_GROUP_NAME || /[\u0000-\u001f]/.test(name)) return { outcome: 'PERSON_ERROR', code: 'BAD_NAME' };
    if (members.length < 1 || members.length > MAX_GROUP_MEMBERS || new Set(members).size !== members.length || !members.every((x) => ZALO_ID.test(x))) {
      return { outcome: 'PERSON_ERROR', code: 'BAD_MEMBERS' };
    }
    if (deputy !== null && (!ZALO_ID.test(deputy) || members.includes(deputy))) return { outcome: 'PERSON_ERROR', code: 'BAD_DEPUTY' };
    const welcome = m?.welcome === undefined || m?.welcome === null ? null : m.welcome;
    // eslint-disable-next-line no-control-regex
    if (welcome !== null && (typeof welcome !== 'string' || welcome.trim().length === 0 || welcome.length > MAX_GROUP_WELCOME || /[\u0000-\u0009\u000b-\u001f]/.test(welcome) || /\{[^}]*\}/.test(welcome))) {
      return { outcome: 'PERSON_ERROR', code: 'BAD_WELCOME' };
    }
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) {
      this.trip('WINDOW');
      return { outcome: 'RESTRICTED', code: 'WINDOW' };
    }
    const times = this.groupLedger.times;
    const last = times.length ? Math.max(...times) : null;
    if (last !== null && t - last < this.config.groupGapMs) {
      return { outcome: 'RATE_LIMITED', retryAfterSec: Math.ceil((this.config.groupGapMs - (t - last)) / 1000) };
    }
    this.busy = true;
    try {
      this.groupLedger.record(t); // ghi trước khi gọi, tính cả lần lỗi
      this.stateFile.write({ inflight: true });
      let created;
      try {
        // Zalo chỉ cho đặt phó nhóm với người ĐANG TRONG nhóm: nên mẹ (phó nhóm) vào nhóm ngay lúc tạo, như một thành viên.
        const zaloMembers = deputy === null ? members : [...members, deputy];
        created = await withTimeout(this.adapter.createGroup(name, zaloMembers), this.config.addTimeoutMs * 2);
      } catch (e) {
        const c = classifyError(e, this.config.personErrorCodes);
        if (c.outcome !== 'PERSON_ERROR') {
          this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
        } else {
          this.stateFile.write({});
        }
        this.log(`tạo nhóm: ${c.outcome}`);
        return c.code ? { outcome: c.outcome, code: c.code } : { outcome: c.outcome };
      }
      const groupId = created?.groupId === undefined || created?.groupId === null ? '' : String(created.groupId);
      if (!ZALO_ID.test(groupId)) {
        // Zalo không xác nhận có nhóm: không biết đã tạo hay chưa, dừng thay vì đoán
        this.trip('SYSTEM_ERROR:NO_ACK');
        this.log('tạo nhóm: SYSTEM_ERROR');
        return { outcome: 'SYSTEM_ERROR', code: 'NO_ACK' };
      }
      const added = (created.sucessMembers ?? []).map(String).filter((x) => members.includes(x));
      const failed = members.filter((x) => !added.includes(x));
      let link = null;
      let deputyState = deputy === null ? 'NONE' : 'FAILED';
      let deputyCode = null;
      let tripped = false;
      const after = (e) => {
        const c = classifyError(e, this.config.personErrorCodes);
        if (c.outcome === 'RESTRICTED' || c.outcome === 'SESSION_LOST') {
          tripped = true;
          this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
        }
        return c;
      };
      try {
        const l = await withTimeout(this.adapter.enableGroupLink(groupId), this.config.addTimeoutMs);
        link = typeof l?.link === 'string' && l.link.startsWith('https://') ? l.link : null;
      } catch (e) {
        after(e);
      }
      if (deputy !== null && !tripped) {
        const r = await this.#makeDeputy(groupId, deputy, !(created.sucessMembers ?? []).map(String).includes(deputy), after);
        deputyState = r.deputy;
        deputyCode = r.code;
      }
      // Tin chào đầu nhóm: đúng MỘT lần, không thử lại; lỗi chỉ được ghi lại, không dừng trừ khi Zalo hạn chế hay mất phiên
      let welcomeState = 'NONE';
      let welcomeCode = null;
      if (welcome !== null) {
        if (tripped) {
          welcomeState = 'SKIPPED';
        } else {
          try {
            await withTimeout(this.adapter.sendText(groupId, welcome, true), this.config.addTimeoutMs);
            welcomeState = 'OK';
          } catch (e) {
            welcomeState = 'FAILED';
            welcomeCode = after(e).code ?? 'UNKNOWN';
          }
        }
      }
      if (!tripped) this.stateFile.write({});
      this.log(`tạo nhóm: OK (link ${link ? 'có' : 'không'}, phó nhóm ${deputyState}${deputyCode ? ` mã ${deputyCode}` : ''}, tin chào ${welcomeState}${welcomeCode ? ` mã ${welcomeCode}` : ''})`);
      return { outcome: 'OK', groupId, link, added, failed, deputy: deputyState, ...(deputyCode ? { deputyCode } : {}), ...(welcome !== null ? { welcome: welcomeState } : {}), ...(welcomeCode ? { welcomeCode } : {}) };
    } finally {
      this.busy = false;
    }
  }

  /**
   * Đặt MỘT phó nhóm (mẹ) cho nhóm: nếu mẹ chưa ở trong nhóm thì thêm vào trước (Zalo chỉ cho đặt phó nhóm với người đang trong nhóm), rồi
   * đặt phó nhóm. Lỗi không dừng cầu dao trừ khi Zalo hạn chế hay mất phiên; trả mã lỗi Zalo thật (dạng Z123, không có mã Zalo của người)
   * để ghi nhật ký. Chỉ dùng từ createGroup và setDeputy.
   * @returns {Promise<{deputy: 'OK'|'FAILED', code: string|null}>}
   */
  async #makeDeputy(groupId, deputy, needAdd, after) {
    let addCode = null;
    if (needAdd) {
      try {
        await withTimeout(this.adapter.addUserToGroup(deputy, groupId), this.config.addTimeoutMs);
      } catch (e) {
        addCode = after(e).code ?? 'UNKNOWN'; // có thể đã ở trong nhóm: vẫn thử đặt phó nhóm
      }
    }
    try {
      await withTimeout(this.adapter.addGroupDeputy(deputy, groupId), this.config.addTimeoutMs);
      return { deputy: 'OK', code: null };
    } catch (e) {
      // Ghi CẢ HAI mã: bước thêm vào nhóm (ADD) và bước đặt phó nhóm (DEPUTY), để biết mẹ đã vào nhóm được chưa.
      const depCode = after(e).code ?? 'UNKNOWN';
      return { deputy: 'FAILED', code: addCode ? `ADD-${addCode}_DEPUTY-${depCode}` : `DEPUTY-${depCode}` };
    }
  }

  /**
   * Thử lại đặt phó nhóm cho MỘT nhóm đã tạo (nút "Thử lại phó nhóm"). Chỉ việc đặt phó nhóm (thêm mẹ vào nhóm nếu chưa có), không việc nào khác.
   * Trần cố định mỗi ngày, một lúc một việc, trong khung giờ. Mã nhóm và mã Zalo không vào log.
   * @param {{groupId: string, uid: string}} m
   */
  async setDeputy(m) {
    const groupId = String(m?.groupId ?? '');
    const uid = String(m?.uid ?? '');
    if (!ZALO_ID.test(groupId) || !ZALO_ID.test(uid)) return { outcome: 'PERSON_ERROR', code: 'BAD_ID' };
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) return { outcome: 'RESTRICTED', code: 'WINDOW' };
    this.deputyTries = (this.deputyTries ?? []).filter((x) => x >= vnDayStart(t));
    if (this.deputyTries.length >= MAX_DEPUTY_TRIES_PER_DAY) return { outcome: 'RATE_LIMITED', retryAfterSec: 3600 };
    this.busy = true;
    try {
      this.deputyTries.push(t);
      let tripped = false;
      const after = (e) => {
        const c = classifyError(e, this.config.personErrorCodes);
        if (c.outcome === 'RESTRICTED' || c.outcome === 'SESSION_LOST') {
          tripped = true;
          this.trip(c.code ? `${c.outcome}:${c.code}` : c.outcome);
        }
        return c;
      };
      const r = await this.#makeDeputy(groupId, uid, true, after);
      this.log(`đặt phó nhóm lại: ${r.deputy}${r.code ? ` mã ${r.code}` : ''}${tripped ? ' (đã dừng)' : ''}`);
      return { outcome: 'OK', deputy: r.deputy, ...(r.code ? { code: r.code } : {}) };
    } finally {
      this.busy = false;
    }
  }

  // ---------- đọc danh sách nhóm (chỉ đọc) ----------

  /**
   * Nhóm tài khoản phụ đang tham gia, để trang quản trị chọn "mã nhóm Zalo". Chỉ đọc nên lỗi không làm cầu dao nhảy; chỉ hỏi khi
   * đang nghe bình thường, không hỏi dồn (giữ kết quả 30 giây). Tên nhóm không vào log.
   */
  async listGroups() {
    if (this.state === 'LOGIN_REQUIRED') return { ok: false, code: 'LOGIN_REQUIRED' };
    if (this.state !== 'LISTENING') return { ok: false, code: 'NOT_ACTIVE' };
    const t = this.now();
    if (this.groupsCache && t - this.groupsCache.at < 30_000) return this.groupsCache.result;
    if (this.busy) return { ok: false, code: 'BUSY' };
    this.busy = true;
    try {
      const groups = await withTimeout(this.adapter.listGroups(), this.config.addTimeoutMs * 2);
      const result = { ok: true, groups };
      this.groupsCache = { at: t, result };
      this.log('đọc danh sách nhóm: xong');
      return result;
    } catch (e) {
      this.log(isSessionDead(e) ? 'đọc danh sách nhóm: phiên có vẻ đã hỏng' : 'đọc danh sách nhóm: lỗi');
      return { ok: false, code: 'LIST_FAILED' };
    } finally {
      this.busy = false;
    }
  }

  /**
   * Đối chiếu bạn bè (24/11/2026, chủ shop duyệt). CHỈ ĐỌC, không gửi gì, không nhận lời mời, không lưu gì ra đĩa: Adh gửi danh sách mã Zalo của những
   * khách đã nhận Tin 1 (tối đa 500), bộ nối đọc danh sách bạn của tài khoản phụ và chỉ trả lại những mã trong danh sách Adh hỏi mà đã là bạn.
   * Mã của người ngoài danh sách hỏi không rời bộ nối. Chốt chặn công khai và cố định: khung giờ, giữa hai lần đọc Zalo tối thiểu
   * {@code friendSyncMinMs} (trong khoảng đó trả từ bản đã đọc, không hỏi Zalo), tối đa {@code friendSyncDaily} lần đọc mỗi ngày, mỗi lúc một việc.
   * Lỗi thì trả {ok:false}, không dừng cầu dao (như đọc danh sách nhóm). Mã Zalo không vào log.
   */
  async checkFriends(uids) {
    if (!Array.isArray(uids) || uids.length === 0 || uids.length > 500 || !uids.every((u) => typeof u === 'string' && ZALO_ID.test(u))) {
      return { ok: false, code: 'BAD_INPUT' };
    }
    if (this.state === 'LOGIN_REQUIRED') return { ok: false, code: 'LOGIN_REQUIRED' };
    if (this.state !== 'LISTENING') return { ok: false, code: 'NOT_ACTIVE' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) return { ok: false, code: 'WINDOW' };
    const answer = (set) => ({ ok: true, friends: [...new Set(uids)].filter((u) => set.has(u)) });
    if (this.friendSnapshot && t - this.friendSnapshot.at < this.config.friendSyncMinMs) return answer(this.friendSnapshot.set);
    const dayStart = vnDayStart(t);
    if (this.friendReads.day !== dayStart) this.friendReads = { day: dayStart, n: 0 };
    if (this.friendReads.n >= this.config.friendSyncDaily) return { ok: false, code: 'LIMIT' };
    if (this.busy) return { ok: false, code: 'BUSY' };
    this.busy = true;
    try {
      this.friendReads.n += 1;
      const list = await withTimeout(this.adapter.friendUids(), this.config.addTimeoutMs * 2);
      this.friendSnapshot = { at: t, set: new Set(list.map(String)) };
      this.log('đối chiếu bạn bè: xong');
      return answer(this.friendSnapshot.set);
    } catch (e) {
      this.log(isSessionDead(e) ? 'đối chiếu bạn bè: phiên có vẻ đã hỏng' : 'đối chiếu bạn bè: lỗi');
      return { ok: false, code: 'LIST_FAILED' };
    } finally {
      this.busy = false;
    }
  }

  /** Chỉ đọc một mã đã được cầu nối này gửi hoặc thử gửi Tin 1. */
  async readFriendRequestStatus(uid, verifyDirection = false) {
    if (typeof uid !== 'string' || !ZALO_ID.test(uid)) return { ok: false, code: 'BAD_INPUT' };
    if (!this.msgLedger.has('INVITE', uid) && !this.msgLedger.has('INVITE_BLOCKED', uid)) {
      return { ok: false, code: 'NO_INVITE' };
    }
    const canRead = () => this.accountOk === true && this.activeListen !== 0
      && (this.state === 'LISTENING' || (this.state === 'TRIPPED' && this.readOnlyConnected
        && (this.canReconcileTrip(this.tripReason) || (this.config.inboxEnabled && isSoftTrip(this.tripReason)))));
    if (!canRead()) return { ok: false, code: 'NOT_ACTIVE' };
    const at = this.now();
    if (!inWindow(at, this.config.windowStartMin, this.config.windowEndMin)) return { ok: false, code: 'WINDOW' };
    const day = vnDayStart(at);
    if (this.friendRequestReads.day !== day) this.friendRequestReads = { day, n: 0, lastAt: null };
    const budget = this.friendRequestReads;
    if (budget.n >= this.config.friendSyncDaily) return { ok: false, code: 'LIMIT' };
    if (budget.lastAt !== null && at - budget.lastAt < FRIEND_REQUEST_READ_GAP_MS) return { ok: false, code: 'GAP' };
    if (this.busy) return { ok: false, code: 'BUSY' };

    const generation = this.generation;
    const listen = this.activeListen;
    const isCurrent = () => generation === this.generation && listen === this.activeListen && canRead();
    const timeout = Math.min(this.config.addTimeoutMs, 8000);
    const deadline = performance.now() + timeout;
    this.busy = true;
    budget.n += 1;
    budget.lastAt = at;
    try {
      const result = await withTimeout(this.adapter.friendRequestStatus(uid), timeout);
      if (!isCurrent()) return { ok: false, code: 'NOT_ACTIVE' };
      const flags = [result?.is_friend, result?.is_requesting, result?.is_requested];
      if (!flags.every((value) => value === 0 || value === 1) || flags.filter((value) => value === 1).length > 1) {
        return { ok: false, code: 'BAD_RESPONSE' };
      }
      const state = ['FRIENDS', 'OUTGOING', 'INCOMING'][flags.indexOf(1)] ?? 'NONE';
      if (verifyDirection && (state === 'OUTGOING' || state === 'INCOMING')) {
        const outgoing = await this.#verifyOutgoing(uid, deadline, budget);
        if (!isCurrent()) return { ok: false, code: 'NOT_ACTIVE' };
        return { ok: true, state: outgoing ? 'OUTGOING_VERIFIED' : 'PENDING' };
      }
      return { ok: true, state };
    } catch (e) {
      if (!isCurrent()) return { ok: false, code: 'NOT_ACTIVE' };
      const { code, outcome } = classifyError(e);
      if (outcome === 'SESSION_LOST' || outcome === 'RESTRICTED') {
        this.trip(code ? `${outcome}:${code}` : outcome);
      }
      this.log(`đọc trạng thái lời mời: ${code}`);
      return { ok: false, code };
    } finally {
      this.busy = false;
    }
  }

  /** So khớp một UID từ sổ Tin 1. Chỉ lấy bằng chứng mới trong chính lần đọc này. */
  async #verifyOutgoing(uid, deadline, budget) {
    const at = this.now();
    const remaining = deadline - performance.now();
    if (remaining <= 0 || budget.n >= this.config.friendSyncDaily || vnDayStart(at) !== budget.day
        || !inWindow(at, this.config.windowStartMin, this.config.windowEndMin)) return false;

    budget.n += 1;
    try {
      const match = await withTimeout(this.adapter.hasSentFriendRequest(uid), remaining);
      return match === true && (this.incoming.get(uid) ?? -1) < at;
    } catch (e) {
      const { code, outcome } = classifyError(e);
      if (outcome === 'SESSION_LOST' || outcome === 'RESTRICTED') throw e;
      this.log(`xác minh chiều lời mời: ${code}`);
      return false;
    }
  }

  // ---------- đăng nhập bằng QR ----------

  startLogin() {
    if (this.login.state === 'WAITING_SCAN' || this.login.state === 'SCANNED') return this.loginStatus();
    this.login = { state: 'WAITING_SCAN', qr: null };
    const handle = this.adapter.loginQR({
      onQr: (b64) => {
        this.login = { state: 'WAITING_SCAN', qr: b64 };
      },
      onScanned: () => {
        this.login = { state: 'SCANNED', qr: null };
      },
      onExpired: () => {
        this.login = { state: 'EXPIRED', qr: null };
      },
      onDeclined: () => {
        this.login = { state: 'FAILED', qr: null };
      },
    });
    this.loginHandle = handle;
    const limit = setTimeout(() => {
      if (this.login.state === 'WAITING_SCAN' || this.login.state === 'SCANNED') {
        handle.abort();
        this.login = { state: 'EXPIRED', qr: null };
      }
    }, 180000);
    limit.unref?.();
    handle.promise
      .then(async ({ creds, ownId }) => {
        clearTimeout(limit);
        if (this.config.mode === 'zca' && String(ownId) !== this.config.accountId) {
          // Quét nhầm tài khoản (ví dụ tài khoản chính): đăng xuất ĐÚNG phiên vừa quét, không lưu gì, không đụng phiên phụ đang chạy.
          // Mã tài khoản vừa quét chỉ để người quản trị điền đúng ZALO_ACCOUNT_ID: nằm trong bộ nhớ, không log, không lưu đĩa.
          this.login = { state: 'ACCOUNT_MISMATCH', qr: null, scannedId: String(ownId), scannedAt: this.now() };
          try {
            await this.adapter.discardLogin();
          } catch {
            // đăng xuất phía Zalo thất bại thì vẫn không lưu gì
          }
          if (this.state === 'LOGIN_REQUIRED') {
            this.accountOk = false;
            this.detail = 'ACCOUNT_MISMATCH';
          }
          this.log('sai tài khoản');
          return;
        }
        this.adapter.adoptLogin();
        this.store.save(creds);
        this.accountOk = true;
        this.detail = null;
        // quét lại xong sau khi mất phiên: bỏ cầu dao vì mất phiên. Lý do khác (đứt kết nối bị đá, cảnh báo...) vẫn cần "Mở lại".
        if (this.tripReason === 'SESSION_LOST' || this.tripReason?.startsWith('SESSION_LOST:')) {
          this.stateFile.write({});
          this.tripReason = null;
          this.state = 'LOGIN_REQUIRED';
        }
        this.login = { state: 'DONE', qr: null };
        if (this.state !== 'TRIPPED' && this.state !== 'STOPPED') {
          this.attempts = 0;
          await this.connect(creds, { loggedIn: true, ownId });
        }
      })
      .catch(() => {
        clearTimeout(limit);
        if (this.login.state === 'WAITING_SCAN' || this.login.state === 'SCANNED') this.login = { state: 'FAILED', qr: null };
      });
    return this.loginStatus();
  }

  loginStatus() {
    return { state: this.login.state, qr: this.login.qr, scannedId: this.login.state === 'ACCOUNT_MISMATCH' && this.now() - this.login.scannedAt < 10 * 60 * 1000 ? this.login.scannedId ?? null : null };
  }

  // ---------- trạng thái ----------

  status() {
    return {
      state: this.state,
      tripReason: this.tripReason,
      accountOk: this.accountOk,
      friendReconcileAvailable: this.friendReconcileAvailable(),
      // Dấu băm ngắn của tài khoản phụ (không đảo ngược được, không phải mã Zalo): để Adh biết mã của mẹ tra ra thuộc tài khoản nào.
      accountTag: this.config.accountId ? createHash('sha256').update(`adh-acct:${this.config.accountId}`).digest('hex').slice(0, 12) : null,
      lastEventAt: this.lastEventAt,
      sessionSince: this.sessionSince,
      detail: this.detail,
    };
  }

  inboxMessages(after) {
    if (!this.config.inboxEnabled) return [];
    return this.inbox.after(Number.isFinite(after) && after > 0 ? after : 0);
  }

  friendEvents(after) {
    return this.events.after(Number.isFinite(after) && after > 0 ? after : 0);
  }
}
