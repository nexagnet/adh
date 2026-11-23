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
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloInviteSender.Step;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chiến dịch TỰ NHẬN khách mới (chủ shop chốt 23/11/2026): bỏ duyệt từng người, thay bằng tiêu chí duyệt sẵn. Khóa: tắt mặc định, chỉ khách
 * nhập SAU lúc bật, chỉ khách đủ tiêu chí (một lần mua, không cần kiểm tra, chưa từ chối, đúng khu vực), mỗi khách một lời mời, hàng chờ có
 * trần, một chiến dịch tự nhận mỗi lúc, và Tin 1 vẫn đi qua đủ chốt chặn.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class ZaloCampaignIntakeTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloCampaignIntake intake;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    long campaign;

    private void importRows(String... rows) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String r : rows) {
            csv.append(r).append('\n');
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t" + System.nanoTime() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    private static String row(int n, String address, String status) {
        return "T" + n + ",DH" + n + ",05/01/2026 09:00:00,Khach " + n + ",09000000" + String.format("%02d", n) + ",1 Duong A Xa B " + address + "," + status;
    }

    @BeforeEach
    void setUp() {
        // khách có từ trước: 1 đã duyệt (vào chiến dịch), 2 và 3 chưa duyệt (mẹ chủ ý giữ lại)
        importRows(row(1, "Hà Nội", "Giao thành công"), row(2, "Hà Nội", "Giao thành công"), row(3, "Hà Nội", "Giao thành công"));
        contacts.approve(cust(1));
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

    private long cust(int n) {
        return jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", "+84900000" + String.format("%03d", n)).query(Long.class).single();
    }

    private boolean hasInvitation(int n) {
        return jdbc.sql("SELECT count(*) FROM invitation WHERE customer_id = :c").param("c", cust(n)).query(Long.class).single() > 0;
    }

    private String basisBy(int n) {
        return jdbc.sql("SELECT recorded_by FROM contact_basis WHERE customer_id = :c").param("c", cust(n)).query(String.class).optional().orElse(null);
    }

    private void on() {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
        intake.enable(campaign, "mẹ");
    }

    @Test
    void macDinhTatKhachMoiKhongTuVao() {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
        importRows(row(11, "Hà Nội", "Giao thành công"));
        assertThat(intake.intake()).isZero();
        assertThat(hasInvitation(11)).isFalse();
        assertThat(intake.status(campaign).active()).isFalse();
    }

    @Test
    void chiBatDuocKhiChienDichDaDuyetVaMoiLucMotChienDich() {
        assertThatThrownBy(() -> intake.enable(campaign, "mẹ")).isInstanceOf(ImportException.class).hasMessageContaining("duyệt");
        sender.approveCampaign(campaign, "mẹ");
        importRows(row(11, "Hà Nội", "Giao thành công"));
        contacts.approve(cust(11));
        long second = invites.createCampaign("Đợt 2", "Chào {ten}", 14, 5);
        sender.approveCampaign(second, "mẹ");
        intake.enable(campaign, "mẹ");
        assertThatThrownBy(() -> intake.enable(second, "mẹ")).isInstanceOf(ImportException.class).hasMessageContaining("đang tự nhận");
        intake.disable(campaign, "mẹ");
        intake.enable(second, "mẹ");
        assertThat(intake.status(second).active()).isTrue();
    }

    @Test
    void khachMoiDuTieuChiTuDuocDuyetVaCoLoiMoiNhungKhachCuThiKhong() {
        on();
        importRows(row(11, "Hà Nội", "Giao thành công"));
        assertThat(intake.intake()).isEqualTo(1);
        assertThat(hasInvitation(11)).isTrue();
        assertThat(basisBy(11)).isEqualTo(ZaloCampaignIntake.ACTOR);
        assertThat(jdbc.sql("SELECT status FROM invitation WHERE customer_id = :c").param("c", cust(11)).query(String.class).single()).isEqualTo("READY");
        assertThat(jdbc.sql("SELECT campaign_id FROM invitation WHERE customer_id = :c").param("c", cust(11)).query(Long.class).single()).isEqualTo(campaign);
        // khách nhập từ trước mà chưa duyệt (mẹ chủ ý giữ) không bị tự duyệt
        assertThat(hasInvitation(2)).isFalse();
        assertThat(basisBy(2)).isNull();
        assertThat(intake.intake()).isZero(); // không nhận lặp
        assertThat(intake.status(campaign).taken()).isEqualTo(1);
    }

    @Test
    void khachKhongDuTieuChiKhongBaoGioVao() {
        on();
        contacts.optOutByPhone("0900000013", "khach-tu-bam"); // đã từ chối trước khi được nhập về
        importRows(row(12, "Hà Nội", "Mới tạo"),            // chưa có đơn giao thành công
                row(13, "Hà Nội", "Giao thành công"),        // đã từ chối
                row(14, "Hà Nội", "Giao thành công"));
        // khách 14 mua hai lần (REPEAT)
        importRows("T99,DH99,06/01/2026 09:00:00,Khach 14,0900000014,1 Duong A Xa B Hà Nội,Giao thành công");
        assertThat(intake.intake()).isZero();
        assertThat(hasInvitation(12)).isFalse();
        assertThat(hasInvitation(13)).isFalse();
        assertThat(hasInvitation(14)).isFalse();
        assertThat(contacts.isSuppressed("+84900000013")).isTrue();
    }

    @Test
    void chiNhanDungKhuVucCuaChienDich() {
        jdbc.sql("UPDATE invitation_campaign SET geo_scope = 'R:NAM' WHERE id = :id").param("id", campaign).update();
        on();
        importRows(row(11, "Hà Nội", "Giao thành công"), row(12, "Cần Thơ", "Giao thành công"));
        assertThat(intake.intake()).isEqualTo(1);
        assertThat(hasInvitation(12)).isTrue();
        assertThat(hasInvitation(11)).isFalse();
    }

    @Test
    void khongNhanKhiTatCongTacTamDungHayBoDuyet() {
        on();
        importRows(row(11, "Hà Nội", "Giao thành công"));
        messages.setEnabled(false, "mẹ");
        assertThat(intake.intake()).isZero();
        messages.setEnabled(true, "mẹ");
        appSettings.set("campaigns.paused", "true", "test");
        assertThat(intake.intake()).isZero();
        appSettings.set("campaigns.paused", "false", "test");
        sender.revokeCampaign(campaign, "mẹ");
        assertThat(intake.intake()).isZero();
        sender.approveCampaign(campaign, "mẹ");
        assertThat(intake.intake()).isEqualTo(1);
    }

    @Test
    void hangChoCoTranTheoHanMucVaNgayHetHan() {
        on();
        appSettings.set("zalo.msg.daily", "1", "test");
        // hạn mức 1 khách/ngày x (14 - 1) ngày = 13 khách có thể gửi trước khi lời mời hết hạn; đã có 1 khách READY sẵn trong chiến dịch
        String[] many = new String[20];
        for (int i = 0; i < 20; i++) {
            many[i] = row(20 + i, "Hà Nội", "Giao thành công");
        }
        importRows(many);
        int taken = 0;
        for (int i = 0; i < 5; i++) {
            taken += intake.intake();
        }
        assertThat(taken).isEqualTo(12); // 13 - 1 khách đã sẵn
        assertThat(intake.status(campaign).ready()).isEqualTo(13);
    }

    @Test
    void tin1CuaKhachTuNhanVanQuaDuChotChan() {
        on();
        appSettings.set("zalo.msg.daily", "1", "test");
        importRows(row(11, "Hà Nội", "Giao thành công"));
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT); // nhận khách rồi gửi Tin 1 đầu hàng chờ
        clock.advance(Duration.ofSeconds(130));
        assertThat(sender.step()).isEqualTo(Step.BLOCKED); // đủ 1 khách trong ngày
        assertThat(fake.messages).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE customer_id = :c").param("c", cust(11)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void khachBiXoaRoiNhapLaiThiLaKhachMoi() {
        on();
        importRows(row(11, "Hà Nội", "Giao thành công"));
        intake.intake();
        // khách đã có lời mời thì không bao giờ nhận lần hai
        importRows(row(11, "Hà Nội", "Giao thành công"));
        assertThat(intake.intake()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE customer_id = :c").param("c", cust(11)).query(Long.class).single()).isEqualTo(1);
    }
}
