package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.CampaignPolicy.Raw;
import vn.shop.crm.zalo.ZaloInviteSender.Step;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D21 PR 4: lịch và nhịp RIÊNG của chiến dịch chỉ làm CHẶT hơn trần hệ thống, không bao giờ nới; để trống thì chạy y như trước;
 * chiến dịch chưa tới lượt không làm tắc chiến dịch khác; mọi chốt chặn của hệ thống vẫn chạy sau đó.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class CampaignPolicyTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloAutoService zalo;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;
    @Autowired CampaignPolicy policy;

    long campaign;
    long north;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 5; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",090000000").append(i).append(",1 Duong A Xa B Hà Nội,Giao thành công\n");
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        north = groups.create("Nhóm miền Bắc", "https://zalo.me/g/bac", 50, "R:BAC");
        groups.setStatus(north, GroupStatus.OPEN);
        groups.setZaloGroupId(north, "1001");
        contacts.approveAllCandidates();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
        appSettings.set("zalo.msg.enabled", "false", "test");
        appSettings.set("zalo.msg.daily", "10", "test");
        appSettings.set("zalo.msg.gap_seconds", "120", "test");
        appSettings.set("zalo.auto.mode", "OFF", "test");
        appSettings.set("campaigns.paused", "false", "test");
        cfg.reopen("test");
    }

    // ---------- tiện ích ----------

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void ready() {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
    }

    private void after(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    private Raw raw(LocalDate start, LocalDate end, LocalTime from, LocalTime to, Integer cap, Integer gap) {
        return new Raw(start, end, from, to, cap, gap);
    }

    // ---------- để trống = y như trước ----------

    @Test
    void chienDichChuaDatGiThiKhongBiKiemGiVaGuiNhuCu() {
        ready();
        assertThat(policy.load(campaign)).isEqualTo(Raw.NONE);
        assertThat(policy.blocker(campaign)).isEmpty();
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        after(120);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).hasSize(2);
    }

    // ---------- từng núm chỉ làm chặt hơn ----------

    @Test
    void chuaToiNgayBatDauThiKhongGuiToiNgayThiGui() {
        ready();
        policy.save(campaign, raw(TODAY.plusDays(1), null, null, null, null, null), "mẹ");
        assertThat(policy.blocker(campaign)).contains(CampaignPolicy.Block.NOT_STARTED);
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).isEmpty();
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'READY'")).isEqualTo(5);
        clock.set(LocalDateTime.of(2026, 11, 23, 10, 0));
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
    }

    @Test
    void quaNgayKetThucThiKhongGuiNua() {
        ready();
        policy.save(campaign, raw(null, TODAY, null, null, null, null), "mẹ");
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        clock.set(LocalDateTime.of(2026, 11, 23, 10, 0));
        assertThat(policy.blocker(campaign)).contains(CampaignPolicy.Block.ENDED);
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).hasSize(1);
    }

    @Test
    void khungGioRiengHepHonKhungGioHeThong() {
        ready();
        policy.save(campaign, raw(null, null, LocalTime.of(12, 0), LocalTime.of(13, 0), null, null), "mẹ");
        assertThat(sender.step()).isEqualTo(Step.NOTHING); // 10:00: hệ thống cho gửi (08:00-20:00) nhưng chiến dịch chưa tới giờ
        assertThat(fake.messages).isEmpty();
        clock.set(LocalDateTime.of(2026, 11, 22, 12, 30));
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        after(120);
        clock.set(LocalDateTime.of(2026, 11, 22, 13, 0)); // hết khung giờ riêng (khoảng mở bên phải)
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).hasSize(1);
    }

    @Test
    void hanMucNgayRiengChiLamThapHon() {
        ready();
        policy.save(campaign, raw(null, null, null, null, 1, null), "mẹ");
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        after(120);
        assertThat(messages.limits().daily()).isEqualTo(10); // hệ thống còn cho gửi nhiều hơn
        assertThat(policy.blocker(campaign)).contains(CampaignPolicy.Block.DAILY_CAP);
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).hasSize(1);
        clock.set(LocalDateTime.of(2026, 11, 23, 10, 0)); // sang ngày mới thì đếm lại
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).hasSize(2);
    }

    @Test
    void nghiRiengDaiHonNghiChung() {
        ready();
        policy.save(campaign, raw(null, null, null, null, null, 600), "mẹ");
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        after(200); // qua khoảng nghỉ chung (120) nhưng chưa tới khoảng nghỉ riêng (600)
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        after(399);
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        after(1);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).hasSize(2);
    }

    @Test
    void chienDichChuaToiLuotKhongLamTacChienDichKhac() {
        long second = jdbc.sql("INSERT INTO invitation_campaign (name, message_template, expires_days, created_by, auto_send_approved_at, auto_send_approved_by)"
                + " VALUES ('Đợt 2', 'Chào {ten}, đợt hai.', 14, 'test', now(), 'test') RETURNING id").query(Long.class).single();
        // ba khách cuối sang chiến dịch 2; chiến dịch 1 (có id nhỏ hơn nên đi trước) còn 2 khách nhưng chưa tới ngày bắt đầu
        jdbc.sql("UPDATE invitation SET campaign_id = :c WHERE id IN (SELECT id FROM invitation ORDER BY id DESC LIMIT 3)")
                .param("c", second).update();
        ready();
        policy.save(campaign, raw(TODAY.plusDays(3), null, null, null, null, null), "mẹ");
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).singleElement().satisfies(m -> assertThat(m.text()).contains("đợt hai"));
        assertThat(count("SELECT count(*) FROM invitation WHERE campaign_id = " + campaign + " AND status = 'READY'")).isEqualTo(2);
    }

    // ---------- không bao giờ nới ----------

    @Test
    void khongNoiDuocTranHeThong() {
        messages.setLimits(10, 120, "mẹ");
        assertThatThrownBy(() -> policy.save(campaign, raw(null, null, LocalTime.of(6, 0), LocalTime.of(10, 0), null, null), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("không được nới");
        assertThatThrownBy(() -> policy.save(campaign, raw(null, null, LocalTime.of(9, 0), LocalTime.of(23, 0), null, null), "mẹ"))
                .isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> policy.save(campaign, raw(null, null, null, null, 11, null), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("không được cao hơn");
        assertThatThrownBy(() -> policy.save(campaign, raw(null, null, null, null, 0, null), "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> policy.save(campaign, raw(null, null, null, null, null, 119), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("không được ngắn hơn");
        assertThatThrownBy(() -> policy.save(campaign, raw(null, null, null, null, null, 3601), "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> policy.save(campaign, raw(null, null, LocalTime.of(9, 0), null, null, null), "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> policy.save(campaign, raw(null, null, LocalTime.of(9, 0), LocalTime.of(9, 30), null, null), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("ít nhất 60 phút");
        assertThatThrownBy(() -> policy.save(campaign, raw(TODAY.plusDays(2), TODAY.plusDays(1), null, null, null, null), "mẹ"))
                .isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> policy.save(campaign, raw(null, TODAY.minusDays(1), null, null, null, null), "mẹ"))
                .isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> policy.save(9999, raw(null, null, null, null, 1, null), "mẹ")).isInstanceOf(ImportException.class);
        assertThat(policy.load(campaign)).as("không có gì được lưu").isEqualTo(Raw.NONE);
    }

    @Test
    void hieuLucLaGiaTriChatHonKhiHeThongSiet() {
        policy.save(campaign, raw(null, null, LocalTime.of(9, 0), LocalTime.of(18, 0), 8, 300), "mẹ");
        messages.setLimits(3, 600, "mẹ"); // hệ thống siết sau khi chiến dịch đã đặt
        var e = policy.effective(policy.load(campaign));
        assertThat(e.dailyCap()).isEqualTo(3);
        assertThat(e.gapSeconds()).isEqualTo(600);
        assertThat(e.windowFrom()).isEqualTo(LocalTime.of(9, 0));
        assertThat(e.windowTo()).isEqualTo(LocalTime.of(18, 0));
        // và để trống thì đúng bằng hệ thống
        var sys = policy.effective(Raw.NONE);
        assertThat(sys.dailyCap()).isEqualTo(3);
        assertThat(sys.gapSeconds()).isEqualTo(600);
        assertThat(sys.windowFrom()).isEqualTo(LocalTime.of(8, 0));
        assertThat(sys.windowTo()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void duyetDeXuatCuaAiKepTrongTranHeThongKhongNoi() {
        messages.setLimits(5, 120, "mẹ");
        policy.saveClamped(campaign, raw(TODAY, TODAY.plusDays(5), LocalTime.of(6, 0), LocalTime.of(23, 0), 40, null), "mẹ");
        var r = policy.load(campaign);
        assertThat(r.windowFrom()).isEqualTo(LocalTime.of(8, 0));
        assertThat(r.windowTo()).isEqualTo(LocalTime.of(22, 0));
        assertThat(r.dailyCap()).isEqualTo(5);
        assertThat(r.gapSeconds()).isNull();
        // khung giờ sau khi kẹp mà quá hẹp thì bỏ khung giờ riêng, theo hệ thống
        policy.saveClamped(campaign, raw(null, null, LocalTime.of(19, 30), LocalTime.of(23, 0), null, null), "mẹ");
        assertThat(policy.load(campaign).windowFrom()).isNull();
        assertThat(policy.load(campaign).windowTo()).isNull();
    }

    @Test
    void xoaGiaTriRiengThiVeTheoHeThong() {
        policy.save(campaign, raw(TODAY, TODAY.plusDays(3), LocalTime.of(9, 0), LocalTime.of(11, 0), 2, 300), "mẹ");
        assertThat(policy.load(campaign).any()).isTrue();
        policy.save(campaign, Raw.NONE, "mẹ");
        assertThat(policy.load(campaign)).isEqualTo(Raw.NONE);
        assertThat(policy.blocker(campaign)).isEmpty();
    }
}
