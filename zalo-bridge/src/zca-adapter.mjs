// Bộ điều hợp THẬT dùng thư viện không chính thức zca-js (ghim đúng 2.3.0 trong package.json và package-lock.json).
// Thư viện giả lập Zalo Web nên trái điều khoản Zalo và tài khoản phụ có thể bị khóa; xem docs/ADR-0002.
// Cố ý KHÔNG dùng: sendMessage, sendFriendRequest, findUser, getAllFriends, bất kỳ hàm gửi hay dò nào khác.
// Không đặt userAgent, không đổi dấu vân tay, không proxy: dùng mặc định của thư viện, không thêm lớp nào để che.

import { Zalo, FriendEventType, LoginQRCallbackEventType } from 'zca-js';

export class ZcaAdapter {
  constructor() {
    // checkUpdate: false để không gọi ra ngoài chỉ để kiểm phiên bản gói; logging: false để thư viện không in dữ liệu.
    this.zalo = new Zalo({ selfListen: false, checkUpdate: false, logging: false });
    this.api = null;
  }

  async restore(creds) {
    this.stopListening();
    this.api = await this.zalo.login({ imei: creds.imei, cookie: creds.cookie, userAgent: creds.userAgent });
    return { ownId: String(this.api.getOwnId()) };
  }

  listen(h) {
    const listener = this.api.listener;
    listener.on('connected', () => h.onConnected());
    listener.on('friend_event', (ev) => {
      // Chỉ loại ADD (đã thành bạn) do người khác thực hiện. REQUEST (người khác tự gửi lời mời) không chuyển đi.
      if (ev.type !== FriendEventType.ADD || ev.isSelf) return;
      const uid = typeof ev.data === 'string' ? ev.data : ev.threadId;
      h.onFriendAdd(String(uid));
    });
    listener.on('disconnected', (code) => h.onDisconnected(code));
    listener.on('error', () => {}); // nội dung lỗi socket có thể chứa dữ liệu: bỏ qua; đứt thật sẽ có 'disconnected'
    listener.start({ retryOnClose: false }); // nối lại do lõi cầu nối làm, có backoff và giới hạn
  }

  stopListening() {
    try {
      this.api?.listener.removeAllListeners();
      this.api?.listener.stop();
    } catch {
      // đã đóng rồi
    }
  }

  async getPhone(uid) {
    const r = await this.api.getUserInfo(uid);
    const profiles = Object.values(r?.changed_profiles ?? {});
    const p = profiles.find((x) => String(x?.userId) === String(uid)) ?? profiles[0];
    return p?.phoneNumber || null;
  }

  async addUserToGroup(uid, groupId) {
    return this.api.addUserToGroup(uid, groupId);
  }

  loginQR(h) {
    let abortFn = null;
    const promise = (async () => {
      let creds = null;
      const api = await this.zalo.loginQR({}, (ev) => {
        switch (ev.type) {
          case LoginQRCallbackEventType.QRCodeGenerated:
            abortFn = ev.actions.abort;
            h.onQr(ev.data.image);
            break;
          case LoginQRCallbackEventType.QRCodeScanned:
            abortFn = ev.actions.abort;
            h.onScanned();
            break;
          case LoginQRCallbackEventType.QRCodeExpired:
            h.onExpired();
            ev.actions.abort();
            break;
          case LoginQRCallbackEventType.QRCodeDeclined:
            h.onDeclined();
            ev.actions.abort();
            break;
          case LoginQRCallbackEventType.GotLoginInfo:
            creds = { imei: ev.data.imei, cookie: ev.data.cookie, userAgent: ev.data.userAgent };
            break;
          default:
        }
      });
      if (!creds) throw new Error('không nhận được thông tin đăng nhập');
      this.api = api;
      return { creds, ownId: String(api.getOwnId()) };
    })();
    return { promise, abort: () => abortFn?.() };
  }

  async logout() {
    await this.api?.logout();
    this.api = null;
  }
}
