package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.util.ReflectionTestUtils.setField;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(ZaloSharedGroupSchedulerTest.Config.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.multi.enabled=true",
        "app.zalo.shared-groups.enabled=true",
        "app.zalo.shared-groups.live-enabled=true",
        "app.zalo.accounts[0].id=2", "app.zalo.accounts[0].live=true",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1", "app.zalo.accounts[0].bridge-key=k2",
        "app.zalo.accounts[1].id=3", "app.zalo.accounts[1].live=true",
        "app.zalo.accounts[1].bridge-url=http://127.0.0.1:2", "app.zalo.accounts[1].bridge-key=k3",
        "app.zalo.accounts[2].id=4", "app.zalo.accounts[2].live=true",
        "app.zalo.accounts[2].bridge-url=http://127.0.0.1:3", "app.zalo.accounts[2].bridge-key=k4"
})
class ZaloSharedGroupSchedulerTest extends AbstractPostgresTest {
    private static final String LINK = "https://zalo.me/g/shared";
    private static final List<Long> ACCOUNTS = List.of(1L, 2L, 3L, 4L);

    static class Bridge extends FakeZaloGateway {
        final long account;
        boolean canAdd = true;
        Runnable onAdd = () -> {};

        Bridge(long account) { this.account = account; }

        @Override
        public GroupInspection inspectGroup(String id) {
            return code(account).equals(id) ? new GroupInspection(true, true, true, canAdd, LINK, tag(account), null)
                    : new GroupInspection(true, false, false, false, null, tag(account), null);
        }

        @Override
        public AddResult addSharedGroupMember(SharedGroupTarget target) {
            assertThat(target.accountTag()).isEqualTo(tag(account));
            assertThat(target.bridgeGroupId()).isEqualTo(code(account));
            return addToGroup(target.uid(), target.bridgeGroupId());
        }

        @Override
        public AddResult addToGroup(String uid, String groupId) {
            onAdd.run();
            return super.addToGroup(uid, groupId);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        List<Bridge> fakeBridges() { return ACCOUNTS.stream().map(Bridge::new).toList(); }

        @Bean
        @Primary
        ZaloAccountRoutes fakeRoutes(List<Bridge> fakeBridges, TestMode mode) {
            return new ZaloAccountRoutes(Map.of(1L, new SafeZaloLink(fakeBridges.get(0), mode),
                    2L, new SafeZaloLink(fakeBridges.get(1), mode), 3L, new SafeZaloLink(fakeBridges.get(2), mode),
                    4L, new SafeZaloLink(fakeBridges.get(3), mode)));
        }

        @Bean
        @Primary
        ZaloTestClock groupClock() { return new ZaloTestClock(); }
    }

    @Autowired List<Bridge> fakeBridges;
    @Autowired ZaloAutoScheduler scheduler;
    @Autowired ZaloAutoService auto;
    @Autowired ZaloAutoSettings settings;
    @Autowired ZaloAccountRegistry registry;
    @Autowired ZaloSharedGroupGrants grants;
    @Autowired ZaloGroupAddAttempts attempts;
    @Autowired GroupService groups;
    @Autowired ContactService contacts;
    @Autowired ImportService imports;
    @Autowired InviteService invites;
    @Autowired ZaloTestClock clock;
    @Autowired ZaloLink router;
    @Autowired TestMode mode;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    long group;

    @BeforeEach
    void setup() {
        registry.ensureRows();
        clock.set(LocalDateTime.of(2026, 11, 27, 10, 0));
        StringBuilder csv = new StringBuilder("Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n");
        for (long account : ACCOUNTS) {
            Bridge bridge = bridge(account);
            bridge.reset();
            bridge.canAdd = true;
            bridge.onAdd = () -> {};
            bridge.status = new ZaloGateway.Status(ZaloGateway.State.LISTENING, null, true, null,
                    Instant.parse("2026-11-26T00:00:00Z"), null, tag(account));
            jdbc.sql("UPDATE zalo_account SET enabled = true, account_tag = :tag WHERE id = :id")
                    .param("id", account).param("tag", tag(account)).update();
            csv.append("T%d,DH%d,05/01/2026 09:00:00,Khách thử %d,090000000%d,Hà Nội,Giao thành công\n"
                    .formatted(account, account, account, account));
            ZaloAccountContext.run(account, () -> settings.setMode(ZaloMode.AUTO, true, "test"));
        }
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "test.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt thử", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        group = groups.create("Nhóm chung", LINK, 5, "P:ha-noi", 2);
        groups.setZaloGroupId(group, code(2), 2);
        groups.setStatus(group, GroupStatus.OPEN);
        grants.setGroupEnabled(group, true);
    }

    private Bridge bridge(long account) { return fakeBridges.get((int) account - 1); }
    private static String tag(long account) { return "%012d".formatted(account); }
    private static String code(long account) { return "100" + account; }
    private static String uid(long account) { return "70000" + account; }
    private static String phone(long account) { return "+8490000000" + account; }

    private void grant(long account) {
        jdbc.sql("""
                        INSERT INTO zalo_group_add_grant
                          (group_id, account_id, bridge_group_id, account_tag, owner_tag, state, approved_by, verified_at, changed_by)
                        VALUES (:group, :account, :code, :tag, :owner, 'APPROVED', 'test', now(), 'test')
                        """).param("group", group).param("account", account).param("code", code(account))
                .param("tag", tag(account)).param("owner", tag(2)).update();
    }

    private long queue(long account) {
        return jdbc.sql("""
                        UPDATE invitation i SET account_id = :account, status = 'FRIENDED', sent_at = now(),
                          friended_at = now(), zalo_uid = :uid, zalo_add_state = 'QUEUED'
                        FROM customer c WHERE c.id = i.customer_id AND c.phone = :phone RETURNING i.id
                        """).param("account", account).param("uid", uid(account)).param("phone", phone(account))
                .query(Long.class).single();
    }

    @Test
    void routerAndSchedulerUseEachAccountsOwnMappingAndSkipUnapprovedAccount() {
        grant(1);
        grant(3);
        ACCOUNTS.forEach(this::queue);
        scheduler.tick();
        for (long account : List.of(1L, 2L, 3L)) {
            assertThat(bridge(account).adds).singleElement().satisfies(args -> assertThat(args).containsExactly(uid(account), code(account)));
        }
        assertThat(bridge(4).adds).isEmpty();
        assertThat(groups.find(group).orElseThrow().accountId()).isEqualTo(2);
        assertThat(groups.find(group).orElseThrow().actual()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT account_id FROM zalo_group_add_attempt WHERE state = 'JOINED' ORDER BY account_id")
                .query(Long.class).list()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void capacityIsSharedBetweenConcurrentAccountsAndAPublicLink() throws Exception {
        grant(3);
        grant(4);
        long third = queue(3);
        long fourth = queue(4);
        groups.recount(group, 4);
        var selected = groups.find(group).orElseThrow();
        var ready = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var a = pool.submit(() -> {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                return dispatch(3, third, selected);
            });
            var b = pool.submit(() -> {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                return dispatch(4, fourth, selected);
            });
            var c = pool.submit(() -> {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                return invites.go(tokenFor(2));
            });
            ready.countDown();
            long claimed = java.util.stream.Stream.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull).count();
            long redirected = c.get(10, TimeUnit.SECONDS).outcome() == InviteService.Outcome.REDIRECT ? 1 : 0;
            assertThat(claimed + redirected).isEqualTo(1);
        }
    }

    private String tokenFor(long account) {
        jdbc.sql("""
                        UPDATE invitation i SET account_id = :account, status = 'SENT', sent_at = now()
                        FROM customer c WHERE c.id = i.customer_id AND c.phone = :phone
                        """).param("account", account).param("phone", phone(account)).update();
        return jdbc.sql("SELECT i.token FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :phone")
                .param("phone", phone(account)).query(String.class).single();
    }

    private ZaloGroupAddAttempts.Attempt dispatch(long account, long invitation, GroupService.Group selected) {
        long customer = jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", invitation).query(Long.class).single();
        return ZaloAccountContext.call(account, () -> attempts.dispatch(invitation, customer, uid(account), "QUEUED",
                selected, grants.grant(group, account).orElseThrow(), false));
    }

    @Test
    void timeoutHoldsOneSeatAfterLegacyHoldExpiresAndCannotBeRetriedWithoutEvidence() {
        grant(1);
        long invitation = queue(1);
        jdbc.sql("UPDATE invitation SET reserved_group_id = :group, reserved_until = now() + interval '1 hour' WHERE id = :id")
                .param("group", group).param("id", invitation).update();
        bridge(1).willReturn(ZaloGateway.AddResult.of(ZaloGateway.Outcome.SYSTEM_ERROR, "TIMEOUT"));
        assertThat(auto.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
        assertThat(groups.find(group).orElseThrow().reserved()).isEqualTo(1);
        assertThatThrownBy(() -> auto.retry(invitation, "owner")).isInstanceOf(ImportException.class);
        jdbc.sql("UPDATE invitation SET reserved_until = now() - interval '1 hour' WHERE id = :id").param("id", invitation).update();
        assertThat(groups.find(group).orElseThrow().reserved()).isEqualTo(1);
        assertThat(attempts.resolve(attempts.reviews().getFirst().id(), true, "Đã kiểm tra trong nhóm thử", "owner")).isTrue();
        assertThat(groups.find(group).orElseThrow().reserved()).isZero();
        assertThat(groups.find(group).orElseThrow().actual()).isEqualTo(1);
        assertThat(bridge(1).adds).hasSize(1);
    }

    @Test
    void failureAfterProviderOkLeavesNoSuccessLogAndNoSecondCall() {
        grant(1);
        queue(1);
        jdbc.sql("""
                CREATE FUNCTION test_reject_joined_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.state = 'JOINED' THEN RAISE EXCEPTION 'commit failure'; END IF; RETURN NEW; END
                $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_reject_joined_attempt BEFORE UPDATE ON zalo_group_add_attempt
                FOR EACH ROW EXECUTE FUNCTION test_reject_joined_attempt()
                """).update();
        try {
            assertThat(auto.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
            assertThat(groups.find(group).orElseThrow().actual()).isZero();
            assertThat(groups.find(group).orElseThrow().reserved()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT result FROM zalo_auto_log WHERE action = 'THEM_NHOM'").query(String.class).single())
                    .isEqualTo("DANG_GOI");
            jdbc.sql("UPDATE zalo_group_add_attempt SET dispatched_at = now() - interval '3 minutes'").update();
            settings.reopen("owner");
            assertThat(auto.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
            assertThat(bridge(1).adds).hasSize(1);
        } finally {
            jdbc.sql("DROP TRIGGER test_reject_joined_attempt ON zalo_group_add_attempt").update();
            jdbc.sql("DROP FUNCTION test_reject_joined_attempt()").update();
        }
    }

    @Test
    void permissionLossOnlyBlocksTheAffectedGrantAndPreviewDoesNotWrite() {
        grant(1);
        grant(3);
        long first = queue(1);
        queue(3);
        jdbc.sql("INSERT INTO zalo_test_number (phone, created_by) VALUES (:phone, 'test')").param("phone", phone(1)).update();
        jdbc.sql("UPDATE invitation SET reserved_group_id = :group, reserved_until = now() + interval '1 hour' WHERE id = :id")
                .param("group", group).param("id", first).update();
        assertThat(auto.preview()).singleElement().satisfies(row -> {
            assertThat(row.heldGroupId()).isEqualTo(group);
            assertThat(row.legacyGroupId()).isNull();
            assertThat(row.proposedGroupId()).isEqualTo(group);
        });
        assertThat(bridge(1).adds).isEmpty();
        assertThat(attempts.reviews()).isEmpty();
        bridge(1).canAdd = false;
        scheduler.tick();
        assertThat(bridge(1).adds).isEmpty();
        assertThat(bridge(3).adds).hasSize(1);
        assertThat(grants.grant(group, 1)).isEmpty();
        assertThat(grants.grant(group, 3)).isPresent();
        assertThat(groups.find(group).orElseThrow().status()).isEqualTo(GroupStatus.OPEN);
        assertThat(jdbc.sql("SELECT reserved_group_id FROM invitation WHERE id = :id").param("id", first).query(Long.class).single()).isEqualTo(group);
    }

    @Test
    void finalPilotGuardRejectsAnyOtherAccountInvitationOrGroup() {
        grant(1);
        long invitation = queue(1);
        jdbc.sql("INSERT INTO zalo_test_number (phone, created_by) VALUES (:phone, 'test')")
                .param("phone", phone(1)).update();
        setField(mode, "sharedLiveEnabled", false);
        setField(mode, "pilotAccount", 1L);
        setField(mode, "pilotInvitation", invitation);
        setField(mode, "pilotGroup", group);
        setField(mode, "pilotBridgeGroup", code(1));
        try {
            var attempt = dispatch(1, invitation, groups.find(group).orElseThrow());
            assertThat(attempt).isNotNull();
            long revision = grants.grant(group, 1).orElseThrow().revision();
            assertThat(router.addSharedGroupMember(new ZaloGateway.SharedGroupTarget(
                    attempt.id(), invitation, group + 1, 1, uid(1), code(1), tag(1), revision)).outcome())
                    .isEqualTo(ZaloGateway.Outcome.UNREACHABLE);
            assertThat(router.addSharedGroupMember(new ZaloGateway.SharedGroupTarget(
                    attempt.id(), invitation + 1, group, 1, uid(1), code(1), tag(1), revision)).outcome())
                    .isEqualTo(ZaloGateway.Outcome.UNREACHABLE);
            assertThat(ZaloAccountContext.call(2, () -> router.addSharedGroupMember(new ZaloGateway.SharedGroupTarget(
                    attempt.id(), invitation, group, 1, uid(1), code(1), tag(1), revision))).outcome())
                    .isEqualTo(ZaloGateway.Outcome.UNREACHABLE);
            assertThat(bridge(1).adds).isEmpty();
            assertThat(router.addSharedGroupMember(new ZaloGateway.SharedGroupTarget(
                    attempt.id(), invitation, group, 1, uid(1), code(1), tag(1), revision)).outcome())
                    .isEqualTo(ZaloGateway.Outcome.OK);
            assertThat(router.addSharedGroupMember(new ZaloGateway.SharedGroupTarget(
                    attempt.id(), invitation, group, 1, uid(1), code(1), tag(1), revision)).outcome())
                    .isEqualTo(ZaloGateway.Outcome.UNREACHABLE);
            assertThat(bridge(1).adds).hasSize(1);
        } finally {
            setField(mode, "sharedLiveEnabled", true);
            setField(mode, "pilotAccount", 0L);
            setField(mode, "pilotInvitation", 0L);
            setField(mode, "pilotGroup", 0L);
            setField(mode, "pilotBridgeGroup", "");
        }
    }

    @Test
    void groupWithLegacyHoldCanBeDisabledWithWorkInProgressWithoutRecountOrMove() {
        grant(1);
        long invitation = queue(1);
        jdbc.sql("UPDATE invitation SET reserved_group_id = :group, reserved_until = now() + interval '2 hours' WHERE id = :id")
                .param("group", group).param("id", invitation).update();
        var before = jdbc.sql("SELECT reserved_group_id, reserved_until FROM invitation WHERE id = :id")
                .param("id", invitation).query().singleRow();
        var attempt = dispatch(1, invitation, groups.find(group).orElseThrow());
        assertThat(attempt).isNotNull();
        grants.setGroupEnabled(group, false);
        assertThatThrownBy(() -> groups.recount(group, 1)).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> groups.setZaloGroupId(group, "2002", 2)).isInstanceOf(ImportException.class);
        assertThat(attempts.complete(attempt, ZaloGateway.AddResult.ok())).isTrue();
        assertThat(jdbc.sql("SELECT reserved_group_id, reserved_until FROM invitation WHERE id = :id")
                .param("id", invitation).query().singleRow()).isEqualTo(before);
        assertThat(groups.find(group).orElseThrow().actual()).isEqualTo(1);
    }

    @Test
    void duplicateCanonicalLinkDeactivatesSharedMappingsWithoutMergingRecords() {
        grant(3);
        assertThat(grants.grant(group, 3)).isPresent();
        long duplicate = groups.create("Bản ghi cần rà soát", LINK, 5, "P:ha-noi", 4);
        assertThat(grants.grant(group, 3)).isEmpty();
        assertThat(groups.find(group)).isPresent();
        assertThat(groups.find(duplicate)).isPresent();
    }

    @Test
    void ownerCanRenderPreviewAndReviewAndOnlyResolveWithCsrf() throws Exception {
        grant(1);
        long invitation = queue(1);
        jdbc.sql("INSERT INTO zalo_test_number (phone, created_by) VALUES (:phone, 'test')")
                .param("phone", phone(1)).update();
        jdbc.sql("UPDATE invitation SET reserved_group_id = :group, reserved_until = now() + interval '2 hours' WHERE id = :id")
                .param("group", group).param("id", invitation).update();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        mvc.perform(get("/zalo/group-add-preview").param("account", "1").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nhóm chung")));
        bridge(1).willReturn(ZaloGateway.AddResult.of(ZaloGateway.Outcome.SYSTEM_ERROR, "TIMEOUT"));
        assertThat(auto.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
        long attempt = attempts.reviews().getFirst().id();
        mvc.perform(get("/zalo/group-add-review").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nhóm chung")));
        String path = "/zalo/group-add-review/" + attempt;
        mvc.perform(post(path).param("account", "1").param("joined", "true").param("note", "Đã kiểm tra")
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).param("account", "1").param("joined", "true").param("note", "Đã kiểm tra")
                        .with(user("staff").roles("STAFF")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).param("account", "1").param("joined", "true").param("note", "Đã kiểm tra")
                        .with(user("owner").roles("OWNER")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(groups.find(group).orElseThrow().actual()).isEqualTo(1);
    }
}
