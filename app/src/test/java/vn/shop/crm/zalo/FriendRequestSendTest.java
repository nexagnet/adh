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

/**
 * 24/11/2026: sau Tin 1, tài khoản phụ CHỦ ĐỘNG gửi lời mời kết bạn. Khóa các chốt chặn: chỉ cho người đã nhận Tin 1, mỗi người một lời mời,
 * trần lời mời mỗi ngày riêng, khoảng nghỉ cố định, dừng khi khách đã kết bạn / vào nhóm / nhắn lại / bị hủy, không bao giờ gửi lại khi
 * không rõ kết quả, câu kèm lời mời không quá 150 ký tự.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class FriendRequestSendTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired ZaloInviteSender sender;
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

    private void gap() {
        clock.advance(Duration.ofSeconds(125));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** Gửi Tin 1 cho một khách, bỏ các lời mời READY còn lại. Trả mã lời mời. */
    private long inviteOne() {
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE status = 'READY'").update();
        return jdbc.sql("SELECT id FROM invitation WHERE status = 'SENT'").query(Long.class).single();
    }

    private String outcome(long invitation) {
        return jdbc.sql("SELECT friend_request_outcome FROM invitation WHERE id = :i").param("i", invitation)
                .query(String.class).optional().orElse(null);
    }

    @Test
    void sauTin1TaiKhoanPhuGuiMotLoiMoiKetBanChoDungNguoiDoVaKhongGuiLai() {
        long invitation = inviteOne();
        assertThat(fake.friendRequests).isEmpty();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(fake.friendRequests).singleElement().satisfies(r -> {
            assertThat(r.uid()).isNotBlank();
            assertThat(r.text()).isNotBlank().hasSizeLessThanOrEqualTo(ZaloFriendRequestSender.MAX_TEXT);
        });
        assertThat(outcome(invitation)).isEqualTo("SENT");
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'FRIEND'")).isEqualTo(1);
        gap();
        assertThat(sender.step()).isNotEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(fake.friendRequests).hasSize(1);
    }

    @Test
    void chuaDuKhoangNghiCoDinhThiChuaGuiLoiMoi() {
        inviteOne();
        clock.advance(Duration.ofSeconds(3));
        assertThat(sender.step()).isNotEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(fake.friendRequests).isEmpty();
        // 25/11/2026: sau Tin 1 gửi OK, lời mời kết bạn của CHÍNH khách đó chỉ chờ 7 giây cố định, không phải cả khoảng nghỉ 120 giây giữa các khách
        clock.advance(Duration.ofSeconds(5));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(fake.friendRequests).hasSize(1);
    }

    @Test
    void tranLoiMoiMoiNgayLaTranRiengVaKhongChanTin1() {
        messages.setFriendDaily(1, "mẹ");
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        gap();
        // đã hết trần lời mời của hôm nay: người thứ hai nhận Tin 1 nhưng chưa được mời kết bạn, hàng sau vẫn được nhắn Tin 1
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        assertThat(fake.friendRequests).hasSize(1);
        assertThat(messages.friendUsedToday()).isEqualTo(1);
        clock.advance(Duration.ofDays(1));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(fake.friendRequests).hasSize(2);
    }

    @Test
    void khongDatTranVuotTranCung() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> messages.setFriendDaily(51, "mẹ")).isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> messages.setFriendDaily(-1, "mẹ")).isInstanceOf(RuntimeException.class);
        messages.setFriendDaily(0, "mẹ");
        assertThat(messages.friendDaily()).isZero();
        inviteOne();
        gap();
        sender.step();
        assertThat(fake.friendRequests).isEmpty();
    }

    @Test
    void khachDaKetBanDaVaoNhomDaNhanLaiHoacBiHuyThiKhongNhanLoiMoi() {
        for (String set : new String[] {"friended_at = now()", "joined_at = now()", "inbound_at = now()", "status = 'CANCELLED'"}) {
            fake.reset();
            jdbc.sql("DELETE FROM zalo_msg_log").update();
            jdbc.sql("UPDATE invitation SET status = 'READY', sent_at = NULL, friended_at = NULL, joined_at = NULL, inbound_at = NULL,"
                    + " friend_request_at = NULL, friend_request_outcome = NULL").update();
            long invitation = inviteOne();
            jdbc.sql("UPDATE invitation SET " + set + " WHERE id = :i").param("i", invitation).update();
            gap();
            sender.step();
            assertThat(fake.friendRequests).as(set).isEmpty();
        }
    }

    @Test
    void chienDichBoDuyetThiDungGuiLoiMoi() {
        inviteOne();
        sender.revokeCampaign(campaign, "mẹ");
        gap();
        sender.step();
        assertThat(fake.friendRequests).isEmpty();
    }

    @Test
    void khongRoKetQuaThiDanhDauUnknownVaKhongBaoGioGuiLai() {
        long invitation = inviteOne();
        gap();
        fake.willReturnMessage(Sent.of(Result.SYSTEM_ERROR, "TIMEOUT", false));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.TRIPPED);
        assertThat(outcome(invitation)).isEqualTo("UNKNOWN");
        assertThat(fake.friendRequests).hasSize(1);
        // cầu dao đã ngắt; dù mở lại và qua nhiều ngày thì dòng UNKNOWN vẫn không được gửi lại
        cfg.reopen("test");
        clock.advance(Duration.ofDays(2));
        sender.step();
        assertThat(fake.friendRequests).hasSize(1);
        assertThat(outcome(invitation)).isEqualTo("UNKNOWN");
    }

    @Test
    void chacChanChuaChamZaloThiTraChoDeThuLai() {
        long invitation = inviteOne();
        gap();
        fake.willReturnMessage(Sent.of(Result.RATE_LIMITED, null, true));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.RETRY);
        assertThat(outcome(invitation)).isNull();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(outcome(invitation)).isEqualTo("SENT");
    }

    @Test
    void loiRiengNguoiThiDongLoiMoiVaKhongThuLai() {
        long invitation = inviteOne();
        gap();
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, "NO_INVITE", true));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FAILED_ONE);
        assertThat(outcome(invitation)).isEqualTo("FAILED");
        gap();
        sender.step();
        assertThat(fake.friendRequests).hasSize(1);
    }
}
