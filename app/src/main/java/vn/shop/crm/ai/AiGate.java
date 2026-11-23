package vn.shop.crm.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Cửa duy nhất để hỏi AI: kiểm tra công tắc, trần lượt mỗi ngày và cầu dao (lỗi liên tiếp thì nghỉ), rồi mới gọi.
 * Mọi lỗi đều thành "không có câu trả lời" để việc rơi về mẹ; AI hỏng không được làm hỏng luồng chính.
 * Nội dung câu hỏi và câu trả lời không bao giờ vào log.
 */
@Service
public class AiGate {

    private static final Logger log = LoggerFactory.getLogger(AiGate.class);
    /** Số lần lỗi liên tiếp thì ngắt, và nghỉ bao lâu (cố định, không ngẫu nhiên). */
    static final int TRIP_AFTER = 5;
    static final Duration PAUSE = Duration.ofMinutes(15);

    public record Status(boolean enabled, int callsToday, int dailyLimit, int failuresToday, boolean paused) {
    }

    private final AiClient client;
    private final AiProperties props;
    private final AiUsage usage;
    private final Clock clock;
    private int consecutiveFailures;
    private Instant pausedUntil = Instant.EPOCH;

    public AiGate(AiClient client, AiProperties props, AiUsage usage, Clock clock) {
        this.client = client;
        this.props = props;
        this.usage = usage;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Hỏi AI. Rỗng nếu AI tắt, hết trần ngày, đang nghỉ do lỗi, hoặc gọi lỗi. {@code user} phải là văn bản đã che. */
    public Optional<String> ask(String system, String user, boolean json) {
        return ask(system, user, json, null);
    }

    /** Như trên nhưng cho một lượt dài hơn bình thường; vẫn tính MỘT lượt vào trần ngày và qua cầu dao như mọi lượt khác. */
    public Optional<String> ask(String system, String user, boolean json, AiClient.Options options) {
        if (!props.enabled() || isPaused()) {
            return Optional.empty();
        }
        if (!usage.tryAcquire(props.dailyLimit())) {
            log.info("AI đã hết trần {} lượt hôm nay", props.dailyLimit());
            return Optional.empty();
        }
        try {
            String out = options == null ? client.complete(system, user, json) : client.complete(system, user, json, options);
            synchronized (this) {
                consecutiveFailures = 0;
            }
            return Optional.of(out);
        } catch (AiClient.AiUnavailableException e) {
            fail();
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("AI lỗi lạ: {}", e.getClass().getSimpleName());
            fail();
            return Optional.empty();
        }
    }

    private void fail() {
        usage.recordFailure();
        synchronized (this) {
            if (++consecutiveFailures >= TRIP_AFTER) {
                pausedUntil = clock.instant().plus(PAUSE);
                consecutiveFailures = 0;
                log.warn("AI lỗi {} lần liên tiếp: nghỉ {} phút", TRIP_AFTER, PAUSE.toMinutes());
            }
        }
    }

    private synchronized boolean isPaused() {
        return clock.instant().isBefore(pausedUntil);
    }

    public Status status() {
        return new Status(props.enabled(), usage.callsToday(), props.dailyLimit(), usage.failuresToday(), isPaused());
    }
}
