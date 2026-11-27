package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@TestPropertySource(properties = "app.zalo.restore-fence=true")
class ZaloRestoreFenceTest extends AbstractPostgresTest {

    @Autowired ZaloLink gateway;
    @Autowired ZaloGroupAddAttempts attempts;
    @Autowired vn.shop.crm.invite.GroupService groups;

    @Test
    void restoreLockComesFromApplicationConfigAndTakesPrecedenceOverTheDatabase() {
        jdbc.sql("INSERT INTO app_setting (key, value) VALUES ('zalo.auto.mode', 'AUTO')").update();

        var result = gateway.addToGroup("fake-customer", "123");
        assertThat(result).isEqualTo(ZaloGateway.AddResult.of(ZaloGateway.Outcome.UNREACHABLE, "RESTORE_FENCED"));
        assertThat(gateway.status().state()).isEqualTo(ZaloGateway.State.DISABLED);
    }

    @Test
    void restoreSkipsTheMutatingTickBeforeItClaimsOldDatabaseWork() {
        ZaloAutoService auto = mock(ZaloAutoService.class);
        ZaloInviteSender sender = mock(ZaloInviteSender.class);
        ZaloAccountRegistry registry = mock(ZaloAccountRegistry.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ZaloTickHook> hooks = mock(ObjectProvider.class);

        new ZaloAutoScheduler(auto, sender, hooks, registry, new ZaloRestoreFence(true)).tick();

        verifyNoInteractions(auto, sender, hooks, registry);
    }

    @Test
    void providerRemembersAnAddThatAnOlderDatabaseWouldTryAgain() {
        String uid = "fake-customer";
        String group = "456";
        var first = new FakeZaloGateway();
        var provider = new FakeZaloGateway();
        var bridges = Map.<Long, ZaloLink>of(1L, first, 2L, provider);
        ZaloLink beforeRestore = new ZaloAccountRoutes(bridges).router();
        ZaloAccountContext.run(2L, () -> assertThat(beforeRestore.addToGroup(uid, group)).isEqualTo(ZaloGateway.AddResult.ok()));

        ZaloLink restored = new ZaloAccountRoutes(bridges, true).router();
        ZaloAccountContext.run(2L, () -> {
            assertThat(restored.addToGroup(uid, group).code()).isEqualTo("RESTORE_FENCED");
            var sharedTarget = new ZaloGateway.SharedGroupTarget(1L, 2L, 3L, 2L, uid, group, "123456789abc", 1L);
            assertThat(restored.addSharedGroupMember(sharedTarget).code()).isEqualTo("RESTORE_FENCED");
            assertThat(restored.sendByPhone(ZaloMessenger.Kind.INVITE, "fake-phone", "test").notSent()).isTrue();
            assertThat(restored.sendByUid(ZaloMessenger.Kind.FOLLOWUP2, uid, "test").notSent()).isTrue();
            assertThat(restored.sendReply(uid, "test", () -> true).notSent()).isTrue();
            assertThat(restored.sendFriendRequest(uid, "test").notSent()).isTrue();
            assertThat(restored.acceptFriendRequest(uid).notSent()).isTrue();
            assertThat(restored.undoFriendRequest(uid).notSent()).isTrue();
            assertThat(restored.createGroup("fake group", List.of(uid), null, null).notCreated()).isTrue();
            assertThat(restored.setDeputy(group, uid).code()).isEqualTo("RESTORE_FENCED");
            assertThat(restored.heart(1, uid).notSent()).isTrue();
            assertThat(restored.status().listening()).isTrue();
            restored.friendEvents(0);
            restored.listGroups();
        });

        assertThat(provider.adds).containsExactly(new String[]{uid, group});
        assertThat(provider.messages).isEmpty();
        assertThat(provider.friendRequests).isEmpty();
        assertThat(provider.accepted).isEmpty();
        assertThat(provider.undone).isEmpty();
        assertThat(provider.creates).isEmpty();
        assertThat(provider.deputyCalls).isEmpty();
        assertThat(provider.hearts).isEmpty();
        assertThat(provider.eventCalls).isEqualTo(1);
        assertThat(provider.groupListCalls).isEqualTo(1);
        assertThat(first.adds).isEmpty();
    }
    @Test
    void ownerCanReconstructAConfirmedAddLostFromBackupWithNoProviderWrite() {
        jdbc.sql("INSERT INTO zalo_account (id, label, enabled) VALUES (2, 'Thử 2', true)").update();
        long group = groups.create("Khôi phục", "https://zalo.me/g/recovered", 5, null, 2);
        long customer = jdbc.sql("""
                INSERT INTO customer (phone, segment, successful_purchases)
                VALUES ('+84900000110', 'ONE_TIME', 1) RETURNING id
                """).query(Long.class).single();
        long campaign = jdbc.sql("""
                INSERT INTO invitation_campaign (name, message_template, expires_days, created_by)
                VALUES ('Thử phục hồi', 'test', 14, 'test') RETURNING id
                """).query(Long.class).single();
        long invitation = jdbc.sql("""
                INSERT INTO invitation (campaign_id, customer_id, token, expires_at, status, zalo_add_state)
                VALUES (:campaign, :customer, 'restored-fixture', now()+interval '1 hour', 'FRIENDED', 'QUEUED')
                RETURNING id
                """).param("campaign", campaign).param("customer", customer).query(Long.class).single();
        attempts.recordRestoredMembership(invitation, group, 1, "Đã đối chiếu danh sách nhóm thử", "owner");
        assertThat(jdbc.sql("SELECT group_id FROM invitation WHERE status = 'JOINED' AND zalo_add_state = 'ADDED'")
                .query(Long.class).single()).isEqualTo(group);
        assertThat(groups.find(group).orElseThrow().actual()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT result FROM zalo_group_add_attempt WHERE restored_evidence")
                .query(String.class).single()).isEqualTo("CONFIRMED_RESTORED");
        assertThat(gateway.addToGroup("fake-customer", "123").code()).isEqualTo("RESTORE_FENCED");
    }
}
