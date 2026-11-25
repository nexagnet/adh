package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloMessenger.Result;
import vn.shop.crm.zalo.ZaloMessenger.Sent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 25/11/2026: bộ đếm hàng đợi chỉ đọc, phải khớp đúng với lúc gửi thật (nghỉ 7 giây cùng khách, nghỉ giữa các khách). */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class ZaloQueueViewTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloQueueView queue;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;
    @Autowired JdbcClient jdbc;

    long campaign;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 3; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",090000000").append(i).append(",1 Duong A Xa B Hà Nội,Giao thành công\n");
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 30, 20);
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
        appSettings.set("zalo.msg.enabled", "false", "test");
        appSettings.set("zalo.msg.daily", "10", "test");
        appSettings.set("zalo.msg.gap_seconds", "120", "test");
        appSettings.set("zalo.friend.daily", "10", "test");
        appSettings.set("zalo.auto.mode", "OFF", "test");
        appSettings.set("campaigns.paused", "false", "test");
        cfg.reopen("test");
        cfg.setMode(ZaloMode.AUTO, true, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
    }

    /** Tên khách đã nhận Tin 1 sớm nhất. */
    private String firstSentName() {
        return jdbc.sql("SELECT c.name FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE i.status = 'SENT' ORDER BY i.sent_at, i.id LIMIT 1")
                .query(String.class).single();
    }

    @Test
    void viecKeTiepLaKetBanCuaCungKhachVaChoDung7Giay() {
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        var p = queue.preview();
        assertThat(p.first().what()).isEqualTo("Gửi lời mời kết bạn");
        assertThat(p.first().who()).contains(firstSentName()).contains("…");
        assertThat(p.next().at()).isEqualTo(clock.instant().plusSeconds(ZaloMessageService.SAME_CUSTOMER_GAP_SECONDS));
        assertThat(p.next().reason()).contains("7 giây").contains("cùng một khách");
        assertThat(p.upcoming()).extracting(ZaloQueueView.Item::what).containsExactly("Gửi lời mời kết bạn", "Tin 1", "Tin 1");
        // đúng mốc thì gửi thật
        clock.advance(Duration.ofSeconds(6));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.BLOCKED);
        clock.advance(Duration.ofSeconds(1));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
    }

    @Test
    void khachKhacChenGiuaThiMoiKhachTinhNghiRieng() {
        appSettings.set("zalo.friend.daily", "0", "test"); // tạm không kết bạn để Tin 1 của khách 2 đi trước
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        String firstName = firstSentName();
        clock.advance(Duration.ofSeconds(125));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT); // khách 2
        appSettings.set("zalo.friend.daily", "10", "test");
        var p = queue.preview();
        assertThat(p.first().what()).isEqualTo("Gửi lời mời kết bạn");
        // khách 2 vừa có tin gần nhất nên được kết bạn sau 7 giây; khách 1 (đứng trước trong hàng) phải chờ nghỉ giữa các khách (120 giây)
        assertThat(p.first().who()).doesNotContain(firstName).contains("Khach");
        assertThat(p.next().at()).isEqualTo(clock.instant().plusSeconds(ZaloMessageService.SAME_CUSTOMER_GAP_SECONDS));
        assertThat(p.next().reason()).contains("7 giây").contains("cùng một khách");
        assertThat(p.upcoming()).anyMatch(i -> i.who().contains(firstName));
        clock.advance(Duration.ofSeconds(6));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.BLOCKED);
        clock.advance(Duration.ofSeconds(1));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        // lời mời vừa đi là của khách 2; khách 1 bây giờ phải chờ nghỉ giữa các khách tính từ lời mời đó
        var after = queue.preview();
        assertThat(after.first().who()).contains(firstName);
        assertThat(after.next().at()).isEqualTo(clock.instant().plusSeconds(120));
        assertThat(after.next().reason()).contains("120 giây").contains("giữa hai khách");
        clock.advance(Duration.ofSeconds(119));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.BLOCKED);
        clock.advance(Duration.ofSeconds(1));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
    }

    @Test
    void daDuHanMucKetBanThiGhiChuVaChuyenSangTin1() {
        appSettings.set("zalo.friend.daily", "0", "test");
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        var p = queue.preview();
        assertThat(p.notes()).anyMatch(n -> n.contains("lời mời kết bạn"));
        assertThat(p.first().what()).isEqualTo("Tin 1");
        assertThat(p.next().at()).isEqualTo(clock.instant().plusSeconds(120));
    }

    @Test
    void ngoaiKhungGioThiChoDen8GioSang() {
        clock.set(LocalDateTime.of(2026, 11, 22, 23, 0));
        var p = queue.preview();
        assertThat(p.first().what()).isEqualTo("Tin 1");
        assertThat(p.next().reason()).contains("Ngoài khung giờ");
        assertThat(p.next().at()).isEqualTo(LocalDateTime.of(2026, 11, 23, 8, 0).atZone(clock.getZone()).toInstant());
    }

    @Test
    void hangTrongThiChiNoiHeThongDangChoGi() {
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED'").update();
        var p = queue.preview();
        assertThat(p.first()).isNull();
        assertThat(p.upcoming()).isEmpty();
        assertThat(p.next().reason()).isNotBlank();
    }
}
