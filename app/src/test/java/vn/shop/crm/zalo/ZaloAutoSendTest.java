package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloGateway.AddResult;
import vn.shop.crm.zalo.ZaloGateway.Outcome;
import vn.shop.crm.zalo.ZaloInviteSender.Step;
import vn.shop.crm.zalo.ZaloMessenger.Kind;
import vn.shop.crm.zalo.ZaloMessenger.Result;
import vn.shop.crm.zalo.ZaloMessenger.Sent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * D18: hệ thống TỰ gửi Tin 1 cho khách của chiến dịch thật qua bộ nối giả. Khóa các chốt chặn: tắt mặc định, phải duyệt chiến dịch,
 * hạn mức ngày, khoảng nghỉ CỐ ĐỊNH, khung giờ, mỗi khách một lần, không gửi cho người không đủ điều kiện, lỗi lạ thì cầu dao nhảy.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class ZaloAutoSendTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @MockitoSpyBean ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloAutoService zalo;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

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

    private long inv(int n) {
        return jdbc.sql("SELECT i.id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p")
                .param("p", "+8490000000" + n).query(Long.class).single();
    }

    private String col(String column, int n) {
        return jdbc.sql("SELECT " + column + " FROM invitation WHERE id = :id").param("id", inv(n)).query(String.class).optional().orElse(null);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** Số (chữ số cuối của số điện thoại) của khách READY sẽ được gửi đầu tiên: thứ tự tạo lời mời không theo số. */
    private int first() {
        String phone = jdbc.sql("SELECT c.phone FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE i.status = 'READY' ORDER BY i.campaign_id, i.id LIMIT 1")
                .query(String.class).single();
        return phone.charAt(phone.length() - 1) - '0';
    }

    /** Bật hết mức cần để tự gửi: nấc bộ nối có nghe, công tắc tự gửi, chiến dịch đã duyệt. */
    private void ready(ZaloMode mode) {
        cfg.setMode(mode, mode == ZaloMode.AUTO, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
    }

    private void after(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    // ---------- tắt mặc định và phải duyệt ----------

    @Test
    void macDinhTatKhongGuiGi() {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
        assertThat(messages.enabled()).isFalse();
        assertThat(sender.step()).isEqualTo(Step.NOT_ACTIVE);
        assertThat(fake.messages).isEmpty();
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'SENT'")).isZero();
    }

    @Test
    void chienDichChuaDuyetKhongBaoGioTuGui() {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        messages.setEnabled(true, "mẹ");
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).isEmpty();
        sender.approveCampaign(campaign, "mẹ");
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        sender.revokeCampaign(campaign, "mẹ");
        after(120);
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).hasSize(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void boDuyetSauKhiChonKhachThiKhongNhanLoiMoiDeGui(CapturedOutput output) {
        ready(ZaloMode.CONFIRM);
        int candidate = first();
        long invitation = inv(candidate);
        doAnswer(call -> {
            boolean eligible = (boolean) call.callRealMethod();
            sender.revokeCampaign(campaign, "mẹ");
            return eligible;
        }).when(contacts).stillEligible(anyLong());

        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).isEmpty();
        assertThat(col("status", candidate)).isEqualTo("READY");
        assertThat(count("SELECT count(*) FROM zalo_msg_log")).isZero();
        assertThat(output).contains("Tin 1 event=claim_skipped account=1 campaign=" + campaign + " invitation=" + invitation);
    }

    @Test
    void nacOffHoacDungTayThiKhongGui() {
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
        assertThat(sender.step()).isEqualTo(Step.BLOCKED); // nấc bộ nối là Tắt
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        cfg.trip("DUNG_TAY", "mẹ");
        assertThat(sender.step()).isEqualTo(Step.BLOCKED);
        assertThat(fake.messages).isEmpty();
    }

    @Test
    void tamDungTatCaChienDichThiKhongGui() {
        ready(ZaloMode.CONFIRM);
        appSettings.pauseAll("thử", "mẹ");
        assertThat(sender.step()).isEqualTo(Step.NOT_ACTIVE);
        assertThat(fake.messages).isEmpty();
    }

    // ---------- một tin mỗi nhịp, khoảng nghỉ cố định, hạn mức, khung giờ ----------

    @Test
    void guiTin1DungNoiDungMoiNhipMotTinVaGhiNhatKy() {
        ready(ZaloMode.CONFIRM);
        int a = first();
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(Kind.INVITE);
            assertThat(m.phone()).isEqualTo("8490000000" + a);
            assertThat(m.text()).startsWith("Chào Khach " + a + ", ").endsWith(" tư vấn.");
        });
        assertThat(col("status", a)).isEqualTo("SENT");
        assertThat(col("sent_channel", a)).isEqualTo("ZALO_AUTO");
        assertThat(col("zalo_target_uid", a)).isEqualTo(fake.phoneToUid.get("8490000000" + a));
        assertThat(count("SELECT count(*) FROM send_log WHERE outcome = 'SENT' AND channel = 'ZALO_AUTO'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM zalo_msg_log WHERE outcome = 'OK'")).isEqualTo(1);
    }

    @Test
    void khoangNghiCoDinhDungBangCaiDat() {
        ready(ZaloMode.CONFIRM);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(sender.step()).isEqualTo(Step.BLOCKED);
        after(119);
        assertThat(sender.step()).isEqualTo(Step.BLOCKED);
        after(1); // đúng 120 giây
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).hasSize(2);
        // mỗi khách chỉ nhận một lần: hai tin là hai khách khác nhau
        assertThat(fake.messages.stream().map(FakeZaloGateway.Msg::phone).distinct()).hasSize(2);
    }

    @Test
    void hanMucNgayLaTranCung() {
        ready(ZaloMode.CONFIRM);
        messages.setLimits(2, 100, "mẹ");
        for (int i = 0; i < 2; i++) {
            assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
            after(100);
        }
        assertThat(sender.step()).isEqualTo(Step.BLOCKED);
        after(3600);
        assertThat(sender.step()).isEqualTo(Step.BLOCKED);
        assertThat(fake.messages).hasSize(2);
        after(24 * 3600); // sang ngày mới: 10:00 + 25 giờ+ vẫn trong khung giờ ngày hôm sau
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
    }

    @Test
    void ngoaiKhungGioKhongGui() {
        ready(ZaloMode.CONFIRM);
        clock.set(LocalDateTime.of(2026, 11, 22, 7, 59));
        assertThat(sender.step()).isEqualTo(Step.BLOCKED);
        clock.set(LocalDateTime.of(2026, 11, 22, 22, 0));
        assertThat(sender.step()).isEqualTo(Step.BLOCKED);
        clock.set(LocalDateTime.of(2026, 11, 22, 8, 0));
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
    }

    @Test
    void caiDatHanMucKhongVuotTranCungVaSanKhoangNghi() {
        assertThatThrownBy(() -> messages.setLimits(51, 120, "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> messages.setLimits(0, 120, "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> messages.setLimits(10, 99, "mẹ")).isInstanceOf(ImportException.class);
        messages.setLimits(50, 100, "mẹ"); // trần cứng 50 khách mỗi ngày (23/11/2026)
        assertThat(messages.limits()).isEqualTo(new ZaloMessageService.Limits(50, 100));
    }

    /** D21 PR 5b: tin nhắc (FOLLOWUP2/3) là tin shop chủ động nhắn nên tính vào hạn mức ngày cùng Tin 1, và chịu khoảng nghỉ chung. */
    @Test
    void tinNhacTinhVaoHanMucNgayCungTin1() {
        ready(ZaloMode.AUTO);
        messages.setLimits(2, 100, "mẹ");
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(messages.usedToday(null)).isEqualTo(1);
        after(100);
        var d = messages.sendByUid(ZaloMessageService.SCOPE_REAL, Kind.FOLLOWUP2, 1L, "9123456", "Nhắc", null, null);
        assertThat(d.status()).isEqualTo(ZaloMessageService.Status.SENT);
        assertThat(messages.usedToday(null)).isEqualTo(2); // tin nhắc tính vào hạn mức
        after(100);
        assertThat(messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.FOLLOWUP3, null, null)).contains(ZaloMessageService.Block.DAILY_LIMIT);
        assertThat(messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.INVITE, null, null)).contains(ZaloMessageService.Block.DAILY_LIMIT);
        assertThat(fake.messages.stream().filter(m -> m.kind() == Kind.FOLLOWUP2)).hasSize(1);
    }

    // ---------- người không đủ điều kiện, lỗi ----------

    @Test
    void khachDaTuChoiKhongBaoGioDuocGui() {
        ready(ZaloMode.CONFIRM);
        int a = first();
        long customer = jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", inv(a)).query(Long.class).single();
        contacts.suppressAs(customer, "Khách từ chối", "mẹ");
        assertThat(contacts.stillEligible(customer)).isFalse();
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).singleElement().satisfies(m -> assertThat(m.phone()).isNotEqualTo("8490000000" + a));
    }

    @Test
    void khongTimThayTrenZaloLaLoiRiengNguoiKhongDungVaVanTinhHanMuc() {
        ready(ZaloMode.CONFIRM);
        int a = first();
        fake.willReturnMessage(new Sent(Result.PERSON_ERROR, "NOT_FOUND", null, 0, false));
        assertThat(sender.step()).isEqualTo(Step.FAILED_ONE);
        assertThat(col("status", a)).isEqualTo("FAILED");
        assertThat(col("last_error", a)).contains("Không tìm thấy");
        assertThat(cfg.tripped()).isFalse();
        assertThat(appSettings.paused()).isFalse(); // "không tìm thấy" không bị tính vào tự dừng vì lỗi gửi liên tiếp
        assertThat(count("SELECT count(*) FROM send_log WHERE outcome = 'SKIPPED' AND channel = 'ZALO_AUTO'")).isEqualTo(1);
        assertThat(messages.usedToday(null)).isEqualTo(1);
        after(120);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
    }

    @Test
    void loiLaHoacKhongBietKetQuaThiCauDaoNhayVaKhongBaoGioTuGuiLai() {
        ready(ZaloMode.CONFIRM);
        int a = first();
        fake.willReturnMessage(new Sent(Result.SYSTEM_ERROR, "TIMEOUT", null, 0, false));
        assertThat(sender.step()).isEqualTo(Step.TRIPPED);
        assertThat(cfg.tripped()).isTrue();
        assertThat(cfg.trippedReason()).startsWith("SYSTEM_ERROR");
        assertThat(fake.stops).isEqualTo(1);
        assertThat(col("status", a)).isEqualTo("FAILED"); // không rõ tin đã đi chưa: KHÔNG ở READY để gửi lại
        assertThat(col("last_error", a)).contains("Không rõ");
        after(3600);
        assertThat(sender.step()).isEqualTo(Step.BLOCKED); // cầu dao giữ nguyên cho tới khi người mở lại
        cfg.reopen("mẹ");
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).hasSize(2);
        assertThat(fake.messages.get(1).phone()).isNotEqualTo(fake.messages.get(0).phone());
    }

    @Test
    void zaloCanhBaoThiDungNgay() {
        ready(ZaloMode.CONFIRM);
        int a = first();
        fake.willReturnMessage(new Sent(Result.RESTRICTED, "Z114", null, 0, false));
        assertThat(sender.step()).isEqualTo(Step.TRIPPED);
        assertThat(cfg.trippedReason()).startsWith("RESTRICTED");
        assertThat(col("status", a)).isEqualTo("FAILED");
    }

    @Test
    void cauNoiTuChoiTruocKhiChamZaloThiVeHangDoiVaKhongTinhHanMuc() {
        ready(ZaloMode.CONFIRM);
        int a = first();
        fake.willReturnMessage(new Sent(Result.RATE_LIMITED, null, null, 60, true));
        assertThat(sender.step()).isEqualTo(Step.RETRY);
        assertThat(col("status", a)).isEqualTo("READY");
        assertThat(messages.usedToday(null)).isZero();
        assertThat(cfg.tripped()).isFalse();
        fake.willReturnMessage(new Sent(Result.UNREACHABLE, "BRIDGE_DOWN", null, 0, true));
        assertThat(sender.step()).isEqualTo(Step.RETRY);
        assertThat(col("status", a)).isEqualTo("READY");
        // cầu nối dừng nhưng chắc chắn chưa gửi: Adh cũng dừng, lời mời quay về READY để gửi tiếp khi mở lại
        fake.willReturnMessage(new Sent(Result.RESTRICTED, "MSG_LIMIT", null, 0, true));
        assertThat(sender.step()).isEqualTo(Step.TRIPPED);
        assertThat(cfg.tripped()).isTrue();
        assertThat(col("status", a)).isEqualTo("READY");
        assertThat(messages.usedToday(null)).isZero();
    }

    @Test
    void sapGiuaChungThiDungKhongGuiTiep() {
        ready(ZaloMode.CONFIRM);
        int a = first();
        jdbc.sql("INSERT INTO zalo_msg_log (at, scope, kind, outcome) VALUES (:at, 'REAL', 'INVITE', 'DANG_GOI')")
                .param("at", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)).update();
        assertThat(sender.step()).isEqualTo(Step.BLOCKED);
        // dòng "đang gọi" làm hạn mức tính là đã dùng; Adh dừng ở lần gọi kế
        after(120);
        assertThat(sender.step()).isEqualTo(Step.TRIPPED);
        assertThat(cfg.trippedReason()).isEqualTo("TIN_INTERRUPTED");
        assertThat(fake.messages).isEmpty();
        assertThat(col("status", a)).isEqualTo("READY");
    }

    @Test
    void nhieuLanKhongTimThayLienTiepThiDungDeNguoiKiemTra() {
        ready(ZaloMode.CONFIRM);
        messages.setLimits(25, 100, "mẹ");
        for (int i = 0; i < 4; i++) {
            fake.willReturnMessage(new Sent(Result.PERSON_ERROR, "NOT_FOUND", null, 0, false));
            assertThat(sender.step()).isEqualTo(Step.FAILED_ONE);
            after(100);
        }
        fake.willReturnMessage(new Sent(Result.PERSON_ERROR, "NOT_FOUND", null, 0, false));
        sender.step();
        assertThat(cfg.tripped()).isTrue();
        assertThat(cfg.trippedReason()).isEqualTo("NHIEU_KHONG_TIM_THAY");
    }

    // ---------- khớp kết bạn theo mã Zalo ----------

    @Test
    void khachKetBanKhopTheoMaDaLuuKhongCanSoDienThoai() {
        ready(ZaloMode.AUTO);
        int a = first();
        sender.step();
        String uid = col("zalo_target_uid", a);
        fake.pushAdd(uid, null, 1); // khách ẩn số: sự kiện chỉ có mã
        assertThat(zalo.poll()).isEqualTo(1);
        assertThat(col("status", a)).isEqualTo("FRIENDED");
        assertThat(col("zalo_uid", a)).isEqualTo(uid);
        assertThat(col("zalo_add_state", a)).isEqualTo("QUEUED");
        assertThat(zalo.manualCount()).isZero();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(col("status", a)).isEqualTo("JOINED");
        // không thêm khách nào khác ngoài khách này, và không gửi Tin 2 khi đã thêm được
        assertThat(fake.adds).hasSize(1);
        after(120);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT); // chỉ là Tin 1 của khách kế tiếp
        assertThat(fake.messages.stream().filter(m -> m.kind() == Kind.LINK)).isEmpty();
    }

    @Test
    void themNhomLoiRiengNguoiThiKhongGuiTin2MaChoNguoiXuLy() {
        ready(ZaloMode.AUTO);
        int a = first();
        sender.step();
        String uid = col("zalo_target_uid", a);
        fake.pushAdd(uid, null, 1);
        zalo.poll();
        fake.willReturn(AddResult.of(Outcome.PERSON_ERROR, "Z240"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.PERSON_FAILED);
        // Tin 2 đã bỏ (24/11/2026): khách ở lại trạng thái lỗi để người xử lý, không có link nào được gửi
        assertThat(col("zalo_link_state", a)).isNull();
        assertThat(col("zalo_add_state", a)).isEqualTo("FAILED");
        after(120);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT); // chỉ là Tin 1 của khách kế tiếp
        assertThat(fake.messages.stream().filter(m -> m.kind() == Kind.LINK)).isEmpty();
    }

    @Test
    void tatTuGuiTinThiLoiThemNhomKhongXepTin2() {
        cfg.setMode(ZaloMode.AUTO, true, "mẹ");
        int a = first();
        sender.approveCampaign(campaign, "mẹ"); // công tắc tự gửi vẫn TẮT
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), zalo_target_uid = '555' WHERE id = :id").param("id", inv(a)).update();
        fake.pushAdd("555", null, 1);
        zalo.poll();
        fake.willReturn(AddResult.of(Outcome.PERSON_ERROR, "Z240"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.PERSON_FAILED);
        assertThat(col("zalo_link_state", a)).isNull();
        assertThat(sender.step()).isEqualTo(Step.NOT_ACTIVE);
    }

    @Test
    void nhatKyKhongChuaSoDienThoaiMaZaloHayNoiDungTin() {
        ready(ZaloMode.CONFIRM);
        int a = first();
        sender.step();
        String all = jdbc.sql("SELECT concat_ws(' ', scope, kind, ref_id, outcome, code) FROM zalo_msg_log").query(String.class).list().toString()
                + jdbc.sql("SELECT concat_ws(' ', result, code) FROM zalo_auto_log").query(String.class).list();
        assertThat(all).doesNotContain("8490000000" + a).doesNotContain(String.valueOf(col("zalo_target_uid", a))).doesNotContain("tư vấn");
        assertThat(jdbc.sql("SELECT phone_tail FROM send_log").query(String.class).single()).isEqualTo("***00" + a);
    }
}
