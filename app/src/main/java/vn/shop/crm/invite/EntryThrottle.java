package vn.shop.crm.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn số lượt MỚI vào nhóm từ cùng một địa chỉ mạng trong một giờ, chống quét/bấm hàng loạt làm các nhóm "đầy ảo".
 * Mức mặc định khá rộng vì nhiều điện thoại dùng chung một địa chỉ mạng. Lưu trong bộ nhớ, không ghi địa chỉ IP ra đĩa.
 */
@Component
public class EntryThrottle {

    private static final int MAX_ENTRIES = 10_000;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final ConcurrentHashMap<String, ArrayDeque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxPerHour;

    public EntryThrottle(Clock clock, @Value("${app.entry.max-new-per-ip-per-hour:20}") int maxPerHour) {
        this.clock = clock;
        this.maxPerHour = maxPerHour;
    }

    /** True nếu còn được phép (và ghi nhận một lượt); false nếu đã quá giới hạn. */
    public boolean tryAcquire(String ip) {
        Instant now = clock.instant();
        if (hits.size() > MAX_ENTRIES) {
            hits.values().removeIf(q -> q.isEmpty() || q.peekLast().isBefore(now.minus(WINDOW)));
        }
        boolean[] ok = {false};
        hits.compute(ip == null ? "?" : ip, (k, q) -> {
            if (q == null) {
                q = new ArrayDeque<>();
            }
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(WINDOW))) {
                q.pollFirst();
            }
            if (q.size() < maxPerHour) {
                q.addLast(now);
                ok[0] = true;
            }
            return q;
        });
        return ok[0];
    }
}
