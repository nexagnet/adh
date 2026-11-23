package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cảnh báo sớm khi tài khoản Zalo phụ có dấu hiệu bị hạn chế, với bộ nối GIẢ và kênh báo ngoài GIẢ: loại dấu hiệu, người tự dừng
 * thì không báo, tỷ lệ lỗi, cầu nối mất, báo ngoài đúng một lần, nội dung báo không có dữ liệu khách.
 */
@Import({ZaloTestConfig.class, ZaloHealthTest.Recorder.class})
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class ZaloHealthTest extends AbstractPostgresTest {

    static class RecordingNotifier implements ZaloHealthNotifier {
        final List<String> sent = new ArrayList<>();
        boolean ok = true;

        @Override
        public boolean send(String text) {
            sent.add(text);
            return ok;
        }
    }

    @TestConfiguration
    static class Recorder {
        @Bean
        RecordingNotifier recordingNotifier() {
            return new RecordingNotifier();
        }
    }

    @Autowired ZaloHealthService health;
    @Autowired ZaloAutoService service;
    @Autowired ZaloAutoSettings cfg;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;
    @Autowired RecordingNotifier notifier;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;

    @BeforeEach
    void setUp() {
        fake.reset();
        notifier.sent.clear();
        notifier.ok = true;
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        service.bridgeStatus(); // LISTENING, đúng tài khoản
        health.evaluate();
    }

    private void msg(String outcome, String code, int times) {
        for (int i = 0; i < times; i++) {
            jdbc.sql("INSERT INTO zalo_msg_log (at, scope, kind, outcome, code) VALUES (:at, 'REAL', 'INVITE', :o, :c)")
                    .param("at", java.time.OffsetDateTime.ofInstant(clock.instant().minusSeconds(60), java.time.ZoneOffset.UTC))
                    .param("o", outcome).param("c", code, java.sql.Types.VARCHAR).update();
        }
    }

    private List<String> kinds() {
        return health.open().stream().map(ZaloHealthService.Alert::kind).toList();
    }

    @Test
    void khoeManhThiKhongCoCanhBao() {
        assertThat(health.evaluate()).isEmpty();
        assertThat(health.open()).isEmpty();
        assertThat(notifier.sent).isEmpty();
    }

    @Test
    void boNoiTatThiKhongCanhBaoDuCoCauDaoNhay() {
        cfg.setMode(ZaloMode.OFF, false, "mẹ");
        cfg.trip("SYSTEM_ERROR:Z1", "system");
        assertThat(health.evaluate()).isEmpty();
    }

    @Test
    void loiLaLamCauDaoNhayThiBaoKhanKemMaLoiVaBaoNgoaiDungMotLan() {
        cfg.trip("SYSTEM_ERROR:Z123", "system");
        assertThat(health.evaluate()).extracting(ZaloHealthService.Signal::kind).containsExactly(ZaloHealthService.K_STRANGE);
        var alert = health.open().get(0);
        assertThat(alert.critical()).isTrue();
        assertThat(alert.detail()).isEqualTo("SYSTEM_ERROR:Z123");
        assertThat(notifier.sent).hasSize(1);
        assertThat(notifier.sent.get(0)).contains("KHẨN").contains("SYSTEM_ERROR:Z123");
        health.evaluate();
        health.evaluate();
        assertThat(notifier.sent).as("không báo lặp mỗi nhịp").hasSize(1);
        assertThat(health.openCriticalCount()).isEqualTo(1);
    }

    @Test
    void mangMaNgoaiLeKhongLotVaoCanhBao() {
        cfg.trip("SYSTEM_ERROR:<b>0900000001</b>", "system");
        health.evaluate();
        assertThat(health.open().get(0).detail()).doesNotContain("<").doesNotContain(">");
    }

    @Test
    void loiLaKhiTraSoCuaMeCungDuocPhanLoaiTheoKetQuaGoc() {
        cfg.trip("TRA_SO_ME_RESTRICTED", "system");
        assertThat(health.evaluate()).extracting(ZaloHealthService.Signal::kind).containsExactly(ZaloHealthService.K_RESTRICTED);
    }

    @Test
    void moLaiXongThiDongCanhBaoVaGhiLichSu() {
        cfg.trip("RESTRICTED", "system");
        health.evaluate();
        assertThat(kinds()).containsExactly(ZaloHealthService.K_RESTRICTED);
        cfg.reopen("mẹ");
        health.evaluate();
        assertThat(health.open()).isEmpty();
        var history = health.recent(10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).resolvedAt()).isNotNull();
    }

    @Test
    void nguoiTuBamDungHoacDangXuatThiKhongPhaiCanhBao() {
        cfg.trip("DUNG_TAY", "mẹ");
        assertThat(health.evaluate()).isEmpty();
        cfg.reopen("mẹ");
        cfg.trip("DANG_XUAT", "mẹ");
        fake.status = new ZaloGateway.Status(ZaloGateway.State.LOGIN_REQUIRED, null, null, null, null, null);
        service.bridgeStatus();
        assertThat(health.evaluate()).as("đăng xuất rồi cần quét QR lại là chuyện của chính người đó").isEmpty();
        assertThat(notifier.sent).isEmpty();
    }

    @Test
    void phienHetHanKhongDoNguoiBamThiBaoMatPhien() {
        fake.status = new ZaloGateway.Status(ZaloGateway.State.LOGIN_REQUIRED, null, null, null, null, null);
        service.bridgeStatus();
        assertThat(health.evaluate()).extracting(ZaloHealthService.Signal::kind).containsExactly(ZaloHealthService.K_LOGIN);
        cfg.trip("SESSION_LOST", "system");
        assertThat(health.evaluate()).hasSize(1);
        assertThat(kinds()).containsExactly(ZaloHealthService.K_LOGIN);
    }

    @Test
    void dangNhapNhamTaiKhoanVaMoNoiKhacBaoKhan() {
        fake.status = new ZaloGateway.Status(ZaloGateway.State.LISTENING, null, false, null, null, null);
        service.bridgeStatus();
        assertThat(health.evaluate()).extracting(ZaloHealthService.Signal::kind).containsExactly(ZaloHealthService.K_ACCOUNT);
        fake.reset();
        service.bridgeStatus();
        cfg.trip("BRIDGE_DUPLICATE_CONNECTION", "system");
        assertThat(health.evaluate()).extracting(ZaloHealthService.Signal::kind).containsExactly(ZaloHealthService.K_ACCOUNT);
    }

    @Test
    void tyLeLoiCaoBaoTruocKhiCauDaoNhayVaNangLenKhanThiBaoLaiMotLan() {
        msg("OK", null, 6);
        msg("PERSON_ERROR", "LOI_NGUOI", 4); // 4/10 = 40%
        assertThat(health.evaluate()).extracting(ZaloHealthService.Signal::kind).containsExactly(ZaloHealthService.K_FAIL_RATE);
        assertThat(health.open().get(0).critical()).isFalse();
        assertThat(health.open().get(0).detail()).isEqualTo("4/10");
        assertThat(notifier.sent).hasSize(1);
        assertThat(notifier.sent.get(0)).contains("lưu ý");
        health.evaluate();
        assertThat(notifier.sent).hasSize(1);

        msg("PERSON_ERROR", "LOI_NGUOI", 10); // 14/20 = 70%
        health.evaluate();
        assertThat(health.open().get(0).critical()).isTrue();
        assertThat(notifier.sent).as("nặng lên thì báo thêm một lần").hasSize(2);
        assertThat(notifier.sent.get(1)).contains("KHẨN");
        assertThat(health.openCriticalCount()).isEqualTo(1);
        assertThat(cfg.tripped()).as("lớp cảnh báo không tự dừng gì").isFalse();
    }

    @Test
    void khongTimThayTrenZaloKhongTinhLaLoiVaItTinThiChuaXet() {
        msg("OK", null, 2);
        msg("PERSON_ERROR", "NOT_FOUND", 8);
        assertThat(health.evaluate()).isEmpty();
        jdbc.sql("DELETE FROM zalo_msg_log").update();
        msg("PERSON_ERROR", "LOI_NGUOI", 4); // 4 tin: chưa đủ để xét
        assertThat(health.evaluate()).isEmpty();
    }

    @Test
    void tinCuHon24GioKhongTinh() {
        jdbc.sql("INSERT INTO zalo_msg_log (at, scope, kind, outcome, code) SELECT :at, 'REAL', 'INVITE', 'PERSON_ERROR', 'LOI_NGUOI' FROM generate_series(1, 10)")
                .param("at", java.time.OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofHours(30)), java.time.ZoneOffset.UTC)).update();
        assertThat(health.evaluate()).isEmpty();
    }

    @Test
    void cauNoiMatQuaMuoiLamPhutMoiBaoVaHetKhiGoiDuocLai() {
        fake.status = ZaloGateway.Status.of(ZaloGateway.State.UNREACHABLE);
        service.bridgeStatus();
        assertThat(health.evaluate()).isEmpty();
        clock.advance(Duration.ofMinutes(10));
        assertThat(health.evaluate()).isEmpty();
        clock.advance(Duration.ofMinutes(6));
        assertThat(health.evaluate()).extracting(ZaloHealthService.Signal::kind).containsExactly(ZaloHealthService.K_BRIDGE_DOWN);
        assertThat(health.open().get(0).critical()).isFalse();
        fake.reset();
        service.bridgeStatus();
        health.evaluate();
        assertThat(health.open()).isEmpty();
        fake.status = ZaloGateway.Status.of(ZaloGateway.State.UNREACHABLE);
        service.bridgeStatus();
        clock.advance(Duration.ofMinutes(20));
        assertThat(health.evaluate()).as("mất lại thì tính lại từ đầu").isEmpty();
    }

    @Test
    void baoNgoaiLoiThiThuLaiMoiNhipChoDenKhiDuocRoiThoi() {
        notifier.ok = false;
        cfg.trip("RESTRICTED", "system");
        health.evaluate();
        health.evaluate();
        assertThat(notifier.sent).as("lỗi thì thử lại").hasSize(2);
        notifier.ok = true;
        health.evaluate();
        assertThat(notifier.sent).hasSize(3);
        health.evaluate();
        assertThat(notifier.sent).as("đã báo được thì thôi").hasSize(3);
    }

    @Test
    void trangQuanTriVaTongQuanHienCanhBaoBangChuDeHieu() throws Exception {
        cfg.trip("RESTRICTED", "system");
        health.evaluate();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/zalo").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sức khỏe tài khoản phụ")))
                .andExpect(content().string(containsString("Zalo có dấu hiệu hạn chế tài khoản phụ")))
                .andExpect(content().string(containsString("Đừng bấm Mở lại ngay")));
        mvc.perform(get("/").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Zalo có dấu hiệu hạn chế tài khoản phụ")));
    }
}
