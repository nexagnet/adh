package vn.shop.crm.zalo;

/**
 * Quản lý phiên đăng nhập của tài khoản phụ (trang quản trị, chỉ admin). Tách khỏi {@link ZaloGateway} để cả ba việc
 * của Zalo vẫn là ba. Mật khẩu và mã OTP của Zalo không bao giờ đi qua đây: đăng nhập bằng quét QR trên điện thoại
 * của tài khoản phụ. Ảnh QR chỉ nằm trong bộ nhớ, không lưu đĩa. Không có đăng nhập lại tự động.
 */
public interface ZaloSession {

    enum LoginState {
        /** Chưa bắt đầu hoặc không có cầu nối. */
        IDLE,
        WAITING_SCAN,
        /** Đã quét, chờ bấm xác nhận trên điện thoại. */
        SCANNED,
        DONE,
        EXPIRED,
        FAILED,
        /** Tài khoản vừa quét không phải tài khoản phụ đã khai báo: đã từ chối và đăng xuất. */
        ACCOUNT_MISMATCH
    }

    /**
     * @param qrPng     ảnh QR dạng PNG (có khi đang chờ quét); không bao giờ ghi ra đĩa
     * @param scannedId mã Zalo (chỉ chữ số) của tài khoản vừa quét, CHỈ có khi {@link LoginState#ACCOUNT_MISMATCH}, để quản trị
     *                  điền đúng {@code ZALO_ACCOUNT_ID}. Chỉ hiện trên trang quét QR của admin; không lưu, không ghi nhật ký.
     */
    record Login(LoginState state, byte[] qrPng, String scannedId) {
        public Login(LoginState state, byte[] qrPng) {
            this(state, qrPng, null);
        }

        public static Login of(LoginState state) {
            return new Login(state, null, null);
        }

        /** Không in ảnh QR hay mã tài khoản ra log nếu ai đó lỡ ghi cả đối tượng. */
        @Override
        public String toString() {
            return "Login[state=" + state + "]";
        }
    }

    /** Một nhóm Zalo mà tài khoản phụ đang tham gia. {@code adminOnlyAdd}: nhóm bật "chỉ quản trị viên được thêm thành viên". */
    record ZaloGroup(String id, String name, int members, boolean adminOnlyAdd, boolean admin) {
    }

    /** @param code khi {@code ok} là false: mã ngắn nói vì sao (LOGIN_REQUIRED, NOT_ACTIVE, BUSY, LIST_FAILED, BRIDGE_DOWN, DISABLED) */
    record GroupList(boolean ok, String code, java.util.List<ZaloGroup> groups) {
        public static GroupList failed(String code) {
            return new GroupList(false, code, java.util.List.of());
        }
    }

    /**
     * Chỉ ĐỌC: các nhóm tài khoản phụ đang tham gia (mã, tên, số thành viên), để quản trị chọn "mã nhóm Zalo" cho nhóm của Adh.
     * Không liệt kê bạn bè, không dò ai. Chỉ trang quản trị (admin) gọi; tên nhóm không vào nhật ký.
     */
    GroupList listGroups();

    /** Bằng chứng nhóm chỉ đọc qua đúng phiên đã ghim. Link có thể thiếu khi Zalo không cho đọc. */
    record GroupInspection(boolean ok, boolean present, boolean adminOnlyAdd, boolean admin,
                           String inviteLink, String accountTag, String code) {
        public boolean canAdd() {
            return ok && present && (!adminOnlyAdd || admin);
        }

        public static GroupInspection failed(String code) {
            return new GroupInspection(false, false, false, false, null, null, code);
        }
    }

    default GroupInspection inspectGroup(String bridgeGroupId) {
        return GroupInspection.failed("INSPECTION_UNAVAILABLE");
    }

    enum JoinGroupOutcome { JOINED, PENDING, NOT_DISPATCHED, UNKNOWN }

    record JoinGroupResult(JoinGroupOutcome outcome, String code, String groupId) {
        public static JoinGroupResult of(JoinGroupOutcome outcome, String code) {
            return new JoinGroupResult(outcome, code, null);
        }

        @Override
        public String toString() {
            return "JoinGroupResult[outcome=" + outcome + ", code=" + code + "]";
        }
    }

    /** Yêu cầu tham gia đúng một nhóm bằng phiên đã ghim. Chỉ gọi sau khi ADH lưu phê duyệt của OWNER. */
    default JoinGroupResult joinGroupViaLink(String expectedGroupId, String inviteLink, String accountTag) {
        return JoinGroupResult.of(JoinGroupOutcome.NOT_DISPATCHED, "JOIN_UNSUPPORTED");
    }

    /** Bắt đầu đăng nhập bằng QR. */
    Login startLogin();

    /** Hỏi tiến độ đăng nhập QR đang chạy. */
    Login loginStatus();

    /** Đăng xuất: hủy phiên cả hai phía, xóa tệp phiên. */
    boolean logout();

    /** Dừng nghe và mọi thao tác (dùng khi cầu dao nhảy hoặc người bấm dừng). Giữ phiên. */
    boolean stop();

    /** Người bấm "Mở lại" sau khi đã xem nhật ký: nghe lại bằng phiên đã lưu (không quét QR). Không bao giờ tự gọi. */
    boolean resume();
}
