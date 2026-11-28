package vn.shop.crm.customers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.customers.CustomerClassifier.ReviewReason;
import vn.shop.crm.shipping.StaleOrders;
import vn.shop.crm.shipping.StatusMappingService;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Mua gần nhất" = ngày đơn giao thành công gần nhất; đơn "đã lấy hàng" kẹt là chưa xác định; tính lại khi khởi động. */
class StaleOrderAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    CustomerService customers;
    @Autowired
    StatusMappingService statuses;
    @Autowired
    CustomerRefresh refresh;
    @Autowired
    Clock clock;

    @Autowired
    WebApplicationContext context;

    private long batch;

    private LocalDateTime ago(int days) {
        return LocalDateTime.now(clock).minusDays(days);
    }

    private void shipment(String tracking, String phone, String status, LocalDateTime createdOn) {
        if (batch == 0) {
            batch = jdbc.sql("""
                    INSERT INTO import_batch (carrier, filename, sha256, file_content) VALUES ('JT_EXPRESS','x','x','\\x00')
                    RETURNING id""").query(Long.class).single();
        }
        jdbc.sql("""
                INSERT INTO shipment (carrier, tracking_no, order_ref, receiver_phone_raw, receiver_phone, raw_status,
                                      status_key, canonical_status, created_on, raw_payload, first_batch_id, last_batch_id)
                VALUES ('JT_EXPRESS', :t, :t, :p, :p, :s, :s, :s, :c, '{}'::jsonb, :b, :b)""")
                .param("t", tracking).param("p", phone).param("s", status).param("c", Timestamp.valueOf(createdOn))
                .param("b", batch).update();
    }

    private CustomerService.CustomerRow customer(String phone) {
        return customers.search(null, false, null, 1, 50).items().stream()
                .filter(c -> c.phone().equals(phone)).findFirst().orElseThrow();
    }

    @Test
    void muaGanNhatLaDonGiaoThanhCongGanNhatKhongPhaiDonHuyMoiNhat() {
        shipment("A1", "+84911111111", "DELIVERED", ago(90));
        shipment("A2", "+84911111111", "DELIVERED", ago(60));
        shipment("A3", "+84911111111", "CANCELLED", ago(5));
        shipment("A4", "+84911111111", "RETURNED", ago(2));
        shipment("B1", "+84922222222", "CANCELLED", ago(3));
        customers.recalculateAll();

        var a = customer("+84911111111");
        assertThat(a.lastOrderOn().toLocalDate()).isEqualTo(ago(60).toLocalDate());
        assertThat(a.successfulPurchases()).isEqualTo(2);
        assertThat(customer("+84922222222").lastOrderOn()).isNull();
    }

    @Test
    void donDaLayHangQuaCuLaChuaXacDinhVaKhachCanKiemTra() {
        shipment("K1", "+84933333333", "IN_TRANSIT", ago(StaleOrders.AFTER_DAYS + 40)); // kẹt từ lâu
        shipment("K2", "+84944444444", "IN_TRANSIT", ago(5));                            // mới, đang giao thật
        shipment("K3", "+84955555555", "DELIVERED", ago(100));
        shipment("K4", "+84955555555", "IN_TRANSIT", ago(StaleOrders.AFTER_DAYS + 1));   // đã giao 1 + kẹt 1
        customers.recalculateAll();

        var stuck = customer("+84933333333");
        assertThat(stuck.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        assertThat(stuck.successfulPurchases()).isZero();
        assertThat(stuck.needsReview()).isTrue();
        assertThat(stuck.reviewReasons()).contains(ReviewReason.STALE_OPEN_ORDER.name());
        assertThat(stuck.lastOrderOn()).isNull();

        assertThat(customer("+84944444444").needsReview()).isFalse();

        var mixed = customer("+84955555555");
        assertThat(mixed.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(mixed.needsReview()).isTrue();

        // trang trạng thái đơn: gom các đơn kẹt, đơn mới không bị tính vào
        List<StatusMappingService.StuckRow> rows = statuses.stuck();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().shipments()).isEqualTo(2);
        assertThat(rows.getFirst().oldest()).isEqualTo(ago(StaleOrders.AFTER_DAYS + 40).toLocalDate());
    }

    /** Thời gian trôi: đơn 29 ngày chưa kẹt, vài ngày sau thì kẹt dù không có file mới; khởi động/hằng ngày tự tính lại. */
    @Test
    void tinhLaiKhiKhoiDongBatDonVuaQuaNguong() {
        shipment("T1", "+84966666666", "IN_TRANSIT", ago(StaleOrders.AFTER_DAYS - 1));
        customers.recalculateAll();
        assertThat(customer("+84966666666").needsReview()).isFalse();

        // giả lập 3 ngày trôi qua: dời ngày tạo đơn về trước 3 ngày
        jdbc.sql("UPDATE shipment SET created_on = created_on - interval '3 days' WHERE tracking_no = 'T1'").update();
        refresh.refresh();
        assertThat(customer("+84966666666").needsReview()).isTrue();

        // đơn được cập nhật thành đã giao (nhập file mới) => hết kẹt
        jdbc.sql("UPDATE shipment SET canonical_status = 'DELIVERED' WHERE tracking_no = 'T1'").update();
        refresh.refresh();
        var c = customer("+84966666666");
        assertThat(c.needsReview()).isFalse();
        assertThat(c.lastOrderOn().toLocalDate()).isEqualTo(ago(StaleOrders.AFTER_DAYS + 2).toLocalDate());
    }

    @Test
    void khachConODuoiPhienBanCuDuocTinhLaiKhiKhoiDong() {
        shipment("V1", "+84977777777", "DELIVERED", ago(80));
        shipment("V2", "+84977777777", "CANCELLED", ago(1));
        // khách như đã tính theo cách cũ: "mua gần nhất" lấy theo đơn hủy mới nhất, calc_version = 0
        jdbc.sql("""
                INSERT INTO customer (phone, segment, successful_purchases, last_order_on, calc_version)
                VALUES ('+84977777777', 'ONE_TIME', 1, :t, 0)""")
                .param("t", Timestamp.valueOf(ago(1))).update();

        refresh.refresh();

        assertThat(customer("+84977777777").lastOrderOn().toLocalDate()).isEqualTo(ago(80).toLocalDate());
        int calc = jdbc.sql("SELECT calc_version FROM customer WHERE phone = '+84977777777'").query(Integer.class).single();
        assertThat(calc).isEqualTo(CustomerService.CALC_VERSION);
        // lần sau không còn gì để tính lại
        assertThat(customers.refreshStale()).isZero();
    }

    /** Chủ shop duyệt 28/11/2026: chỉ thiếu mã đơn thì hết "cần kiểm tra"; dòng cũ còn cờ được gỡ khi khởi động, số lần mua giữ nguyên. */
    @Test
    void khachConCoThieuMaDonCuDuocGoCoKhiKhoiDong() {
        shipment("N1", "+84955555555", "DELIVERED", ago(80));
        shipment("N2", "+84955555555", "DELIVERED", ago(40));
        jdbc.sql("UPDATE shipment SET order_ref = NULL WHERE receiver_phone = '+84955555555'").update();
        jdbc.sql("""
                INSERT INTO customer (phone, segment, successful_purchases, last_order_on, needs_review, review_reasons, calc_version)
                VALUES ('+84955555555', 'REPEAT', 2, :t, TRUE, 'REPEAT_WITHOUT_ORDER_REF', 3)""")
                .param("t", Timestamp.valueOf(ago(40))).update();

        refresh.refresh();

        var c = customer("+84955555555");
        assertThat(c.needsReview()).isFalse();
        assertThat(c.reviewReasons()).isEmpty();
        assertThat(c.segment()).isEqualTo(Segment.REPEAT);
        assertThat(c.successfulPurchases()).isEqualTo(2);
    }

    @Test
    void trangTrangThaiVaChiTietKhachHienRoDonKet() throws Exception {
        shipment("W1", "+84988888888", "IN_TRANSIT", ago(StaleOrders.AFTER_DAYS + 50));
        customers.recalculateAll();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");

        String page = mvc.perform(get("/status-mappings").with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("Đơn kẹt, chưa xác định").contains("<span>1</span> đơn").contains("IN_TRANSIT");

        long id = jdbc.sql("SELECT id FROM customer WHERE phone = '+84988888888'").query(Long.class).single();
        String detail = mvc.perform(get("/customers/" + id).with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).contains("Chưa xác định: đơn kẹt quá " + StaleOrders.AFTER_DAYS + " ngày");
        String list = mvc.perform(get("/customers?review=true").with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(list).contains("Cần kiểm tra");
    }
}
