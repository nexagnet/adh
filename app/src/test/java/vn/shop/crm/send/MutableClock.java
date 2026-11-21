package vn.shop.crm.send;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Đồng hồ giả chỉnh được, để kiểm tra giờ gửi, hạn mức theo ngày và khoảng nghỉ giữa hai tin. */
public class MutableClock extends Clock {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private Instant now = LocalDateTime.of(2026, 11, 21, 10, 0).atZone(VN).toInstant();

    void set(LocalDateTime at) {
        now = at.atZone(VN).toInstant();
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return VN;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }
}
