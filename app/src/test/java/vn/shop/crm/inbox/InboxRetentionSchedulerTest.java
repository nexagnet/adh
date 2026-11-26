package vn.shop.crm.inbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.AbstractPostgresTest;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"app.zalo.auto.enabled=false", "app.zalo.inbox.enabled=false", "app.ai.enabled=false"})
@Transactional
class InboxRetentionSchedulerTest extends AbstractPostgresTest {

    @Autowired ScheduledTaskHolder scheduler;

    @Test
    void purgesOnlyExpiredContentForBothAccountsEvenWithConnectorDisabled() {
        jdbc.sql("INSERT INTO zalo_account (id, label, enabled) VALUES (2, 'Tài khoản phụ', false)").update();
        jdbc.sql("""
                INSERT INTO customer (id, phone, segment, successful_purchases)
                VALUES (1, '+84900000001', 'ONE_TIME', 1), (2, '+84900000002', 'ONE_TIME', 1)
                """).update();
        jdbc.sql("""
                INSERT INTO invitation_campaign (id, name, message_template, expires_days, created_by)
                VALUES (1, 'Thử', 'Tin', 30, 'test')
                """).update();
        jdbc.sql("""
                INSERT INTO invitation (id, campaign_id, customer_id, token, expires_at, account_id)
                VALUES (1, 1, 1, 'a', now(), 1), (2, 1, 2, 'b', now(), 2)
                """).update();
        jdbc.sql("""
                INSERT INTO refusal_review (customer_id, invitation_id, state, reason, prior_status, excerpt, created_at)
                VALUES (1, 1, 'HELD', 'AI_LOI', 'SENT', 'hết hạn', now() - interval '31 days'),
                       (2, 2, 'HELD', 'AI_LOI', 'SENT', 'còn hạn', now() - interval '30 days')
                """).update();
        jdbc.sql("""
                INSERT INTO comeback (customer_id, invitation_id, state, reason, excerpt, created_at)
                VALUES (1, 1, 'TASK', 'AI_LOI', 'còn hạn', now() - interval '29 days'),
                       (2, 2, 'TASK', 'AI_LOI', 'hết hạn', now() - interval '30 days 1 second')
                """).update();
        jdbc.sql("""
                INSERT INTO reply_draft (customer_id, account_id, zalo_uid, state, inbound_text, reply_text, inbound_at, created_at)
                VALUES (1, 1, '1', 'DRAFT', 'hết hạn', 'nháp', now() - interval '31 days', now() - interval '31 days'),
                       (2, 2, '1', 'DRAFT', 'còn hạn', 'nháp', now() - interval '30 days', now() - interval '30 days')
                """).update();
        jdbc.sql("""
                INSERT INTO warranty_case (customer_id, account_id, zalo_uid, state, category, urgency,
                                           inbound_text, summary, draft_text, inbound_at, created_at)
                VALUES (1, 1, '1', 'OPEN', 'KHAC', 'THUONG', 'còn hạn', 'tóm tắt', 'nháp', now(), now()),
                       (2, 2, '1', 'OPEN', 'KHAC', 'THUONG', 'hết hạn', 'tóm tắt', 'nháp', now(), now() - interval '31 days')
                """).update();

        var retention = scheduler.getScheduledTasks().stream().map(task -> task.getTask().getRunnable())
                .filter(task -> task.toString().endsWith("InboxRetentionScheduler.purge")).toList();
        assertThat(retention).hasSize(1);
        retention.getFirst().run();
        assertRetainedContent();

        retention.getFirst().run();
        assertRetainedContent();
    }

    private void assertRetainedContent() {
        assertThat(jdbc.sql("SELECT excerpt FROM refusal_review ORDER BY customer_id").query(String.class).list())
                .containsExactly(null, "còn hạn");
        assertThat(jdbc.sql("SELECT excerpt FROM comeback ORDER BY customer_id").query(String.class).list())
                .containsExactly("còn hạn", null);
        assertThat(jdbc.sql("SELECT concat_ws('/', inbound_text, reply_text) FROM reply_draft ORDER BY account_id")
                .query(String.class).list()).containsExactly("", "còn hạn/nháp");
        assertThat(jdbc.sql("SELECT concat_ws('/', inbound_text, summary, draft_text) FROM warranty_case ORDER BY account_id")
                .query(String.class).list()).containsExactly("còn hạn/tóm tắt/nháp", "");
    }
}
