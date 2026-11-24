// Bộ điều hợp THẬT dùng thư viện không chính thức zca-js (ghim đúng 2.3.0 trong package.json và package-lock.json).
// Thư viện giả lập Zalo Web nên trái điều khoản Zalo và tài khoản phụ có thể bị khóa; xem docs/ADR-0002.
// Cố ý KHÔNG dùng: acceptFriendRequest, bất kỳ hàm gửi hay dò hàng loạt nào khác.
// Từ 24/11/2026 (chủ shop duyệt) có thêm đúng MỘT hàm CHỈ ĐỌC: getAllFriends, chỉ để đối chiếu người đã nhận Tin 1 (xem Bridge.checkFriends); danh sách không rời bộ nối.
// Từ 23/11/2026 (chủ shop chốt: tài khoản phụ chủ động kết bạn) có thêm đúng MỘT hàm: sendFriendRequest, chỉ cho người đã nhận Tin 1.
// Từ D19 (23/11/2026, chủ shop chốt) có thêm đúng ba hàm để tạo nhóm theo khu vực: createGroup, enableGroupLink, addGroupDeputy.
// Từ D18 (23/11/2026, chủ shop chốt) có thêm đúng hai hàm để nhắn khách đã mua của chính họ, mỗi người một tin: findUser (một số)
// và sendMessage (văn bản, một người). Có trần ngày, khoảng nghỉ cố định, khung giờ và cầu dao ở bridge.mjs; không che việc dùng thư viện.
// Không đặt userAgent, không đổi dấu vân tay, không proxy: dùng mặc định của thư viện, không thêm lớp nào để che.

import { Zalo, AvatarSize, FriendEventType, LoginQRCallbackEventType, ThreadType } from 'zca-js';

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
    if (h.onMessage) {
      // Chỉ tin nhắn RIÊNG (1-1) dạng văn bản của người khác. Không đọc tin nhóm, không đọc tệp/ảnh, không đọc lịch sử cũ.
      listener.on('message', (m) => {
        if (m.type !== ThreadType.User || m.isSelf || typeof m.data?.content !== 'string') return;
        h.onMessage(String(m.threadId), m.data.content);
      });
    }
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
    const u = await this.findOne(phone, AvatarSize.Large);
    return u?.uid ? String(u.uid) : null;
  }

  /** Chỗ DUY NHẤT gọi findUser của thư viện (test no-send khóa số chỗ gọi). */
  async findOne(phone, avatarSize) {
    return this.api.findUser(phone, avatarSize);
  }

  /**
   * Tra MỘT số điện thoại: mã Zalo, tên hiển thị và đường dẫn ảnh đại diện (cỡ nhỏ). Chỉ để chủ shop xác nhận đúng mẹ trước khi làm phó
   * nhóm. Không liệt kê, không dò hàng loạt, không nhắn, không kết bạn.
   */
  async findProfile(phone) {
    const u = await this.findOne(phone, AvatarSize.Small);
    if (!u?.uid) return null;
    return { uid: String(u.uid), name: String(u.zalo_name || u.display_name || ''), avatarUrl: typeof u.avatar === 'string' ? u.avatar : null };
  }

  /**
   * Tải ảnh đại diện (nhỏ) thành data URI để trang quản trị hiện được mà không phải mở thêm nguồn ảnh ngoài. Chỉ https tới máy chủ ảnh của
   * Zalo, không theo chuyển hướng, giới hạn dung lượng và loại tệp. Lỗi thì trả null (chỉ mất ảnh).
   */
  async fetchAvatar(url) {
    let u;
    try {
      u = new URL(String(url));
    } catch {
      return null;
    }
    if (u.protocol !== 'https:' || u.port !== '' || !/(^|\.)(zadn\.vn|zdn\.vn|zalo\.me|zaloapp\.com)$/.test(u.hostname)) return null;
    const r = await fetch(u, { redirect: 'error', signal: AbortSignal.timeout(6000) });
    if (!r.ok) return null;
    const type = (r.headers.get('content-type') ?? '').split(';')[0].trim();
    if (!/^image\/(jpeg|png|webp)$/.test(type)) return null;
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length === 0 || buf.length > 100 * 1024) return null;
    return `data:${type};base64,${buf.toString('base64')}`;
  }

  /**
   * Gửi MỘT tin văn bản cho MỘT người, hoặc (group = true) vào MỘT nhóm vừa tự tạo (tin chào đầu nhóm). Không đính kèm, không gửi lời mời
   * kết bạn. Chỗ DUY NHẤT gọi sendMessage.
   */
  async sendText(id, text, group = false) {
    return this.api.sendMessage({ msg: text }, id, group ? ThreadType.Group : ThreadType.User);
  }

  /** Gửi MỘT lời mời kết bạn (kèm câu ngắn) cho MỘT người đã nhận Tin 1. Chỗ DUY NHẤT gọi sendFriendRequest. Thành công trả chuỗi rỗng. */
  async sendFriendRequest(uid, text) {
    return this.api.sendFriendRequest(text, uid);
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

  /** Chỉ ĐỌC: mã Zalo của mọi bạn hiện có. Chỉ dùng cho {@code Bridge.checkFriends}; không trả tên, số hay ảnh và không rời khỏi bộ nối. */
  async friendUids() {
    const all = await this.api.getAllFriends();
    return (Array.isArray(all) ? all : []).map((u) => String(u?.userId ?? '')).filter((id) => /^\d{1,32}$/.test(id));
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
