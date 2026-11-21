package vn.shop.crm.imports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.customers.Segment;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Nhập J&T: đúng 14 cột như file xuất trên cổng khách hàng, nhiều file một lần, tách tỉnh từ "Địa chỉ hành chính". */
class JtImportTest extends AbstractPostgresTest {

    private static final String HEADER = "Mã vận đơn,Trạng thái vận đơn,SĐT người nhận,Địa chỉ người nhận,Tên người nhận,"
            + "Địa chỉ hành chính,Nội dung hàng hóa,Thời gian tạo đơn,Trọng lượng tính phí,Cước phí,"
            + "Phương thức kết toán,Giao một phần,Tiền thu hộ COD,COD thực thu\n";

    @Autowired
    ImportService imports;
    @Autowired
    CustomerService customers;

    @BeforeEach
    void statuses() throws Exception {
        for (String file : List.of("V3__jt_status_mapping.sql", "V11__jt_more_statuses.sql")) {
            jdbc.sql(new String(getClass().getResourceAsStream("/db/migration/" + file).readAllBytes(),
                    StandardCharsets.UTF_8)).update();
        }
    }

    private static String line(String tracking, String status, String phone, String address, String area) {
        return tracking + "," + status + "," + phone + ",\"" + address + "\",Nguyen Van A,\"" + area
                + "\",Ao khoac,2026-11-01 09:30,1.0,20000,Nguoi gui tra,,300000,300000\n";
    }

    private static ImportService.UploadedFile file(String name, String... lines) {
        return new ImportService.UploadedFile(name,
                (HEADER + String.join("", lines)).getBytes(StandardCharsets.UTF_8));
    }

    private ImportService.Report importFiles(ImportService.UploadedFile... files) {
        long id = imports.createBatch(Carrier.JT_EXPRESS, List.of(files));
        var view = imports.open(id, null);
        return imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    @Test
    void docDungCotCuaFileJtThatVaGhepDiaChiHanhChinhDeTachTinh() {
        long id = imports.createBatch(Carrier.JT_EXPRESS, List.of(file("Data 21-11-2026-09-00-00 1234 ĐƠN HÀNG.xlsx",
                line("861234567890", "Giao thành công", "0912345678", "12 Nguyen Trai", "Phường 1, Quận 5, Hồ Chí Minh"))));
        var view = imports.open(id, null);

        assertThat(view.suggested()).containsEntry(FieldKey.TRACKING_NO, "Mã vận đơn")
                .containsEntry(FieldKey.STATUS, "Trạng thái vận đơn")
                .containsEntry(FieldKey.RECEIVER_PHONE, "SĐT người nhận")
                .containsEntry(FieldKey.RECEIVER_ADDRESS, "Địa chỉ người nhận")
                .containsEntry(FieldKey.ADMIN_AREA, "Địa chỉ hành chính")
                .containsEntry(FieldKey.CREATED_ON, "Thời gian tạo đơn");
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));

        var customer = customers.search(null, false, null, 1, 10).items().getFirst();
        assertThat(customer.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(jdbc.sql("SELECT province FROM customer").query(String.class).single()).isNotNull();
        assertThat(jdbc.sql("SELECT receiver_address FROM shipment").query(String.class).single())
                .isEqualTo("12 Nguyen Trai, Phường 1, Quận 5, Hồ Chí Minh");
    }

    @Test
    void nhieuFileGopTheoMaVanDonFileXuatSauThang() {
        var older = file("Data 20-11-2026-08-00-00 1234 ĐƠN HÀNG.xlsx",
                line("861000000001", "Đang vận chuyển", "0912345678", "1 A", "Phường 1, Quận 1, Hà Nội"),
                line("861000000002", "Đã lấy hàng", "0987654321", "2 B", "Xã X, Huyện Y, Cần Thơ"));
        var newer = file("Data 21-11-2026-08-00-00 1234 ĐƠN HÀNG.xlsx",
                line("861000000001", "Giao thành công", "0912345678", "1 A", "Phường 1, Quận 1, Hà Nội"));

        // thứ tự tải lên đảo ngược: vẫn phải lấy file có dấu thời gian xuất sau
        var report = importFiles(newer, older);

        assertThat(report.validRows()).isEqualTo(2);
        assertThat(report.duplicatesInFile()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT canonical_status FROM shipment WHERE tracking_no = '861000000001'")
                .query(String.class).single()).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("SELECT count(*) FROM shipment").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void nhapLaiCungCacFileKhongLamTangSoDonVaDoiTrangThaiDuocGhiLichSu() {
        importFiles(file("Data 20-11-2026-08-00-00 1.xlsx",
                line("861000000001", "Đang vận chuyển", "0912345678", "1 A", "Phường 1, Quận 1, Hà Nội")));
        var again = importFiles(file("Data 21-11-2026-08-00-00 1.xlsx",
                line("861000000001", "Giao thành công", "0912345678", "1 A", "Phường 1, Quận 1, Hà Nội")));
        var third = importFiles(file("Data 21-11-2026-08-00-00 1.xlsx",
                line("861000000001", "Giao thành công", "0912345678", "1 A", "Phường 1, Quận 1, Hà Nội")));

        assertThat(again.statusChanges()).isEqualTo(1);
        assertThat(third.statusChanges()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM shipment").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM shipment_status_history").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void cacTrangThaiJtMoiGanNghiaDungVaTrangThaiMoHoKhongTinhLaThanhCong() {
        var report = importFiles(file("Data 21-11-2026-08-00-00 1.xlsx",
                line("861000000001", "Đợi lấy hàng", "0912345678", "1 A", "Phường 1, Quận 1, Hà Nội"),
                line("861000000002", "Đang giao hàng", "0912345679", "1 A", "Phường 1, Quận 1, Hà Nội"),
                line("861000000003", "Đang chuyển hoàn", "0912345680", "1 A", "Phường 1, Quận 1, Hà Nội"),
                line("861000000004", "Kết thúc", "0912345681", "1 A", "Phường 1, Quận 1, Hà Nội"),
                line("861000000005", "Kiện vấn đề", "0912345682", "1 A", "Phường 1, Quận 1, Hà Nội")));

        assertThat(report.unmappedStatuses().keySet()).containsExactlyInAnyOrder("Kết thúc", "Kiện vấn đề");
        assertThat(customers.search(null, false, null, 1, 10).items())
                .allSatisfy(c -> assertThat(c.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE));
    }

    @Test
    void fileKhacCotTieuDeBiTuChoiVaNeuTenFile() {
        var other = new ImportService.UploadedFile("khac.csv",
                "Mã vận đơn,Trạng thái\nX1,Giao thành công\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> imports.createBatch(Carrier.JT_EXPRESS, List.of(
                file("Data 21-11-2026-08-00-00 1.xlsx",
                        line("861000000001", "Giao thành công", "0912345678", "1 A", "Phường 1, Quận 1, Hà Nội")),
                other)))
                .isInstanceOf(ImportException.class).hasMessageContaining("khac.csv");
    }

    @Test
    void thoiDiemXuatLayTuTenFileJt() {
        assertThat(ImportService.exportStamp("Data 21-11-2026-09-30-15 12345 ĐƠN HÀNG.xlsx"))
                .isEqualTo(LocalDateTime.of(2026, 11, 21, 9, 30, 15));
        assertThat(ImportService.exportStamp("don.csv")).isNull();
        assertThat(ImportService.exportStamp("Data 99-99-2026-09-30-15 x.xlsx")).isNull();
    }

    @Test
    void ghepDiaChiKhongLapVaGiuPhanHanhChinhKhiQuaDai() {
        assertThat(ImportService.joinAddress("12 A", "Xã B, Huyện C, Nghệ An")).isEqualTo("12 A, Xã B, Huyện C, Nghệ An");
        assertThat(ImportService.joinAddress("12 A, Xã B, Huyện C, Nghệ An", "Xã B, Huyện C, Nghệ An"))
                .isEqualTo("12 A, Xã B, Huyện C, Nghệ An");
        assertThat(ImportService.joinAddress("", "Xã B, Nghệ An")).isEqualTo("Xã B, Nghệ An");
        assertThat(ImportService.joinAddress("12 A", "")).isEqualTo("12 A");
        String longAddress = "x".repeat(600);
        String joined = ImportService.joinAddress(longAddress, "Xã B, Nghệ An");
        assertThat(joined).hasSizeLessThanOrEqualTo(500).endsWith("Xã B, Nghệ An");
    }

    @Test
    void ganQuaNhieuDongBiTuChoiBangThongBaoTiengViet() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            many.append(line("86100000" + (1000 + i), "Giao thành công", "0912345678", "1 A", "Hà Nội"));
        }
        // dùng ImportService với giới hạn nhỏ để kiểm tra mà không cần tạo hàng chục nghìn dòng
        var small = new ImportService(jdbc, null, null, 2, 104857600L);
        assertThatThrownBy(() -> small.createBatch(Carrier.JT_EXPRESS, List.of(
                file("Data 20-11-2026-08-00-00 1.xlsx", many.toString()),
                file("Data 21-11-2026-08-00-00 1.xlsx", many.toString()))))
                .isInstanceOf(ImportException.class).hasMessageContaining("quá nhiều dòng");
    }
}
