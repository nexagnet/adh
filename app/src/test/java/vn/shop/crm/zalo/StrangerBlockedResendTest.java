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
 * 24/11/2026: Zalo từ chối Tin 1 vì khách không nhận tin của người lạ (Z122, bộ nối báo STRANGER_BLOCKED). Đó là lỗi RIÊNG khách: không dừng gì,
 * kết bạn trước bằng câu không nhắc tới tin đã gửi, khách đồng ý kết bạn thì Tin 1 được gửi lại đúng một lần; chưa gửi lại thì chưa thêm vào nhóm
 * và không nhận tin nhắc; không rõ kết quả gửi lại thì không bao giờ tự gửi lại.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class StrangerBlockedResendTest extends AbstractPostgresTest {

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

    private static final String UID = "9777001";

    /** Tin 1 bị Zalo từ chối vì khách không nhận tin người lạ; bỏ các lời mời READY còn lại. Trả mã lời mời. */
    private long blockedInvite() {
        fake.willReturnMessage(new Sent(Result.PERSON_ERROR, "STRANGER_BLOCKED", UID, 0, false));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FAILED_ONE);
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE status = 'READY'").update();
        return jdbc.sql("SELECT id FROM invitation WHERE tin1_blocked_at IS NOT NULL").query(Long.class).single();
    }

    private void friended(long invitation) {
        jdbc.sql("UPDATE invitation SET status = 'FRIENDED', friended_at = now(), zalo_uid = :u WHERE id = :i")
                .param("u", UID).param("i", invitation).update();
    }

    @Test
    void tin1BiChanVoiNguoiLaLaLoiRiengKhachKhongDungGiVaDuocKetBanTruoc() {
        long invitation = blockedInvite();
        assertThat(cfg.tripped()).isFalse();
        var row = jdbc.sql("SELECT status, zalo_target_uid, tin1_resent_at FROM invitation WHERE id = :i").param("i", invitation)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).single();
        assertThat(row[0]).isEqualTo("SENT");
        assertThat(row[1]).isEqualTo(UID);
        assertThat(row[2]).isNull();
        assertThat(jdbc.sql("SELECT outcome FROM invitation_touch WHERE invitation_id = :i AND step_no = 1").param("i", invitation)
                .query(String.class).single()).isEqualTo("FAILED");
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(fake.friendRequests).singleElement().satisfies(r -> {
            assertThat(r.uid()).isEqualTo(UID);
            assertThat(r.text()).hasSizeLessThanOrEqualTo(ZaloFriendRequestSender.MAX_TEXT).doesNotContain("vừa nhắn mời");
        });
        gap();
        assertThat(sender.step()).isNotEqualTo(ZaloInviteSender.Step.FRIEND_SENT); // mỗi người một lời mời
    }

    @Test
    void khachChanTinLaDuocKetBanNgayKhongChoThemMotLanNghi() {
        long invitation = blockedInvite();
        // 25/11/2026: Tin 1 bị chặn chưa tới khách nên lời mời kết bạn đi NGAY, không phải chờ thêm 2 phút
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(fake.friendRequests).singleElement().satisfies(r -> assertThat(r.uid()).isEqualTo(UID));
        assertThat(invitation).isPositive();
        // khoảng nghỉ giữa các KHÁCH vẫn giữ: ngay sau lời mời kết bạn chưa có tin nào khác đi
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.BLOCKED);
    }

    @Test
    void khachNhanTin1BinhThuongVanChoDuKhoangNghiTruocKhiKetBan() {
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.BLOCKED);
        assertThat(fake.friendRequests).isEmpty();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
    }

    @Test
    void khachChuaKetBanThiChuaGuiLaiTin1() {
        blockedInvite();
        gap();
        sender.step(); // lời mời kết bạn
        long messagesBefore = fake.messages.size();
        clock.advance(Duration.ofDays(10));
        sender.step();
        sender.step();
        assertThat(fake.messages).hasSize((int) messagesBefore); // chưa kết bạn thì chưa gửi lại Tin 1
    }

    @Test
    void khachDongYKetBanThiTin1DuocGuiLaiDungMotLanTheoMaZalo() {
        long invitation = blockedInvite();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        friended(invitation);
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        assertThat(fake.messages).last().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(ZaloMessenger.Kind.INVITE);
            assertThat(m.uid()).isEqualTo(UID);
            assertThat(m.phone()).isNull();
        });
        assertThat(count("SELECT count(*) FROM invitation WHERE id = " + invitation + " AND tin1_resent_at IS NOT NULL")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT outcome FROM invitation_touch WHERE invitation_id = :i AND step_no = 1").param("i", invitation)
                .query(String.class).single()).isEqualTo("SENT");
        long messages = fake.messages.size();
        gap();
        sender.step();
        assertThat(fake.messages).hasSize((int) messages); // đúng một lần
    }

    @Test
    void khachKhongNhanLaiTin1HoacDaNhanTinRiengThiKhongGuiLai() {
        long invitation = blockedInvite();
        friended(invitation);
        jdbc.sql("UPDATE invitation SET inbound_at = now() WHERE id = :i").param("i", invitation).update();
        long messages = fake.messages.size();
        gap();
        sender.step();
        assertThat(fake.messages).hasSize((int) messages);
    }

    @Test
    void khongRoGuiLaiDaDiChuaThiDungVaKhongBaoGioTuGuiLai() {
        long invitation = blockedInvite();
        friended(invitation);
        gap();
        fake.willReturnMessage(Sent.of(Result.SYSTEM_ERROR, "TIMEOUT", false));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.TRIPPED);
        assertThat(jdbc.sql("SELECT outcome FROM invitation_touch WHERE invitation_id = :i AND step_no = 1").param("i", invitation)
                .query(String.class).single()).isEqualTo("UNKNOWN");
        long messages = fake.messages.size();
        cfg.reopen("mẹ");
        gap();
        sender.step();
        assertThat(fake.messages).hasSize((int) messages);
    }
}
