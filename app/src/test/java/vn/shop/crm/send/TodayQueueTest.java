package vn.shop.crm.send;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Lô nhắn hôm nay": chỉ gợi ý đủ hạn mức ngày và giờ, trong giờ nhắn; người thật nhắn tay rồi bấm "Đã nhắn".
 * Hệ thống không gọi dịch vụ ngoài nào.
 */
@Import(MutableClock.Config.class)
class TodayQueueTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
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
    @Autowired
    WebApplicationContext context;

    long campaign;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 11, 21, 10, 0));
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 8; i++) {
            csv.append("T%d,DH%d,05/01/2026 09:00:00,Khach %d,09000000%02d,%d Duong A Xa B Cần Thơ,Giao thành công\n"
                    .formatted(i, i, i, i, i));
        }
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        campaign = invites.createCampaign("Rét miền Nam", null, 14, 8, "R:NAM");
    }

    private SendService service(int daily, int hourly) {
        return new SendService(jdbc, settings, contacts, invites,
                new SendProperties(false, LocalTime.of(7, 0), LocalTime.of(22, 0), daily, hourly, 120, false, "",
                        1_000_000, 3, 0.3, 0.05, 3),
                clock, List.of(mock, manual));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void goiYKhongQuaHanMucGioVaNgay() {
        SendService send = service(5, 2);
        var today = send.today(null);
        assertThat(today.allowedNow()).isEqualTo(2);
        assertThat(today.invitationIds()).hasSize(2);
        assertThat(today.readyTotal()).isEqualTo(8);

        today.invitationIds().forEach(send::markMessaged);
        var after = send.today(null);
        assertThat(after.allowedNow()).isZero();
        assertThat(after.invitationIds()).isEmpty();
        assertThat(after.emptyReason()).contains("Giờ này đã đủ hạn mức");
        assertThat(after.nextSuggestedAt()).isNotNull();

        // sau hơn một giờ: hạn mức giờ được trả lại, hạn mức ngày còn 3
        clock.advance(Duration.ofMinutes(61));
        assertThat(send.today(null).allowedNow()).isEqualTo(2);
        send.today(null).invitationIds().forEach(send::markMessaged);
        clock.advance(Duration.ofMinutes(61));
        assertThat(send.today(null).allowedNow()).isEqualTo(1);
        send.today(null).invitationIds().forEach(send::markMessaged);
        clock.advance(Duration.ofMinutes(61));
        var full = send.today(null);
        assertThat(full.allowedNow()).isZero();
        assertThat(full.emptyReason()).contains("Hôm nay đã đủ hạn mức");

        // qua ngày hôm sau thì được nhắn tiếp
        clock.set(LocalDateTime.of(2026, 11, 22, 9, 0));
        assertThat(send.today(null).allowedNow()).isEqualTo(2);
    }

    @Test
    void khongGioiHanTheoGioKhiDatKhong() {
        SendService send = service(5, 0);
        assertThat(send.today(null).allowedNow()).isEqualTo(5);
    }

    @Test
    void ngoaiGioNhanVaKhiTamDungThiKhongGoiYAi() {
        SendService send = service(5, 5);
        clock.set(LocalDateTime.of(2026, 11, 21, 23, 0));
        var late = send.today(null);
        assertThat(late.outsideWindow()).isTrue();
        assertThat(late.invitationIds()).isEmpty();
        assertThat(late.emptyReason()).contains("Ngoài giờ nhắn");

        clock.set(LocalDateTime.of(2026, 11, 21, 10, 0));
        settings.pauseAll("thử", "test");
        var paused = send.today(null);
        assertThat(paused.invitationIds()).isEmpty();
        assertThat(paused.emptyReason()).contains("tạm dừng");
    }

    @Test
    void daNhanGhiNhatKyVaTrangThaiVaChiNhanMotLan() {
        SendService send = service(5, 5);
        long inv = send.today(null).invitationIds().get(0);
        send.markMessaged(inv);
        assertThat(jdbc.sql("SELECT status FROM invitation WHERE id = :i").param("i", inv).query(String.class).single())
                .isEqualTo("SENT");
        assertThat(jdbc.sql("SELECT sent_channel FROM invitation WHERE id = :i").param("i", inv).query(String.class).single())
                .isEqualTo("ZALO_PHU");
        assertThat(count("SELECT count(*) FROM send_log WHERE outcome = 'SENT' AND channel = 'ZALO_PHU'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM contact_event WHERE event = 'INVITATION_SENT'")).isEqualTo(1);
        assertThatThrownBy(() -> send.markMessaged(inv)).isInstanceOf(ImportException.class)
                .hasMessageContaining("một lần");
        assertThat(count("SELECT count(*) FROM send_log WHERE outcome = 'SENT'")).isEqualTo(1);
        // người đã nhắn không còn trong lô gợi ý
        assertThat(send.today(null).invitationIds()).doesNotContain(inv);
    }

    @Test
    void loiMoiDaHuyBaoDungLyDoKhongNoiLaDaNhan() {
        SendService send = service(5, 5);
        long inv = send.today(null).invitationIds().get(0);
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE id = :i").param("i", inv).update();
        assertThatThrownBy(() -> send.markMessaged(inv)).isInstanceOf(ImportException.class)
                .hasMessageContaining("đã bị hủy");
    }

    @Test
    void khachDaTuChoiThiLoiMoiChuaNhanBiXoaVaKhongConTrongLo() {
        SendService send = service(5, 5);
        long inv = send.today(null).invitationIds().get(0);
        long customer = jdbc.sql("SELECT customer_id FROM invitation WHERE id = :i").param("i", inv).query(Long.class).single();
        contacts.suppress(customer, "Khách xin ngừng nhận tin");
        assertThat(send.today(null).invitationIds()).doesNotContain(inv);
        assertThatThrownBy(() -> send.markMessaged(inv)).isInstanceOf(ImportException.class);
        assertThat(count("SELECT count(*) FROM send_log")).isZero();
    }

    @Test
    void khachBiChanSauKhiTaoChienDichThiKhongDuocGoiYVaKhongGhiNhanDaNhan() {
        SendService send = service(5, 5);
        long inv = send.today(null).invitationIds().get(0);
        long customer = jdbc.sql("SELECT customer_id FROM invitation WHERE id = :i").param("i", inv).query(Long.class).single();
        // lời mời còn nhưng khách không còn đủ điều kiện (ví dụ bị chặn ngay sau khi tạo lô)
        jdbc.sql("UPDATE contact_basis SET status = 'BLOCKED' WHERE customer_id = :c").param("c", customer).update();
        assertThat(send.today(null).invitationIds()).doesNotContain(inv);
        assertThatThrownBy(() -> send.markMessaged(inv)).isInstanceOf(ImportException.class)
                .hasMessageContaining("Đừng nhắn");
        assertThat(jdbc.sql("SELECT status FROM invitation WHERE id = :i").param("i", inv).query(String.class).single())
                .isEqualTo("READY");
        assertThat(count("SELECT count(*) FROM send_log")).isZero();
    }

    @Test
    void ghiNhanKhongBiChanNgoaiGioVoiDieuKienDaNhanThat() {
        // tin đã nhắn thật rồi mới bấm: không được từ chối chỉ vì quá giờ, nếu không hệ thống sẽ không biết và có thể nhắn lại
        SendService send = service(5, 5);
        long inv = send.today(null).invitationIds().get(0);
        clock.set(LocalDateTime.of(2026, 11, 21, 23, 30));
        send.markMessaged(inv);
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'SENT'")).isEqualTo(1);
    }

    @Test
    void locTheoChienDichVaTrangThaiCaiDat() {
        SendService send = service(5, 5);
        assertThat(send.today(campaign).invitationIds()).hasSize(5);
        assertThat(send.today(campaign + 99).invitationIds()).isEmpty();
        var status = send.status();
        assertThat(status.hourlyLimit()).isEqualTo(5);
        assertThat(status.suggestedGapMinutes()).isEqualTo(3);
    }

    @Test
    void xuatDanhSachCungTuanTheoHanMucGio() {
        SendService send = service(20, 3);
        var result = send.runBatch(campaign, manual, 8);
        assertThat(result.sent()).isEqualTo(3);
        assertThat(result.stoppedBy()).isEqualTo(SendService.StopReason.HOURLY_LIMIT);
    }

    @Test
    void trangLoNhacHomNayHienTinVaNutDaNhan() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/today").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Lô nhắn hôm nay")))
                .andExpect(content().string(containsString("Đã nhắn")))
                .andExpect(content().string(containsString("Dạ em chào bác Khach ")));
        long inv = jdbc.sql("SELECT min(id) FROM invitation").query(Long.class).single();
        mvc.perform(post("/today/" + inv + "/messaged").with(owner).with(csrf()).param("campaign", String.valueOf(campaign)))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.sql("SELECT status FROM invitation WHERE id = :i").param("i", inv).query(String.class).single())
                .isEqualTo("SENT");
        mvc.perform(get("/zalo").param("tab", "thu").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tạm dừng và nhật ký gửi")));
    }
}
