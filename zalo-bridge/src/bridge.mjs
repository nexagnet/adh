// Lõi của cầu nối: máy trạng thái phiên, cầu dao phía cầu nối, nối lại có backoff, thêm MỘT người vào MỘT nhóm.
// Việc được làm: nghe sự kiện kết bạn, tra tên và ảnh của MỘT số điện thoại để xác nhận mẹ (D19), thêm MỘT người vào MỘT nhóm, và (từ D18) gửi MỘT tin văn bản cho MỘT khách đã mua. Từ 23/11/2026 (chủ shop
// chốt "tài khoản phụ chủ động kết bạn") còn gửi MỘT lời mời kết bạn cho MỘT người ĐÃ nhận Tin 1. Không có liệt kê bạn bè, không nhận lời mời, không gửi hàng loạt (ADR-0002 mục 3.1, 4).
// Không bao giờ ghi uid, số điện thoại, tên vào log.

import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { InboxBuffer } from './events.mjs';
import { AddLedger, MessageLedger, ZALO_ID, inWindow, vnDayStart } from './guard.mjs';
import { classifyError, classifyResult, isSessionDead } from './classify.mjs';

const CLOSE_DUPLICATE = 3000; // cùng tài khoản mở Zalo Web/PC nơi khác: kết nối nghe bị đá
const CLOSE_KICK = 3003;
const MAX_STARTS = 5; // quá 5 lần khởi động trong 10 phút là vòng sập-bật lại
const START_WINDOW_MS = 10 * 60 * 1000;
const MAX_GROUP_NAME = 100;
const MAX_GROUP_MEMBERS = 5;
/** Số lần thử lại đặt phó nhóm tối đa mỗi ngày (nút "Thử lại phó nhóm"). */
const MAX_DEPUTY_TRIES_PER_DAY = 10;
const REPLY_WINDOW_MS = 24 * 3600 * 1000; // WELCOME và REPLY chỉ trả lời người vừa nhắn trong khoảng này
const MAX_REPLYABLE = 500;
const MAX_FRIEND_TEXT = 150; // lời mời kết bạn của Zalo chỉ nhận câu ngắn
const MAX_TEXT = 1000; // ký tự; tin mời của shop chỉ vài trăm ký tự

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

export class Bridge {
  constructor({ config, adapter, store, ledger, msgLedger = null, friendLedger = null, groupLedger = null, events, inbox = null, now = () => Date.now(), log = () => {} }) {
    this.config = config;
    this.adapter = adapter;
    this.store = store;
    this.ledger = ledger;
    this.msgLedger = msgLedger ?? new MessageLedger(null, Buffer.alloc(32));
    this.friendLedger = friendLedger ?? new MessageLedger(null, Buffer.alloc(32));
    this.groupLedger = groupLedger ?? new AddLedger(null);
    this.events = events;
    this.inbox = inbox ?? new InboxBuffer({ now: () => this.now() });
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
    this.login = { state: 'IDLE', qr: null };
    this.loginHandle = null;
    this.generation = 0; // tăng mỗi lần dừng/đổi phiên để bỏ qua sự kiện của kết nối cũ
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
    const handlers = {
      onConnected: () => this.onConnected(gen),
      onFriendAdd: (uid) => this.onFriendAdd(gen, uid),
      onDisconnected: (code) => this.onDisconnected(gen, code),
    };
    // Chỉ đăng ký nghe tin nhắn khi bật cờ riêng (tắt mặc định).
    if (this.config.inboxEnabled) handlers.onMessage = (uid, text) => this.onMessage(gen, uid, text);
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
  onMessage(gen, uid, text) {
    if (gen !== this.generation || !this.config.inboxEnabled || !ZALO_ID.test(String(uid)) || typeof text !== 'string') return;
    const body = text.trim();
    if (body.length === 0) return;
    if (!this.msgLedger.has('INVITE', String(uid)) && !this.msgLedger.has('LINK', String(uid))) return;
    this.inbox.push({ uid: String(uid), text: body });
    const t = this.now();
    this.replyable.set(String(uid), t);
    if (this.replyable.size > MAX_REPLYABLE) {
      for (const [k, v] of this.replyable) if (t - v > REPLY_WINDOW_MS) this.replyable.delete(k);
      while (this.replyable.size > MAX_REPLYABLE) this.replyable.delete(this.replyable.keys().next().value);
    }
  }

  onDisconnected(gen, code) {
    if (gen !== this.generation) return;
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
    this.adapter.stopListening();
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

  /** Dừng ngay: đóng kết nối nghe, ngừng mọi thao tác. KHÔNG tự thử lại; chỉ resume() từ trang quản trị mới thoát. */
  trip(reason) {
    this.generation += 1;
    this.clearTimer();
    this.adapter.stopListening();
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
    this.adapter.stopListening();
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
        c = classifyResult(result, uid);
      } catch (e) {
        c = classifyError(e, this.config.personErrorCodes);
      }
      if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR') {
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
   * @param {{kind: 'INVITE'|'LINK'|'WELCOME'|'REPLY'|'FOLLOWUP2'|'FOLLOWUP3', phone?: string, uid?: string, text: string}} m
   */
  async sendMessage(m) {
    const kind = m?.kind;
    const text = typeof m?.text === 'string' ? m.text : '';
    if (!['INVITE', 'LINK', 'WELCOME', 'REPLY', 'FOLLOWUP2', 'FOLLOWUP3'].includes(kind) || text.trim().length === 0 || text.length > MAX_TEXT) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
    const isReply = kind === 'WELCOME' || kind === 'REPLY'; // trả lời người vừa nhắn tới
    const isFollow = kind === 'FOLLOWUP2' || kind === 'FOLLOWUP3'; // tin nhắc cho người đã nhận Tin 1
    const phone = m.phone === undefined || m.phone === null ? null : String(m.phone).replace(/\D/g, '');
    const uidIn = m.uid === undefined || m.uid === null ? null : String(m.uid);
    if ((phone === null) === (uidIn === null)) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' }; // đúng một trong hai
    if (phone !== null && !/^\d{8,15}$/.test(phone)) return { outcome: 'PERSON_ERROR', code: 'BAD_PHONE' };
    if (uidIn !== null && !ZALO_ID.test(uidIn)) return { outcome: 'PERSON_ERROR', code: 'BAD_ID' };
    if (isReply && (uidIn === null || !this.config.inboxEnabled)) return { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' };
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
    if (last !== null && t - last < this.config.msgGapMs) {
      return { outcome: 'RATE_LIMITED', retryAfterSec: Math.ceil((this.config.msgGapMs - (t - last)) / 1000) };
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
        const result = await withTimeout(this.adapter.sendText(uid, text), this.config.addTimeoutMs);
        if (!result?.message) {
          // Zalo không xác nhận có tin: không biết đã gửi hay chưa, dừng thay vì đoán
          c = { outcome: 'SYSTEM_ERROR', code: 'NO_ACK' };
        } else {
          if (!isReply) this.msgLedger.markSent(kind, uid); // WELCOME và REPLY có thể lặp ở lần nhắn sau (Adh giữ quy tắc 30 ngày)
          c = { outcome: 'OK' };
        }
      } catch (e) {
        c = classifyError(e, this.config.personErrorCodes);
      }
      if (c.outcome !== 'OK' && c.outcome !== 'PERSON_ERROR') {
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
    if (last !== null && t - last < this.config.msgGapMs) {
      return { outcome: 'RATE_LIMITED', retryAfterSec: Math.ceil((this.config.msgGapMs - (t - last)) / 1000) };
    }
    // chỉ người đã nhận Tin 1 từ chính cầu nối này; mỗi người một lời mời
    if (!this.msgLedger.has('INVITE', uid)) return { outcome: 'PERSON_ERROR', code: 'NO_INVITE' };
    if (this.friendLedger.has('FRIEND', uid)) return { outcome: 'PERSON_ERROR', code: 'DUPLICATE' };
    this.busy = true;
    try {
      this.msgLedger.record(t); // ghi trước khi gọi, tính vào phanh khẩn cấp và khoảng nghỉ chung
      this.friendLedger.record(t);
      this.stateFile.write({ inflight: true });
      let c;
      try {
        await withTimeout(this.adapter.sendFriendRequest(uid, text), this.config.addTimeoutMs);
        this.friendLedger.markSent('FRIEND', uid); // thư viện không ném lỗi nghĩa là Zalo đã nhận (thành công trả chuỗi rỗng)
        c = { outcome: 'OK' };
      } catch (e) {
        c = classifyError(e, this.config.personErrorCodes);
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
   * cố định, không ngẫu nhiên hóa: khung giờ, trần mỗi ngày (vượt thì dừng cầu dao), khoảng nghỉ tối thiểu giữa hai lần tạo (sớm hơn thì
   * chỉ bị từ chối RATE_LIMITED), một lúc một việc, lỗi lạ hoặc không rõ kết quả thì dừng và không tự thử lại. Tên nhóm, mã Zalo không vào log.
   * Bật link hoặc thêm phó nhóm lỗi thì KHÔNG dừng: nhóm đã tạo, báo lại để Adh ghi và nhắc mẹ làm tay.
   * @param {{name: string, members: string[], deputy?: string}} m
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
    if (this.state === 'LOGIN_REQUIRED') return { outcome: 'SESSION_LOST', code: 'LOGIN_REQUIRED' };
    if (this.state === 'TRIPPED' || this.state === 'STOPPED') return { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
    if (this.state !== 'LISTENING') return { outcome: 'SYSTEM_ERROR', code: 'NOT_CONNECTED' };
    if (this.busy) return { outcome: 'SYSTEM_ERROR', code: 'BUSY' };
    const t = this.now();
    if (!inWindow(t, this.config.windowStartMin, this.config.windowEndMin)) {
      this.trip('WINDOW');
      return { outcome: 'RESTRICTED', code: 'WINDOW' };
    }
    if (this.groupLedger.countSince(vnDayStart(t)) >= this.config.groupDaily) {
      this.trip('GROUP_LIMIT');
      return { outcome: 'RESTRICTED', code: 'GROUP_LIMIT' };
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
      if (!tripped) this.stateFile.write({});
      this.log(`tạo nhóm: OK (link ${link ? 'có' : 'không'}, phó nhóm ${deputyState}${deputyCode ? ` mã ${deputyCode}` : ''})`);
      return { outcome: 'OK', groupId, link, added, failed, deputy: deputyState, ...(deputyCode ? { deputyCode } : {}) };
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
    let code = null;
    if (needAdd) {
      try {
        await withTimeout(this.adapter.addUserToGroup(deputy, groupId), this.config.addTimeoutMs);
      } catch (e) {
        code = after(e).code ?? null; // có thể đã ở trong nhóm: vẫn thử đặt phó nhóm
      }
    }
    try {
      await withTimeout(this.adapter.addGroupDeputy(deputy, groupId), this.config.addTimeoutMs);
      return { deputy: 'OK', code: null };
    } catch (e) {
      return { deputy: 'FAILED', code: after(e).code ?? code ?? 'UNKNOWN' };
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
