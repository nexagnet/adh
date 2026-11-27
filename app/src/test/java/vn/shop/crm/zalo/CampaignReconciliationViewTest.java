package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.zalo.reconcile.enabled=true",
        "app.zalo.reconcile.manual-initial-delay-ms=3600000",
        "app.zalo.reconcile.initial-delay-ms=3600000"
})
class CampaignReconciliationViewTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    long campaign;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        campaign = jdbc.sql("""
                INSERT INTO invitation_campaign (name, message_template, expires_days, created_by)
                VALUES ('Đợt thử', 'Chào {ten} ạ.', 14, 'test') RETURNING id
                """).query(Long.class).single();
        jdbc.sql("INSERT INTO zalo_account (id, label) VALUES (2, 'Tài khoản kiểm thử 2')").update();
    }

    @Test
    void hienSoGuiVaTrangThaiZaloVoiLanDocGanNhat() throws Exception {
        long invitation = invite(2, "Khách thử", "0900000123");
        jdbc.sql("""
                INSERT INTO zalo_friend_observation
                    (invitation_id, account_id, state, observed_at, last_confirmed_state, last_confirmed_at, needs_review, attempt_at)
                SELECT id, account_id, 'UNAVAILABLE', '2026-11-22T04:00:00Z', 'INCOMING', '2026-11-22T03:00:00Z', true, friend_request_at
                FROM invitation WHERE id = :inv
                """).param("inv", invitation).update();

        mvc.perform(get("/campaigns/" + campaign).param("tab", "doi-chieu").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sổ gửi ADH")))
                .andExpect(content().string(containsString("Trạng thái trên Zalo")))
                .andExpect(content().string(containsString("Tài khoản kiểm thử 2")))
                .andExpect(content().string(containsString("Khách thử (số …0123)")))
                .andExpect(content().string(containsString("Đã gửi lời mời kết bạn")))
                .andExpect(content().string(containsString("22/11/2026 09:00")))
                .andExpect(content().string(containsString("Chưa đọc được trạng thái từ Zalo")))
                .andExpect(content().string(containsString("22/11/2026 11:00")))
                .andExpect(content().string(containsString("22/11/2026 10:00")))
                .andExpect(content().string(containsString("Có lời mời đang chờ trên Zalo, chưa xác minh bên gửi")))
                .andExpect(content().string(not(containsString("0900000123"))))
                .andExpect(content().string(not(containsString("/resend-friend"))));
    }

    @Test
    void boQuaKetQuaCuaLuotGuiTruoc() throws Exception {
        long invitation = invite(2, "Khách mới", "0900000321");
        jdbc.sql("""
                INSERT INTO zalo_friend_observation
                    (invitation_id, account_id, state, observed_at, last_confirmed_state, last_confirmed_at, needs_review, attempt_at)
                SELECT id, account_id, 'NONE', '2026-11-22T04:00:00Z', 'NONE', '2026-11-22T04:00:00Z', true,
                    friend_request_at - interval '1 day'
                FROM invitation WHERE id = :inv
                """).param("inv", invitation).update();

        mvc.perform(get("/campaigns/" + campaign).param("tab", "doi-chieu").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa đối chiếu lượt này")))
                .andExpect(content().string(not(containsString("Zalo không còn hiển thị lời mời đang chờ"))));
    }

    @Test
    void chiHienChieuGuiKhiZaloDaXacMinh() throws Exception {
        long verified = invite(2, "Khách đã xác minh", "0900000345");
        long pending = invite(1, "Khách còn chờ", "0900000368");
        jdbc.sql("""
                INSERT INTO zalo_friend_observation
                    (invitation_id, account_id, state, observed_at, last_confirmed_state, last_confirmed_at, needs_review,
                     attempt_at, outgoing_verified_at)
                SELECT id, account_id, 'OUTGOING', '2026-11-22T04:00:00Z', 'OUTGOING',
                    '2026-11-22T04:00:00Z', false, friend_request_at,
                    CASE WHEN id = :verified THEN '2026-11-22T04:00:00Z'::timestamptz
                         ELSE '2026-11-22T03:00:00Z'::timestamptz END
                FROM invitation WHERE id IN (:verified, :pending)
                """).param("verified", verified).param("pending", pending).update();

        mvc.perform(get("/campaigns/" + campaign).param("tab", "doi-chieu").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tài khoản phụ đã gửi lời mời, đang chờ")))
                .andExpect(content().string(containsString("Có lời mời đang chờ trên Zalo, chưa xác minh bên gửi")));
    }

    @Test
    void hienTienDoLoiVaNutTheoTaiKhoanTrongTrangCuaChuCuaHang() throws Exception {
        long invitation = invite(1, "Khách thử", "0900000123");
        long queued = invite(1, "Khách chưa đọc", "0900000321");
        long job = jdbc.sql("""
                INSERT INTO zalo_friend_read_job (campaign_id, account_id, waiting_reason, started_at, next_at)
                VALUES (:campaign, 1, 'LIMIT', '2026-11-27T09:00:00+07', '2026-11-28T00:00:00+07') RETURNING id
                """).param("campaign", campaign).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO zalo_friend_read_item (job_id, invitation_id, attempt_at, result)
                SELECT :job, id, friend_request_at, CASE WHEN id = :inv THEN 'FAILED' END
                FROM invitation WHERE id IN (:inv, :queued)
                """).param("job", job).param("inv", invitation).param("queued", queued).update();

        String path = "/campaigns/" + campaign;
        mvc.perform(get(path).param("tab", "doi-chieu").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(path + "/reconcile/1")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("Đối chiếu ngay · Tài khoản phụ 1")))
                .andExpect(content().string(containsString("1 / 2")))
                .andExpect(content().string(containsString("1 lỗi đọc")))
                .andExpect(content().string(containsString(path + "/reconcile/1/cancel")))
                .andExpect(content().string(containsString("Xác nhận hủy phần chưa đọc")))
                .andExpect(content().string(containsString("27/11/2026 09:00")))
                .andExpect(content().string(containsString("Đã tới hạn mức đọc trong ngày")))
                .andExpect(content().string(containsString("Lần đọc tiếp theo dự kiến · 28/11/2026 00:00")));
        mvc.perform(get(path).param("tab", "doi-chieu").with(user("employee")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(path + "/reconcile/1"))));
    }

    private long invite(long account, String name, String phone) {
        long customer = jdbc.sql("""
                INSERT INTO customer (name, phone, segment, successful_purchases)
                VALUES (:name, :phone, 'ONE_TIME', 1) RETURNING id
                """).param("name", name).param("phone", phone).query(Long.class).single();
        return jdbc.sql("""
                INSERT INTO invitation (campaign_id, customer_id, account_id, token, expires_at, status,
                    friend_request_at, friend_request_outcome)
                VALUES (:camp, :customer, :account, :token, '2026-12-22T00:00:00Z', 'SENT', '2026-11-22T02:00:00Z', 'SENT')
                RETURNING id
                """).param("camp", campaign).param("customer", customer).param("account", account)
                .param("token", "fixture-" + customer)
                .query(Long.class).single();
    }
}
