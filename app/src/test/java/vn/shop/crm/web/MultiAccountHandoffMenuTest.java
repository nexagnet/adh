package vn.shop.crm.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@TestPropertySource(properties = {
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2"})
class MultiAccountHandoffMenuTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired NavMenu menu;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO zalo_account (id, label, enabled) VALUES (2, 'Zalo thử số hai', false)
                ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label""").update();
    }

    @Test
    void showsAnIndependentQueueAndHealthBadgeForEachConfiguredBridge() throws Exception {
        jdbc.sql("""
                INSERT INTO zalo_friend_event (account_id, bridge_seq, uid, event_at, outcome) VALUES
                    (1, 9, '700', now(), 'NO_PHONE'), (2, 9, '700', now(), 'NO_PHONE')""").update();
        jdbc.sql("""
                INSERT INTO zalo_health_alert (account_id, kind, severity, opened_at, last_seen_at) VALUES
                    (2, 'TEST', 'CRITICAL', now(), now()), (1, 'TEST', 'WARN', now(), now())""").update();

        assertThat(menu.groups("/").stream().flatMap(g -> g.items().stream())
                .filter(i -> i.url().startsWith("/zalo?")))
                .extracting(i -> i.url() + "=" + i.badge())
                .containsExactly("/zalo?account=1=1", "/zalo?account=2=2");
        mvc.perform(get("/").with(user("owner").roles("OWNER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/zalo?account=2\"")));
        mvc.perform(get("/zalo").param("account", "2").with(user("staff").roles("STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void groupsWorkByTypeAndShowsExactSourceEvenWhenUidIsShared() throws Exception {
        long customer = jdbc.sql("""
                INSERT INTO customer (phone, name, segment, successful_purchases)
                VALUES ('+84900000001', 'Một khách', 'ONE_TIME', 1) RETURNING id""").query(Long.class).single();
        var replyIds = jdbc.sql("""
                INSERT INTO reply_draft (account_id, customer_id, zalo_uid, state, reason, inbound_text, inbound_at)
                VALUES (1, :c, '700', 'HANDOFF', 'NGOAI_PHAM_VI', 'Việc từ máy một', now()),
                       (2, :c, '700', 'HANDOFF', 'NGOAI_PHAM_VI', 'Việc từ máy hai', now())
                RETURNING id""").param("c", customer).query(Long.class).list();
        long group = jdbc.sql("INSERT INTO zalo_group (name, account_id) VALUES ('Nhóm thử', 2) RETURNING id")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO handoff (group_id, reason, excerpt) VALUES (:id, 'HUMAN', 'Việc trong nhóm')")
                .param("id", group).update();

        var owner = user("owner").roles("OWNER");
        mvc.perform(get("/handoffs").param("type", "replies").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString("data-zalo-account=\"1\">Tài khoản phụ 1")))
                .andExpect(content().string(containsString("data-zalo-account=\"2\">Zalo thử số hai")))
                .andExpect(content().string(not(containsString("id=\"v-nhom\""))));
        mvc.perform(get("/handoffs").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"v-nhom\"")))
                .andExpect(content().string(containsString("data-zalo-account=\"2\">Zalo thử số hai")));
        mvc.perform(post("/handoffs/replies/{id}/done", replyIds.get(1)).with(owner)
                        .with(csrf()).param("type", "replies"))
                .andExpect(redirectedUrl("/handoffs?type=replies"));
        assertThat(jdbc.sql("SELECT state FROM reply_draft ORDER BY account_id").query(String.class).list())
                .containsExactly("HANDOFF", "DONE");
    }
}
