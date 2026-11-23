package vn.shop.crm.imports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.PrivacyService;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.shipping.Carrier;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nhập "Lịch sử đối soát" J&T: tiêu đề ở dòng 3 dưới "Mã khách hàng" và "Mã kỳ đối soát", dòng "Tổng" cuối file.
 * Toàn bộ dữ liệu trong test là dữ liệu giả.
 */
class SettlementImportTest extends AbstractPostgresTest {

    private static final String ORDER_HEADER = "Mã vận đơn,Trạng thái vận đơn,SĐT người nhận,Địa chỉ người nhận,"
            + "Tên người nhận,Địa chỉ hành chính,Nội dung hàng hóa,Thời gian tạo đơn,Trọng lượng tính phí,Cước phí,"
            + "Phương thức kết toán,Giao một phần,Tiền thu hộ COD,COD thực thu\n";
    private static final String SETTLEMENT_HEADER = "Mã vận đơn,Mã đơn KH,Tên người nhận,Số điện thoại người nhận,"
            + "Địa chỉ người nhận,Ngày Gửi Hàng,Thời gian Ký Nhận,Tiền COD đã ký nhận,Tiền cước PP_PM,Phí thu hộ COD,"
            + "Phí chuyển hoàn,Phí giao một phần,Điều chỉnh,Số tiền phải trả sau cấn trừ,Thời gian điều chỉnh,Ghi chú\n";

    @Autowired
    ImportService imports;
    @Autowired
    SettlementImportService settlements;
    @Autowired
    CustomerService customers;
    @Autowired
    PrivacyService privacy;

    @BeforeEach
    void statuses() throws Exception {
        for (String file : List.of("V3__jt_status_mapping.sql", "V11__jt_more_statuses.sql")) {
            jdbc.sql(new String(getClass().getResourceAsStream("/db/migration/" + file).readAllBytes(),
                    StandardCharsets.UTF_8)).update();
        }
    }

    private void order(String tracking, String status, String phone) {
        String line = tracking + "," + status + "," + phone + ",\"12 Duong A\",Khach A,\"Phường 1, Quận 5, Hồ Chí Minh\","
                + "Ao,2026-10-20 09:30,1.0,20000,Nguoi gui tra,,300000,300000\n";
        long id = imports.createBatch(Carrier.JT_EXPRESS, "orders.csv", (ORDER_HEADER + line).getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    /** Một dòng vận đơn: mã, mã đơn KH, SĐT, ngày ký nhận, COD, cước, điều chỉnh, thực trả. */
    private static String row(String tracking, String ref, String phone, String signed, String cod, String freight,
                              String adjustment, String net) {
        return tracking + "," + ref + ",Khach A," + phone + ",\"12 Duong A\",2026-10-21 08:00:00," + signed + "," + cod + ","
                + freight + ",0,0,0," + adjustment + "," + net + ",,\n";
    }

    private static String period(String code, String footerCod, String footerNet, String... rows) {
        return "Mã khách hàng,KH0001\nMã kỳ đối soát," + code + "\n" + SETTLEMENT_HEADER + String.join("", rows)
                + "Tổng,,,,,,," + footerCod + ",,,,,," + footerNet + ",,\n";
    }

    private static ImportService.UploadedFile file(String name, String content) {
        return new ImportService.UploadedFile(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private SettlementImportService.Report importFiles(ImportService.UploadedFile... files) {
        return settlements.commit(settlements.createBatch(List.of(files)));
    }

    @Test
    void docDungKyDoiSoatVaChiBoSungMaDonVaNgayKyNhanChoDonGiaoThanhCong() {
        order("861000000001", "Giao thành công", "0912345601");
        order("861000000002", "Kí nhận CPN", "0912345602"); // trạng thái chưa rõ nghĩa
        String content = period("KH0001-20261021-1022", "600000", "560000",
                row("861000000001", "DH-100", "0912345601", "2026-10-22 10:00:00", "300000", "20000", "0", "280000"),
                row("861000000002", "", "0912345602", "2026-10-22 11:00:00", "300000", "20000", "0", "280000"),
                row("861000000009", "", "0912345609", "", "0", "21000", "0", "-21000"));
        // tổng cuối file J&T ghi (560000) khác tổng cộng 3 dòng (539000): cố ý để kiểm cảnh báo
        var preview = settlements.preview(settlements.createBatch(List.of(file("KH0001-20261021-1022.csv", content))));

        assertThat(preview.validRows()).isEqualTo(3);
        assertThat(preview.matchedShipments()).isEqualTo(2);
        assertThat(preview.unknownShipments()).isEqualTo(1);
        assertThat(preview.orderRefToFill()).isEqualTo(1);
        assertThat(preview.deliveredOnToFill()).isEqualTo(1);
        assertThat(preview.signedNotDelivered()).isEqualTo(1);
        assertThat(preview.signedNotDeliveredByStatus()).containsEntry("Kí nhận CPN", 1L);
        assertThat(preview.periods()).hasSize(1);
        var p = preview.periods().getFirst();
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(p.to()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(p.net()).isEqualByComparingTo("539000");
        assertThat(p.footerDiffers()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM settlement_line").query(Long.class).single()).isZero(); // xem trước chưa lưu gì

        var report = importFiles(file("KH0001-20261021-1022.csv", content));

        assertThat(report.customersRecalculated()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT order_ref FROM shipment WHERE tracking_no = '861000000001'")
                .query(String.class).single()).isEqualTo("DH-100");
        assertThat(jdbc.sql("SELECT to_char(delivered_on, 'YYYY-MM-DD HH24:MI:SS') FROM shipment WHERE tracking_no = '861000000001'")
                .query(String.class).single()).isEqualTo("2026-10-22 10:00:00");
        // đơn trạng thái chưa rõ nghĩa: không đổi trạng thái, không điền ngày giao
        assertThat(jdbc.sql("SELECT canonical_status FROM shipment WHERE tracking_no = '861000000002'")
                .query(String.class).single()).isEqualTo("UNKNOWN");
        assertThat(jdbc.sql("SELECT count(*) FROM shipment WHERE tracking_no = '861000000002' AND delivered_on IS NULL")
                .query(Long.class).single()).isEqualTo(1);
        // không tạo đơn hay khách mới từ file đối soát
        assertThat(jdbc.sql("SELECT count(*) FROM shipment").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT sum(net_payable) FROM settlement_line").query(BigDecimal.class).single())
                .isEqualByComparingTo("539000");
    }

    @Test
    void nhapLaiCungKyThayTheKyCuVaNhieuFileMotLan() {
        importFiles(file("a.csv", period("KH0001-20261021-1022", "300000", "280000",
                row("861000000001", "", "0912345601", "2026-10-22 10:00:00", "300000", "20000", "0", "280000"))));
        var again = importFiles(
                file("a.csv", period("KH0001-20261021-1022", "300000", "280000",
                        row("861000000001", "", "0912345601", "2026-10-22 10:00:00", "300000", "20000", "0", "280000"))),
                file("b.csv", period("KH0001-20261023-1025", "100000", "80000",
                        row("861000000002", "", "0912345602", "2026-10-24 10:00:00", "100000", "20000", "0", "80000"))));

        assertThat(again.periods()).hasSize(2);
        assertThat(again.periods().stream().filter(SettlementImportService.PeriodInfo::replacesExisting)).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM settlement_period").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM settlement_line").query(Long.class).single()).isEqualTo(2);
        assertThat(settlements.periods()).extracting(SettlementImportService.PeriodInfo::code)
                .containsExactly("KH0001-20261023-1025", "KH0001-20261021-1022"); // mới nhất trước
        assertThat(settlements.latestPeriodEnd()).contains(LocalDate.of(2026, 10, 25));
        assertThat(settlements.periods().getFirst().footerDiffers()).isFalse();
    }

    @Test
    void kyQuaNamMoiTinhNgayCuoiSangNamSau() {
        assertThat(SettlementImportService.periodRange("KH-20261230-0102"))
                .containsExactly(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2));
        assertThat(SettlementImportService.periodRange("khong-dung-dang")).containsExactly(null, null);
    }

    @Test
    void dongDieuChinhVaGhiChuDuocLuuVaDongSaiCongThucDuocDem() {
        String content = "Mã khách hàng,KH0001\nMã kỳ đối soát,KH0001-20261116-1117\n" + SETTLEMENT_HEADER
                + "861000000001,,Khach A,0912345601,\"12 Duong A\",2026-11-08 08:00:00,,0,0,0,0,0,2160,2160,"
                + "2026-11-16 15:10:17,Cập nhật phí lưu kho\n"
                + "861000000002,,Khach A,0912345602,\"12 Duong A\",2026-11-08 08:00:00,,0,21000,0,0,0,0,-99,,\n"
                + "Tổng,,,,,,,0,,,,,,2061,,\n";
        var report = importFiles(file("x.csv", content));

        assertThat(report.validRows()).isEqualTo(2);
        assertThat(report.netMismatchLines()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT note FROM settlement_line WHERE tracking_no = '861000000001'")
                .query(String.class).single()).isEqualTo("Cập nhật phí lưu kho");
        assertThat(jdbc.sql("SELECT adjustment FROM settlement_line WHERE tracking_no = '861000000001'")
                .query(BigDecimal.class).single()).isEqualByComparingTo("2160");
    }

    @Test
    void khongLuuTenSdtDiaChiKhachVaXoaFileGocSauKhiXacNhan() {
        importFiles(file("a.csv", period("KH0001-20261021-1022", "300000", "280000",
                row("861000000001", "", "0912345601", "2026-10-22 10:00:00", "300000", "20000", "0", "280000"))));

        assertThat(jdbc.sql("SELECT length(file_content) FROM import_batch WHERE kind = 'SETTLEMENT'")
                .query(Integer.class).single()).isZero();
        String everything = jdbc.sql("SELECT row_to_json(l)::text FROM settlement_line l").query(String.class).single()
                + jdbc.sql("SELECT row_to_json(p)::text FROM settlement_period p").query(String.class).single();
        assertThat(everything).doesNotContain("Khach A").doesNotContain("0912345601").doesNotContain("Duong A");
    }

    @Test
    void khachDaYeuCauXoaThiBoQuaDongVaXoaKhachThiXoaLuonDongDoiSoat() {
        order("861000000001", "Giao thành công", "0912345601");
        order("861000000002", "Giao thành công", "0912345602");
        String content = period("KH0001-20261021-1022", "600000", "560000",
                row("861000000001", "", "0912345601", "2026-10-22 10:00:00", "300000", "20000", "0", "280000"),
                row("861000000002", "", "0912345602", "2026-10-22 11:00:00", "300000", "20000", "0", "280000"));
        importFiles(file("a.csv", content));
        assertThat(jdbc.sql("SELECT count(*) FROM settlement_line").query(Long.class).single()).isEqualTo(2);

        long customerId = customers.search(null, false, "0912345601", 1, 10).items().getFirst().id();
        privacy.erase(customerId, LocalDate.of(2026, 11, 22), "test");
        assertThat(jdbc.sql("SELECT tracking_no FROM settlement_line").query(String.class).list())
                .containsExactly("861000000002");

        // nhập lại kỳ đó: dòng của khách đã xóa không quay lại
        var report = importFiles(file("a.csv", content));
        assertThat(report.erasedRows()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT tracking_no FROM settlement_line").query(String.class).list())
                .containsExactly("861000000002");
    }

    @Test
    void khongLanVaoDanhSachNhapDonVaMocCanXuatFile() {
        importFiles(file("a.csv", period("KH0001-20261021-1022", "300000", "280000",
                row("861000000001", "", "0912345601", "2026-10-22 10:00:00", "300000", "20000", "0", "280000"))));

        assertThat(imports.list()).isEmpty();
        assertThat(imports.staleCarriers(1)).isEmpty();
    }

    @Test
    void tuChoiFileKhongPhaiDoiSoatVaFileDoiSoatNhapNhamODonHang() {
        String orders = ORDER_HEADER + "861000000001,Giao thành công,0912345601,\"12 Duong A\",Khach A,,Ao,2026-10-20 09:30,1,2,3,,4,5\n";
        assertThatThrownBy(() -> settlements.createBatch(List.of(file("don.csv", orders))))
                .isInstanceOf(ImportException.class).hasMessageContaining("Mã kỳ đối soát");

        String settlement = period("KH0001-20261021-1022", "300000", "280000",
                row("861000000001", "", "0912345601", "2026-10-22 10:00:00", "300000", "20000", "0", "280000"));
        assertThatThrownBy(() -> imports.createBatch(Carrier.JT_EXPRESS, "doisoat.csv",
                settlement.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImportException.class).hasMessageContaining("Lịch sử đối soát");
    }

    @Test
    void baoLoiRoRangKhiThieuCotBatBuoc() {
        String content = "Mã khách hàng,KH0001\nMã kỳ đối soát,KH0001-20261021-1022\n"
                + "Mã vận đơn,Mã đơn KH,Tiền COD đã ký nhận\n861000000001,,300000\n";
        assertThatThrownBy(() -> settlements.createBatch(List.of(file("x.csv", content))))
                .isInstanceOf(ImportException.class).hasMessageContaining("thiếu cột")
                .hasMessageContaining("Tiền cước PP_PM");
    }

    @Test
    void dongSoTienSaiBiLoaiVaMaVanDonThieuBiLoai() {
        String content = "Mã khách hàng,KH0001\nMã kỳ đối soát,KH0001-20261021-1022\n" + SETTLEMENT_HEADER
                + "861000000001,,Khach A,0912345601,\"12 Duong A\",2026-10-21 08:00:00,,abc,20000,0,0,0,0,1,,\n"
                + ",,Khach A,0912345602,\"12 Duong A\",2026-10-21 08:00:00,,0,0,0,0,0,0,0,,\n"
                + "861000000003,,Khach A,0912345603,\"12 Duong A\",2026-10-21 08:00:00,,\"1.234.567\",0,0,0,0,0,\"1.234.567\",,\n";
        var report = importFiles(file("x.csv", content));

        assertThat(report.totalRows()).isEqualTo(3);
        assertThat(report.validRows()).isEqualTo(1);
        assertThat(report.errorRows()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT cod_collected FROM settlement_line").query(BigDecimal.class).single())
                .isEqualByComparingTo("1234567");
        assertThat(jdbc.sql("SELECT code FROM import_error ORDER BY row_number").query(String.class).list())
                .containsExactly("INVALID_AMOUNT", "MISSING_TRACKING");
    }
}
