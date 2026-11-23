package vn.shop.crm.send;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.contact.PrivacyService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.send.InvitationSender.OutboundInvite;
import vn.shop.crm.send.InvitationSender.SendResult;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Mốc 1D: cổng chặn gửi, nhật ký gửi, tự dừng. Bộ gửi giả đếm số lần được gọi; không có dịch vụ ngoài nào. */
@Import(MutableClock.Config.class)
class SendAcceptanceTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
    @Autowired
    PrivacyService privacy;
    @Autowired
    InviteService invites;
    @Autowired
    AppSettings settings;
    @Autowired
    MutableClock clock;
    @Autowired
    MockSender mock;
    @Autowired
    ManualExportSender manual;

    /** Bộ gửi giả: tự động hay không, chi phí, kết quả theo thứ tự lần gọi. */
    static class FakeSender implements InvitationSender {
        final boolean automated;
        final int cost;
        final Function<Integer, SendResult> script;
        final List<OutboundInvite> calls = new ArrayList<>();

        FakeSender(boolean automated, int cost, Function<Integer, SendResult> script) {
            this.automated = automated;
            this.cost = cost;
            this.script = script;
        }

        static FakeSender ok(boolean automated) {
            return new FakeSender(automated, 0, n -> SendResult.success());
        }

        @Override
        public String channel() {
            return "FAKE";
        }

        @Override
        public boolean automated() {
            return automated;
        }

        @Override
        public boolean realDelivery() {
            return true;
        }

        @Override
        public int costPerMessageVnd() {
            return cost;
        }

        @Override
        public synchronized SendResult send(OutboundInvite invite) {
            calls.add(invite);
            return script.apply(calls.size());
        }
    }

    @BeforeEach
    void resetClock() {
        clock.set(LocalDateTime.of(2026, 11, 21, 10, 0));
    }

    private SendProperties props(boolean enabled, int dailyLimit, long budget, int consecutive, double failRate) {
        return props(enabled, dailyLimit, 0, budget, consecutive, failRate);
    }

    private SendProperties props(boolean enabled, int dailyLimit, int hourlyLimit, long budget, int consecutive,
                                 double failRate) {
        return new SendProperties(enabled, LocalTime.of(7, 0), LocalTime.of(22, 0), dailyLimit, hourlyLimit, 120,
                false, "", budget, consecutive, failRate, 0.05, 3);
    }

    private SendService service(SendProperties p) {
        return new SendService(jdbc, settings, contacts, invites, p, clock, List.of(mock, manual));
    }

    private SendService defaultService() {
        return service(props(false, 20, 1_000_000, 3, 0.3));
    }

    /** Tạo n khách đã duyệt và một chiến dịch gồm n lời mời; trả id chiến dịch. */
    private long campaignOf(int n) {
        String[] rows = new String[n];
        for (int i = 0; i < n; i++) {
            rows[i] = "T%d,DH%d,05/01/2026 09:00:00,Khach %d,09000000%02d,%d Duong Bi Mat,Giao thành công"
                    .formatted(i + 1, i + 1, i + 1, i + 1, i + 1);
        }
        String csv = HEADER + String.join("\n", rows) + "\n";
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        return invites.createCampaign("Đợt thử", "Chào {ten}, {cua_hang} tư vấn. Muốn ngừng nhận tin thì bấm: {link}", 14, Math.min(n, invites.maxBatch()));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ---------- chạy thử và gửi tay ----------

    @Test
    void chayThuKhongGuiGiKhongDoiTrangThaiVaKhongTinhVaoHanMuc() {
        long camp = campaignOf(3);

        var result = defaultService().runBatch(camp, mock, 10);

        assertThat(result.dryRun()).isEqualTo(3);
        assertThat(result.sent()).isZero();
        assertThat(result.delivered()).hasSize(3);
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'READY'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM send_log WHERE outcome = 'DRY_RUN' AND channel = 'MOCK'")).isEqualTo(3);
        assertThat(defaultService().sentToday()).isZero();
    }

    @Test
    void xuatDanhSachGuiTayDanhDauDaGuiVaKhongGuiTrungLanHai() {
        long camp = campaignOf(3);
        SendService svc = defaultService();

        var first = svc.runBatch(camp, manual, 10);
        var second = svc.runBatch(camp, manual, 10);

        assertThat(first.sent()).isEqualTo(3);
        assertThat(second.sent()).isZero();
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'SENT' AND sent_channel = 'MANUAL_EXPORT'"))
                .isEqualTo(3);
        String csv = new String(SendService.exportCsv(first.delivered()), StandardCharsets.UTF_8);
        assertThat(csv).contains("so_dien_thoai").contains("/i/").contains("0900000001");
        assertThat(csv).doesNotContain("Duong Bi Mat"); // tin không chứa địa chỉ
        assertThat(count("SELECT count(*) FROM contact_event WHERE event = 'INVITATION_SENT'")).isEqualTo(3);
    }

    @Test
    void haiLanChayCungLucKhongBaoGioGuiTrungMotKhach() throws Exception {
        long camp = campaignOf(10);
        SendService svc = service(props(false, 100, 1_000_000, 100, 0.99));
        FakeSender sender = FakeSender.ok(false);
        Callable<Integer> job = () -> svc.runBatch(camp, sender, 10).sent();

        var pool = Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(job));
        }
        int total = 0;
        for (var f : futures) {
            total += f.get();
        }
        pool.shutdown();

        assertThat(total).isEqualTo(10);
        assertThat(sender.calls).hasSize(10);
        assertThat(sender.calls.stream().map(OutboundInvite::invitationId).distinct()).hasSize(10);
    }

    // ---------- các cổng chặn ----------

    @Test
    void tamDungChanMoiKenhKeCaChayThu() {
        long camp = campaignOf(2);
        settings.pauseAll("kiểm tra", "test");

        assertThat(defaultService().runBatch(camp, manual, 10).stoppedBy()).isEqualTo(SendService.StopReason.PAUSED);
        assertThat(defaultService().runBatch(camp, mock, 10).stoppedBy()).isEqualTo(SendService.StopReason.PAUSED);
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'READY'")).isEqualTo(2);

        settings.resumeAll("test");
        assertThat(defaultService().runBatch(camp, manual, 10).sent()).isEqualTo(2);
    }

    @Test
    void kenhTuDongMacDinhBiTatVaChiChayTrongGioChoPhep() {
        long camp = campaignOf(2);
        FakeSender sender = FakeSender.ok(true);

        var off = service(props(false, 20, 1_000_000, 3, 0.3)).runBatch(camp, sender, 10);
        assertThat(off.stoppedBy()).isEqualTo(SendService.StopReason.DISABLED);

        clock.set(LocalDateTime.of(2026, 11, 21, 22, 0));
        var night = service(props(true, 20, 1_000_000, 3, 0.3)).runBatch(camp, sender, 10);
        assertThat(night.stoppedBy()).isEqualTo(SendService.StopReason.OUTSIDE_WINDOW);
        clock.set(LocalDateTime.of(2026, 11, 21, 6, 59));
        assertThat(service(props(true, 20, 1_000_000, 3, 0.3)).runBatch(camp, sender, 10).stoppedBy())
                .isEqualTo(SendService.StopReason.OUTSIDE_WINDOW);

        assertThat(sender.calls).isEmpty();
        clock.set(LocalDateTime.of(2026, 11, 21, 10, 0));
        assertThat(service(props(true, 20, 1_000_000, 3, 0.3)).runBatch(camp, sender, 10).sent()).isEqualTo(2);
    }

    @Test
    void hanMucNgayDungDungSoLuongVaSangNgaySauGuiTiep() {
        long camp = campaignOf(3);
        SendService svc = service(props(false, 2, 1_000_000, 100, 0.99));

        var today = svc.runBatch(camp, manual, 10);
        assertThat(today.sent()).isEqualTo(2);
        assertThat(today.stoppedBy()).isEqualTo(SendService.StopReason.DAILY_LIMIT);

        clock.advance(Duration.ofDays(1));
        var tomorrow = svc.runBatch(camp, manual, 10);
        assertThat(tomorrow.sent()).isEqualTo(1);
    }

    @Test
    void kiemLaDieuKienLucGuiVaBoQuaLoiMoiHetHan() {
        long camp = campaignOf(3);
        // khách 1 bị gắn "cần kiểm tra" sau khi lời mời đã tạo; lời mời của khách 2 hết hạn
        jdbc.sql("UPDATE customer SET needs_review = TRUE WHERE phone = '+84900000001'").update();
        // hết hạn so với đồng hồ giả của test (không phụ thuộc ngày thật)
        jdbc.sql("UPDATE invitation SET expires_at = :t "
                + "WHERE customer_id = (SELECT id FROM customer WHERE phone = '+84900000002')")
                .param("t", java.time.OffsetDateTime.now(clock).minusDays(1)).update();
        FakeSender sender = FakeSender.ok(false);

        var result = service(props(false, 20, 1_000_000, 100, 0.99)).runBatch(camp, sender, 10);

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(sender.calls).extracting(OutboundInvite::phone).containsExactly("+84900000003");
        assertThat(count("SELECT count(*) FROM send_log WHERE outcome = 'SKIPPED'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'READY'")).isEqualTo(2);
    }

    @Test
    void khachDaTuChoiTruocKhiGuiKhongBaoGioNhanTin() {
        long camp = campaignOf(2);
        long cust = jdbc.sql("SELECT id FROM customer WHERE phone = '+84900000001'").query(Long.class).single();
        contacts.suppress(cust, "khách nhắn");
        FakeSender sender = FakeSender.ok(false);

        var result = service(props(false, 20, 1_000_000, 100, 0.99)).runBatch(camp, sender, 10);

        assertThat(sender.calls).extracting(OutboundInvite::phone).containsExactly("+84900000002");
        assertThat(result.sent()).isEqualTo(1);
    }

    // ---------- tự dừng ----------

    @Test
    void loiLienTiepTuDungVaGhiLoiMoiLaFailed() {
        long camp = campaignOf(5);
        FakeSender sender = new FakeSender(false, 0, n -> SendResult.failure("Không có Zalo"));

        var result = service(props(false, 20, 1_000_000, 3, 0.99)).runBatch(camp, sender, 10);

        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.autoStopped()).contains("3 lần gửi lỗi liên tiếp");
        assertThat(settings.paused()).isTrue();
        assertThat(settings.pausedReason()).startsWith("Tự dừng");
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'FAILED' AND last_error = 'Không có Zalo'"))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'READY'")).isEqualTo(2);
        assertThat(sender.calls).hasSize(3);
    }

    @Test
    void tyLeLoiCaoTrong24GioTuDung() {
        long camp = campaignOf(6);
        FakeSender sender = new FakeSender(false, 0, n -> n <= 3 ? SendResult.success() : SendResult.failure("lỗi"));

        // 3 thành công rồi 2 lỗi = 2/5 = 40% >= 30%, nhưng chưa đủ 3 lỗi liên tiếp
        var result = service(props(false, 20, 1_000_000, 3, 0.3)).runBatch(camp, sender, 10);

        assertThat(result.autoStopped()).contains("Tỷ lệ gửi lỗi cao");
        assertThat(settings.paused()).isTrue();
        assertThat(sender.calls).hasSize(5);
    }

    @Test
    void nhieuKhachTuChoiSauKhiNhanTinTuDung() {
        long camp = campaignOf(10);
        SendService svc = service(props(false, 20, 1_000_000, 100, 0.99));
        assertThat(svc.runBatch(camp, FakeSender.ok(false), 10).sent()).isEqualTo(10);
        assertThat(svc.evaluateAutoStop()).isNull();

        long cust = jdbc.sql("SELECT id FROM customer WHERE phone = '+84900000001'").query(Long.class).single();
        privacy.stopContact(cust, null, "khách nhắn");

        assertThat(svc.evaluateAutoStop()).contains("từ chối");
        assertThat(settings.paused()).isTrue();
    }

    @Test
    void tranNganSachThangDungDungVaTuDung() {
        long camp = campaignOf(4);
        FakeSender sender = new FakeSender(false, 500, n -> SendResult.success());

        var result = service(props(false, 20, 1000, 100, 0.99)).runBatch(camp, sender, 10);

        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.autoStopped()).contains("hết ngân sách");
        assertThat(sender.calls).hasSize(2);
        assertThat(service(props(false, 20, 1000, 100, 0.99)).monthSpend()).isEqualTo(1000);
    }

    // ---------- gửi từng tin một, nghỉ cố định ----------

    @Test
    void sendNextGuiMotTinRoiNghiDungKhoangCoDinh() {
        campaignOf(3);
        SendService svc = service(props(true, 20, 1_000_000, 100, 0.99));
        FakeSender sender = FakeSender.ok(true);

        var first = svc.sendNext(sender);
        assertThat(first.sent()).isEqualTo(1);

        var wait = svc.sendNext(sender);
        assertThat(wait.waiting()).isTrue();
        assertThat(sender.calls).hasSize(1);

        clock.advance(Duration.ofSeconds(119));
        assertThat(svc.sendNext(sender).waiting()).isTrue();
        clock.advance(Duration.ofSeconds(2)); // quá đúng 120 giây từ tin đầu
        assertThat(svc.sendNext(sender).sent()).isEqualTo(1);
        // mốc tin sau cách tin vừa gửi ĐÚNG 120 giây, không dao động
        assertThat(svc.status().nextAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant().plusSeconds(120), clock.getZone()));
    }

    // ---------- nhật ký ----------

    @Test
    void loiBoGuiKhongLoThongTinVaNhatKyGiuLaiSauKhiXoaKhach() {
        long camp = campaignOf(1);
        FakeSender sender = new FakeSender(false, 0, n -> {
            throw new IllegalStateException("kết nối thất bại tới 0900000001");
        });

        var result = service(props(false, 20, 1_000_000, 100, 0.99)).runBatch(camp, sender, 10);

        assertThat(result.failed()).isEqualTo(1);
        String reason = jdbc.sql("SELECT reason FROM send_log WHERE outcome = 'FAILED'").query(String.class).single();
        assertThat(reason).doesNotContain("0900000001").contains("IllegalStateException");

        long cust = jdbc.sql("SELECT id FROM customer").query(Long.class).single();
        privacy.erase(cust, null, "khách yêu cầu");
        assertThat(count("SELECT count(*) FROM send_log")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT phone_tail FROM send_log").query(String.class).single()).isEqualTo("***001");
    }

    @Test
    void trangThaiHienThiHanMucVaNhatKyGanDay() {
        long camp = campaignOf(2);
        SendService svc = defaultService();
        svc.runBatch(camp, manual, 1);

        var status = svc.status();

        assertThat(status.enabled()).isFalse();
        assertThat(status.sentToday()).isEqualTo(1);
        assertThat(status.dailyLimit()).isEqualTo(20);
        assertThat(status.recent()).hasSize(1);
        assertThat(status.recent().getFirst().outcome()).isEqualTo("SENT");
    }
}
