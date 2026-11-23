package vn.shop.crm.imports;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.customers.CustomerClassifier.ReviewReason;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.customers.CustomerService.CustomerRow;
import vn.shop.crm.customers.Segment;
import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.shipping.StatusMappingService;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tiêu chí nghiệm thu 1–5 của mốc 1A (file 01, mục 12), chạy trên Postgres thật. */
class ImportAcceptanceTest extends AbstractPostgresTest {

    private static final String VTP_HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";
    private static final String JT_HEADER =
            "Mã vận đơn,Mã đơn hàng,Thời gian tạo,Người nhận,Số điện thoại người nhận,Địa chỉ nhận,Trạng thái\n";

    @Autowired
    ImportService imports;
    @Autowired
    CustomerService customers;
    @Autowired
    StatusMappingService statuses;

    // ---------- tiện ích ----------

    private ImportService.Report importCsv(Carrier carrier, String csv) {
        long id = imports.createBatch(carrier, "test.csv", csv.getBytes(StandardCharsets.UTF_8));
        ImportService.FileView view = imports.open(id, null);
        return imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    private static String vtp(String... rows) {
        return VTP_HEADER + String.join("\n", rows) + "\n";
    }

    private static String jt(String... rows) {
        return JT_HEADER + String.join("\n", rows) + "\n";
    }

    private List<CustomerRow> all() {
        return customers.search(null, false, null, 1, 200).items();
    }

    private CustomerRow only() {
        assertThat(all()).hasSize(1);
        return all().getFirst();
    }

    private long shipmentCount() {
        return jdbc.sql("SELECT count(*) FROM shipment").query(Long.class).single();
    }

    @Test
    void shopHuyLayLaTrangThaiDaBietVaKhongTinhLaMuaHang() {
        var report = importCsv(Carrier.VIETTEL_POST,
                vtp("VTP9,DH9,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Shop hủy lấy"));

        assertThat(report.unmappedStatuses()).isEmpty();
        assertThat(only().segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
    }

    @Test
    void trangThaiJtThatDuocGanNghiaDungVaDaLayHangKhongTinhLaMuaThanhCong() throws Exception {
        // cleanDatabase xóa ánh xạ J&T; nạp lại đúng file migration V3 để kiểm tra chính dữ liệu gán nghĩa đó
        jdbc.sql(new String(getClass().getResourceAsStream("/db/migration/V3__jt_status_mapping.sql").readAllBytes(),
                StandardCharsets.UTF_8)).update();

        var report = importCsv(Carrier.JT_EXPRESS, jt(
                "JT1,,2026-06-30 16:47,Khach A,0912345678,Dia chi,Đã lấy hàng",
                "JT2,,2026-07-01 10:00,Khach B,0987654321,Dia chi,Giao thành công",
                "JT3,,2026-07-02 10:00,Khach C,0911111111,Dia chi,Ký nhận chuyển hoàn",
                "JT4,,2026-07-03 10:00,Khach D,0922222222,Dia chi,Kiện vấn đề"));

        assertThat(report.errorRows()).isZero();
        // chỉ "Kiện vấn đề" chưa rõ nghĩa; định dạng ngày "yyyy-MM-dd HH:mm" của J&T đọc được
        assertThat(report.unmappedStatuses()).containsOnlyKeys("Kiện vấn đề");
        assertThat(all()).hasSize(4);
        long success = all().stream().filter(c -> c.successfulPurchases() > 0).count();
        assertThat(success).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM shipment WHERE created_on IS NOT NULL")
                .query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void oSdtCoHaiSoLayDuocSoDauTien() {
        var report = importCsv(Carrier.VIETTEL_POST,
                vtp("VTP8,DH8,05/01/2026 09:00:00,Nguyen Van A,\"0912345678, 0987654321\",12 Duong A,Giao thành công"));

        assertThat(report.errorRows()).isZero();
        assertThat(only().phone()).isEqualTo("+84912345678");
    }

    // ---------- tiêu chí 1 ----------

    @Test
    void nhapCungMotFileHaiLanKhongLamTangSoLanMua() {
        String file = vtp("VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công");

        var first = importCsv(Carrier.VIETTEL_POST, file);
        var second = importCsv(Carrier.VIETTEL_POST, file);

        assertThat(first.newShipments()).isEqualTo(1);
        assertThat(second.newShipments()).isZero();
        assertThat(second.existingShipments()).isEqualTo(1);
        assertThat(shipmentCount()).isEqualTo(1);
        assertThat(only().successfulPurchases()).isEqualTo(1);
        assertThat(only().segment()).isEqualTo(Segment.ONE_TIME);
    }

    // ---------- tiêu chí 2 ----------

    @Test
    void motDonThanhCongOMoiHangCungSdtLaRepeat() {
        importCsv(Carrier.VIETTEL_POST,
                vtp("VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công"));
        statuses.assign(Carrier.JT_EXPRESS, "Đã ký nhận", CanonicalStatus.DELIVERED);
        importCsv(Carrier.JT_EXPRESS,
                jt("JT1,DH2,20/01/2026 10:00:00,Nguyen Van A,+84912345678,12 Duong A,Đã ký nhận"));

        CustomerRow customer = only();
        assertThat(customer.phone()).isEqualTo("+84912345678");
        assertThat(customer.segment()).isEqualTo(Segment.REPEAT);
        assertThat(customer.successfulPurchases()).isEqualTo(2);
        assertThat(customer.needsReview()).isFalse();
    }

    // ---------- tiêu chí 3 ----------

    @Test
    void motDonGiaoThanhCongVaHaiDonHuyHoanLaOneTime() {
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công",
                "VTP2,DH2,06/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Đã hủy",
                "VTP3,DH3,07/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Đã duyệt hoàn"));

        assertThat(only().segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(only().successfulPurchases()).isEqualTo(1);
    }

    // ---------- tiêu chí 4 ----------

    @Test
    void trangThaiChuaXacDinhKhongDuocTuCoiLaThanhCong() {
        var report = importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Chờ xử lý"));

        assertThat(report.unmappedStatuses()).containsKey("Chờ xử lý");
        CustomerRow customer = only();
        assertThat(customer.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        assertThat(customer.reviewReasons()).contains(ReviewReason.UNKNOWN_STATUS.name());
        assertThat(statuses.unmapped()).hasSize(1);

        // chủ cửa hàng chọn nghĩa -> khách được tính lại ngay
        statuses.assign(Carrier.VIETTEL_POST, "Chờ xử lý", CanonicalStatus.DELIVERED);
        assertThat(only().segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(statuses.unmapped()).isEmpty();
    }

    // ---------- tiêu chí 5 ----------

    @Test
    void xemDuocTungDonTaoRaKetQuaPhanLoai() {
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công",
                "VTP2,DH2,06/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Đã hủy"));

        var detail = customers.detail(only().id());

        assertThat(detail.classification().purchases()).hasSize(2);
        assertThat(detail.classification().purchases().stream().filter(p -> p.successful())).hasSize(1);
        assertThat(detail.shipments()).hasSize(2);
        assertThat(customers.search(Segment.ONE_TIME, false, null, 1, 50).total()).isEqualTo(1);
        assertThat(customers.search(Segment.REPEAT, false, null, 1, 50).total()).isZero();
    }

    // ---------- quy tắc nhập khác trong file 01 mục 4 ----------

    @Test
    void cungMaDonBanHangNhieuKienLaMotLanMua() {
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH10,05/01/2026 09:00:00,Do Van K,0944555666,44 Duong K,Giao thành công",
                "VTP2,DH10,05/01/2026 09:00:00,Do Van K,0944555666,44 Duong K,Giao thành công"));

        assertThat(only().segment()).isEqualTo(Segment.ONE_TIME);
    }

    @Test
    void khongCoMaDonGocThiGanCoCanKiemTraKhongTuGop() {
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,,05/01/2026 09:00:00,Do Van K,0944555666,44 Duong K,Giao thành công",
                "VTP2,,05/01/2026 09:00:00,Do Van K,0944555666,44 Duong K,Giao thành công"));

        CustomerRow customer = only();
        assertThat(customer.segment()).isEqualTo(Segment.REPEAT);
        assertThat(customer.needsReview()).isTrue();
        assertThat(customer.reviewReasons()).contains(ReviewReason.REPEAT_WITHOUT_ORDER_REF.name());
        assertThat(customers.search(null, true, null, 1, 50).total()).isEqualTo(1);
    }

    @Test
    void dongThieuHoacSaiDuLieuVaoDanhSachLoiVaKhongVaoHeThong() {
        var report = importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,A,0912345678,Dia chi,Giao thành công",
                "VTP2,DH2,05/01/2026 09:00:00,B,,Dia chi,Giao thành công",
                "VTP3,DH3,05/01/2026 09:00:00,C,0912****78,Dia chi,Giao thành công",
                "VTP4,DH4,05/01/2026 09:00:00,D,0933444555,Dia chi,",
                ",DH5,05/01/2026 09:00:00,E,0933444556,Dia chi,Giao thành công"));

        assertThat(report.totalRows()).isEqualTo(5);
        assertThat(report.validRows()).isEqualTo(1);
        assertThat(report.errorRows()).isEqualTo(4);
        assertThat(report.sampleErrors()).extracting(ImportService.RowError::code)
                .containsExactlyInAnyOrder("MISSING_PHONE", "INVALID_PHONE", "MISSING_STATUS", "MISSING_TRACKING");
        assertThat(shipmentCount()).isEqualTo(1);
        assertThat(all()).hasSize(1);
        long stored = jdbc.sql("SELECT count(*) FROM import_error").query(Long.class).single();
        assertThat(stored).isEqualTo(4);
    }

    @Test
    void cacDinhDangSdtKhacNhauGopVeMotKhach() {
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công",
                "VTP2,DH2,06/01/2026 09:00:00,Nguyen Van A,912345678,12 Duong A,Giao thành công",
                "VTP3,DH3,07/01/2026 09:00:00,Nguyen Van A,+84 912 345 678,12 Duong A,Giao thành công"));

        CustomerRow customer = only();
        assertThat(customer.phone()).isEqualTo("+84912345678");
        assertThat(customer.successfulPurchases()).isEqualTo(3);
    }

    @Test
    void giaoRoiHoanSauDoCapNhatLaiVaGhiLichSu() {
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công"));
        assertThat(only().segment()).isEqualTo(Segment.ONE_TIME);

        var second = importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Đang chuyển hoàn"));

        assertThat(second.statusChanges()).isEqualTo(1);
        assertThat(only().segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        long history = jdbc.sql("SELECT count(*) FROM shipment_status_history").query(Long.class).single();
        assertThat(history).isEqualTo(2);
    }

    @Test
    void doiSdtCuaMotVanDonTinhLaiCaKhachCuLanKhachMoi() {
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,A,0912345678,Dia chi,Giao thành công"));
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,A,0987654321,Dia chi,Giao thành công"));

        // Quy tắc 5a: không tự xóa số điện thoại. Khách cũ còn nguyên bản ghi nhưng không còn tính là đã mua.
        assertThat(all()).extracting(CustomerRow::phone).containsExactlyInAnyOrder("+84912345678", "+84987654321");
        assertThat(all().stream().filter(c -> c.phone().equals("+84912345678")).findFirst().orElseThrow().segment())
                .isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        assertThat(all().stream().filter(c -> c.phone().equals("+84987654321")).findFirst().orElseThrow().successfulPurchases()).isEqualTo(1);
    }

    @Test
    void xemTruocKhongGhiGiVaoCoSoDuLieu() {
        String csv = vtp("VTP1,DH1,05/01/2026 09:00:00,A,0912345678,Dia chi,Giao thành công");
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);

        var report = imports.preview(id, new ImportService.Mapping(view.headerRow(), view.suggested()));

        assertThat(report.newShipments()).isEqualTo(1);
        assertThat(shipmentCount()).isZero();
        assertThat(all()).isEmpty();
        assertThat(imports.summary(id).committed()).isFalse();
    }

    @Test
    void xacNhanHaiLanCungMotLoBiTuChoi() {
        String csv = vtp("VTP1,DH1,05/01/2026 09:00:00,A,0912345678,Dia chi,Giao thành công");
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var mapping = new ImportService.Mapping(1, imports.open(id, null).suggested());
        imports.commit(id, mapping);

        assertThatThrownBy(() -> imports.commit(id, mapping)).isInstanceOf(ImportException.class);
    }

    @Test
    void thieuCotBatBuocBaoLoiRoRang() {
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv",
                "Cột lạ,Cột khác\na,b\n".getBytes(StandardCharsets.UTF_8));
        var mapping = new ImportService.Mapping(1, imports.open(id, null).suggested());

        assertThatThrownBy(() -> imports.commit(id, mapping))
                .isInstanceOf(ImportException.class).hasMessageContaining("Chưa chọn cột");
    }

    @Test
    void dongTrungMaVanDonTrongFileGiuDongCuoi() {
        var report = importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,A,0912345678,Dia chi,Đang giao",
                "VTP1,DH1,05/01/2026 09:00:00,A,0912345678,Dia chi,Giao thành công"));

        assertThat(report.duplicatesInFile()).isEqualTo(1);
        assertThat(shipmentCount()).isEqualTo(1);
        assertThat(only().segment()).isEqualTo(Segment.ONE_TIME);
    }

    @Test
    void mappingCotDuocNhoChoLanSau() {
        importCsv(Carrier.VIETTEL_POST, vtp(
                "VTP1,DH1,05/01/2026 09:00:00,A,0912345678,Dia chi,Giao thành công"));
        long second = imports.createBatch(Carrier.VIETTEL_POST, "t2.csv", vtp(
                "VTP2,DH2,05/01/2026 09:00:00,B,0933444555,Dia chi,Giao thành công")
                .getBytes(StandardCharsets.UTF_8));

        var view = imports.open(second, null);

        assertThat(view.suggested().get(FieldKey.RECEIVER_PHONE)).isEqualTo("SĐT người nhận");
        assertThat(view.suggested().get(FieldKey.TRACKING_NO)).isEqualTo("Mã vận đơn");
    }
}
