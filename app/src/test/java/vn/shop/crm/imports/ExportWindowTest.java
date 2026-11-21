package vn.shop.crm.imports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Báo cần xuất Excel từ ngày nào: mốc theo thời gian tạo đơn mới nhất, gối đầu, giới hạn lùi, chia khoảng ngày. */
class ExportWindowTest extends AbstractPostgresTest {

    private static final String HEADER = "Mã vận đơn,Trạng thái vận đơn,SĐT người nhận,Địa chỉ người nhận,Tên người nhận,"
            + "Địa chỉ hành chính,Nội dung hàng hóa,Thời gian tạo đơn,Trọng lượng tính phí,Cước phí,"
            + "Phương thức kết toán,Giao một phần,Tiền thu hộ COD,COD thực thu\n";

    @Autowired
    ImportService imports;
    @Autowired
    WebApplicationContext context;

    LocalDate today;
    ExportWindowService service;

    @BeforeEach
    void setUp() throws Exception {
        for (String file : List.of("V3__jt_status_mapping.sql", "V11__jt_more_statuses.sql")) {
            jdbc.sql(new String(getClass().getResourceAsStream("/db/migration/" + file).readAllBytes(),
                    StandardCharsets.UTF_8)).update();
        }
        today = LocalDate.now(ExportWindowService.ZONE);
        service = new ExportWindowService(jdbc, Clock.system(ExportWindowService.ZONE), 14, 30, 7);
    }

    private static String line(String tracking, String status, String phone, LocalDate created) {
        return tracking + "," + status + "," + phone + ",\"1 A\",Nguyen Van A,\"Phường 1, Quận 1, Hà Nội\",Ao,"
                + created + " 09:30,1.0,20000,Nguoi gui tra,,0,0\n";
    }

    private void importJt(String... lines) {
        long id = imports.createBatch(Carrier.JT_EXPRESS, List.of(new ImportService.UploadedFile("a.csv",
                (HEADER + String.join("", lines)).getBytes(StandardCharsets.UTF_8))));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    @Test
    void chuaNhapDonNaoThiChiBaoXuatTuNgayBatDauBan() {
        var a = service.advise(Carrier.JT_EXPRESS);
        assertThat(a.hasData()).isFalse();
        assertThat(a.ranges()).isEmpty();
        assertThat(a.overdue()).isFalse();
        assertThat(service.overdue()).isEmpty();
    }

    @Test
    void moTheoNgayTaoDonMoiNhatLuiVaiNgayGoiDau() {
        importJt(line("861000000001", "Giao thành công", "0912345678", today.minusDays(5)),
                line("861000000002", "Giao thành công", "0987654321", today.minusDays(40)));
        var a = service.advise(Carrier.JT_EXPRESS);
        assertThat(a.hasData()).isTrue();
        assertThat(a.latestOrder()).isEqualTo(today.minusDays(5));
        assertThat(a.from()).isEqualTo(today.minusDays(19));
        assertThat(a.to()).isEqualTo(today);
        assertThat(a.ranges()).containsExactly(new ExportWindowService.Range(today.minusDays(19), today));
        assertThat(a.split()).isFalse();
        assertThat(a.overdue()).isFalse();
        assertThat(a.daysSince()).isZero();
    }

    @Test
    void donChuaCoKetQuaCuoiTrongVongLuiKeoMocLuiConDonTreoQuaCuThiChiDemRieng() {
        importJt(line("861000000001", "Giao thành công", "0912345678", today.minusDays(2)),
                line("861000000002", "Đang vận chuyển", "0987654321", today.minusDays(25)),
                // "Đã lấy hàng" từ nhiều tháng trước: hãng không cập nhật nữa, không được kéo mốc lùi mãi
                line("861000000003", "Đã lấy hàng", "0911111111", today.minusDays(150)),
                line("861000000004", "Đã lấy hàng", "0922222222", today.minusDays(140)));
        var a = service.advise(Carrier.JT_EXPRESS);
        assertThat(a.from()).isEqualTo(today.minusDays(25));
        assertThat(a.stuckOpen()).isEqualTo(2);
    }

    @Test
    void quaHanNhacKhiLanNhapGanNhatDaLauVaDonKetThucKhongKeoMoc() {
        importJt(line("861000000001", "Giao thành công", "0912345678", today.minusDays(9)));
        jdbc.sql("UPDATE import_batch SET committed_at = now() - interval '10 days'").update();
        var a = service.advise(Carrier.JT_EXPRESS);
        assertThat(a.overdue()).isTrue();
        assertThat(a.daysSince()).isBetween(9L, 10L);
        assertThat(service.overdue()).extracting(ExportWindowService.Advice::carrier).containsExactly(Carrier.JT_EXPRESS);
        assertThat(service.advise(Carrier.VIETTEL_POST).hasData()).isFalse();
    }

    @Test
    void khoangDaiHon31NgayThiChiaThanhCacKhoangLienTiepKhongHoKhongChong() {
        var t = LocalDate.of(2026, 11, 21);
        var a = ExportWindowService.plan(Carrier.JT_EXPRESS, t, t.minusDays(60), null, 2.0, 14, 500, 31, 0);
        assertThat(a.from()).isEqualTo(t.minusDays(74));
        assertThat(a.ranges()).hasSize(3);
        assertThat(a.ranges().getFirst().from()).isEqualTo(a.from());
        assertThat(a.ranges().getLast().to()).isEqualTo(t);
        for (int i = 0; i < a.ranges().size(); i++) {
            var r = a.ranges().get(i);
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(r.from(), r.to()) + 1).isLessThanOrEqualTo(31);
            if (i > 0) {
                assertThat(r.from()).isEqualTo(a.ranges().get(i - 1).to().plusDays(1));
            }
        }
    }

    @Test
    void nhieuDonMoiNgayThiChiaNhoHonDeKhongChamHanMuc500() {
        var t = LocalDate.of(2026, 11, 21);
        // 20 đơn/ngày: 25 ngày = 500 đơn, sát hạn mức, nên chia nhỏ để chừa chỗ
        var a = ExportWindowService.plan(Carrier.JT_EXPRESS, t, t.minusDays(10), null, 20.0, 14, 500, 31, 0);
        assertThat(a.ranges().size()).isGreaterThan(1);
        for (var r : a.ranges()) {
            assertThat((java.time.temporal.ChronoUnit.DAYS.between(r.from(), r.to()) + 1) * 20).isLessThanOrEqualTo(500);
        }
        assertThat(a.estimated()).isEqualTo(Math.round(20.0 * 25));
    }

    @Test
    void hangChuaBietHanMucThiKhongChiaVaNgayTuongLaiBiKep() {
        var t = LocalDate.of(2026, 11, 21);
        var a = ExportWindowService.plan(Carrier.VIETTEL_POST, t, t.minusDays(100), null, 5.0, 14, 0, 0, 0);
        assertThat(a.ranges()).hasSize(1);
        var future = ExportWindowService.plan(Carrier.JT_EXPRESS, t, t.plusDays(10), null, 1.0, 14, 500, 31, 0);
        assertThat(future.latestOrder()).isEqualTo(t);
        assertThat(future.from()).isEqualTo(t.minusDays(14));
    }

    @Test
    void trangNhapFileVaTongQuanHienMocXuatExcel() throws Exception {
        importJt(line("861000000001", "Giao thành công", "0912345678", today.minusDays(5)));
        jdbc.sql("UPDATE import_batch SET committed_at = now() - interval '10 days'").update();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/imports/new").with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cần xuất Excel từ ngày nào?")))
                .andExpect(content().string(containsString("thời gian tạo đơn")))
                .andExpect(content().string(containsString("Đã quá hạn nhắc")))
                .andExpect(content().string(containsString(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").format(today.minusDays(19)))));
        mvc.perform(get("/").with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Xuất Excel từ ngày")));
    }
}
