package vn.shop.crm.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Cửa hỏi AI: tắt thì không gọi, hết trần ngày thì dừng, lỗi liên tiếp thì ngắt rồi nghỉ, thành công thì đếm lại. */
class AiGateTest extends AbstractPostgresTest {

    @Autowired
    AiUsage usage;

    private static AiProperties props(boolean enabled, int limit) {
        return new AiProperties(enabled, "khoa", "m", "https://api.deepseek.com", limit, 10, 300);
    }

    @Test
    void tatThiKhongGoiVaKhongDem() {
        AtomicInteger calls = new AtomicInteger();
        AiGate gate = new AiGate((s, u, j) -> {
            calls.incrementAndGet();
            return "x";
        }, props(false, 10), usage, Clock.systemUTC());
        assertThat(gate.ask("s", "u", false)).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(usage.callsToday()).isZero();
    }

    @Test
    void dungTranLuotMoiNgay() {
        AtomicInteger calls = new AtomicInteger();
        AiGate gate = new AiGate((s, u, j) -> {
            calls.incrementAndGet();
            return "ok";
        }, props(true, 2), usage, Clock.systemUTC());
        assertThat(gate.ask("s", "u", false)).contains("ok");
        assertThat(gate.ask("s", "u", false)).contains("ok");
        assertThat(gate.ask("s", "u", false)).isEmpty();
        assertThat(calls).hasValue(2);
        assertThat(usage.callsToday()).isEqualTo(2);
        assertThat(gate.status().callsToday()).isEqualTo(2);
    }

    @Test
    void ngayMoiDemLai() {
        AiGate day1 = new AiGate((s, u, j) -> "ok", props(true, 1), new AiUsage(jdbc, fixed("2026-11-23T05:00:00Z")),
                fixed("2026-11-23T05:00:00Z"));
        assertThat(day1.ask("s", "u", false)).isPresent();
        assertThat(day1.ask("s", "u", false)).isEmpty();
        AiGate day2 = new AiGate((s, u, j) -> "ok", props(true, 1), new AiUsage(jdbc, fixed("2026-11-24T05:00:00Z")),
                fixed("2026-11-24T05:00:00Z"));
        assertThat(day2.ask("s", "u", false)).isPresent();
    }

    @Test
    void loiLienTiepNgatCauDaoRoiNghiMuoiLamPhut() {
        AtomicInteger calls = new AtomicInteger();
        Clock[] now = {fixed("2026-11-23T05:00:00Z")};
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId z) {
                return this;
            }

            public Instant instant() {
                return now[0].instant();
            }
        };
        AiGate gate = new AiGate((s, u, j) -> {
            calls.incrementAndGet();
            throw new AiClient.AiUnavailableException("lỗi");
        }, props(true, 100), new AiUsage(jdbc, moving), moving);
        for (int i = 0; i < AiGate.TRIP_AFTER; i++) {
            assertThat(gate.ask("s", "u", false)).isEmpty();
        }
        assertThat(calls).hasValue(AiGate.TRIP_AFTER);
        assertThat(gate.status().paused()).isTrue();
        assertThat(gate.ask("s", "u", false)).isEmpty();
        assertThat(calls).hasValue(AiGate.TRIP_AFTER); // đang nghỉ: không gọi
        now[0] = fixed("2026-11-23T05:16:00Z");
        assertThat(gate.status().paused()).isFalse();
        gate.ask("s", "u", false);
        assertThat(calls).hasValue(AiGate.TRIP_AFTER + 1);
        assertThat(usage.failuresToday()).isEqualTo(AiGate.TRIP_AFTER + 1);
    }

    @Test
    void thanhCongDatLaiBoDemLoi() {
        AtomicInteger n = new AtomicInteger();
        AiGate gate = new AiGate((s, u, j) -> {
            if (n.incrementAndGet() % 3 != 0) {
                throw new AiClient.AiUnavailableException("lỗi");
            }
            return "ok";
        }, props(true, 100), usage, Clock.systemUTC());
        for (int i = 0; i < 12; i++) {
            gate.ask("s", "u", false); // hai lỗi rồi một thành công: không bao giờ chạm 5 lỗi liên tiếp
        }
        assertThat(gate.status().paused()).isFalse();
    }

    private static Clock fixed(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
    }
}
