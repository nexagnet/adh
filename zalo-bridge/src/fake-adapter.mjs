// Bộ điều hợp GIẢ: không nối Zalo. Dùng cho test và để chạy thử cả hệ thống ở máy (BRIDGE_MODE=fake).
// Cùng giao diện với zca-adapter nên test lõi cầu nối không cần Zalo.

const PNG_1X1 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

export class FakeAdapter {
  constructor({ ownId = '1000', autoScanMs = null } = {}) {
    this.ownId = ownId;
    this.autoScanMs = autoScanMs;
    this.phones = new Map();
    this.adds = [];
    this.handlers = null;
    this.listening = false;
    this.restoreError = null;
    this.restores = 0;
    this.stops = 0;
    this.logouts = 0;
    this.nextAdd = null; // hàm hoặc giá trị: kết quả/ lỗi cho lần thêm kế tiếp
    this.phoneError = null;
    this.loginHandlers = null;
  }

  async restore() {
    this.restores += 1;
    if (this.restoreError) throw this.restoreError;
    return { ownId: this.ownId };
  }

  listen(handlers) {
    this.handlers = handlers;
    this.listening = true;
    queueMicrotask(() => this.listening && handlers.onConnected());
  }

  stopListening() {
    if (this.listening) this.stops += 1;
    this.listening = false;
    this.handlers = null;
  }

  async getPhone(uid) {
    if (this.phoneError) throw this.phoneError;
    return this.phones.get(String(uid)) ?? null;
  }

  async addUserToGroup(uid, groupId) {
    this.adds.push({ uid, groupId });
    const n = this.nextAdd;
    this.nextAdd = null;
    if (typeof n === 'function') return n(uid, groupId);
    if (n instanceof Error) throw n;
    return n ?? { errorMembers: [], error_data: {} };
  }

  loginQR(h) {
    this.loginHandlers = h;
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => {
      resolve = res;
      reject = rej;
    });
    this.loginResolve = (ownId = this.ownId) => resolve({ creds: { imei: 'fake', cookie: [], userAgent: 'fake' }, ownId });
    this.loginReject = (e = new Error('hủy')) => reject(e);
    h.onQr(PNG_1X1);
    if (this.autoScanMs !== null) {
      const t1 = setTimeout(() => h.onScanned(), this.autoScanMs / 2);
      const t2 = setTimeout(() => this.loginResolve(), this.autoScanMs);
      t1.unref?.();
      t2.unref?.();
    }
    return { promise, abort: () => this.loginReject(new Error('hủy')) };
  }

  async logout() {
    this.logouts += 1;
  }

  // ---- hàm của test / chế độ giả ----
  emitFriendAdd(uid, phone = null) {
    if (phone) this.phones.set(String(uid), phone);
    this.handlers?.onFriendAdd(String(uid));
  }

  emitDisconnect(code) {
    this.handlers?.onDisconnected(code);
  }
}
