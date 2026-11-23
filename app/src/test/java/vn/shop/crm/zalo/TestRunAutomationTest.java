package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.testrun.TestRunAutomation;
import vn.shop.crm.testrun.TestRunService;
import vn.shop.crm.testrun.TestRunService.Config;
import vn.shop.crm.testrun.TestRunService.Entry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D18: chiến dịch thử chạy qua bộ nối giả: Tin 1 theo hạn mức và khoảng nghỉ cố định, kết bạn, tự thêm vào nhóm đích, Tin 2,
 * và dữ liệu khách thật không đổi. Đặt ở gói zalo để dùng chung bộ nối giả và đồng hồ giả.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class TestRunAutomationTest extends AbstractPostgresTest {

    @Autowired TestRunService tests;
    @Autowired TestRunAutomation automation;
    @Autowired GroupService groups;
    @Autowired ZaloAutoService zalo;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    long group;

    @BeforeEach
    void setUp() {
        group = groups.create("Nhóm thử", "https://zalo.me/g/thu", 50);
        groups.setStatus(group, GroupStatus.OPEN);
        groups.setZaloGroupId(group, "2002");
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
        appSettings.set("zalo.msg.enabled", "false", "test");
        appSettings.set("zalo.msg.daily", "10", "test");
        appSettings.set("zalo.msg.gap_seconds", "120", "test");
        appSettings.set("zalo.auto.mode", "OFF", "test");
        cfg.reopen("test");
    }

    private long createRun(int daily, int gap) {
        return tests.create(new Config("Thử 1", "Chào {ten}, {cua_hang} mời bạn. {link}", "Vào nhóm: {link_nhom}", daily, gap, group, true),
                List.of(new Entry("0900000001", "A", null), new Entry("0900000002", "B", null)));
    }

    private void on() {
        cfg.setMode(ZaloMode.AUTO, true, "mẹ");
        messages.setEnabled(true, "mẹ");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String status(String phoneTail) {
        return jdbc.sql("SELECT status FROM test_target WHERE phone LIKE :p").param("p", "%" + phoneTail).query(String.class).single();
    }

    @Test
    void chuaBamBatDauThiKhongGuiGi() {
        createRun(5, 120);
        on();
        automation.tick();
        assertThat(fake.messages).isEmpty();
    }

    @Test
    void batDauCanCoNhomDich() {
        long run = tests.create(new Config("Không nhóm", "Chào {ten}", "x", 5, 120, null, true), List.of(new Entry("0900000001", null, null)));
        assertThatThrownBy(() -> tests.start(run)).isInstanceOf(ImportException.class).hasMessageContaining("nhóm đích");
    }

    @Test
    void hanMucThapHonMucChungThiDuocCaoHonThiTu() {
        assertThatThrownBy(() -> createRun(9999, 120)).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> createRun(5, 1)).isInstanceOf(ImportException.class);
    }

    @Test
    void tin1TheoKhoangNghiCoDinhRoiKetBanThiThemVaoNhomRoiTin2() {
        long run = createRun(5, 120);
        on();
        tests.start(run);

        automation.tick();
        assertThat(fake.messages).hasSize(1);
        assertThat(fake.messages.get(0).kind()).isEqualTo(ZaloMessenger.Kind.INVITE);
        assertThat(fake.messages.get(0).text()).doesNotContain("{link}").contains("Chào A");
        assertThat(status("0001")).isEqualTo("SENT");

        automation.tick(); // chưa đủ khoảng nghỉ
        assertThat(fake.messages).hasSize(1);

        clock.advance(Duration.ofSeconds(121));
        automation.tick();
        assertThat(fake.messages).hasSize(2);
        assertThat(status("0002")).isEqualTo("SENT");

        // A đồng ý kết bạn: sự kiện chỉ có mã (khách ẩn số)
        String uidA = fake.phoneToUid.values().stream().sorted().findFirst().orElseThrow();
        fake.pushAdd(uidA, null, 1);
        zalo.poll();
        assertThat(status("0001")).isEqualTo("FRIENDED");

        clock.advance(Duration.ofSeconds(121));
        automation.tick(); // thêm vào nhóm đích
        assertThat(fake.adds).hasSize(1);
        assertThat(fake.adds.get(0)).containsExactly(uidA, "2002");
        assertThat(status("0001")).isEqualTo("JOINED");

        clock.advance(Duration.ofSeconds(121));
        automation.tick(); // Tin 2
        assertThat(fake.messages).hasSize(3);
        assertThat(fake.messages.get(2).kind()).isEqualTo(ZaloMessenger.Kind.LINK);
        assertThat(fake.messages.get(2).uid()).isEqualTo(uidA);
        assertThat(fake.messages.get(2).text()).contains("https://zalo.me/g/thu");
        assertThat(count("SELECT count(*) FROM test_target WHERE link_state = 'SENT'")).isEqualTo(1);
    }

    @Test
    void khongDungToiKhachThat() {
        long run = createRun(5, 120);
        on();
        tests.start(run);
        automation.tick();
        clock.advance(Duration.ofSeconds(121));
        automation.tick();
        String uid = fake.phoneToUid.values().stream().sorted().findFirst().orElseThrow();
        fake.pushAdd(uid, null, 1);
        zalo.poll();
        clock.advance(Duration.ofSeconds(121));
        automation.tick();

        assertThat(count("SELECT count(*) FROM customer")).isZero();
        assertThat(count("SELECT count(*) FROM invitation")).isZero();
        assertThat(count("SELECT count(*) FROM send_log")).isZero();
        assertThat(count("SELECT count(*) FROM suppression")).isZero();
        assertThat(groups.find(group).orElseThrow().actual()).isZero(); // số khách thật của nhóm không tăng
    }

    @Test
    void hetHanMucNgayThiDungDuNhieuSoConLai() {
        long run = createRun(1, 120);
        on();
        tests.start(run);
        automation.tick();
        clock.advance(Duration.ofSeconds(300));
        automation.tick();
        assertThat(fake.messages).hasSize(1);
    }

    @Test
    void congTacTatThiDungNgay() {
        long run = createRun(5, 120);
        on();
        tests.start(run);
        messages.setEnabled(false, "mẹ");
        automation.tick();
        assertThat(fake.messages).isEmpty();
    }
}
