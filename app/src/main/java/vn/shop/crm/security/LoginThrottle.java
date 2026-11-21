package vn.shop.crm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn đăng nhập sai (mốc 1F): sai quá {@code maxFailures} lần liên tiếp thì khóa cặp (địa chỉ IP, tên đăng nhập)
 * trong {@code lockMinutes} phút. Khóa theo cặp để người lạ không khóa được chủ cửa hàng đang đăng nhập từ nơi khác.
 * Lưu trong bộ nhớ: khởi động lại ứng dụng thì xóa sạch (chấp nhận được với một tài khoản và một máy chủ).
 */
@Component
public class LoginThrottle {

    private record State(int failures, Instant windowStart, Instant lockedUntil) {
    }

    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxFailures;
    private final Duration lock;

    public LoginThrottle(Clock clock, @Value("${app.security.max-login-failures:5}") int maxFailures,
                         @Value("${app.security.lock-minutes:15}") int lockMinutes) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.lock = Duration.ofMinutes(lockMinutes);
    }

    static String key(String ip, String username) {
        return (ip == null ? "?" : ip) + "|" + (username == null ? "" : username.strip().toLowerCase());
    }

    public boolean isLocked(String ip, String username) {
        State s = states.get(key(ip, username));
        return s != null && s.lockedUntil() != null && clock.instant().isBefore(s.lockedUntil());
    }

    public void recordFailure(String ip, String username) {
        Instant now = clock.instant();
        if (states.size() > MAX_ENTRIES) {
            states.values().removeIf(s -> expired(s, now));
        }
        states.compute(key(ip, username), (k, old) -> {
            if (old == null || expired(old, now)) {
                old = new State(0, now, null);
            }
            int failures = old.failures() + 1;
            Instant lockedUntil = failures >= maxFailures ? now.plus(lock) : null;
            return new State(failures, old.windowStart(), lockedUntil);
        });
    }

    public void recordSuccess(String ip, String username) {
        states.remove(key(ip, username));
    }

    private boolean expired(State s, Instant now) {
        if (s.lockedUntil() != null) {
            return !now.isBefore(s.lockedUntil());
        }
        return !now.isBefore(s.windowStart().plus(lock));
    }
}
