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

    /** @param qrPng ảnh QR dạng PNG (có khi đang chờ quét); không bao giờ ghi ra đĩa */
    record Login(LoginState state, byte[] qrPng) {
        public static Login of(LoginState state) {
            return new Login(state, null);
        }
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
