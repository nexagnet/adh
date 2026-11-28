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
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.safety.TestMode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(ZaloDeputyReconcileTest.Config.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.multi.enabled=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].live=true",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2"
})
class ZaloDeputyReconcileTest extends AbstractPostgresTest {
    private static final String PRIMARY_TAG = "100000000001";
    private static final String SECONDARY_TAG = "100000000002";
    private static final String PRIMARY_UID = "4241";
    private static final String SECONDARY_UID = "4242";
    private static final String PRIORITY_NAME = "Áo điều hòa ADH - Tây Ninh - 01";

    @Autowired ZaloDeputyReconcileService reconcile;
    @Autowired ZaloAutoScheduler scheduler;
    @Autowired ZaloAccountRegistry accounts;
    @Autowired GroupService groups;
    @Autowired AppSettings settings;
    @Autowired ZaloAutoSettings auto;
    @Autowired ZaloTestClock clock;
    @Autowired FakeBridges bridges;

    @BeforeEach
    void setUp() {
        accounts.ensureRows();
        clock.set(LocalDateTime.of(2026, 11, 28, 10, 0));
        resetBridge(1, bridges.first(), PRIMARY_TAG);
        resetBridge(2, bridges.second(), SECONDARY_TAG);
    }

    @Test
    void prioritizesOpenTayNinhAndUsesOnlyItsOwnerBridge() {
        long primary = open("Đồng Nai - 01", "6101", 1);
        long ordinary = open("Tây Ninh - 02", "6202", 2);
        long priority = open(PRIORITY_NAME, "6201", 2);
        long closed = open("Tây Ninh - 03", "6203", 2);
        groups.setStatus(closed, GroupStatus.CLOSED);
        bridges.second().observed.put("6201", evidence("6201", true, "MEMBER"));
        configure(2, SECONDARY_UID, SECONDARY_TAG);

        scheduler.tick();

        assertThat(bridges.first().reads).isEmpty();
        assertThat(bridges.first().writes).isEmpty();
        assertThat(bridges.second().reads).containsOnly(new Read("6201", SECONDARY_UID));
        assertThat(bridges.second().writes).containsExactly(new Write("6201", SECONDARY_UID, true));
        assertThat(ZaloAccountContext.call(2, reconcile::groupStatuses))
                .extracting(ZaloDeputyReconcileService.GroupStatus::groupId)
                .containsExactlyInAnyOrder(priority, ordinary);
        assertThat(ZaloAccountContext.call(1, reconcile::groupStatuses))
                .extracting(ZaloDeputyReconcileService.GroupStatus::groupId)
                .containsExactly(primary);
    }

    @Test
    void readAlreadyAdminDoesNotSendAnyMutation() {
        long group = open(PRIORITY_NAME, "6201", 2);
        configure(2, SECONDARY_UID, SECONDARY_TAG);
        bridges.second().observed.put("6201", evidence("6201", true, "ADMIN"));

        tick(2);

        assertThat(status(2, group).status()).isEqualTo("VERIFIED");
        assertThat(status(2, group).checkedAt()).isNotNull();
        assertThat(bridges.second().writes).isEmpty();
        assertThat(bridges.second().messages).isEmpty();
    }

    @Test
    void prioritizesFirstCheckWithoutStarvingOtherGroupsThatHaveNeverBeenRead() {
        for (int i = 2; i <= 7; i++) {
            open("Nhóm mở " + i, "620" + i, 2);
        }
        open(PRIORITY_NAME, "6201", 2);
        configure(2, SECONDARY_UID, SECONDARY_TAG);

        for (int i = 0; i < 7; i++) {
            tick(2);
            clock.advance(Duration.ofMinutes(10));
        }

        assertThat(bridges.second().reads).extracting(Read::groupId)
                .containsExactly("6201", "6202", "6203", "6204", "6205", "6206", "6207");
        assertThat(bridges.second().writes).isEmpty();
    }

    @Test
    void waitsForRoleReadAfterBridgeAcknowledgesWrite() {
        long group = open(PRIORITY_NAME, "6201", 2);
        configure(2, SECONDARY_UID, SECONDARY_TAG);
        bridges.second().observed.put("6201", evidence("6201", true, "ABSENT"));

        tick(2);

        assertThat(status(2, group).status()).isEqualTo("PENDING_VERIFY");
        assertThat(bridges.second().writes).containsExactly(new Write("6201", SECONDARY_UID, false));

        tick(2);
        assertThat(bridges.second().writes).hasSize(1);

        bridges.second().observed.put("6201", evidence("6201", true, "ADMIN"));
        clock.advance(Duration.ofHours(2));
        tick(2);

        assertThat(status(2, group).status()).isEqualTo("VERIFIED");
        assertThat(bridges.second().writes).hasSize(1);
    }

    @Test
    void unknownRoleDoesNotAddOrPromote() {
        long group = open(PRIORITY_NAME, "6201", 2);
        configure(2, SECONDARY_UID, SECONDARY_TAG);
        bridges.second().observed.put("6201", evidence("6201", true, "UNKNOWN"));

        tick(2);

        assertThat(status(2, group).status()).isNotIn("VERIFIED", "PENDING_VERIFY");
        assertThat(bridges.second().reads).isNotEmpty();
        assertThat(bridges.second().writes).isEmpty();
    }

    @Test
    void persistentMissingRoleConsumesBoundedWriteBudgetButStillAllowsLaterVerification() {
        long group = open(PRIORITY_NAME, "6201", 2);
        configure(2, SECONDARY_UID, SECONDARY_TAG);
        bridges.second().observed.put("6201", evidence("6201", true, "MEMBER"));

        for (int i = 0; i < 5; i++) {
            tick(2);
            clock.advance(Duration.ofMinutes(90));
        }

        assertThat(bridges.second().writes).hasSize(3);
        assertThat(status(2, group).status()).isNotEqualTo("VERIFIED");

        bridges.second().observed.put("6201", evidence("6201", true, "ADMIN"));
        clock.advance(Duration.ofDays(1));
        tick(2);

        assertThat(status(2, group).status()).isEqualTo("VERIFIED");
        assertThat(bridges.second().writes).hasSize(3);
    }

    @Test
    void canNeverPromoteAsNonOwnerOrAfterReadingAnotherGroup() {
        long group = open(PRIORITY_NAME, "6201", 2);
        configure(2, SECONDARY_UID, SECONDARY_TAG);
        bridges.second().observed.put("6201", evidence("6201", false, "MEMBER"));

        tick(2);

        assertThat(status(2, group).status()).isEqualTo("BLOCKED");
        bridges.second().observed.put("6201", evidence("6209", true, "MEMBER"));
        clock.advance(Duration.ofDays(1));
        tick(2);

        assertThat(status(2, group).status()).isEqualTo("CHECK_FAILED");
        assertThat(bridges.second().reads).hasSize(2);
        assertThat(bridges.second().writes).isEmpty();
    }

    @Test
    void willNotBorrowPrimaryIdentityOrUseThePreviousSession() {
        long group = open(PRIORITY_NAME, "6201", 2);
        configure(1, PRIMARY_UID, PRIMARY_TAG);
        ZaloAccountContext.run(2, () -> reconcile.setEnabled(true, "test"));

        tick(2);

        assertThat(status(2, group).status()).isEqualTo("NO_CONFIG");
        configure(2, SECONDARY_UID, PRIMARY_TAG);
        tick(2);

        assertThat(status(2, group).status()).isNotIn("VERIFIED", "PENDING_VERIFY");
        assertThat(bridges.second().reads).isEmpty();
        assertThat(bridges.second().writes).isEmpty();
    }

    @Test
    void accountCircuitPreventsMutation() {
        open(PRIORITY_NAME, "6201", 2);
        configure(2, SECONDARY_UID, SECONDARY_TAG);
        bridges.second().observed.put("6201", evidence("6201", true, "MEMBER"));
        ZaloAccountContext.run(2, () -> auto.trip("TEST_CIRCUIT", "test"));

        tick(2);

        assertThat(bridges.second().writes).isEmpty();
    }

    @Test
    void uncertainWriteHaltsOtherAccountDuringSameSchedulerTick() {
        long first = open("Đồng Nai - 01", "6101", 1);
        open(PRIORITY_NAME, "6201", 2);
        configure(1, PRIMARY_UID, PRIMARY_TAG);
        configure(2, SECONDARY_UID, SECONDARY_TAG);
        bridges.first().observed.put("6101", evidence("6101", true, "MEMBER"));
        bridges.second().observed.put("6201", evidence("6201", true, "ABSENT"));
        bridges.first().writeResult = ZaloGroupMaker.DeputyResult.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "TIMEOUT");

        scheduler.tick();

        assertThat(bridges.first().writes).containsExactly(new Write("6101", PRIMARY_UID, true));
        assertThat(status(1, first).status()).isEqualTo("PENDING_VERIFY");
        assertThat(ZaloAccountContext.call(2, auto::tripped)).isTrue();
        assertThat(bridges.second().reads).isEmpty();
        assertThat(bridges.second().writes).isEmpty();
    }

    @Test
    void closingGroupDuringInspectionPreventsMutation() {
        long group = open(PRIORITY_NAME, "6201", 2);
        configure(2, SECONDARY_UID, SECONDARY_TAG);
        bridges.second().observed.put("6201", evidence("6201", true, "MEMBER"));
        bridges.second().onRead = () -> groups.setStatus(group, GroupStatus.CLOSED);

        tick(2);

        assertThat(bridges.second().reads).containsExactly(new Read("6201", SECONDARY_UID));
        assertThat(bridges.second().writes).isEmpty();
        assertThat(ZaloAccountContext.call(2, reconcile::groupStatuses)).isEmpty();
    }

    private long open(String name, String providerId, long account) {
        long group = groups.create(name, "https://zalo.me/g/fixture" + providerId, 5, "P:tay-ninh", account);
        groups.setZaloGroupId(group, providerId, account);
        groups.setStatus(group, GroupStatus.OPEN);
        return group;
    }

    private void configure(long account, String uid, String tag) {
        ZaloAccountContext.run(account, () -> {
            settings.set(ZaloDeputyService.UID, uid, "test");
            settings.set(ZaloDeputyService.ACCT, tag, "test");
            reconcile.setEnabled(true, "test");
        });
    }

    private void tick(long account) {
        ZaloAccountContext.run(account, reconcile::tick);
    }

    private ZaloDeputyReconcileService.GroupStatus status(long account, long group) {
        return ZaloAccountContext.call(account, reconcile::groupStatuses).stream()
                .filter(item -> item.groupId() == group).findFirst().orElseThrow();
    }

    private void resetBridge(long account, Bridge bridge, String tag) {
        jdbc.sql("UPDATE zalo_account SET enabled = true, account_tag = :tag WHERE id = :id")
                .param("tag", tag).param("id", account).update();
        bridge.reset();
        bridge.observed.clear();
        bridge.reads.clear();
        bridge.writes.clear();
        bridge.onRead = () -> {};
        bridge.writeResult = new ZaloGroupMaker.DeputyResult(ZaloGroupMaker.GroupOutcome.OK, null, "OK");
        bridge.status = new ZaloGateway.Status(ZaloGateway.State.LISTENING, null, true, null,
                clock.instant(), null, tag);
    }

    private static ZaloSession.DeputyInspection evidence(String group, boolean owner, String role) {
        return new ZaloSession.DeputyInspection("OK", null, group, owner, role);
    }

    record Read(String groupId, String uid) {
    }

    record Write(String groupId, String uid, boolean alreadyMember) {
    }

    static class Bridge extends FakeZaloGateway {
        final Map<String, DeputyInspection> observed = new HashMap<>();
        final List<Read> reads = new ArrayList<>();
        final List<Write> writes = new ArrayList<>();
        Runnable onRead = () -> {};
        DeputyResult writeResult;

        @Override
        public DeputyInspection inspectDeputy(String groupId, String deputyUid) {
            reads.add(new Read(groupId, deputyUid));
            onRead.run();
            return observed.getOrDefault(groupId, evidence(groupId, true, "UNKNOWN"));
        }

        @Override
        public DeputyResult setDeputy(String groupId, String deputyUid, boolean alreadyMember) {
            writes.add(new Write(groupId, deputyUid, alreadyMember));
            return writeResult;
        }
    }

    record FakeBridges(Bridge first, Bridge second) {
    }

    @TestConfiguration
    static class Config {
        @Bean
        FakeBridges fakeBridges() {
            return new FakeBridges(new Bridge(), new Bridge());
        }

        @Bean
        @Primary
        ZaloAccountRoutes isolatedRoutes(FakeBridges bridge, TestMode mode) {
            return new ZaloAccountRoutes(Map.of(
                    1L, new SafeZaloLink(bridge.first(), mode),
                    2L, new SafeZaloLink(bridge.second(), mode)));
        }

        @Bean
        @Primary
        ZaloTestClock deputyClock() {
            return new ZaloTestClock();
        }
    }
}
