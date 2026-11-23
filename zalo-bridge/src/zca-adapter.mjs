// Bộ điều hợp THẬT dùng thư viện không chính thức zca-js (ghim đúng 2.3.0 trong package.json và package-lock.json).
// Thư viện giả lập Zalo Web nên trái điều khoản Zalo và tài khoản phụ có thể bị khóa; xem docs/ADR-0002.
// Cố ý KHÔNG dùng: sendFriendRequest, getAllFriends, acceptFriendRequest, bất kỳ hàm gửi hay dò hàng loạt nào khác.
// Từ D19 (23/11/2026, chủ shop chốt) có thêm đúng ba hàm để tạo nhóm theo khu vực: createGroup, enableGroupLink, addGroupDeputy.
// Từ D18 (23/11/2026, chủ shop chốt) có thêm đúng hai hàm để nhắn khách đã mua của chính họ, mỗi người một tin: findUser (một số)
// và sendMessage (văn bản, một người). Có trần ngày, khoảng nghỉ cố định, khung giờ và cầu dao ở bridge.mjs; không che việc dùng thư viện.
// Không đặt userAgent, không đổi dấu vân tay, không proxy: dùng mặc định của thư viện, không thêm lớp nào để che.

import { Zalo, FriendEventType, LoginQRCallbackEventType, ThreadType } from 'zca-js';

export class ZcaAdapter {
  constructor() {
    // checkUpdate: false để không gọi ra ngoài chỉ để kiểm phiên bản gói; logging: false để thư viện không in dữ liệu.
    this.zalo = new Zalo({ selfListen: false, checkUpdate: false, logging: false });
    this.api = null;
    this.pending = null;
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

  /** Tìm mã Zalo của MỘT số điện thoại khách đã mua (để gửi tin mời). Không liệt kê, không dò hàng loạt. */
  async findUid(phone) {
    const u = await this.api.findUser(phone);
    return u?.uid ? String(u.uid) : null;
  }

  /** Gửi MỘT tin văn bản cho MỘT người. Không đính kèm, không gửi lời mời kết bạn. */
  async sendText(uid, text) {
    return this.api.sendMessage({ msg: text }, uid, ThreadType.User);
  }

  async addUserToGroup(uid, groupId) {
    return this.api.addUserToGroup(uid, groupId);
  }

  /** Tạo MỘT nhóm với vài người đã đồng ý vào nhóm (D19). Không ảnh đại diện, không gì khác. */
  async createGroup(name, members) {
    return this.api.createGroup({ name, members });
  }

  /** Bật link mời của nhóm vừa tạo (để khách tự bấm khi việc thêm trực tiếp lỗi). */
  async enableGroupLink(groupId) {
    return this.api.enableGroupLink(groupId);
  }

  /** Thêm MỘT phó nhóm (mẹ) vào nhóm vừa tạo. */
  async addGroupDeputy(uid, groupId) {
    return this.api.addGroupDeputy(uid, groupId);
  }

  /**
   * Chỉ ĐỌC: nhóm mà tài khoản phụ đang tham gia (mã, tên, số thành viên, cài đặt "chỉ quản trị viên được thêm", tài khoản phụ có
   * phải quản trị viên không). Để chủ shop chọn đúng "mã nhóm Zalo" cho Adh. Không liệt kê bạn bè, không dò ai.
   */
  async listGroups() {
    const own = String(this.api.getOwnId());
    const all = await this.api.getAllGroups();
    const ids = Object.keys(all?.gridVerMap ?? {}).filter((id) => /^\d{1,32}$/.test(id)).slice(0, 300);
    const out = [];
    for (let i = 0; i < ids.length; i += 20) {
      const r = await this.api.getGroupInfo(ids.slice(i, i + 20));
      for (const [id, g] of Object.entries(r?.gridInfoMap ?? {})) {
        out.push({
          id: String(id),
          name: String(g?.name ?? '').slice(0, 120),
          members: Number(g?.totalMember ?? 0) || 0,
          adminOnlyAdd: Number(g?.setting?.addMemberOnly ?? 0) === 1,
          isAdmin: String(g?.creatorId) === own || (g?.adminIds ?? []).map(String).includes(own),
        });
      }
    }
    return out;
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
      // Chưa nhận làm phiên chính: lõi cầu nối kiểm đúng tài khoản phụ rồi mới adoptLogin(); sai thì discardLogin().
      this.pending = api;
      return { creds, ownId: String(api.getOwnId()) };
    })();
    return { promise, abort: () => abortFn?.() };
  }

  /** Nhận phiên vừa quét QR làm phiên chính, đóng kết nối nghe cũ (nếu có) để không bị Zalo đá vì trùng kết nối. */
  adoptLogin() {
    this.stopListening();
    this.api = this.pending;
    this.pending = null;
  }

  /** Bỏ phiên vừa quét (sai tài khoản): chỉ đăng xuất phiên đó, KHÔNG đụng phiên chính đang chạy. */
  async discardLogin() {
    const p = this.pending;
    this.pending = null;
    await p?.logout();
  }

  async logout() {
    await this.api?.logout();
    this.api = null;
  }
}
