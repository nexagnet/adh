package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {
        "app.zalo.reconcile.enabled=true",
        "app.zalo.reconcile.manual-initial-delay-ms=3600000",
        "app.zalo.reconcile.initial-delay-ms=3600000",
        "app.zalo.reconcile.test-only=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:2",
        "app.zalo.accounts[0].bridge-key=test-bridge-2",
        "app.zalo.accounts[0].live=true"
})
class CampaignFriendReconciliationTest extends AbstractPostgresTest {
    @Autowired WebApplicationContext context;
    @Autowired CampaignFriendReconciliation jobs;
    @MockitoBean ZaloLink bridge;
    @MockitoBean ZaloAccountRegistry accounts;
    MockMvc mvc;
    long campaign;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        campaign = campaign("Chiến dịch đang đọc");
        when(accounts.active()).thenReturn(java.util.List.of(1L, 2L));
        jdbc.sql("INSERT INTO zalo_account(id, label, enabled) VALUES (2, 'Tài khoản thử 2', true)").update();
    }

    @Test
    void readsOnlyTheRequestedCampaignAccountAndReportsLatestRead() throws Exception {
        long target = invitation(campaign, 2, "501");
        invitation(campaign, 1, "502");
        invitation(campaign("Chiến dịch khác"), 2, "503");
        when(bridge.friendRequestObservation("501", true)).thenAnswer(call -> {
            assertThat(ZaloAccountContext.current()).isEqualTo(2);
            return new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.NONE, null);
        });
        String path = "/campaigns/" + campaign + "/reconcile/2";
        mvc.perform(post(path).with(user("admin").roles("OWNER"))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("worker")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("admin").roles("OWNER")).with(csrf()))
                .andExpect(redirectedUrl("/campaigns/" + campaign + "?tab=doi-chieu"));
        jobs.start(campaign, 2);
        verifyNoInteractions(bridge);
        assertThat(jobs.status(campaign, 2).total()).isEqualTo(1);

        jobs.work(2);
        jobs.work(2);

        verify(bridge).friendRequestObservation("501", true);
        verifyNoMoreInteractions(bridge);
        assertThat(jdbc.sql("SELECT state FROM zalo_friend_observation WHERE invitation_id = :id")
                .param("id", target).query(String.class).single()).isEqualTo("NONE");
        assertThat(jdbc.sql("SELECT friend_request_outcome FROM invitation WHERE id = :id")
                .param("id", target).query(String.class).single()).isEqualTo("SENT");
        mvc.perform(get(path).with(user("admin").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.running").value(false)).andExpect(jsonPath("$.latestReadAt").isNotEmpty());
    }

    @Test
    void defersBudgetDenialsAndSkipsAnAttemptReplacedAfterQueueing() {
        long target = invitation(campaign, 2, "501");
        when(bridge.friendRequestObservation("501", true))
                .thenReturn(new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.UNAVAILABLE, "LIMIT"));
        jobs.start(campaign, 2);
        jobs.work(2);
        assertThat(jobs.status(campaign, 2).running()).isTrue();
        assertThat(jobs.status(campaign, 2).processed()).isZero();
        assertThat(jobs.status(campaign, 2).latestReadAt()).isNull();
        jdbc.sql("UPDATE zalo_friend_read_job SET next_at = now()").update();
        jdbc.sql("UPDATE invitation SET friend_request_at = friend_request_at - interval '1 second' WHERE id = :id")
                .param("id", target).update();
        jobs.work(2);
        assertThat(jobs.status(campaign, 2).skipped()).isEqualTo(1);
        assertThat(jobs.status(campaign, 2).running()).isFalse();
        verify(bridge, times(1)).friendRequestObservation("501", true);
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_friend_observation").query(Long.class).single()).isZero();
    }

    @Test
    void resumesAfterAWorkerDiesAndKeepsOtherAccountIndependent() {
        invitation(campaign, 1, "501");
        invitation(campaign, 2, "502");
        when(bridge.friendRequestObservation("501", true))
                .thenThrow(new AssertionError("worker died"))
                .thenReturn(new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.FRIENDS, null));
        when(bridge.friendRequestObservation("502", true))
                .thenReturn(new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.NONE, null));
        jobs.start(campaign, 1);
        jobs.start(campaign, 2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.work(1)).isInstanceOf(AssertionError.class);
        assertThat(jobs.status(campaign, 1).processed()).isZero();
        jobs.work(2);
        assertThat(jobs.status(campaign, 2).running()).isFalse();
        jobs.work(1);
        verify(bridge, times(1)).friendRequestObservation("501", true);

        jdbc.sql("UPDATE zalo_friend_read_job SET next_at = now() WHERE account_id = 1").update();
        jobs.work(1);
        assertThat(jobs.status(campaign, 1).running()).isFalse();
        assertThat(jobs.status(campaign, 1).processed()).isEqualTo(1);
        verify(bridge, times(2)).friendRequestObservation("501", true);
        verify(bridge, times(1)).friendRequestObservation("502", true);
        verifyNoMoreInteractions(bridge);
    }

    @Test
    void retriesABridgeRestartAndStoresDirectionProofForOnlyThatRead() {
        long target = invitation(campaign, 2, "501");
        when(bridge.friendRequestObservation("501", true))
                .thenReturn(new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.UNAVAILABLE, "BRIDGE_DOWN"),
                        new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.OUTGOING_VERIFIED, null),
                        new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.PENDING, null));
        jobs.start(campaign, 2);
        jobs.work(2);
        assertThat(jobs.status(campaign, 2).processed()).isZero();
        assertThat(jobs.status(campaign, 2).nextReadAt()).isNotNull();
        jdbc.sql("UPDATE zalo_friend_read_job SET next_at = now() WHERE account_id = 2").update();
        jobs.work(2);
        assertThat(jobs.status(campaign, 2).processed()).isEqualTo(1);
        assertThat(directionVerified(target)).isTrue();
        assertThat(jdbc.sql("SELECT state FROM zalo_friend_observation WHERE invitation_id = :id")
                .param("id", target).query(String.class).single()).isEqualTo("OUTGOING");

        jobs.start(campaign, 2);
        jobs.work(2);
        assertThat(directionVerified(target)).isFalse();
        assertThat(jdbc.sql("""
                SELECT outgoing_verified FROM zalo_friend_observation_event
                WHERE invitation_id = :id ORDER BY id
                """).param("id", target).query(Boolean.class).list()).containsExactly(true, false);
    }

    @Test
    void waitsForTheAccountToRecoverWithoutConsumingTheBatch() {
        invitation(campaign, 2, "501");
        when(bridge.friendRequestObservation("501", true))
                .thenReturn(new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.UNAVAILABLE, "NOT_ACTIVE"));
        jobs.start(campaign, 2);
        for (int i = 0; i < 5; i++) {
            jdbc.sql("UPDATE zalo_friend_read_job SET next_at = now()").update();
            jobs.work(2);
        }
        var status = jobs.status(campaign, 2);
        assertThat(status.running()).isTrue();
        assertThat(status.processed()).isZero();
        assertThat(status.nextReadAt()).isNotNull();
        assertThat(jdbc.sql("SELECT retries FROM zalo_friend_read_item").query(Integer.class).single()).isZero();
    }

    @Test
    void aRepeatedExceptionDoesNotBlockTheNextInvitation() {
        invitation(campaign, 2, "501");
        invitation(campaign, 2, "502");
        when(bridge.friendRequestObservation("501", true)).thenThrow(new IllegalStateException("failed to read"));
        when(bridge.friendRequestObservation("502", true))
                .thenReturn(new ZaloLink.FriendRequestObservation(ZaloLink.FriendRequestState.NONE, null));
        jobs.start(campaign, 2);
        for (int i = 0; i < 5; i++) {
            jdbc.sql("UPDATE zalo_friend_read_job SET next_at = now()").update();
            jobs.work(2);
        }
        assertThat(jobs.status(campaign, 2).failed()).isEqualTo(1);
        assertThat(jobs.status(campaign, 2).processed()).isEqualTo(2);
        assertThat(jobs.status(campaign, 2).running()).isFalse();
        verify(bridge, times(4)).friendRequestObservation("501", true);
        verify(bridge).friendRequestObservation("502", true);
    }

    private boolean directionVerified(long id) {
        return jdbc.sql("""
                SELECT coalesce(outgoing_verified_at = observed_at, false)
                FROM zalo_friend_observation WHERE invitation_id = :id
                """).param("id", id).query(Boolean.class).single();
    }

    private long campaign(String name) {
        return jdbc.sql("""
                INSERT INTO invitation_campaign(name, message_template, expires_days, created_by)
                VALUES (:name, 't', 14, 'test') RETURNING id
                """).param("name", name).query(Long.class).single();
    }

    private long invitation(long campaign, long account, String uid) {
        long customer = jdbc.sql("""
                INSERT INTO customer(phone, segment, successful_purchases) VALUES (:phone, 'ONE_TIME', 1) RETURNING id
                """).param("phone", "0900000" + uid).query(Long.class).single();
        return jdbc.sql("""
                INSERT INTO invitation(campaign_id, account_id, customer_id, token, expires_at, sent_channel, status,
                                       friend_request_outcome, friend_request_at, zalo_target_uid)
                VALUES (:campaign, :account, :customer, :uid, now() + interval '1 day', 'ZALO_AUTO', 'SENT',
                        'SENT', now() - interval '20 minutes', :uid) RETURNING id
                """).param("campaign", campaign).param("account", account).param("customer", customer)
                .param("uid", uid).query(Long.class).single();
    }
}
