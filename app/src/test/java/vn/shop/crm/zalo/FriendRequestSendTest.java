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
        appSettings.set("zalo.friend.withdraw_days", "7", "test");
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
    void gianDoanKhiGhiNhatKyTruocKhiGoiZaloThiLanSauGuiMotLan() {
        long invitation = inviteOne();
        gap();
        jdbc.sql("""
                CREATE FUNCTION interrupt_friend_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test interrupt before dispatch';
                END;
                $$
                """).update();
        try {
            jdbc.sql("""
                    CREATE TRIGGER interrupt_friend_attempt BEFORE INSERT ON zalo_msg_log
                    FOR EACH ROW WHEN (NEW.kind = 'FRIEND') EXECUTE FUNCTION interrupt_friend_attempt()
                    """).update();
            assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.TRIPPED);
            assertThat(fake.friendRequests).isEmpty();
            assertThat(outcome(invitation)).isNull();
            assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'FRIEND'")).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS interrupt_friend_attempt ON zalo_msg_log").update();
            jdbc.sql("DROP FUNCTION interrupt_friend_attempt()").update();
        }
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        gap();
        sender.step();
        assertThat(fake.friendRequests).hasSize(1);
        assertThat(outcome(invitation)).isEqualTo("SENT");
    }

    @Test
    void gianDoanSauKhiGoiCauNoiKhongTuGuiLai() {
        long invitation = inviteOne();
        gap();
        fake.afterFriendRequest = () -> {
            assertThat(attention.rows(campaign)).singleElement().satisfies(row -> assertThat(row.canResendFriend()).isFalse());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> attention.resendFriend(campaign, invitation, "test"))
                    .isInstanceOf(vn.shop.crm.common.ImportException.class);
            throw new AssertionError("test interrupt after dispatch");
        };
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(sender::step)
                    .isInstanceOf(AssertionError.class).hasMessage("test interrupt after dispatch");
        } finally {
            fake.afterFriendRequest = () -> {};
        }
        assertThat(outcome(invitation)).isEqualTo("UNKNOWN");
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'FRIEND' AND outcome = 'DANG_GOI'")).isEqualTo(1);
        gap();
        sender.step();
        assertThat(fake.friendRequests).hasSize(1);
    }

    @Test
    void dongLuotKetBanBiGianDoanSauKhiXemKhongMoCauDaoHayXepGuiLai() {
        long invitation = inviteOne();
        gap();
        fake.afterFriendRequest = () -> { throw new AssertionError("test interrupt after dispatch"); };
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(sender::step).isInstanceOf(AssertionError.class);
        } finally {
            fake.afterFriendRequest = () -> {};
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attention.markDone(campaign, invitation, "test"))
                .isInstanceOf(vn.shop.crm.common.ImportException.class);
        cfg.trip("TIN_INTERRUPTED", "test");
        gap();
        attention.markDone(campaign, invitation, "test");
        attention.markDone(campaign, invitation, "test");
        assertThat(cfg.tripped()).isTrue();
        assertThat(outcome(invitation)).isEqualTo("UNKNOWN");
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'FRIEND' AND outcome = 'KHONG_RO_DA_DONG'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM zalo_auto_log WHERE result LIKE 'DONG_LUOT_KET_BAN_CHUA_RO %'")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attention.resendFriend(campaign, invitation, "test"))
                .isInstanceOf(vn.shop.crm.common.ImportException.class);
        assertThat(fake.friendRequests).hasSize(1);
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
    void loiKetNoiChacChanChuaGuiChiThuLaiBaLan() {
        long invitation = inviteOne();
        gap();
        fake.defaultFriend = Sent.of(Result.UNREACHABLE, "BRIDGE_DOWN", true);
        var delays = java.util.List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
        for (int attempt = 1; attempt <= 4; attempt++) {
            sender.step();
            assertThat(fake.friendRequests).hasSize(attempt);
            if (attempt <= delays.size()) {
                clock.advance(delays.get(attempt - 1).minusSeconds(1));
                sender.step();
                assertThat(fake.friendRequests).hasSize(attempt);
                clock.advance(Duration.ofSeconds(1));
            }
        }
        assertThat(outcome(invitation)).isEqualTo("FAILED");
        assertThat(attention.rows(campaign)).singleElement().satisfies(row -> assertThat(row.canResendFriend()).isFalse());
        sender.step();
        assertThat(fake.friendRequests).hasSize(4);
        assertThat(cfg.tripped()).isFalse();
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
    void hienTaiKhoanVaBangChungDoiChieuMaKhongThemNutGuiLai() throws Exception {
        long invitation = inviteOne();
        jdbc.sql("INSERT INTO zalo_account (id, label) VALUES (2, 'Tài khoản đối chiếu')").update();
        jdbc.sql("""
                UPDATE invitation SET account_id = 2, friend_request_outcome = 'SENT', friend_request_at = '2026-11-22T02:00:00Z'
                WHERE id = :i
                """).param("i", invitation).update();
        jdbc.sql("""
                INSERT INTO zalo_friend_observation
                    (account_id, invitation_id, state, observed_at, last_confirmed_state, last_confirmed_at, needs_review)
                VALUES (2, :i, 'UNAVAILABLE', '2026-11-22T04:00:00Z', 'NONE', '2026-11-22T03:00:00Z', false)
                """).param("i", invitation).update();
        assertThat(attention.count(campaign)).isZero();
        jdbc.sql("UPDATE zalo_friend_observation SET needs_review = true WHERE account_id = 2 AND invitation_id = :i")
                .param("i", invitation).update();
        assertThat(attention.count(campaign)).isEqualTo(1);
        assertThat(attention.rows(campaign)).singleElement().satisfies(row -> {
            assertThat(row.accountLabel()).isEqualTo("Tài khoản đối chiếu");
            assertThat(row.canResendFriend()).isFalse();
            assertThat(row.canMarkDone()).isFalse();
        });
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var owner = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("OWNER");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/campaigns/" + campaign)
                        .param("tab", "can-xu-ly").with(owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("Tài khoản đối chiếu"),
                                org.hamcrest.Matchers.containsString("Chưa đọc được trạng thái từ Zalo"),
                                org.hamcrest.Matchers.containsString("22/11/2026 11:00"),
                                org.hamcrest.Matchers.containsString("22/11/2026 10:00"),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/resend-friend")))));
        jdbc.sql("UPDATE invitation SET friended_at = now() WHERE id = :i").param("i", invitation).update();
        assertThat(attention.count(campaign)).isZero();
    }

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
    void bangChungLoiMoiDangChoChanGuiLaiKeCaKhiLanDocMoiLoi() {
        long invitation = inviteOne();
        jdbc.sql("""
                UPDATE invitation SET friend_request_outcome = 'UNKNOWN',
                    friend_request_at = now() - interval '10 minutes' WHERE id = :i
                """).param("i", invitation).update();
        jdbc.sql("""
                INSERT INTO zalo_friend_observation
                    (account_id, invitation_id, state, observed_at, last_confirmed_state, last_confirmed_at, needs_review)
                VALUES (1, :i, 'UNAVAILABLE', now(), 'OUTGOING', now() - interval '5 minutes', true)
                """).param("i", invitation).update();
        assertThat(attention.rows(campaign)).singleElement().satisfies(row -> assertThat(row.canResendFriend()).isFalse());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attention.resendFriend(campaign, invitation, "mẹ"))
                .isInstanceOf(vn.shop.crm.common.ImportException.class);
        assertThat(outcome(invitation)).isEqualTo("UNKNOWN");
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

    // ---------- tự thu hồi lời mời quá N ngày chưa ai đồng ý ----------

    @Test
    void hienTrangThaiDoiChieuMaKhongSuyRaChieuLoiMoi() {
        long invitation = friendRequestedDaysAgo(3);
        jdbc.sql("""
                INSERT INTO zalo_friend_observation
                    (account_id, invitation_id, state, observed_at, last_confirmed_state, last_confirmed_at, needs_review, attempt_at)
                SELECT account_id, id, 'INCOMING', now(), 'INCOMING', now(), true, friend_request_at
                FROM invitation WHERE id = :i
                """).param("i", invitation).update();
        for (String state : java.util.List.of("INCOMING", "OUTGOING", "NONE")) {
            jdbc.sql("UPDATE zalo_friend_observation SET state = :state WHERE invitation_id = :i")
                    .param("state", state).param("i", invitation).update();
            assertThat(attention.rows(campaign)).singleElement().satisfies(row -> {
                assertThat(row.observation().stateLabel()).isEqualTo("NONE".equals(state)
                        ? "Zalo không còn hiển thị lời mời đang chờ"
                        : "Có lời mời đang chờ trên Zalo, chưa xác minh bên gửi");
                assertThat(row.canResendFriend()).isFalse();
            });
        }
        assertThat(outcome(invitation)).isEqualTo("SENT");
    }

    @Test
    void khongThuHoiKhiDoiChieuCungLanGuiDaKhongConLoiMoi() {
        long invitation = friendRequestedDaysAgo(8);
        jdbc.sql("""
                INSERT INTO zalo_friend_observation
                    (account_id, invitation_id, state, observed_at, last_confirmed_state, last_confirmed_at, needs_review, attempt_at)
                SELECT account_id, id, 'NONE', now(), 'NONE', now(), true, friend_request_at
                FROM invitation WHERE id = :i
                """).param("i", invitation).update();
        gap();
        sender.step();
        assertThat(fake.undone).isEmpty();
        assertThat(outcome(invitation)).isEqualTo("SENT");
        // Bằng chứng của lần gửi cũ không chặn lần gửi hiện tại.
        jdbc.sql("UPDATE zalo_friend_observation SET attempt_at=attempt_at - interval '1 day' WHERE invitation_id=:i")
                .param("i", invitation).update();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
    }

    /** Một khách đã được gửi lời mời cách đây {@code daysAgo} ngày (giờ giả của test); trả mã lời mời. */
    private long friendRequestedDaysAgo(int daysAgo) {
        long invitation = inviteOne();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        jdbc.sql("UPDATE invitation SET friend_request_at = :at WHERE id = :i")
                .param("at", java.time.OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofDays(daysAgo)), java.time.ZoneOffset.UTC))
                .param("i", invitation).update();
        return invitation;
    }

    private String undoOutcome(long invitation) {
        return jdbc.sql("SELECT friend_undo_outcome FROM invitation WHERE id = :i").param("i", invitation).query(String.class).optional().orElse(null);
    }

    @Test
    void loiMoiQuaBaNgayChuaAiDongYThiTuThuHoiVaChiMotLan() {
        long invitation = friendRequestedDaysAgo(8);
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
        assertThat(fake.undone).hasSize(1);
        assertThat(outcome(invitation)).isEqualTo("WITHDRAWN");
        assertThat(undoOutcome(invitation)).isEqualTo("DONE");
        // không xóa khách, không nhắn gì thêm, không thu hồi lần hai
        assertThat(count("SELECT count(*) FROM customer")).isEqualTo(3);
        gap();
        sender.step();
        assertThat(fake.undone).hasSize(1);
        assertThat(cfg.tripped()).isFalse();
    }

    @Test
    void loiMoiChuaQuaHanHoacKhachDaDongYThiKhongThuHoi() {
        long recent = friendRequestedDaysAgo(3);
        gap();
        sender.step();
        assertThat(fake.undone).isEmpty();
        jdbc.sql("UPDATE invitation SET friend_request_at = now() - interval '40 days', friended_at = now() WHERE id = :i").param("i", recent).update();
        gap();
        sender.step();
        assertThat(fake.undone).isEmpty();
        // đặt 0 là tắt
        jdbc.sql("UPDATE invitation SET friended_at = NULL WHERE id = :i").param("i", recent).update();
        messages.setFriendWithdrawDays(0, "mẹ");
        gap();
        sender.step();
        assertThat(fake.undone).isEmpty();
        messages.setFriendWithdrawDays(5, "mẹ");
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
    }

    @Test
    void thuHoiXongThiBoTamDungLoiMoiDoZ223() {
        friendRequestedDaysAgo(9);
        messages.pauseFriendRequests("test");
        assertThat(messages.friendPausedUntil()).isPresent();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
        assertThat(messages.friendPausedUntil()).isEmpty();
    }

    @Test
    void zaloTuChoiThuHoiThiDanhDauFailedKhongThuLaiVaBaLanTrongNgayThiDung() {
        long a = friendRequestedDaysAgo(9);
        gap();
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, "Z217", false));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FAILED_ONE);
        assertThat(undoOutcome(a)).isEqualTo("FAILED");
        assertThat(outcome(a)).isEqualTo("SENT"); // lời mời vẫn coi là đã gửi, chỉ việc thu hồi lỗi
        assertThat(cfg.tripped()).isFalse();
        gap();
        sender.step();
        assertThat(fake.undone).hasSize(1);
    }

    @Test
    void khongRoKetQuaThuHoiThiKhongThuLai() {
        long a = friendRequestedDaysAgo(9);
        gap();
        fake.willReturnMessage(Sent.of(Result.SYSTEM_ERROR, "TIMEOUT", false));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.TRIPPED);
        assertThat(undoOutcome(a)).isEqualTo("UNKNOWN");
        cfg.reopen("test");
        clock.advance(Duration.ofDays(2));
        sender.step();
        assertThat(fake.undone).hasSize(1);
    }

    @Test
    void chacChanChuaChamZaloThiTraChoDeThuHoiSau() {
        long a = friendRequestedDaysAgo(9);
        gap();
        fake.willReturnMessage(Sent.of(Result.RATE_LIMITED, null, true));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.RETRY);
        assertThat(undoOutcome(a)).isNull();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
    }

    @Test
    void thuHoiTuanTuMoiNhipMotLoiMoi() {
        long a = friendRequestedDaysAgo(9);
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
        // khách đã thu hồi được nhờ tự kết bạn (một tin), rồi mới tới khách thứ hai: Tin 1, lời mời, đến hạn thì thu hồi, mỗi nhịp đúng một việc
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.ASK_SENT);
        jdbc.sql("UPDATE invitation SET status = 'READY' WHERE status = 'CANCELLED'").update();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE status = 'READY'").update();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FRIEND_SENT);
        jdbc.sql("UPDATE invitation SET friend_request_at = :at WHERE friend_request_at IS NOT NULL AND friend_undo_at IS NULL")
                .param("at", java.time.OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofDays(9)), java.time.ZoneOffset.UTC)).update();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
        assertThat(fake.undone).hasSize(2);
        assertThat(messages.undoUsedToday()).isEqualTo(2);
        assertThat(undoOutcome(a)).isEqualTo("DONE");
    }

    // ---------- nhận lời mời kết bạn của khách + tin nhờ kết bạn sau khi thu hồi ----------

    private String targetUid(long invitation) {
        return jdbc.sql("SELECT zalo_target_uid FROM invitation WHERE id = :i").param("i", invitation).query(String.class).single();
    }

    private String acceptOutcome(long invitation) {
        return jdbc.sql("SELECT friend_accept_outcome FROM invitation WHERE id = :i").param("i", invitation).query(String.class).optional().orElse(null);
    }

    @Test
    void khachDaNhanTin1TuGuiLoiMoiThiDuocNhanMotLanVaKhongBiMoiLai() {
        long invitation = inviteOne();
        fake.incoming.add("999"); // người lạ: không thuộc lời mời nào, bỏ qua
        fake.incoming.add(targetUid(invitation));
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.ACCEPT_DONE);
        assertThat(fake.accepted).containsExactly(targetUid(invitation));
        assertThat(acceptOutcome(invitation)).isEqualTo("DONE");
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'ACCEPT_FRIEND'")).isEqualTo(1);
        // đã là bạn bên khách gửi: hệ thống không gửi thêm lời mời của mình, không nhờ kết bạn, không nhận lại
        fake.incoming.clear();
        fake.incoming.add(targetUid(invitation));
        gap();
        sender.step();
        assertThat(fake.accepted).hasSize(1);
        assertThat(fake.friendRequests).isEmpty();
        assertThat(asks()).isZero();
        assertThat(cfg.tripped()).isFalse();
    }

    @Test
    void khachChuaTungNhanTin1HoacKhongCoLoiMoiDenThiKhongNhan() {
        fake.incoming.add("999");
        gap();
        assertThat(sender.step()).isNotEqualTo(ZaloInviteSender.Step.ACCEPT_DONE);
        assertThat(fake.accepted).isEmpty();
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE kind = 'ACCEPT_FRIEND'")).isZero();
    }

    @Test
    void zaloTuChoiNhanLoiMoiThiDanhDauFailedKhongThuLaiVaKhongDungCauDao() {
        long invitation = inviteOne();
        fake.incoming.add(targetUid(invitation));
        gap();
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, "Z217", false));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FAILED_ONE);
        assertThat(acceptOutcome(invitation)).isEqualTo("FAILED");
        assertThat(cfg.tripped()).isFalse();
        gap();
        sender.step();
        assertThat(fake.accepted).hasSize(1);
    }

    @Test
    void khongRoKetQuaNhanLoiMoiThiKhongNhanLai() {
        long invitation = inviteOne();
        fake.incoming.add(targetUid(invitation));
        gap();
        fake.willReturnMessage(Sent.of(Result.SYSTEM_ERROR, "TIMEOUT", false));
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.TRIPPED);
        assertThat(acceptOutcome(invitation)).isEqualTo("UNKNOWN");
        cfg.reopen("test");
        clock.advance(Duration.ofDays(1));
        sender.step();
        assertThat(fake.accepted).hasSize(1);
    }

    @Test
    void thuHoiLoiMoiXongThiNhoKhachTuKetBanMotLanVaKhongLap() {
        long invitation = friendRequestedDaysAgo(9);
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
        assertThat(asks()).isZero();
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.ASK_SENT);
        var ask = fake.messages.stream().filter(m -> m.kind() == ZaloMessenger.Kind.ASK_FRIEND).findFirst().orElseThrow();
        assertThat(ask.uid()).isEqualTo(targetUid(invitation));
        assertThat(ask.text()).doesNotContain("{").doesNotContain("http");
        assertThat(jdbc.sql("SELECT friend_ask_outcome FROM invitation WHERE id = :i").param("i", invitation).query(String.class).single()).isEqualTo("SENT");
        gap();
        sender.step();
        assertThat(asks()).isEqualTo(1);
    }

    @Test
    void khachDaDongYKetBanThiKhongNhoKetBanSauKhiThuHoi() {
        long invitation = friendRequestedDaysAgo(9);
        gap();
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.UNDO_DONE);
        jdbc.sql("UPDATE invitation SET friended_at = now() WHERE id = :i").param("i", invitation).update();
        gap();
        sender.step();
        assertThat(asks()).isZero();
    }
}
