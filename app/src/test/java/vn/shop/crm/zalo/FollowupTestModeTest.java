package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.CampaignSteps;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloFollowupSender.Step;
import vn.shop.crm.zalo.ZaloMessenger.Kind;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHẾ ĐỘ THỬ (mặc định) và tin nhắc (D21 PR 5b): dù công tắc tự gửi, nấc tự động và chiến dịch nhiều lần gửi đều BẬT, tin nhắc chỉ đến số thử.
 * Khách "thật" (0900000001..3) đã nhận Tin 1 từ trước (y như trên máy chủ nếu từng chạy thật) vẫn không bao giờ được nhắc.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.safety.live=false",
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.inbox.enabled=true",
        "app.ai.enabled=true",
        "app.ai.api-key=test",
        "app.ai.model=test"})
class FollowupTestModeTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired CampaignSteps steps;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloFollowupSender followups;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;
    @Autowired TestMode mode;

    long campaign;

    private static String phone(int n) {
        return "+84900000" + String.format("%03d", n);
    }

    private long cust(int n) {
        return jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", phone(n)).query(Long.class).single();
    }

    @BeforeEach
    void setUp() {
        // Khai báo số thử TRƯỚC khi nhập đơn (số đã là khách thì bị từ chối).
        mode.add("0900000004", "Số của tôi", "test");
        mode.add("0900000005", "Bạn A", "test");
        StringBuilder csv = new StringBuilder(HEADER);
        for (int n = 1; n <= 5; n++) {
            csv.append("T").append(n).append(",DH").append(n).append(",05/01/2026 09:00:00,Khach ").append(n).append(",09000000")
                    .append(String.format("%02d", n)).append(",1 Duong A Xa B Hà Nội,Giao thành công\n");
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        jdbc.sql("""
                INSERT INTO contact_basis (customer_id, purpose, basis, status, statement, recorded_by)
                SELECT id, 'GROUP_INVITE_POSTSALE', 'TRANSACTION_POSTSALE', 'ELIGIBLE', 'test', 'test' FROM customer""").update();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 30, 20);
        invites.addInvitations(campaign, 30, "Đợt 1", List.of(
                new ContactService.Invitee(cust(1), phone(1), "Khach 1"),
                new ContactService.Invitee(cust(2), phone(2), "Khach 2"),
                new ContactService.Invitee(cust(3), phone(3), "Khach 3")));
        steps.saveFollowups(campaign, List.of(new CampaignSteps.Step(2, "Dạ {cua_hang} hỏi thăm anh/chị dùng có ổn không ạ.", 3)), "mẹ");
        // tất cả đã nhận Tin 1 qua bộ nối từ 5 ngày trước, có mã Zalo
        jdbc.sql("""
                UPDATE invitation SET status = 'SENT', sent_channel = 'ZALO_AUTO', sent_at = '2026-11-17T03:00:00Z',
                       zalo_target_uid = '7000' || id WHERE campaign_id = :c""").param("c", campaign).update();
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
        appSettings.set("zalo.msg.enabled", "false", "test");
        appSettings.set("zalo.msg.daily", "10", "test");
        appSettings.set("zalo.msg.gap_seconds", "120", "test");
        appSettings.set("zalo.auto.mode", "OFF", "test");
        appSettings.set("campaigns.paused", "false", "test");
        cfg.reopen("test");
        cfg.setMode(ZaloMode.AUTO, true, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
    }

    @Test
    void tinNhacChiDenSoThuKhachThatDaNhanTin1VanKhongBaoGioDuocNhac() {
        assertThat(followups.step()).isEqualTo(Step.SENT);
        clock.advance(Duration.ofSeconds(150));
        assertThat(followups.step()).isEqualTo(Step.SENT);
        clock.advance(Duration.ofSeconds(150));
        assertThat(followups.step()).isEqualTo(Step.NOTHING); // hết số thử; khách thật vẫn đến hạn nhưng không được nhắc
        assertThat(fake.messages).hasSize(2);
        assertThat(fake.messages).allSatisfy(m -> assertThat(m.kind()).isEqualTo(Kind.FOLLOWUP2));
        for (int n = 1; n <= 3; n++) {
            long inv = jdbc.sql("SELECT id FROM invitation WHERE customer_id = :c").param("c", cust(n)).query(Long.class).single();
            assertThat(jdbc.sql("SELECT count(*) FROM invitation_touch WHERE invitation_id = :i").param("i", inv).query(Long.class).single())
                    .as("khách thật " + n).isZero();
            assertThat(fake.messages).noneMatch(m -> ("7000" + inv).equals(m.uid()));
        }
    }

    @Test
    void luoiCuoiChanTinNhacDenMaZaloKhongPhaiSoThuKhiBiGoiThang() {
        // gọi thẳng cổng gửi tới mã Zalo của khách thật: SafeZaloLink từ chối trước khi tới bộ nối, không tính hạn mức
        long inv = jdbc.sql("SELECT id FROM invitation WHERE customer_id = :c").param("c", cust(1)).query(Long.class).single();
        var d = messages.sendByUid(ZaloMessageService.SCOPE_REAL, Kind.FOLLOWUP2, inv, "7000" + inv, "Nhắc", null, null);
        assertThat(d.status()).isNotEqualTo(ZaloMessageService.Status.SENT);
        assertThat(fake.messages).isEmpty();
        assertThat(messages.usedToday(null)).isZero();
    }
}
