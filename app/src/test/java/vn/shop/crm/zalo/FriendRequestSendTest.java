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
    @Autowired vn.shop.crm.invite.CampaignSteps steps;
    @Autowired ManualAttention attention;
    @Autowired org.springframework.web.context.WebApplicationContext context;

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
        appSettings.set("zalo.friend.paused_until", "", "test");
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

    // ---------- 25/11/2026: Zalo báo danh sách lời mời đầy (Z223) và trần lời mời đang chờ ----------

    @Test
    void z223ChiTamDungLoiMoiKhongNgatCauDaoVaTin1VanChay() {
        long invitation = inviteOne();
        gap();
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, ZaloMessageService.FRIEND_LIST_FULL, true));
        sender.step();
        assertThat(fake.friendRequests).hasSize(1);
        assertThat(cfg.tripped()).isFalse();
        assertThat(fake.stops).isZero();
        // lời mời chưa đi: trả chỗ, không tính trần ngày, không đánh dấu lỗi khách
        assertThat(outcome(invitation)).isNull();
        assertThat(messages.friendUsedToday()).isZero();
        assertThat(messages.friendPausedUntil()).isPresent();
        // đang tạm dừng: nhịp sau không gọi Zalo kết bạn nữa, dù qua khoảng nghỉ
        gap();
        sender.step();
        assertThat(fake.friendRequests).hasSize(1);
        // Tin 1 cho khách mới vẫn chạy trong lúc tạm dừng
        jdbc.sql("UPDATE invitation SET status = 'READY', sent_at = NULL WHERE status = 'CANCELLED'").update();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        assertThat(cfg.tripped()).isFalse();
        // sáng hôm sau tự mở lại và gửi lời mời đã được trả chỗ
        clock.set(LocalDateTime.of(2026, 11, 23, 8, 5));
        assertThat(messages.friendPausedUntil()).isEmpty();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(fake.friendRequests).hasSize(2);
        assertThat(outcome(invitation)).isEqualTo("SENT");
    }

    @Test
    void z223KhongThemVaoMaLoiNguoiVaNguoiBamGuiLaiThiMoLaiNgay() {
        long invitation = inviteOne();
        gap();
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, ZaloMessageService.FRIEND_LIST_FULL, true));
        sender.step();
        assertThat(messages.friendPausedUntil()).isPresent();
        // ba lần "lỗi riêng người" liên tiếp thường làm cầu dao nhảy; Z223 không tính vào đó
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE outcome = 'PERSON_ERROR'")).isZero();
        messages.resumeFriendRequests("mẹ");
        assertThat(messages.friendPausedUntil()).isEmpty();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(outcome(invitation)).isEqualTo("SENT");
    }

    /** Gọi Zalo kết bạn bị Z223 cho khách đầu hàng rồi dừng ở bước ngay sau, trả mã lời mời. */
    private long hitZ223() {
        long invitation = inviteOne();
        gap();
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, ZaloMessageService.FRIEND_LIST_FULL, true));
        return invitation;
    }

    private String askOutcome(long invitation) {
        return jdbc.sql("SELECT friend_ask_outcome FROM invitation WHERE id = :i").param("i", invitation).query(String.class).optional().orElse(null);
    }

    private long asks() {
        return fake.messages.stream().filter(m -> m.kind() == ZaloMessenger.Kind.ASK_FRIEND).count();
    }

    @Test
    void z223ThiGuiThemMotTinNhoKhachKetBanLaiVaChiMotLan() {
        long invitation = hitZ223();
        long tin1Used = messages.usedToday(null);
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.ASK_SENT);
        assertThat(asks()).isEqualTo(1);
        var ask = fake.messages.stream().filter(m -> m.kind() == ZaloMessenger.Kind.ASK_FRIEND).findFirst().orElseThrow();
        assertThat(ask.uid()).isNotBlank();
        assertThat(ask.text()).contains("kết bạn lại").doesNotContain("{");
        assertThat(askOutcome(invitation)).isEqualTo("SENT");
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'ASK_FRIEND'")).isEqualTo(1);
        // không tính vào số khách được nhắn Tin 1 và không ngắt cầu dao
        assertThat(messages.usedToday(null)).isEqualTo(tin1Used);
        assertThat(cfg.tripped()).isFalse();
        // mỗi khách một lần, kể cả qua nhiều ngày
        gap();
        sender.step();
        clock.advance(Duration.ofDays(2));
        sender.step();
        assertThat(asks()).isEqualTo(1);
    }

    @Test
    void khongCoZ223ThiKhongGuiTinNhoKetBanLai() {
        inviteOne();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        gap();
        sender.step();
        assertThat(asks()).isZero();
    }

    @Test
    void khachDaDuocGuiLoiMoiHoacDaKetBanHoacNhanLaiThiKhongNhanTinNho() {
        for (String set : new String[] {"friend_request_at = now(), friend_request_outcome = 'SENT'", "friended_at = now()", "inbound_at = now()",
                "joined_at = now()", "tin1_blocked_at = now()", "status = 'CANCELLED'"}) {
            fake.reset();
            jdbc.sql("DELETE FROM zalo_msg_log").update();
            jdbc.sql("UPDATE invitation SET status = 'READY', sent_at = NULL, friended_at = NULL, joined_at = NULL, inbound_at = NULL, tin1_blocked_at = NULL,"
                    + " friend_request_at = NULL, friend_request_outcome = NULL, friend_ask_at = NULL, friend_ask_outcome = NULL").update();
            long invitation = inviteOne();
            messages.pauseFriendRequests("test");
            jdbc.sql("UPDATE invitation SET " + set + " WHERE id = :i").param("i", invitation).update();
            gap();
            sender.step();
            assertThat(asks()).as(set).isZero();
        }
    }

    @Test
    void tinNhoKetBanLaiDungTranMoiNgayCuaLoiMoiVaTatKhiDat0() {
        // hai khách đã nhận Tin 1, chưa ai được mời (đặt 0 để lời mời kết bạn tắt)
        messages.setFriendDaily(0, "mẹ");
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        gap();
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE status = 'READY'").update();
        messages.pauseFriendRequests("test");
        // đặt 0 là tắt cả lời mời lẫn tin nhờ kết bạn lại
        sender.step();
        assertThat(asks()).isZero();
        messages.setFriendDaily(1, "mẹ");
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.ASK_SENT);
        gap();
        sender.step();
        assertThat(asks()).isEqualTo(1);
        clock.set(LocalDateTime.of(2026, 11, 23, 8, 5)); // hết tạm dừng: lời mời kết bạn gửi lại, không cần tin nhờ nữa
        messages.pauseFriendRequests("test");
        clock.set(LocalDateTime.of(2026, 11, 23, 9, 0));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.ASK_SENT);
        assertThat(asks()).isEqualTo(2);
    }

    @Test
    void noiDungTinNhoSuaDuocTheoChienDichVaChoDienLaThiKhongGui() {
        @SuppressWarnings("unused")
        long invitation = hitZ223();
        steps.saveFriendAskMessage(campaign, "Dạ bác {ten} kết bạn lại với {cua_hang} giúp em nhé.", "mẹ");
        assertThat(steps.friendAskMessage(campaign)).contains("kết bạn lại với");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> steps.saveFriendAskMessage(campaign, "Chào {tên_lạ}", "mẹ")).isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> steps.saveFriendAskMessage(campaign, "  ", "mẹ")).isInstanceOf(RuntimeException.class);
        // mẫu bị sửa lậu trong cơ sở dữ liệu thành chỗ điền lạ: không gửi
        jdbc.sql("UPDATE invitation_campaign SET friend_ask_template = 'Chào {abc}' WHERE id = :c").param("c", campaign).update();
        sender.step();
        assertThat(asks()).isZero();
        jdbc.sql("UPDATE invitation_campaign SET friend_ask_template = NULL WHERE id = :c").param("c", campaign).update();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.ASK_SENT);
        assertThat(asks()).isEqualTo(1);
    }

    @Test
    void khongRoKetQuaTinNhoThiDanhDauUnknownVaKhongGuiLai() {
        long invitation = hitZ223();
        fake.willReturnMessage(Sent.of(Result.SYSTEM_ERROR, "TIMEOUT", false)); // lần gọi tin nhờ (lần đầu là Z223 ở trên)
        // hàng đợi giả trả Z223 trước, rồi tới kết quả lạ cho tin nhờ
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.TRIPPED);
        assertThat(askOutcome(invitation)).isEqualTo("UNKNOWN");
        cfg.reopen("test");
        clock.advance(Duration.ofDays(2));
        sender.step();
        assertThat(asks()).isEqualTo(1);
    }

    // ---------- tab "Cần xử lý tay" ----------

    @Test
    void khachChuaRoLoiMoiHienOTabVaGuiLaiKetBanDuocDungMotLan() {
        long invitation = inviteOne();
        gap();
        fake.willReturnMessage(Sent.of(Result.SYSTEM_ERROR, "TIMEOUT", false));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.TRIPPED);
        assertThat(outcome(invitation)).isEqualTo("UNKNOWN");
        assertThat(attention.count(campaign)).isEqualTo(1);
        var row = attention.rows(campaign).get(0);
        assertThat(row.canResendFriend()).isTrue();
        assertThat(row.who()).contains("số …").doesNotContain("090000000");
        assertThat(String.join(" ", row.problems())).contains("Chưa rõ lời mời kết bạn");
        // gửi lại một lần: trả về hàng chờ, chưa gọi Zalo
        int calls = fake.friendRequests.size();
        attention.resendFriend(campaign, invitation, "mẹ");
        assertThat(fake.friendRequests).hasSize(calls);
        assertThat(outcome(invitation)).isNull();
        cfg.reopen("test");
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        assertThat(outcome(invitation)).isEqualTo("SENT");
        assertThat(attention.count(campaign)).isZero();
        // lần hai không còn nút: UNKNOWN lại thì chỉ được đánh dấu đã xử lý
        jdbc.sql("UPDATE invitation SET friend_request_outcome = 'UNKNOWN' WHERE id = :i").param("i", invitation).update();
        assertThat(attention.rows(campaign).get(0).canResendFriend()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attention.resendFriend(campaign, invitation, "mẹ")).isInstanceOf(RuntimeException.class);
        attention.markDone(campaign, invitation, "mẹ");
        assertThat(attention.count(campaign)).isZero();
        assertThat(count("SELECT count(*) FROM invitation WHERE id = " + invitation)).isEqualTo(1); // không xóa gì
    }

    @Test
    void khachTin1LoiHienKemLyDoVaKhongCoNutGuiLai() {
        long invitation = inviteOne();
        jdbc.sql("UPDATE invitation SET status = 'FAILED', last_error = 'Không tìm thấy trên Zalo' WHERE id = :i").param("i", invitation).update();
        var row = attention.rows(campaign).get(0);
        assertThat(String.join(" ", row.problems())).contains("Tin 1 không gửi được").contains("Không tìm thấy trên Zalo");
        assertThat(row.canResendFriend()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attention.resendFriend(campaign, invitation, "mẹ")).isInstanceOf(RuntimeException.class);
        // khách của chiến dịch khác không bị tác động
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attention.markDone(campaign + 999, invitation, "mẹ")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void khachDaKetBanHoacNhanLaiThiKhongGuiLaiKetBanDuoc() {
        long invitation = inviteOne();
        jdbc.sql("UPDATE invitation SET friend_request_at = now(), friend_request_outcome = 'UNKNOWN', inbound_at = now() WHERE id = :i").param("i", invitation).update();
        assertThat(attention.rows(campaign).get(0).canResendFriend()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attention.resendFriend(campaign, invitation, "mẹ")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void trangChienDichCoTabCanXuLyTayBangTiengViet() throws Exception {
        long invitation = inviteOne();
        jdbc.sql("UPDATE invitation SET friend_request_at = now(), friend_request_outcome = 'UNKNOWN' WHERE id = :i").param("i", invitation).update();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var owner = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("OWNER");
        var csrf = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/campaigns/" + campaign).with(owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("Có <strong>1</strong> khách cần bạn xử lý tay")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/campaigns/" + campaign).param("tab", "can-xu-ly").with(owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("Gửi lại kết bạn")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("Đã xử lý")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/campaigns/" + campaign + "/attention/" + invitation + "/resend-friend").with(owner).with(csrf))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
        assertThat(outcome(invitation)).isNull();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/campaigns/" + campaign + "/attention/" + invitation + "/done").with(owner).with(csrf))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
        assertThat(attention.count(campaign)).isZero();
    }
}
