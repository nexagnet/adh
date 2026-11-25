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
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloInviteSender.Step;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D23, PR 4: khách MỚI thuộc về tài khoản còn nhiều chỗ trống nhất (bằng nhau thì mã nhỏ trước), gắn CỐ ĐỊNH lúc Tin 1; mọi việc sau đó
 * (lời mời kết bạn...) chỉ do đúng tài khoản đó làm. Dùng hai bộ nối giả độc lập qua bộ định tuyến thật.
 */
@Import(ZaloTwoBridgeSchedulerTest.Bridges.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.multi.enabled=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2",
        "app.zalo.accounts[0].live=true"})
class ZaloMultiAccountSendTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired ZaloTwoBridgeSchedulerTest.BridgePair bridges;
    @Autowired ZaloTestClock clock;
    @Autowired ZaloAccountRegistry registry;

    long campaign;

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
        contacts.approveAllCandidates();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 30, 20);
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        bridges.reset();
        appSettings.set("campaigns.paused", "false", "test");
        registry.setEnabled(2, true);
        for (long acct : new long[]{1, 2}) {
            ZaloAccountContext.run(acct, () -> {
                appSettings.set("zalo.msg.enabled", "false", "test");
                appSettings.set("zalo.msg.daily", "10", "test");
                appSettings.set("zalo.msg.gap_seconds", "120", "test");
                appSettings.set("zalo.friend.daily", "0", "test"); // lời mời kết bạn tắt để chỉ xem việc chia khách; bài riêng bật lại
                appSettings.set("zalo.auto.mode", "OFF", "test");
                cfg.reopen("test");
                cfg.setMode(ZaloMode.AUTO, true, "mẹ");
                messages.setEnabled(true, "mẹ");
            });
        }
        sender.approveCampaign(campaign, "mẹ");
    }

    private Step stepAs(long account) {
        return ZaloAccountContext.call(account, sender::step);
    }

    private void gap() {
        clock.advance(Duration.ofSeconds(125));
    }

    private Long accountOfSent(int n) {
        return jdbc.sql("SELECT i.account_id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p AND i.status = 'SENT'")
                .param("p", "+8490000000" + n).query(Long.class).optional().orElse(null);
    }

    @Test
    void haiTaiKhoanDangChayVaTaiKhoanMotDungDau() {
        assertThat(registry.active()).containsExactly(1L, 2L);
    }

    @Test
    void khachMoiChiaTheoChoTrongVaGanCoDinhLucTin1() {
        // bằng nhau (10 chỗ mỗi bên): tài khoản 2 chưa tới lượt, tài khoản 1 nhận khách đầu tiên
        assertThat(stepAs(2)).isEqualTo(Step.NOTHING);
        assertThat(bridges.second().messages).isEmpty();
        assertThat(stepAs(1)).isEqualTo(Step.INVITE_SENT);
        long first = jdbc.sql("SELECT i.account_id FROM invitation i WHERE i.status = 'SENT'").query(Long.class).single();
        assertThat(first).isEqualTo(1L);

        // tài khoản 1 còn nghỉ: tài khoản 2 (đang được phép gửi) nhận khách kế tiếp
        clock.advance(Duration.ofSeconds(8));
        assertThat(stepAs(2)).isEqualTo(Step.INVITE_SENT);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE status = 'SENT' AND account_id = 2").query(Long.class).single()).isEqualTo(1L);

        // cả hai hết nghỉ, 9 chỗ mỗi bên: lại tài khoản 1 trước
        gap();
        assertThat(stepAs(2)).isEqualTo(Step.NOTHING);
        assertThat(stepAs(1)).isEqualTo(Step.INVITE_SENT);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE status = 'SENT' AND account_id = 1").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void viecSauTin1ChiDoDungTaiKhoanDaNhanKhach() {
        ZaloAccountContext.run(1, () -> appSettings.set("zalo.friend.daily", "10", "test"));
        ZaloAccountContext.run(2, () -> appSettings.set("zalo.friend.daily", "10", "test"));
        assertThat(stepAs(1)).isEqualTo(Step.INVITE_SENT);
        clock.advance(Duration.ofSeconds(8));
        // lời mời kết bạn của khách này chỉ tài khoản 1 được gửi; tài khoản 2 không đụng tới (nó nhận khách mới thay vì kết bạn)
        Step other = stepAs(2);
        assertThat(other).isNotEqualTo(Step.FRIEND_SENT);
        assertThat(bridges.first().friendRequests).isEmpty();
        assertThat(bridges.second().friendRequests).isEmpty();
        gap();
        assertThat(stepAs(1)).isEqualTo(Step.FRIEND_SENT);
        assertThat(bridges.first().friendRequests).hasSize(1);
        assertThat(bridges.second().friendRequests).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_msg_log WHERE kind = 'FRIEND' AND account_id = 1").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void tranTongChanCaHaiTaiKhoan() {
        messages.setTotalDaily(2, "mẹ");
        assertThat(stepAs(1)).isEqualTo(Step.INVITE_SENT);
        clock.advance(Duration.ofSeconds(8));
        assertThat(stepAs(2)).isEqualTo(Step.INVITE_SENT);
        gap();
        assertThat(stepAs(1)).isEqualTo(Step.BLOCKED);
        assertThat(stepAs(2)).isEqualTo(Step.BLOCKED);
        assertThat(bridges.first().messages).hasSize(1);
        assertThat(bridges.second().messages).hasSize(1);
    }
}
