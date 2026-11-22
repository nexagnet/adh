package vn.shop.crm.imports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Màn nhập file: cảnh báo file J&T đúng 500 dòng và số khách đổi nhóm sau khi nhập. */
class ImportExtrasTest extends AbstractPostgresTest {

    private static final String HEADER = "Mã vận đơn,Trạng thái vận đơn,SĐT người nhận,Địa chỉ người nhận,Tên người nhận,"
            + "Địa chỉ hành chính,Nội dung hàng hóa,Thời gian tạo đơn,Trọng lượng tính phí,Cước phí,"
            + "Phương thức kết toán,Giao một phần,Tiền thu hộ COD,COD thực thu\n";

    @Autowired
    ImportService imports;

    @BeforeEach
    void statuses() throws Exception {
        for (String file : List.of("V3__jt_status_mapping.sql", "V11__jt_more_statuses.sql")) {
            jdbc.sql(new String(getClass().getResourceAsStream("/db/migration/" + file).readAllBytes(),
                    StandardCharsets.UTF_8)).update();
        }
    }

    private static String line(String tracking, String phone, String created) {
        return tracking + ",Giao thành công," + phone + ",\"12 Nguyen Trai\",Nguyen Van A,\"Phường 1, Quận 5, Hồ Chí Minh\","
                + "Ao khoac," + created + ",1.0,20000,Nguoi gui tra,,300000,300000\n";
    }

    private ImportService.Mapping mapping(long id) {
        var view = imports.open(id, null);
        return new ImportService.Mapping(view.headerRow(), view.suggested());
    }

    private long batch(String... lines) {
        return imports.createBatch(Carrier.JT_EXPRESS, List.of(new ImportService.UploadedFile("a.csv",
                (HEADER + String.join("", lines)).getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void fileJtDungNamTramDongBiCanhBaoCon499ThiKhong() {
        String[] rows = new String[500];
        for (int i = 0; i < 500; i++) {
            rows[i] = line(String.valueOf(861000000000L + i), String.format("09%08d", 10000000 + i), "2026-11-01 09:30");
        }
        long full = batch(rows);
        ImportService.Report report = imports.preview(full, mapping(full));
        assertThat(report.totalRows()).isEqualTo(500);
        assertThat(report.fileLimit()).isEqualTo(500);
        assertThat(report.atFileLimit()).isTrue();

        long almost = batch(java.util.Arrays.copyOf(rows, 499));
        assertThat(imports.preview(almost, mapping(almost)).atFileLimit()).isFalse();
    }

    @Test
    void khongCanhBaoChoHangKhongBietHanMuc() {
        var report = new ImportService.Report(500, 500, 0, 0, 500, 0, 0, 0, java.util.Map.of(), List.of(), 0);
        assertThat(report.atFileLimit()).isFalse();
    }

    @Test
    void demKhachDoiNhomMua1LanThanhTu2LanVaLuuVaoLoNhap() {
        long first = batch(line("861000000001", "0912345678", "2026-10-01 09:30"));
        imports.commit(first, mapping(first));
        assertThat(imports.summary(first).segmentChanges()).isZero();   // khách mới: chưa có "nhóm cũ" để so sánh

        long second = batch(line("861000000002", "0912345678", "2026-11-01 09:30"),
                line("861000000003", "0987654321", "2026-11-01 09:30"));
        imports.commit(second, mapping(second));
        assertThat(imports.summary(second).segmentChanges()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM customer WHERE segment = 'REPEAT'").query(Long.class).single())
                .isEqualTo(1L);
    }
}
