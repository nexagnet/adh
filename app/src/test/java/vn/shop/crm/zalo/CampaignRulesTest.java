package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.CampaignPolicy.Extra;
import vn.shop.crm.zalo.ZaloInviteSender.Step;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D21 PR 4 phần còn lại: ngày trong tuần được gửi, hoãn khi mưa lớn, tự dừng khi khách từ chối nhiều. Tất cả chỉ làm CHẶT hơn hệ thống:
 * để trống thì chạy y như trước; chỉ hoãn, không bao giờ gửi nhanh hơn; tự dừng chỉ bỏ duyệt (không xóa khách hay số điện thoại).
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.weather.enabled=true",
        "app.weather.initial-delay-millis=3600000"})
class CampaignRulesTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;
    @Autowired CampaignPolicy policy;
    @Autowired CampaignAutoStop autoStop;
    @Autowired RainHold rain;
    @Autowired WebApplicationContext context;

    long campaign;
    String province;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 25; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",09000000").append(String.format("%02d", i)).append(",1 Duong A Xa B Hà Nội,Giao thành công\n");
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        long north = groups.create("Nhóm miền Bắc", "https://zalo.me/g/bac", 50, "R:BAC");
        groups.setStatus(north, GroupStatus.OPEN);
        groups.setZaloGroupId(north, "1001");
        contacts.approveAllCandidates();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 25);
        province = jdbc.sql("SELECT province FROM customer WHERE province IS NOT NULL LIMIT 1").query(String.class).single();
        clock.set(LocalDateTime.of(2026, 11, 23, 10, 0)); // thứ Hai
        fake.reset();
        appSettings.set("zalo.msg.enabled", "false", "test");
        appSettings.set("zalo.msg.daily", "50", "test");
        appSettings.set("zalo.msg.gap_seconds", "120", "test");
        appSettings.set("zalo.auto.mode", "OFF", "test");
        appSettings.set("campaigns.paused", "false", "test");
        cfg.reopen("test");
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void after(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    private void forecast(String code, double rainMm, String symbol, long hoursFromNow) {
        long epoch = clock.instant().plus(Duration.ofHours(hoursFromNow)).getEpochSecond();
        String hourly = "[[" + epoch + ",30.0," + rainMm + ",90,\"" + symbol + "\"]]";
        jdbc.sql("""
                        INSERT INTO weather_forecast (province, min_temp, max_temp, symbol, hourly, fetched_at, expires_at)
                        VALUES (:p, 25, 32, 'cloudy', CAST(:h AS jsonb), now(), now() + interval '6 hours')
                        ON CONFLICT (province) DO UPDATE SET hourly = EXCLUDED.hourly, fetched_at = now()""")
                .param("p", code).param("h", hourly).update();
    }

    // ---------- để trống = y như trước ----------

    @Test
    void chuaDatDieuKienNaoThiGuiNhuCu() {
        assertThat(policy.loadExtra(campaign)).isEqualTo(Extra.NONE);
        assertThat(policy.blocker(campaign)).isEmpty();
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(fake.messages).hasSize(1);
    }

    // ---------- ngày trong tuần ----------

    @Test
    void chiGuiVaoNgayDuocChon() {
        policy.saveExtra(campaign, new Extra(Extra.maskOf(List.of(2, 4)), false, null), "mẹ"); // thứ Ba và thứ Năm
        assertThat(policy.blocker(campaign)).contains(CampaignPolicy.Block.WRONG_DAY); // hôm nay thứ Hai
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).isEmpty();
        clock.set(LocalDateTime.of(2026, 11, 24, 10, 0)); // thứ Ba
        assertThat(policy.blocker(campaign)).isEmpty();
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        clock.set(LocalDateTime.of(2026, 11, 29, 10, 0)); // Chủ nhật
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
    }

    @Test
    void chonDuBayNgayLaMoiNgayVaKhongChonNgayNaoBiTuChoi() {
        assertThat(Extra.maskOf(List.of(1, 2, 3, 4, 5, 6, 7))).isNull();
        assertThatThrownBy(() -> Extra.maskOf(List.of())).isInstanceOf(ImportException.class).hasMessageContaining("ít nhất một ngày");
        assertThatThrownBy(() -> Extra.maskOf(List.of(8))).isInstanceOf(ImportException.class);
        assertThat(new Extra(Extra.maskOf(List.of(7)), false, null).allows(7)).isTrue();
        assertThat(new Extra(Extra.maskOf(List.of(7)), false, null).allows(1)).isFalse();
    }

    // ---------- hoãn khi mưa lớn ----------

    @Test
    void mauLonSapToiThiKhachONoiDoDuocHoanKhongBiDongVaTiepTucKhiHetMua() {
        policy.saveExtra(campaign, new Extra(null, true, null), "mẹ");
        forecast(province, 6.5, "heavyrain", 1);
        assertThat(rain.rainyProvinces()).contains(province);
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).isEmpty();
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'READY'")).isEqualTo(25); // chỉ hoãn, chưa đóng lời mời nào
        forecast(province, 0.2, "cloudy", 1);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
    }

    @Test
    void khongBatHoanThiMuaLonKhongAnhHuong() {
        forecast(province, 9.0, "thunder", 0);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
    }

    @Test
    void duBaoCuHoacChuaCoThiKhongHoanVaMuaXaGioKhongTinh() {
        policy.saveExtra(campaign, new Extra(null, true, null), "mẹ");
        assertThat(rain.rainyProvinces()).isEmpty(); // chưa có dự báo: không có căn cứ để hoãn
        forecast(province, 8.0, "heavyrain", 8); // 8 giờ nữa: ngoài cửa sổ 3 giờ
        assertThat(rain.rainyProvinces()).isEmpty();
        forecast(province, 8.0, "heavyrain", 1);
        jdbc.sql("UPDATE weather_forecast SET fetched_at = now() - interval '2 days'").update();
        assertThat(rain.rainyProvinces()).isEmpty(); // dự báo quá cũ
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
    }

    // ---------- tự dừng khi khách từ chối nhiều ----------

    private void sendFirst(int n) {
        for (int i = 0; i < n; i++) {
            assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
            after(120);
        }
    }

    private void refuse(int fromId, int n) {
        List<Long> ids = jdbc.sql("SELECT customer_id FROM invitation WHERE status = 'SENT' ORDER BY id LIMIT :n OFFSET :o")
                .param("n", n).param("o", fromId).query(Long.class).list();
        ids.forEach(id -> contacts.suppressAs(id, "Khách từ chối", "test"));
    }

    @Test
    void duMuoiTinVaNamPhanTramTuChoiThiBoDuyetVaGhiLyDoKhongXoaKhach() {
        sendFirst(20);
        long customersBefore = count("SELECT count(*) FROM customer");
        refuse(0, 1); // 1/20 = 5%
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(sender.approved(campaign)).isFalse();
        assertThat(autoStop.stoppedReason(campaign)).hasValueSatisfying(r -> assertThat(r).contains("1/20").contains("5%"));
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'READY'")).isEqualTo(5); // chưa gửi thêm ai
        assertThat(count("SELECT count(*) FROM customer")).isEqualTo(customersBefore); // không xóa khách nào
        assertThat(count("SELECT count(*) FROM suppression")).isEqualTo(1); // số vẫn nằm trong sổ từ chối
        assertThat(count("SELECT count(*) FROM zalo_auto_log WHERE result LIKE 'TU_DUNG_TU_CHOI_CAO%'")).isEqualTo(1);
    }

    @Test
    void chuaDuMuoiTinThiKhongDungDuLaTiLeCao() {
        sendFirst(19);
        refuse(0, 3);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        assertThat(sender.approved(campaign)).isTrue();
    }

    @Test
    void duoiNguongThiKhongDung() {
        sendFirst(25);
        refuse(0, 1); // 1/25 = 4%
        sender.step();
        assertThat(sender.approved(campaign)).isTrue();
        assertThat(autoStop.stoppedReason(campaign)).isEmpty();
    }

    @Test
    void nguongRiengCuaChienDichThapHonThiDungSom() {
        policy.saveExtra(campaign, new Extra(null, false, 2), "mẹ");
        sendFirst(25);
        refuse(0, 1); // 4% chưa tới 5% chung nhưng đã quá 2% riêng
        sender.step();
        assertThat(sender.approved(campaign)).isFalse();
    }

    @Test
    void duyetLaiBangTayThiDemLaiTuDauKhongDungLaiNgay() {
        sendFirst(20);
        refuse(0, 2);
        sender.step();
        assertThat(sender.approved(campaign)).isFalse();
        after(60);
        sender.approveCampaign(campaign, "mẹ");
        assertThat(autoStop.stoppedReason(campaign)).isEmpty(); // người đã xem và duyệt lại: dấu dừng được xóa
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT); // bộ đếm bắt đầu lại, chưa đủ 20 tin
        assertThat(sender.approved(campaign)).isTrue();
    }

    @Test
    void ngayDungTuDongHienCanhBaoSucKhoe() {
        sendFirst(20);
        refuse(0, 2);
        sender.step();
        var signals = health().signals();
        assertThat(signals).anyMatch(s -> ZaloHealthService.K_REFUSAL.equals(s.kind()));
    }

    @Autowired ZaloHealthService healthService;

    private ZaloHealthService health() {
        return healthService;
    }

    // ---------- lưu qua trang ----------

    @Test
    void nguongRiengCaoHonHeThongBiTuChoi() {
        assertThatThrownBy(() -> policy.saveExtra(campaign, new Extra(null, false, CampaignAutoStop.SYSTEM_PCT + 1), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("không được cao hơn");
        assertThatThrownBy(() -> policy.saveExtra(campaign, new Extra(null, false, 0), "mẹ")).isInstanceOf(ImportException.class);
        assertThat(policy.loadExtra(campaign)).isEqualTo(Extra.NONE);
    }

    @Test
    void trangLuuDieuKienRoiHienLaiVaNutTheoHeThongXoa() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/campaigns/" + campaign + "/rules").with(csrf()).param("days", "1")).andExpect(status().is3xxRedirection());
        assertThat(policy.loadExtra(campaign)).isEqualTo(Extra.NONE); // chưa đăng nhập thì không lưu
        mvc.perform(post("/campaigns/" + campaign + "/rules").with(user("admin")).with(csrf())
                        .param("days", "1").param("days", "2").param("holdOnRain", "true").param("stopPct", "3"))
                .andExpect(redirectedUrl("/campaigns/" + campaign));
        assertThat(policy.loadExtra(campaign)).isEqualTo(new Extra(3, true, 3));
        mvc.perform(get("/campaigns/" + campaign).with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Điều kiện gửi riêng")));
        mvc.perform(post("/campaigns/" + campaign + "/rules").with(user("admin")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("ít nhất một ngày")));
        mvc.perform(post("/campaigns/" + campaign + "/rules").with(user("admin")).with(csrf()).param("days", "1").param("stopPct", "50"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("không được cao hơn")));
        mvc.perform(post("/campaigns/" + campaign + "/rules").with(user("admin")).with(csrf()).param("reset", "true"))
                .andExpect(redirectedUrl("/campaigns/" + campaign));
        assertThat(policy.loadExtra(campaign)).isEqualTo(Extra.NONE);
    }
}
