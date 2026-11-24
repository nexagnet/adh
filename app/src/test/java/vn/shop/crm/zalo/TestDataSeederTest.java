package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.safety.TestDataSeeder;
import vn.shop.crm.safety.TestMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dựng khách thử từ số thử đã khai báo (chế độ thử): dữ liệu giả có dấu hiệu rõ, không đụng khách sẵn có, không chạy khi chạy thật. */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = "app.zalo.safety.live=false")
class TestDataSeederTest extends AbstractPostgresTest {

    @Autowired TestDataSeeder seeder;
    @Autowired TestMode mode;

    @Test
    void dungKhachThuTuSoThuDaKhaiBaoVaDanhDauRo() {
        mode.add("0900000011", "thử 1", "test");
        mode.add("0900000012", "thử 2", "test");
        var r = seeder.seed();
        assertThat(r.created()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM customer WHERE phone IN ('+84900000011', '+84900000012')").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM shipment WHERE tracking_no LIKE 'JTTEST%' AND carrier = 'JT_EXPRESS'").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(DISTINCT c.province) FROM customer c WHERE c.phone IN ('+84900000011', '+84900000012')").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT province FROM customer WHERE phone = '+84900000011'").query(String.class).single()).isNotBlank();
        // trạng thái phải là chữ bảng nhập đơn J&T hiểu sẵn (V3: 'giao thanh cong' = DELIVERED), không phải "Đã ký nhận" làm đơn rơi vào
        // "cần kiểm tra" và chiến dịch không mời được (bảng ánh xạ J&T bị xóa trong test nên kiểm theo khóa trạng thái)
        assertThat(jdbc.sql("SELECT count(*) FROM shipment WHERE status_key = 'giao thanh cong'").query(Long.class).single()).isEqualTo(2);
        // chạy lại: số đã là khách thì bỏ qua, không tạo thêm đơn
        var again = seeder.seed();
        assertThat(again.created()).isZero();
        assertThat(again.skipped()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM shipment").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void khongCoSoThuThiKhongDungGi() {
        assertThat(seeder.seed().created()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isZero();
    }
}
