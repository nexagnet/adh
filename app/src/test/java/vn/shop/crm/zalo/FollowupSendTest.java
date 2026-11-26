package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.inbox.RefusalReviewService;
import vn.shop.crm.invite.CampaignSteps;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.send.SendService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloFollowupSender.Step;
import vn.shop.crm.zalo.ZaloMessenger.Kind;
import vn.shop.crm.zalo.ZaloMessenger.Result;
import vn.shop.crm.zalo.ZaloMessenger.Sent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D21 PR 5b: tin nhắc (lần 2 và lần 3) của chiến dịch nhiều lần gửi. Khóa các chốt chặn: chỉ khi phần đọc tin riêng đang chạy, đủ số ngày,
 * mỗi lần một lần, lần ba cần lần hai, tính vào hạn mức ngày, dừng khi khách kết bạn / vào nhóm / nhắn lại / từ chối / chiến dịch bỏ duyệt,
 * không bao giờ gửi lại khi không rõ kết quả, mã thêm câu xin ngừng, lịch riêng của chiến dịch.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.inbox.enabled=true",
        "app.ai.enabled=true",
        "app.ai.api-key=test",
        "app.ai.model=test"})
class FollowupSendTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";
    private static final String SECOND = "Dạ {cua_hang} hỏi thăm anh/chị dùng sản phẩm có ổn không ạ.";
    private static final String THIRD = "Dạ nếu anh/chị cần hướng dẫn thêm thì cứ nhắn {cua_hang} nhé ạ.";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired CampaignSteps steps;
    @Autowired CampaignPolicy policy;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloFollowupSender followups;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired RefusalReviewService reviews;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;
    @Autowired JdbcClient jdbc;
    @Autowired SendService sends;
    @Autowired TestMode testMode;
    @Autowired RainHold rain;

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
        steps.saveFollowups(campaign, List.of(new CampaignSteps.Step(2, SECOND, 3), new CampaignSteps.Step(3, THIRD, 4)), "mẹ");
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

    // ---------- tiện ích ----------

    private long inv(int n) {
        return jdbc.sql("SELECT i.id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p")
                .param("p", "+8490000000" + n).query(Long.class).single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String touch(long invitation, int step) {
        return jdbc.sql("SELECT outcome FROM invitation_touch WHERE invitation_id = :i AND step_no = :s")
                .param("i", invitation).param("s", step).query(String.class).optional().orElse(null);
    }

    private void days(int d) {
        clock.advance(Duration.ofDays(d));
    }

    private void seconds(long s) {
        clock.advance(Duration.ofSeconds(s));
    }

    /** Gửi Tin 1 cho đúng MỘT khách (khách đầu tiên trong hàng) rồi bỏ các lời mời READY còn lại để thử riêng tin nhắc. Trả mã lời mời. */
    private long inviteOne() {
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE status = 'READY'").update();
        return jdbc.sql("SELECT id FROM invitation WHERE status = 'SENT'").query(Long.class).single();
    }

    private List<FakeZaloGateway.Msg> followupsSent() {
        return fake.messages.stream().filter(m -> m.kind().followup()).toList();
    }

    // ---------- Tin 1 của chiến dịch nhiều lần gửi ----------

    @Test
    void tin1CuaChienDichNhieuLanCoCauXinNgungVaGhiSoDem() {
        long invitation = inviteOne();
        assertThat(fake.messages).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(Kind.INVITE);
            assertThat(m.text()).endsWith(CampaignSteps.STOP_FOOTER);
        });
        assertThat(touch(invitation, 1)).isEqualTo("SENT");
    }

    @Test
    void tin1CuaChienDichMotLanKhongThemCauXinNgungVaVanGhiSoDem() {
        jdbc.sql("UPDATE invitation_campaign SET max_touches = 1 WHERE id = :c").param("c", campaign).update();
        long invitation = inviteOne();
        assertThat(fake.messages.get(0).text()).doesNotContain(CampaignSteps.STOP_FOOTER);
        assertThat(touch(invitation, 1)).isEqualTo("SENT");
    }

    // ---------- đến hạn và thứ tự ----------

    @Test
    void tinNhacChiDiKhiDuSoNgayMoiLanMotLanVaLanBaCanLanHai() {
        long invitation = inviteOne();
        seconds(150);
        assertThat(followups.step()).isEqualTo(Step.NOTHING); // chưa đủ 3 ngày
        days(2);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        days(1);
        assertThat(followups.step()).isEqualTo(Step.SENT);
        var second = followupsSent().get(0);
        assertThat(second.kind()).isEqualTo(Kind.FOLLOWUP2);
        assertThat(second.uid()).isNotNull();
        assertThat(second.phone()).isNull(); // chỉ theo mã Zalo
        assertThat(second.text()).startsWith("Dạ ").endsWith(CampaignSteps.STOP_FOOTER).doesNotContain("{");
        assertThat(touch(invitation, 2)).isEqualTo("SENT");
        seconds(150);
        assertThat(followups.step()).isEqualTo(Step.NOTHING); // lần ba chưa đủ 4 ngày, lần hai không gửi lại
        days(4);
        assertThat(followups.step()).isEqualTo(Step.SENT);
        assertThat(followupsSent()).extracting(FakeZaloGateway.Msg::kind).containsExactly(Kind.FOLLOWUP2, Kind.FOLLOWUP3);
        assertThat(touch(invitation, 3)).isEqualTo("SENT");
        days(30);
        assertThat(followups.step()).isEqualTo(Step.NOTHING); // tối đa 3 lần, không có lần bốn
        assertThat(count("SELECT count(*) FROM invitation_touch WHERE invitation_id = " + invitation)).isEqualTo(3);
        assertThat(steps.progress(campaign)).isEqualTo(new CampaignSteps.Progress(1, 1, 1, 0));
    }

    @Test
    void tinNhacDiQuaBoGuiChungVaNhuongTin2() {
        inviteOne();
        days(3);
        // qua bộ gửi chung: tin nhắc đi trước Tin 1 của khách khác (đã bị bỏ ở trên nên không còn), và tính là bước FOLLOWUP_SENT
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.FOLLOWUP_SENT);
        assertThat(followupsSent()).hasSize(1);
        assertThat(count("SELECT count(*) FROM send_log WHERE reason LIKE 'Tin nhắc lần 2%'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM contact_event WHERE event = 'INVITATION_FOLLOWUP'")).isEqualTo(1);
    }

    @Test
    void chienDichMotLanThiKhongBaoGioNhacDuTinNhacConLuu() {
        jdbc.sql("UPDATE invitation_campaign SET max_touches = 1 WHERE id = :c").param("c", campaign).update();
        inviteOne();
        days(40);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        assertThat(followupsSent()).isEmpty();
    }

    @Test
    void khiMaxTouchesGiamThiLanBaKhongDiNua() {
        long invitation = inviteOne();
        days(3);
        assertThat(followups.step()).isEqualTo(Step.SENT);
        jdbc.sql("UPDATE invitation_campaign SET max_touches = 2 WHERE id = :c").param("c", campaign).update();
        seconds(150);
        days(4);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        assertThat(touch(invitation, 3)).isNull();
    }

    // ---------- điều kiện dừng ----------

    private void stopsFor(String update) {
        long invitation = inviteOne();
        jdbc.sql(update).param("id", invitation).update();
        days(5);
        assertThat(followups.step()).as(update).isEqualTo(Step.NOTHING);
        assertThat(followupsSent()).isEmpty();
        assertThat(touch(invitation, 2)).isNull();
    }

    @Test
    void khachKetBanVaoNhomHuyHoacTamDungThiKhongNhac() {
        stopsFor("UPDATE invitation SET status = 'FRIENDED', friended_at = now() WHERE id = :id");
    }

    @Test
    void khachDaVaoNhomThiKhongNhac() {
        stopsFor("UPDATE invitation SET status = 'JOINED', joined_at = now() WHERE id = :id");
    }

    @Test
    void khachBiHuyHoacTamDungChoMeXemThiKhongNhac() {
        stopsFor("UPDATE invitation SET status = 'CANCELLED' WHERE id = :id");
    }

    @Test
    void loiMoiHetHanThiKhongNhac() {
        stopsFor("UPDATE invitation SET expires_at = sent_at + interval '1 day' WHERE id = :id");
    }

    @Test
    void khachNhanRiengBatCuDieuGiThiKhongBaoGioNhacDuAIGanNhanGi() {
        long invitation = inviteOne();
        reviews.noteInbound(invitation); // process() gọi cho MỌI tin riêng của người có lời mời, trước khi nhờ AI
        days(5);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        assertThat(followupsSent()).isEmpty();
        assertThat(count("SELECT count(*) FROM invitation WHERE inbound_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void khachBiChanThiHuyLoiMoiVaKhongBaoGioNhac() {
        long invitation = inviteOne();
        long customer = jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", invitation).query(Long.class).single();
        contacts.block(customer, "thử");
        days(5);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        assertThat(followupsSent()).isEmpty();
    }

    @Test
    void khachKhongConDuDieuKienLienHeMaLoiMoiConSentThiDongLanNayVaKhongNhacTiep() {
        long invitation = inviteOne();
        long customer = jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", invitation).query(Long.class).single();
        // lớp chặn thứ hai: dù lời mời vì lý do nào đó còn SENT, khách không còn đủ điều kiện (ví dụ đã từ chối) thì không bao giờ được nhắc
        jdbc.sql("UPDATE contact_basis SET status = 'OPTED_OUT' WHERE customer_id = :c").param("c", customer).update();
        days(5);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        assertThat(followupsSent()).isEmpty();
        assertThat(touch(invitation, 2)).isEqualTo("FAILED"); // đã đóng: không bị xem lại mỗi nhịp
        days(10);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        assertThat(touch(invitation, 3)).isNull();
    }

    @Test
    void chienDichBoDuyetHoacTamDungTatCaThiKhongNhac() {
        long invitation = inviteOne();
        days(5);
        sender.revokeCampaign(campaign, "mẹ");
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        sender.approveCampaign(campaign, "mẹ");
        appSettings.pauseAll("thử", "mẹ");
        assertThat(followups.step()).isEqualTo(Step.NOT_ACTIVE);
        appSettings.set("campaigns.paused", "false", "test");
        cfg.reopen("test");
        assertThat(touch(invitation, 2)).isNull();
        assertThat(followupsSent()).isEmpty();
    }

    @Test
    void congTacTatNacOffCauDaoNhayThiKhongNhac() {
        long invitation = inviteOne();
        days(5);
        messages.setEnabled(false, "mẹ");
        assertThat(followups.step()).isEqualTo(Step.NOT_ACTIVE);
        messages.setEnabled(true, "mẹ");
        cfg.trip("DUNG_TAY", "mẹ");
        assertThat(followups.step()).isEqualTo(Step.BLOCKED);
        assertThat(followupsSent()).isEmpty();
        assertThat(touch(invitation, 2)).isNull();
    }

    @Test
    void phanDocTinRiengKhongChayThiKhongNhac() {
        long invitation = inviteOne();
        days(5);
        ZaloInboxService off = Mockito.mock(ZaloInboxService.class);
        Mockito.when(off.running()).thenReturn(false);
        ZaloFollowupSender blind = new ZaloFollowupSender(jdbc, appSettings, messages, contacts, invites, sends, clock, testMode, policy, off, rain);
        assertThat(blind.step()).isEqualTo(Step.NOT_ACTIVE); // không nghe được khách xin ngừng thì không nhắc
        assertThat(followupsSent()).isEmpty();
        assertThat(touch(invitation, 2)).isNull();
    }

    // ---------- hạn mức, lịch riêng ----------

    @Test
    void tinNhacTinhVaoHanMucNgayVaKhongBiGuiKhiDaDu() {
        long invitation = inviteOne();
        days(3);
        messages.setLimits(1, 100, "mẹ"); // hôm nay chưa có tin nào (đã sang ngày mới): hạn mức 1
        assertThat(followups.step()).isEqualTo(Step.SENT);
        assertThat(messages.usedToday(null)).isEqualTo(1);
        seconds(150);
        days(4);
        messages.setLimits(1, 100, "mẹ");
        assertThat(followups.step()).isEqualTo(Step.SENT); // sang ngày khác nên lại được
        assertThat(touch(invitation, 3)).isEqualTo("SENT");
    }

    @Test
    void hanMucNgayDaDuThiTinNhacChuaNhanPhanViecVaKhongMatChoLanSau() {
        long invitation = inviteOne();
        days(3);
        messages.setLimits(1, 100, "mẹ");
        // lấp đầy hạn mức hôm nay bằng một tin chủ động khác
        jdbc.sql("INSERT INTO zalo_msg_log (at, scope, kind, outcome) VALUES (:at, 'REAL', 'INVITE', 'OK')")
                .param("at", java.time.OffsetDateTime.now(clock)).update();
        assertThat(followups.step()).isEqualTo(Step.BLOCKED);
        assertThat(followupsSent()).isEmpty();
        assertThat(touch(invitation, 2)).isNull(); // chưa nhận phần việc
        days(1);
        assertThat(followups.step()).isEqualTo(Step.SENT); // ngày sau gửi tiếp
    }

    @Test
    void lichRiengCuaChienDichChiLamChatHon() {
        long invitation = inviteOne();
        days(3);
        policy.saveClamped(campaign, new CampaignPolicy.Raw(clock.instant().atZone(clock.getZone()).toLocalDate().plusDays(2), null, null, null, null, null), "mẹ");
        assertThat(followups.step()).isEqualTo(Step.NOTHING); // chiến dịch chưa tới ngày bắt đầu
        assertThat(touch(invitation, 2)).isNull();
        days(2);
        assertThat(followups.step()).isEqualTo(Step.SENT);
    }

    @Test
    void hanMucNgayRiengCuaChienDichTinhCaTinNhac() {
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        seconds(150);
        assertThat(sender.step()).isEqualTo(ZaloInviteSender.Step.INVITE_SENT);
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE status = 'READY'").update();
        days(3);
        policy.saveClamped(campaign, new CampaignPolicy.Raw(null, null, null, null, 1, null), "mẹ");
        // hôm nay chưa có tin nào của chiến dịch (đã sang ngày mới): tin nhắc đầu được gửi, rồi hạn mức riêng 1 đã đủ
        assertThat(followups.step()).isEqualTo(Step.SENT);
        assertThat(policy.sentToday(campaign, clock.instant().atZone(clock.getZone()).toLocalDate())).isEqualTo(1);
        seconds(150);
        assertThat(followups.step()).isEqualTo(Step.NOTHING); // khách còn lại đã đến hạn nhưng hạn mức riêng của chiến dịch đã đủ
        assertThat(count("SELECT count(*) FROM invitation_touch WHERE step_no = 2")).isEqualTo(1);
        days(1);
        assertThat(followups.step()).isEqualTo(Step.SENT); // ngày sau tới lượt khách còn lại
        assertThat(count("SELECT count(*) FROM invitation_touch WHERE step_no = 2 AND outcome = 'SENT'")).isEqualTo(2);
    }

    // ---------- lỗi, không rõ kết quả ----------

    @Test
    void khongRoKetQuaThiDungVaKhongBaoGioTuGuiLai() {
        long invitation = inviteOne();
        days(3);
        fake.willReturnMessage(Sent.of(Result.SYSTEM_ERROR, "TIMEOUT", false));
        assertThat(followups.step()).isEqualTo(Step.TRIPPED);
        assertThat(touch(invitation, 2)).isEqualTo("UNKNOWN");
        assertThat(steps.progress(campaign).unknown()).isEqualTo(1);
        assertThat(cfg.tripped()).isTrue();
        cfg.reopen("mẹ");
        seconds(500);
        assertThat(followups.step()).isEqualTo(Step.NOTHING); // dòng UNKNOWN giữ chỗ: không gửi lại lần hai, không sang lần ba
        days(10);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        assertThat(followupsSent()).hasSize(1);
        assertThat(touch(invitation, 3)).isNull();
    }

    @Test
    void capNoiTuChoiTruocKhiChamZaloThiTraChoVeHangDoi() {
        long invitation = inviteOne();
        days(3);
        fake.willReturnMessage(Sent.of(Result.RATE_LIMITED, null, true));
        assertThat(followups.step()).isEqualTo(Step.RETRY);
        assertThat(touch(invitation, 2)).isNull(); // chắc chắn chưa gửi: trả chỗ
        seconds(150);
        assertThat(followups.step()).isEqualTo(Step.SENT);
        assertThat(touch(invitation, 2)).isEqualTo("SENT");
    }

    @Test
    void boNoiBaoKhachVuaNhanTinThiCoiNhuKhachDaLenTiengVaDungHan() {
        long invitation = inviteOne();
        days(3);
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, "CUSTOMER_REPLIED", true));
        assertThat(followups.step()).isEqualTo(Step.FAILED_ONE);
        assertThat(count("SELECT count(*) FROM invitation WHERE id = " + invitation + " AND inbound_at IS NOT NULL")).isEqualTo(1);
        assertThat(touch(invitation, 2)).isNull();
        days(10);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
    }

    @Test
    void loiRiengNguoiThiDongLanNayVaKhongNhacTiep() {
        long invitation = inviteOne();
        days(3);
        fake.willReturnMessage(Sent.of(Result.PERSON_ERROR, "NO_INVITE", true));
        assertThat(followups.step()).isEqualTo(Step.FAILED_ONE);
        assertThat(touch(invitation, 2)).isEqualTo("FAILED");
        days(10);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        assertThat(touch(invitation, 3)).isNull();
    }

    @Test
    void chiNhacNguoiDaNhanTin1TuBoNoiVaCoMaZalo() {
        inviteOne();
        jdbc.sql("UPDATE invitation SET zalo_target_uid = NULL WHERE status = 'SENT'").update();
        days(5);
        assertThat(followups.step()).isEqualTo(Step.NOTHING);
        jdbc.sql("UPDATE invitation SET zalo_target_uid = '12345', sent_channel = 'MANUAL_EXPORT' WHERE status = 'SENT'").update();
        assertThat(followups.step()).isEqualTo(Step.NOTHING); // Tin 1 gửi tay (không qua bộ nối) thì không nhắc
        assertThat(followupsSent()).isEmpty();
    }
}
