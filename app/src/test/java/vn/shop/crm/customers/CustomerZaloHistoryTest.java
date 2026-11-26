package vn.shop.crm.customers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

class CustomerZaloHistoryTest extends AbstractPostgresTest {

    @Autowired
    WebApplicationContext context;

    @Test
    void showsConfirmedSendersEvenWhenAccountIsDisabledAndReferencesOverlap() throws Exception {
        jdbc.sql("""
                INSERT INTO zalo_account (id, label, enabled) VALUES (2, 'Zalo khu vực Nam', false);
                INSERT INTO customer (id, phone, name, segment, successful_purchases) VALUES
                    (100, '+84911000001', 'An', 'ONE_TIME', 1),
                    (200, '+84911000002', 'Bé', 'ONE_TIME', 1),
                    (300, '+84911000003', 'Cầm', 'ONE_TIME', 1);
                INSERT INTO invitation_campaign (id, name, message_template, expires_days, created_by)
                    VALUES (1, 'Đợt 1', 't', 14, 'test');
                INSERT INTO invitation (id, campaign_id, customer_id, token, expires_at, account_id) VALUES
                    (10, 1, 100, 'tok-a', now() + interval '5 days', 2),
                    (20, 1, 200, 'tok-b', now() + interval '5 days', 1),
                    (30, 1, 300, 'tok-c', now() + interval '5 days', 2);
                INSERT INTO comeback (id, customer_id, invitation_id, state, reason, welcome_state)
                    VALUES (20, 100, 10, 'LIFTED', 'AI_CHAC', 'SENT');
                INSERT INTO reply_draft (id, customer_id, invitation_id, zalo_uid, state, inbound_at)
                    VALUES (10, 200, null, 'masked', 'SENT', now());
                INSERT INTO zalo_msg_log (at, scope, kind, ref_id, outcome, account_id, code) VALUES
                    ('2026-11-20 08:00:00+00', 'REAL', 'LINK', 20, 'OK', 1, null),
                    ('2026-11-20 08:01:00+00', 'REAL', 'INVITE', 10, 'OK', 2, 'PARTIAL'),
                    ('2026-11-20 08:02:00+00', 'REAL', 'REPLY', 10, 'OK', 1, null),
                    ('2026-11-20 08:03:00+00', 'REAL', 'WELCOME', 20, 'OK', 2, null),
                    ('2026-11-20 08:04:00+00', 'REAL', 'FOLLOWUP3', 10, 'PERSON_ERROR', 2, null),
                    ('2026-11-20 08:05:00+00', 'REAL', 'ASK_FRIEND', 10, 'DANG_GOI', 2, null),
                    ('2026-11-20 08:06:00+00', 'TEST', 'FOLLOWUP2', 10, 'OK', 2, null),
                    ('2026-11-20 08:07:00+00', 'REAL', 'UNDO_FRIEND', 10, 'OK', 2, null),
                    ('2026-11-20 08:08:00+00', 'REAL', 'INVITE', 30, 'SYSTEM_ERROR', 2, null),
                    ('2026-11-20 08:09:00+00', 'REAL', 'FOLLOWUP2', 10, 'OK', 1, null)
                """).update();

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/customers/100")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/customers/100").with(user("test").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lịch sử gửi Zalo")))
                .andExpect(content().string(containsString("Zalo khu vực Nam")))
                .andExpect(content().string(containsString("Tin mẫu trả lời")))
                .andExpect(content().string(containsString("Đã gửi một phần")))
                .andExpect(content().string(containsString("20/11/2026 15:03")))
                .andExpect(content().string(not(containsString("Tin trả lời AI"))))
                .andExpect(content().string(not(containsString("Tài khoản phụ 1"))))
                .andExpect(content().string(not(containsString("Thu hồi"))))
                .andExpect(content().string(not(containsString("Tin nhắc lần"))));
        mvc.perform(get("/customers/200").with(user("test").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tin trả lời AI")))
                .andExpect(content().string(containsString("Tin gửi link nhóm")))
                .andExpect(content().string(not(containsString("Zalo khu vực Nam"))));
        mvc.perform(get("/customers/300").with(user("test").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa ghi nhận lần gửi Zalo nào.")));
        mvc.perform(get("/customers").with(user("test").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zalo gửi gần nhất · Zalo khu vực Nam")))
                .andExpect(content().string(containsString("Zalo gửi gần nhất · Tài khoản phụ 1")));
    }
}
