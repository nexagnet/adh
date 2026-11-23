package vn.shop.crm.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trang hiệu quả chiến dịch cần đăng nhập; báo cáo xuất chỉ có số tổng hợp. */
class PerformanceWebTest extends AbstractPostgresTest {

    @Autowired
    WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO invitation_campaign (name, message_template, expires_days, created_by) VALUES ('Rét đợt 1', 't', 14, 'test')""").update();
    }

    @Test
    void canDangNhap() throws Exception {
        mvc.perform(get("/performance")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/performance.csv")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/performance/settings").with(csrf()).param("discount", "10")).andExpect(status().is3xxRedirection());
    }

    @Test
    void trangDungDuocKhiChuaCoSoLieu() throws Exception {
        mvc.perform(get("/performance").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hiệu quả chiến dịch")))
                .andExpect(content().string(containsString("Rét đợt 1")))
                .andExpect(content().string(containsString("Chưa đủ số liệu")))
                .andExpect(content().string(containsString("Số mua là ước tính")))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void luuGiaTriRoiTrangHienTienTo() throws Exception {
        mvc.perform(post("/performance/settings").with(user("admin")).with(csrf())
                        .param("discount", "10").param("fee", "12").param("prefix", "nhom").param("freeShip", "true"))
                .andExpect(redirectedUrl("/performance"));
        mvc.perform(get("/performance").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"NHOM\"")))
                .andExpect(content().string(not(containsString("Số mua là ước tính"))));
        // Giá trị sai bị từ chối bằng thông báo tiếng Việt, không lưu.
        mvc.perform(post("/performance/settings").with(user("admin")).with(csrf()).param("discount", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Ưu đãi phải từ 0 đến 90")));
    }

    /** Có đủ số liệu thì trang vẽ phần phễu, hòa vốn và lời lỗ mà không lỗi mẫu trang. */
    @Test
    void trangVeDuSoLieuKhiCoDonDaDoiSoat() throws Exception {
        long batch = jdbc.sql("INSERT INTO import_batch (carrier, filename, sha256, file_content) VALUES ('JT_EXPRESS', 'f', 'x', '\\x00') RETURNING id")
                .query(Long.class).single();
        long period = jdbc.sql("INSERT INTO settlement_period (carrier, period_code, last_batch_id) VALUES ('JT_EXPRESS', 'K1', :b) RETURNING id")
                .param("b", batch).query(Long.class).single();
        for (int i = 0; i < 5; i++) {
            String phone = "091100000" + i;
            jdbc.sql("INSERT INTO customer (phone, segment, successful_purchases) VALUES (:p, 'ONE_TIME', 1)").param("p", phone).update();
            jdbc.sql("""
                    INSERT INTO invitation (campaign_id, customer_id, token, status, expires_at, sent_at, friended_at, joined_at)
                    VALUES ((SELECT min(id) FROM invitation_campaign), (SELECT id FROM customer WHERE phone = :p), :t, 'JOINED',
                            now() + interval '30 days', now() - interval '20 days', now() - interval '19 days', now() - interval '18 days')""")
                    .param("p", phone).param("t", "t" + i).update();
            jdbc.sql("""
                    INSERT INTO shipment (carrier, tracking_no, order_ref, receiver_phone_raw, receiver_phone, created_on, raw_status,
                                          status_key, canonical_status, raw_payload, first_batch_id, last_batch_id)
                    VALUES ('JT_EXPRESS', :t, :o, :p, :p, now() - interval '10 days', 'x', 'x', 'DELIVERED', '{}'::jsonb, :b, :b)""")
                    .param("t", "TT" + i).param("o", "NHOM-" + i).param("p", phone).param("b", batch).update();
            jdbc.sql("""
                    INSERT INTO settlement_line (period_id, tracking_no, cod_collected, freight_fee, cod_fee, return_fee, partial_fee, adjustment, net_payable)
                    VALUES (:pe, :t, 900000, 40000, 10000, 0, 0, 0, 850000)""").param("pe", period).param("t", "TT" + i).update();
        }
        mvc.perform(get("/performance").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("4.500.000")))
                .andExpect(content().string(containsString("Hòa vốn khi TikTok thu")))
                .andExpect(content().string(not(containsString("Chưa đủ số liệu"))));
        mvc.perform(post("/performance/settings").with(user("admin")).with(csrf()).param("discount", "10").param("fee", "20"));
        mvc.perform(get("/performance").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lời hơn (+) hoặc lỗ hơn (-)")));
        var csv = mvc.perform(get("/performance.csv").with(user("admin"))).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("4500000");
    }

    @Test
    void baoCaoXuatLaCsvTongHopKhongCoThongTinKhach() throws Exception {
        jdbc.sql("INSERT INTO customer (phone, name, segment, successful_purchases) VALUES ('0911222333', 'Nguyễn Văn Bí Mật', 'ONE_TIME', 1)").update();
        var result = mvc.perform(get("/performance.csv").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("hieu_qua_chien_dich.csv")))
                .andReturn();
        String csv = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("ma_chien_dich", "Rét đợt 1", "tong");
        assertThat(csv).doesNotContain("0911222333").doesNotContain("Bí Mật");
    }
}
