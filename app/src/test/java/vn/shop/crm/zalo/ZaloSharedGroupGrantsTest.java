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
import vn.shop.crm.invite.GroupService;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import vn.shop.crm.common.ImportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = "app.zalo.shared-groups.enabled=true")
class ZaloSharedGroupGrantsTest extends AbstractPostgresTest {
    private static final String OWNER_TAG = "100000000001";
    private static final String OTHER_TAG = "100000000002";
    private static final String LINK = "https://zalo.me/g/fixture";
    private static final String BASE = "/zalo/shared-groups/";

    @Autowired WebApplicationContext context;
    @Autowired GroupService groups;
    @Autowired ZaloSharedGroupGrants grants;
    @MockitoBean ZaloAccountRegistry accounts;
    @MockitoBean ZaloLink bridge;

    MockMvc mvc;
    long group;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("UPDATE zalo_account SET account_tag = :tag WHERE id = 1").param("tag", OWNER_TAG).update();
        jdbc.sql("""
                INSERT INTO zalo_account (id, label, enabled, account_tag) VALUES (2, 'Tài khoản thử 2', true, :tag)
                """).param("tag", OTHER_TAG).update();
        when(accounts.selectable(1)).thenReturn(true);
        when(accounts.selectable(2)).thenReturn(true);
        when(accounts.views()).thenReturn(List.of(new ZaloAccountRegistry.View(1, "Phiên 1", true),
                new ZaloAccountRegistry.View(2, "Phiên 2", true)));
        group = groups.create("Nhóm thử", LINK, 5);
        groups.setZaloGroupId(group, "222");
    }

    @Test
    void onlyOwnerWithCsrfCanApproveTheExactPair() throws Exception {
        String path = BASE + group + "/grants";
        mvc.perform(post(path).with(user("admin").roles("OWNER"))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("worker")).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(bridge);
        mvc.perform(get(BASE + group).with(user("admin").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(view().name("zalo-shared-groups"));
        observations("555", LINK, OTHER_TAG, true);
        mvc.perform(post(path).with(user("admin").roles("OWNER")).with(csrf())
                        .param("account", "1").param("grantee", "2").param("bridgeGroupId", "555"))
                .andExpect(redirectedUrl(BASE + group));
        assertThat(grants.grant(group, 2)).isEmpty(); // nhóm mới tắt dùng chung
        mvc.perform(post(BASE + group + "/enabled").with(user("admin").roles("OWNER")).with(csrf())
                        .param("account", "1").param("sharing", "true"))
                .andExpect(redirectedUrl(BASE + group));
        assertThat(grants.grant(group, 2)).get().satisfies(g -> {
            assertThat(g.bridgeGroupId()).isEqualTo("555");
            assertThat(g.accountTag()).isEqualTo(OTHER_TAG);
        });
        mvc.perform(get(BASE + group).with(user("admin").roles("OWNER")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Đã xác minh")));
        verify(bridge, never()).addToGroup(any(), any());
    }

    @Test
    void sameNameOrProviderIdIsInsufficientWithoutTheLiveLinkAndRole() {
        observations("222", "https://zalo.me/g/other", OTHER_TAG, true);
        assertThatThrownBy(() -> grants.approve(group, 2, "222", "admin")).isInstanceOf(ImportException.class);
        observations("222", LINK, OWNER_TAG, true);
        assertThatThrownBy(() -> grants.approve(group, 2, "222", "admin")).isInstanceOf(ImportException.class);
        observations("222", LINK, OTHER_TAG, false);
        assertThatThrownBy(() -> grants.approve(group, 2, "222", "admin")).isInstanceOf(ImportException.class);
        assertThat(grants.view(group).grants()).isEmpty();
        verify(bridge, never()).addToGroup(any(), any());
    }

    @Test
    void disableInvalidatesPreparedRevisionAndRestoringLinkDoesNotRestoreGrant() {
        observations("555", LINK, OTHER_TAG, true);
        grants.approve(group, 2, "555", "admin");
        grants.setGroupEnabled(group, true);
        var initial = grants.grant(group, 2).orElseThrow();
        grants.setGroupEnabled(group, false);
        assertThat(grants.grant(group, 2)).isEmpty();
        grants.setGroupEnabled(group, true);
        assertThat(grants.grant(group, 2).orElseThrow().revision()).isGreaterThan(initial.revision());
        groups.update(group, "Nhóm thử", "https://zalo.me/g/temporary", 5, null);
        groups.update(group, "Nhóm thử", LINK, 5, null);
        assertThat(grants.grant(group, 2)).isEmpty();
        assertThat(grants.view(group).grants()).singleElement().satisfies(g -> assertThat(g.state()).isEqualTo("REVIEW"));
        grants.approve(group, 2, "555", "admin");
        assertThat(grants.grantedGroupIds(2)).containsExactly(group);
        jdbc.sql("UPDATE zalo_account SET account_tag = '100000000099' WHERE id = 2").update();
        jdbc.sql("UPDATE zalo_account SET account_tag = :tag WHERE id = 2").param("tag", OTHER_TAG).update();
        assertThat(grants.grant(group, 2)).isEmpty();
    }

    @Test
    void sharedApprovalAndLegacyAssignmentCannotClaimTheSameMapping() throws Exception {
        long legacy = groups.create("Nhóm khác", LINK, 5, null, 2);
        observations("555", LINK, OTHER_TAG, true);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> succeeded(start, () -> grants.approve(group, 2, "555", "admin")));
            var assignment = executor.submit(() -> succeeded(start, () -> groups.setZaloGroupId(legacy, "555", 2)));
            start.countDown();
            assertThat(List.of(approval.get(10, TimeUnit.SECONDS), assignment.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.sql("""
                        SELECT (SELECT count(*) FROM zalo_group WHERE account_id = 2 AND zalo_group_id = '555')
                             + (SELECT count(*) FROM zalo_group_add_grant WHERE account_id = 2 AND bridge_group_id = '555')
                        """).query(Long.class).single()).isEqualTo(1L);
    }

    @Autowired ZaloSharedGroupJoin joins;

    @Test
    void ownerApprovalJoinsOnceAndVerifiesBeforeOpeningTheGroup() throws Exception {
        long target = groups.create("Nhóm của tài khoản 2", "https://zalo.me/g/otherFixture", 5, null, 2);
        groups.setZaloGroupId(target, "700", 2);
        when(accounts.active()).thenReturn(List.of(1L, 2L));
        String link = "https://zalo.me/g/otherFixture";
        var member = new java.util.concurrent.atomic.AtomicBoolean();
        when(bridge.inspectGroup("700")).thenAnswer(call -> ZaloAccountContext.current() == 2
                ? new ZaloSession.GroupInspection(true, true, false, true, link, OTHER_TAG, null)
                : new ZaloSession.GroupInspection(true, member.get(), false, false, member.get() ? link : null, OWNER_TAG, null));
        when(bridge.joinGroupViaLink("700", link, OWNER_TAG)).thenAnswer(call -> {
            assertThat(ZaloAccountContext.current()).isEqualTo(1);
            return ZaloSession.JoinGroupResult.of(ZaloSession.JoinGroupOutcome.PENDING, "Z240");
        });

        String path = BASE + target + "/join";
        mvc.perform(post(path).with(user("admin").roles("OWNER"))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("worker")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("admin").roles("OWNER")).with(csrf())
                .param("account", "2").param("grantee", "1").param("confirmed", "true").param("activate", "true"))
                .andExpect(redirectedUrl(BASE + target));

        assertThat(grants.view(target).sharing()).isFalse();
        assertThat(grants.view(target).grants()).isEmpty();
        assertThatThrownBy(() -> joins.start(target, 1, true, "admin")).isInstanceOf(ImportException.class);
        joins.verify(target, 1);
        assertThat(joins.views(target)).singleElement().satisfies(row -> assertThat(row.state()).isEqualTo("PENDING"));
        member.set(true);
        joins.verify(target, 1);

        assertThat(joins.views(target)).singleElement().satisfies(row -> assertThat(row.state()).isEqualTo("VERIFIED"));
        assertThat(grants.grant(target, 1)).get().satisfies(row -> assertThat(row.bridgeGroupId()).isEqualTo("700"));
        verify(bridge, times(1)).joinGroupViaLink("700", link, OWNER_TAG);
        verify(bridge, never()).addToGroup(any(), any());
        mvc.perform(get(BASE + target).with(user("admin").roles("OWNER")).param("account", "2"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Đã tham gia và xác minh quyền")));
    }

    @Test
    void unknownRequestNeverResendsAndCannotApproveAfterTheGroupLinkChanges() {
        long target = groups.create("Nhóm tài khoản 2", "https://zalo.me/g/oldFixture", 5, null, 2);
        groups.setZaloGroupId(target, "701", 2);
        when(accounts.active()).thenReturn(List.of(1L, 2L));
        String original = "https://zalo.me/g/oldFixture";
        when(bridge.inspectGroup("701")).thenAnswer(call -> ZaloAccountContext.current() == 2
                ? new ZaloSession.GroupInspection(true, true, false, true, original, OTHER_TAG, null)
                : new ZaloSession.GroupInspection(true, false, false, false, null, OWNER_TAG, null));
        when(bridge.joinGroupViaLink("701", original, OWNER_TAG))
                .thenReturn(ZaloSession.JoinGroupResult.of(ZaloSession.JoinGroupOutcome.UNKNOWN, "TIMEOUT"));
        joins.start(target, 1, true, "admin");
        assertThatThrownBy(() -> joins.start(target, 1, true, "admin")).isInstanceOf(ImportException.class);
        groups.update(target, "Nhóm tài khoản 2", "https://zalo.me/g/newFixture", 5, null);
        joins.verify(target, 1);
        assertThat(joins.views(target)).singleElement().satisfies(row -> assertThat(row.state()).isEqualTo("CHANGED"));
        assertThat(grants.view(target).grants()).isEmpty();
        assertThat(grants.view(target).sharing()).isFalse();
        verify(bridge, times(1)).joinGroupViaLink("701", original, OWNER_TAG);
    }

    private static boolean succeeded(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
            return true;
        } catch (ImportException rejected) {
            return false;
        }
    }

    private void observations(String actorGroup, String actorLink, String actorTag, boolean mayAdd) {
        when(bridge.inspectGroup(anyString())).thenAnswer(request -> {
            String target = request.getArgument(0);
            if (ZaloAccountContext.current() == 1 && "222".equals(target)) {
                return new ZaloSession.GroupInspection(true, true, true, true, LINK, OWNER_TAG, null);
            }
            if (ZaloAccountContext.current() == 2 && actorGroup.equals(target)) {
                return new ZaloSession.GroupInspection(true, true, true, mayAdd, actorLink, actorTag, null);
            }
            return ZaloSession.GroupInspection.failed("BAD_ID");
        });
    }
}