package vn.shop.crm.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.test.context.TestPropertySource(properties = "APP_ADH_STAGE2_ENABLED=true")
class ZaloGroupIngressTest extends AbstractPostgresTest {
    @Autowired ZaloGroupIngress ingress;
    @Autowired AppSettings settings;
    @Autowired HandoffService handoffs;

    @Test
    void onlyRoutesEnabledGroupByAccountAndQueuesOneNoticeForEachOpenHandoff() {
        settings.set(AppSettings.BOT_ENABLED, "true", "test");
        jdbc.sql("UPDATE zalo_account SET enabled = true WHERE id = 1").update();
        jdbc.sql("INSERT INTO zalo_account (id, label, enabled) VALUES (2, 'test2', true)").update();
        jdbc.sql("""
                INSERT INTO zalo_group (name, account_id, zalo_group_id, bot_enabled)
                VALUES ('Nhóm một', 1, 'x123', true), ('Nhóm hai', 2, 'x123', true),
                       ('Tắt', 1, 'off', false)
                """).update();
        String text = "Cho mình gặp người thật 0912345678";
        var customer = BotEngine.SenderKind.CUSTOMER;
        assertThat(ingress.receive(3, "x123", "e1", customer, text)).isEmpty();
        assertThat(ingress.receive(1, "off", "e1", customer, text)).isEmpty();
        assertThat(ingress.receive(1, "x123", "e1", BotEngine.SenderKind.BOT, text))
                .get().extracting(BotEngine.Decision::type).isEqualTo(BotEngine.Type.SELF);
        assertThat(ingress.receive(1, "x123", "e2", customer, text))
                .get().extracting(BotEngine.Decision::type).isEqualTo(BotEngine.Type.HANDOFF);
        assertThat(ingress.receive(2, "x123", "e2", customer, text))
                .get().extracting(BotEngine.Decision::type).isEqualTo(BotEngine.Type.HANDOFF);
        assertThat(ingress.receive(2, "x123", "e2", customer, text))
                .get().extracting(BotEngine.Decision::type).isEqualTo(BotEngine.Type.DUPLICATE);
        assertThat(jdbc.sql("SELECT count(*) FROM handoff_notification WHERE state = 'PENDING'")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT excerpt FROM handoff").query(String.class).list())
                .allMatch(excerpt -> !excerpt.contains("0912345678"));
    }

    @Test
    void noticeContainsOnlyWorkLinkAndAnUnknownAttemptIsHeld() {
        long group = jdbc.sql("INSERT INTO zalo_group(name) VALUES ('Nhóm thử') RETURNING id").query(Long.class).single();
        assertThat(handoffs.open(group, "ORDER", "Chi tiết nội bộ")).isTrue();
        var channel = org.mockito.Mockito.mock(HandoffNotificationTransport.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<HandoffNotificationTransport> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(channel);
        org.mockito.Mockito.when(channel.send(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(HandoffNotificationTransport.Outcome.UNKNOWN);
        var dispatcher = new HandoffNotificationDispatcher(jdbc, settings, provider, "https://adh.test");
        settings.set(AppSettings.BOT_ENABLED, "true", "test");
        dispatcher.tick();
        org.mockito.Mockito.verifyNoInteractions(channel); // mặc định chạy thử
        settings.set(AppSettings.BOT_DRY_RUN, "false", "test");
        dispatcher.tick();
        dispatcher.tick();
        org.mockito.Mockito.verify(channel).send(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("Có việc cần xử lý trong ADH. https://adh.test/staff/work"));
        assertThat(jdbc.sql("SELECT state FROM handoff_notification").query(String.class).single()).isEqualTo("UNKNOWN");
    }
}
