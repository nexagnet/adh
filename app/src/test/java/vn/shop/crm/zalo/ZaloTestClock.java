package vn.shop.crm.zalo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Đồng hồ giả chỉnh được cho test hạn mức theo giờ/ngày/khoảng cách và khung giờ 07:00–22:00. */
public class ZaloTestClock extends Clock {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private Instant now = LocalDateTime.of(2026, 11, 22, 10, 0).atZone(VN).toInstant();

    public void set(LocalDateTime at) {
        now = at.atZone(VN).toInstant();
    }

    public void advance(Duration d) {
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
}
