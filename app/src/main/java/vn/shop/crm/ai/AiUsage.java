package vn.shop.crm.ai;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Đếm lượt gọi AI mỗi ngày (giờ Việt Nam) để giữ trần chi phí. Chỉ lưu số đếm, không lưu nội dung. */
@Service
public class AiUsage {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcClient jdbc;
    private final Clock clock;

    public AiUsage(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** Giữ chỗ một lượt gọi; false nếu hôm nay đã hết trần. Nguyên tử, an toàn khi nhiều luồng cùng gọi. */
    public boolean tryAcquire(int dailyLimit) {
        if (dailyLimit <= 0) {
            return false;
        }
        return jdbc.sql("""
                        INSERT INTO ai_usage (day, calls) VALUES (:d, 1)
                        ON CONFLICT (day) DO UPDATE SET calls = ai_usage.calls + 1 WHERE ai_usage.calls < :limit""")
                .param("d", today()).param("limit", dailyLimit).update() == 1;
    }

    public void recordFailure() {
        jdbc.sql("""
                        INSERT INTO ai_usage (day, calls, failures) VALUES (:d, 0, 1)
                        ON CONFLICT (day) DO UPDATE SET failures = ai_usage.failures + 1""")
                .param("d", today()).update();
    }

    public int callsToday() {
        return jdbc.sql("SELECT coalesce(max(calls), 0) FROM ai_usage WHERE day = :d").param("d", today())
                .query(Integer.class).single();
    }

    public int failuresToday() {
        return jdbc.sql("SELECT coalesce(max(failures), 0) FROM ai_usage WHERE day = :d").param("d", today())
                .query(Integer.class).single();
    }
}
