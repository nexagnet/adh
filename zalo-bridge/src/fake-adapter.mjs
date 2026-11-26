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

  async friendUids() {
    this.friendReads = (this.friendReads ?? 0) + 1;
    if (this.friendError) throw this.friendError;
    return [...(this.friends ?? [])].map(String);
  }

  async friendRequestStatus(uid) {
    (this.friendRequestReads ??= []).push(uid);
    if (this.friendRequestError) throw this.friendRequestError;
    return this.friendRequestStates?.get(uid);
  }

  async findUid(phone) {
    if (this.findError) throw this.findError;
    return this.uids?.get(String(phone)) ?? null;
  }

  async findProfile(phone) {
    if (this.profileError) throw this.profileError;
    (this.profileLookups ??= []).push(String(phone));
    return this.profiles?.get(String(phone)) ?? null;
  }

  async fetchAvatar(url) {
    if (this.avatarError) throw this.avatarError;
    return url ? `data:image/png;base64,${PNG_1X1}` : null;
  }

  async sendText(uid, text, group = false) {
    if (group) {
      if (this.groupSendError) throw this.groupSendError;
      (this.groupSent ??= []).push({ groupId: uid, text });
      return { message: { msgId: 2 }, attachment: [] };
    }
    if (this.sendError) throw this.sendError;
    (this.sent ??= []).push({ uid, text });
    return { message: { msgId: 1 }, attachment: [] };
  }

  async addHeart(uid, msgId, cliMsgId) {
    if (this.heartError) throw this.heartError;
    (this.hearts ??= []).push({ uid, msgId, cliMsgId });
  }

  async sendFriendRequest(uid, text) {
    if (this.friendError) throw this.friendError;
    (this.friendRequests ??= []).push({ uid, text });
    return '';
  }

  async acceptFriendRequest(uid) {
    if (this.acceptError) throw this.acceptError;
    (this.accepted ??= []).push(uid);
    return '';
  }

  /** Giả lập: người khác gửi lời mời kết bạn tới tài khoản phụ. */
  emitFriendRequest(uid) {
    this.handlers?.onFriendRequest?.(String(uid));
  }

  async undoFriendRequest(uid) {
    if (this.undoError) throw this.undoError;
    (this.undone ??= []).push(uid);
    return '';
  }

  async createGroup(name, members) {
    if (this.createError) throw this.createError;
    (this.created ??= []).push({ name, members });
    if (this.createNoAck) return {};
    const failed = members.filter((m) => (this.createFailMembers ?? []).includes(m));
    return { groupId: String(9000 + this.created.length), sucessMembers: members.filter((m) => !failed.includes(m)), errorMembers: failed };
  }

  async enableGroupLink(groupId) {
    if (this.linkError) throw this.linkError;
    return { link: `https://zalo.me/g/fake${groupId}`, enabled: 1, expiration_date: 0 };
  }

  async addGroupDeputy(uid, groupId) {
    if (this.deputyError) throw this.deputyError;
    (this.deputies ??= []).push({ uid, groupId });
    return '';
  }

  async listGroups() {
    if (this.groupsError) throw this.groupsError;
    this.groupListCalls = (this.groupListCalls ?? 0) + 1;
    return this.groups ?? [];
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

  adoptLogin() {
    this.stopListening();
    this.adopts = (this.adopts ?? 0) + 1;
  }

  async discardLogin() {
    this.logouts += 1;
    this.discards = (this.discards ?? 0) + 1;
  }

  async logout() {
    this.logouts += 1;
  }

  // ---- hàm của test / chế độ giả ----
  emitFriendAdd(uid, phone = null) {
    if (phone) this.phones.set(String(uid), phone);
    this.handlers?.onFriendAdd(String(uid));
  }

  emitMessage(uid, text) {
    this.msgCounter = (this.msgCounter ?? 0) + 1;
    this.handlers?.onMessage?.(String(uid), text, { msgId: String(5000 + this.msgCounter), cliMsgId: String(7000 + this.msgCounter) });
  }

  emitDisconnect(code) {
    this.handlers?.onDisconnected(code);
  }
}
