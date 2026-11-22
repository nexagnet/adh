// Lõi của cầu nối: máy trạng thái phiên, cầu dao phía cầu nối, nối lại có backoff, thêm MỘT người vào MỘT nhóm.
// Không có hàm gửi tin hay gửi lời mời kết bạn ở bất kỳ đâu trong mã này (ADR-0002 mục 3.1, 4).
// Không bao giờ ghi uid, số điện thoại, tên vào log.

import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { ZALO_ID, inWindow, vnDayStart } from './guard.mjs';
import { classifyError, classifyResult, isSessionDead } from './classify.mjs';

const CLOSE_DUPLICATE = 3000; // cùng tài khoản mở Zalo Web/PC nơi khác: kết nối nghe bị đá
const CLOSE_KICK = 3003;
const MAX_STARTS = 5; // quá 5 lần khởi động trong 10 phút là vòng sập-bật lại
const START_WINDOW_MS = 10 * 60 * 1000;

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
  constructor({ config, adapter, store, ledger, events, now = () => Date.now(), log = () => {} }) {
    this.config = config;
    this.adapter = adapter;
    this.store = store;
    this.ledger = ledger;
    this.events = events;
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
    this.adapter.listen({
      onConnected: () => this.onConnected(gen),
      onFriendAdd: (uid) => this.onFriendAdd(gen, uid),
      onDisconnected: (code) => this.onDisconnected(gen, code),
    });
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

  friendEvents(after) {
    return this.events.after(Number.isFinite(after) && after > 0 ? after : 0);
  }
}
