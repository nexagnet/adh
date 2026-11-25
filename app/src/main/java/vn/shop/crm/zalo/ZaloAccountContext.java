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
