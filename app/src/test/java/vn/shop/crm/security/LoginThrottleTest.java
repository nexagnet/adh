package vn.shop.crm.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void khoaSauNamLanSaiVaMoKhoaSauMuoiLamPhut() {
        TestClock clock = new TestClock();
        LoginThrottle t = new LoginThrottle(clock, 5, 15);

        for (int i = 0; i < 4; i++) {
            t.recordFailure("1.1.1.1", "admin");
        }
        assertThat(t.isLocked("1.1.1.1", "admin")).isFalse();
        t.recordFailure("1.1.1.1", "admin");
        assertThat(t.isLocked("1.1.1.1", "admin")).isTrue();

        clock.now = clock.now.plus(Duration.ofMinutes(14));
        assertThat(t.isLocked("1.1.1.1", "admin")).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(t.isLocked("1.1.1.1", "admin")).isFalse();
        // sau khi hết khóa, đếm lại từ đầu
        t.recordFailure("1.1.1.1", "admin");
        assertThat(t.isLocked("1.1.1.1", "admin")).isFalse();
    }

    @Test
    void khoaTheoCapIpVaTenNenNguoiLaKhongKhoaDuocChuCuaHang() {
        LoginThrottle t = new LoginThrottle(new TestClock(), 3, 15);
        for (int i = 0; i < 3; i++) {
            t.recordFailure("9.9.9.9", "admin");
        }

        assertThat(t.isLocked("9.9.9.9", "admin")).isTrue();
        assertThat(t.isLocked("9.9.9.9", "ADMIN ")).isTrue(); // không phân biệt hoa thường/khoảng trắng
        assertThat(t.isLocked("2.2.2.2", "admin")).isFalse();
        assertThat(t.isLocked("9.9.9.9", "me")).isFalse();
    }

    @Test
    void dangNhapDungXoaBoDem() {
        LoginThrottle t = new LoginThrottle(new TestClock(), 3, 15);
        t.recordFailure("1.1.1.1", "admin");
        t.recordFailure("1.1.1.1", "admin");
        t.recordSuccess("1.1.1.1", "admin");
        t.recordFailure("1.1.1.1", "admin");
        t.recordFailure("1.1.1.1", "admin");

        assertThat(t.isLocked("1.1.1.1", "admin")).isFalse();
    }

    @Test
    void sauMuoiLamPhutKhongLoiMoiLoiSaiCuDuocTinhLai() {
        TestClock clock = new TestClock();
        LoginThrottle t = new LoginThrottle(clock, 3, 15);
        t.recordFailure("1.1.1.1", "admin");
        t.recordFailure("1.1.1.1", "admin");
        clock.now = clock.now.plus(Duration.ofMinutes(16));

        t.recordFailure("1.1.1.1", "admin"); // lần sai cũ đã hết hạn, đây chỉ là lần thứ nhất của cửa sổ mới

        assertThat(t.isLocked("1.1.1.1", "admin")).isFalse();
    }
}
