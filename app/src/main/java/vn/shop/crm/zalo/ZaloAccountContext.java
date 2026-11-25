package vn.shop.crm.zalo;

import java.util.function.Supplier;

/**
 * Tài khoản Zalo phụ mà luồng hiện tại đang làm việc cho (D23, nhiều tài khoản cố định song song). Mặc định là tài khoản 1 (tài khoản duy nhất
 * của hệ thống trước D23), nên khi chỉ có một tài khoản thì không đổi gì. Bộ hẹn giờ gửi tin sẽ chạy mỗi nhịp một lần cho TỪNG tài khoản trong
 * {@link #run}; {@link ZaloAccountRoutes} dựa vào đây để chuyển mọi lời gọi tới đúng cầu nối.
 *
 * <p>Chỉ có tác dụng trong LUỒNG đang chạy: việc chạy ở luồng khác (tác vụ bất đồng bộ) không thừa kế, phải tự đặt lại. Không bao giờ được
 * dùng để trộn việc của hai tài khoản trong cùng một lần gọi.
 */
public final class ZaloAccountContext {

    /** Tài khoản đầu tiên, có sẵn từ migration V57; cấu hình cũ (một cầu nối) thuộc về nó. */
    public static final long DEFAULT_ACCOUNT = 1L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private ZaloAccountContext() {
    }

    public static long current() {
        Long v = CURRENT.get();
        return v == null ? DEFAULT_ACCOUNT : v;
    }

    /**
     * Mệnh đề SQL giới hạn một bảng có cột {@code account_id} vào tài khoản đang làm việc, ví dụ {@code "... WHERE account_id = 2"}. Là SỐ nguyên
     * lấy từ {@link #current()} (không bao giờ là chữ do người dùng nhập) nên nối thẳng vào câu lệnh là an toàn.
     */
    public static String sql() {
        return "account_id = " + current();
    }

    /** Khóa cài đặt thuộc RIÊNG từng tài khoản (hạn mức, khoảng nghỉ, cầu dao, mốc đọc, phó nhóm); khóa khác dùng chung cả hệ thống. */
    private static final String[] PER_ACCOUNT_PREFIXES = {
            "zalo.auto.", "zalo.msg.", "zalo.friend.daily", "zalo.friend.paused_until",
            "zalo.deputy.", "zalo.inbox.cursor", "zalo.group.last_plan_at", "zalo.me"};

    /**
     * Tên khóa cài đặt ({@code app_setting}) dành cho tài khoản đang làm việc. Tài khoản 1 giữ nguyên tên khóa cũ (không đổi gì khi chỉ có một
     * tài khoản); tài khoản khác thêm tiền tố {@code acct<N>.} cho các khóa riêng từng tài khoản. Khóa dùng chung (chế độ thử, tạm dừng tất cả,
     * cấu hình nhóm, AI...) không bao giờ bị đổi tên.
     */
    public static String settingKey(String key) {
        long acct = current();
        if (acct == DEFAULT_ACCOUNT) {
            return key;
        }
        for (String prefix : PER_ACCOUNT_PREFIXES) {
            if (key.startsWith(prefix)) {
                return "acct" + acct + "." + key;
            }
        }
        return key;
    }

    /** Như {@link #sql()} cho cột có tên khác (ví dụ {@code i.account_id} của bảng lời mời đặt tên tắt {@code i}). */
    public static String sql(String column) {
        return column + " = " + current();
    }

    /**
     * Gắn tài khoản cho cả LUỒNG yêu cầu web (trang quản trị chọn tài khoản để xem và chỉnh) cho tới khi {@link #unbind()}. Chỉ bộ chặn yêu cầu
     * ({@link ZaloAccountInterceptor}) được gọi; việc chạy nền dùng {@link #run}.
     */
    static void bind(long accountId) {
        if (accountId < 1) {
            throw new IllegalArgumentException("Mã tài khoản Zalo phải từ 1");
        }
        CURRENT.set(accountId);
    }

    static void unbind() {
        CURRENT.remove();
    }

    public static <T> T call(long accountId, Supplier<T> body) {
        if (accountId < 1) {
            throw new IllegalArgumentException("Mã tài khoản Zalo phải từ 1");
        }
        Long previous = CURRENT.get();
        CURRENT.set(accountId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(long accountId, Runnable body) {
        call(accountId, () -> {
            body.run();
            return null;
        });
    }
}
