package vn.shop.crm.geo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.customers.CustomerService;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy lại đúng file migration V13 trên dữ liệu "như đã nhập sai" (tỉnh gửi bị nối vào cuối địa chỉ nhận) và kiểm tra:
 * đuôi sai được gỡ, địa chỉ đúng và hãng khác không bị đụng, khu vực tính lại đúng, ánh xạ cột sai bị bỏ.
 */
class VtpSenderProvinceMigrationTest extends AbstractPostgresTest {

    @Autowired
    DataSource dataSource;
    @Autowired
    CustomerService customers;

    private void shipment(long batch, String carrier, String tracking, String phone, String address, String senderJson) {
        jdbc.sql("""
                INSERT INTO shipment (carrier, tracking_no, receiver_phone_raw, receiver_phone, receiver_address,
                                      raw_status, status_key, canonical_status, raw_payload, first_batch_id, last_batch_id)
                VALUES (:c, :t, :p, :p, :a, 'Giao thành công', 'giao thanh cong', 'DELIVERED', CAST(:j AS jsonb), :b, :b)""")
                .param("c", carrier).param("t", tracking).param("p", phone).param("a", address).param("j", senderJson)
                .param("b", batch).update();
    }

    private void customer(String phone, String address) {
        jdbc.sql("""
                INSERT INTO customer (phone, address, segment, successful_purchases, province, geo_version)
                VALUES (:p, :a, 'ONE_TIME', 1, 'vinh-phuc', 2)""").param("p", phone).param("a", address).update();
    }

    private String shipmentAddress(String tracking) {
        return jdbc.sql("SELECT receiver_address FROM shipment WHERE tracking_no = :t").param("t", tracking)
                .query(String.class).single();
    }

    private String customerAddress(String phone) {
        return jdbc.sql("SELECT address FROM customer WHERE phone = :p").param("p", phone).query(String.class).single();
    }

    private String customerProvince(String phone) {
        return jdbc.sql("SELECT province FROM customer WHERE phone = :p").param("p", phone).query(String.class).optional()
                .orElse(null);
    }

    @Test
    void goDuoiTinhGuiVaTinhLaiKhuVuc() throws Exception {
        long batch = jdbc.sql("""
                INSERT INTO import_batch (carrier, filename, sha256, file_content) VALUES ('VIETTEL_POST','x','x','\\x00')
                RETURNING id""").query(Long.class).single();
        String vp = "{\"Tỉnh/Thành phố gửi\": \"Vĩnh Phúc\"}";

        // 1. địa chỉ nhận có số nhà + tỉnh thật, bị nối thêm ", Vĩnh Phúc" ở cuối
        shipment(batch, "VIETTEL_POST", "A1", "+84911111111", "12 Lê Lợi, Cần Thơ, Vĩnh Phúc", vp);
        customer("+84911111111", "12 Lê Lợi, Cần Thơ, Vĩnh Phúc");
        // 2. địa chỉ nhận chỉ có số nhà, tỉnh nhận không rõ: sau khi gỡ không còn tỉnh nào
        shipment(batch, "VIETTEL_POST", "A2", "+84922222222", "Số 5 ngõ 3, Vĩnh Phúc", vp);
        customer("+84922222222", "Số 5 ngõ 3, Vĩnh Phúc");
        // 3. địa chỉ nhận vốn đã là Vĩnh Phúc nhưng đuôi không đúng dạng ", <tỉnh gửi>": giữ nguyên
        shipment(batch, "VIETTEL_POST", "A3", "+84933333333", "Vĩnh Phúc", vp);
        customer("+84933333333", "Vĩnh Phúc");
        // 4. hãng khác có cùng khóa trong dữ liệu gốc: không đụng
        shipment(batch, "JT_EXPRESS", "B1", "+84944444444", "9 Hai Bà Trưng, Vĩnh Phúc", vp);
        customer("+84944444444", "9 Hai Bà Trưng, Vĩnh Phúc");
        // 5. Viettel Post không có cột tỉnh gửi trong dữ liệu gốc: không đụng
        shipment(batch, "VIETTEL_POST", "A5", "+84955555555", "3 Trần Phú, Vĩnh Phúc", "{}");
        customer("+84955555555", "3 Trần Phú, Vĩnh Phúc");

        jdbc.sql("INSERT INTO column_mapping (carrier, field, header) VALUES ('VIETTEL_POST','ADMIN_AREA','Tỉnh/Thành phố gửi')")
                .update();
        jdbc.sql("INSERT INTO column_mapping (carrier, field, header) VALUES ('VIETTEL_POST','RECEIVER_NAME','Người nhận')")
                .update();
        jdbc.sql("INSERT INTO column_mapping (carrier, field, header) VALUES ('JT_EXPRESS','ADMIN_AREA','Tỉnh/Thành phố')")
                .update();

        String script = new ClassPathResource("db/migration/V13__fix_vtp_sender_province.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(script);
        }

        assertThat(shipmentAddress("A1")).isEqualTo("12 Lê Lợi, Cần Thơ");
        assertThat(shipmentAddress("A2")).isEqualTo("Số 5 ngõ 3");
        assertThat(shipmentAddress("A3")).isEqualTo("Vĩnh Phúc");
        assertThat(shipmentAddress("B1")).isEqualTo("9 Hai Bà Trưng, Vĩnh Phúc");
        assertThat(shipmentAddress("A5")).isEqualTo("3 Trần Phú, Vĩnh Phúc");
        assertThat(customerAddress("+84911111111")).isEqualTo("12 Lê Lợi, Cần Thơ");
        assertThat(customerAddress("+84922222222")).isEqualTo("Số 5 ngõ 3");
        assertThat(customerAddress("+84944444444")).isEqualTo("9 Hai Bà Trưng, Vĩnh Phúc");

        // ánh xạ cột sai bị bỏ; ánh xạ khác giữ nguyên
        assertThat(jdbc.sql("SELECT carrier || ':' || field FROM column_mapping ORDER BY 1").query(String.class).list())
                .containsExactly("JT_EXPRESS:ADMIN_AREA", "VIETTEL_POST:RECEIVER_NAME");

        // khởi động lại: khách có geo_version 2 < 3 nên được tính lại từ địa chỉ đã sửa
        assertThat(customers.refreshProvinces(false)).isEqualTo(5);
        assertThat(customerProvince("+84911111111")).isEqualTo("can-tho");
        assertThat(customerProvince("+84922222222")).isNull();
        assertThat(customerProvince("+84944444444")).isEqualTo("vinh-phuc");
        assertThat(customers.refreshProvinces(false)).isZero();
    }
}
