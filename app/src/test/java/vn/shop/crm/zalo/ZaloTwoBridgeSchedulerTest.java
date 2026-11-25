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
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.inbox.ReplyService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(ZaloTwoBridgeSchedulerTest.Bridges.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.multi.enabled=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2"})
class ZaloTwoBridgeSchedulerTest extends AbstractPostgresTest {

    record BridgePair(FakeZaloGateway first, FakeZaloGateway second) {
        void reset() {
            first.reset();
            second.reset();
            first.status = listening(FIRST_TAG, FIRST_SESSION);
            second.status = listening(SECOND_TAG, SECOND_SESSION);
        }
    }

    @TestConfiguration
    static class Bridges {
        @Bean
        BridgePair bridgePair() {
            return new BridgePair(new FakeZaloGateway(), new FakeZaloGateway());
        }

        @Bean
        @Primary
        ZaloAccountRoutes fakeRoutes(BridgePair pair, TestMode mode) {
            return new ZaloAccountRoutes(Map.of(1L, new SafeZaloLink(pair.first(), mode),
                    2L, new SafeZaloLink(pair.second(), mode)));
        }

        @Bean
        @Primary
        ZaloTestClock schedulerClock() {
            return new ZaloTestClock();
        }
    }

    private static final Instant FIRST_SESSION = Instant.parse("2026-11-22T00:00:00Z");
    private static final Instant SECOND_SESSION = FIRST_SESSION.plusSeconds(60);
    private static final String FIRST_TAG = "100000000001";
    private static final String SECOND_TAG = "200000000002";

    @Autowired BridgePair bridges;
    @Autowired ZaloAutoScheduler scheduler;
    @Autowired ZaloAccountRegistry registry;
    @Autowired ZaloAutoSettings auto;
    @Autowired AppSettings settings;
    @Autowired ZaloTestClock clock;
    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloReactionSender reactions;
    @Autowired ReplyService replies;

    @BeforeEach
    void prepareAccounts() {
        bridges.reset();
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        registry.setEnabled(2, true);
        for (long account : new long[]{1, 2}) {
            ZaloAccountContext.run(account, () -> {
                auto.setMode(ZaloMode.AUTO, true, "test");
                settings.set("zalo.msg.enabled", "true", "test");
                settings.set("zalo.msg.daily", account == 1 ? "5" : "10", "test");
                settings.set("zalo.friend.daily", "0", "test");
            });
        }
    }

    @Test
    void restrictionOnSecondBridgePreventsFirstBridgeMutationInSameTick() {
        prepareCampaign();
        jdbc.sql("DELETE FROM zalo_test_number").update();
        settings.set("zalo.msg.daily", "20", "test");
        bridges.second().status = new ZaloGateway.Status(ZaloGateway.State.TRIPPED, "RESTRICTED:VERIFY", true,
                null, SECOND_SESSION, null, SECOND_TAG);

        scheduler.tick();

        assertThat(bridges.first().messages).isEmpty();
        assertThat(bridges.second().messages).isEmpty();
        assertThat(auto.tripped()).isTrue();
        assertThat(ZaloAccountContext.call(2, auto::tripped)).isTrue();
        registry.setEnabled(2, false);
        scheduler.tick();
        assertThat(bridges.first().messages).isEmpty();
    }

    @Test
    void offlineSecondBridgeDoesNotBlockNewCustomerOnFirstBridge() {
        prepareCampaign();
        bridges.second().status = ZaloGateway.Status.of(ZaloGateway.State.UNREACHABLE);

        scheduler.tick();

        assertThat(bridges.first().messages).hasSize(1);
        assertThat(bridges.second().messages).isEmpty();
        assertThat(jdbc.sql("SELECT account_id FROM invitation WHERE status = 'SENT'").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void globalTripStillBlocksAccountThatWasPausedDuringIncident() {
        prepareCampaign();
        registry.setEnabled(2, false);
        bridges.first().status = new ZaloGateway.Status(ZaloGateway.State.TRIPPED, "SYSTEM_ERROR:NO_ACK", true,
                null, FIRST_SESSION, null, FIRST_TAG);
        scheduler.tick();

        assertThat(ZaloAccountContext.call(2, auto::tripped)).isTrue();
        bridges.first().status = listening(FIRST_TAG, FIRST_SESSION.plusSeconds(120));
        registry.setEnabled(2, true);
        scheduler.tick();
        assertThat(ZaloAccountContext.call(2, auto::tripped)).isTrue();
        assertThat(bridges.second().messages).isEmpty();
    }

    @Test
    void healthTickEvaluatesSecondBridgeIndependently() {
        bridges.second().status = ZaloGateway.Status.of(ZaloGateway.State.LOGIN_REQUIRED);
        clock.advance(Duration.ofMinutes(2));

        scheduler.tick();

        assertThat(jdbc.sql("SELECT account_id FROM zalo_health_alert WHERE kind = :kind AND resolved_at IS NULL")
                .param("kind", ZaloHealthService.K_LOGIN).query(Long.class).list()).containsExactly(2L);
    }

    @Test
    void uncertainReactionAlsoBlocksTheOtherBridge() {
        prepareCampaign();
        replies.setMode(ReplyService.Mode.DRAFT, false, "test");
        replies.setMode(ReplyService.Mode.AUTO, true, "test");
        bridges.first().heartResult = new ZaloReactor.Reacted(false, "TIMEOUT", false, "TIN_INTERRUPTED");

        assertThat(reactions.react(null, 1, "60000001")).isFalse();
        assertThat(bridges.first().hearts).hasSize(1);

        scheduler.tick();
        assertThat(ZaloAccountContext.call(2, auto::tripped)).isTrue();
        assertThat(bridges.second().messages).isEmpty();
    }

    @Test
    void schedulerRoutesNewCustomerOnlyToSelectedBridge() {
        prepareCampaign();
        settings.set("zalo.msg.daily", "20", "test");

        scheduler.tick();

        assertThat(bridges.first().messages).isEmpty();
        assertThat(bridges.second().messages).hasSize(1);
        assertThat(jdbc.sql("SELECT account_id FROM invitation WHERE status = 'SENT'").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void pilotBridgeWithMoreRoomDoesNotHoldOrdinaryCustomers() {
        prepareCampaign();
        jdbc.sql("DELETE FROM zalo_test_number").update();

        scheduler.tick();

        assertThat(bridges.first().messages).hasSize(1);
        assertThat(bridges.second().messages).isEmpty();
    }

    @Test
    void certaintyOfNoSendReleasesTentativeAssignment() {
        prepareCampaign();
        bridges.second().willReturnMessage(ZaloMessenger.Sent.of(ZaloMessenger.Result.RATE_LIMITED, null, true));

        scheduler.tick();

        assertThat(bridges.second().messages).hasSize(1);
        assertThat(jdbc.sql("SELECT account_id FROM invitation WHERE status = 'READY'").query(Long.class).single()).isEqualTo(1L);
    }

    private static ZaloGateway.Status listening(String tag, Instant session) {
        return new ZaloGateway.Status(ZaloGateway.State.LISTENING, null, true, null, session, null, tag);
    }

    private void prepareCampaign() {
        String csv = """
                Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái
                T1,DH1,05/01/2026 09:00:00,Khach 1,0900000001,1 Duong A Xa B Hà Nội,Giao thành công
                """;
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        long campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 30, 20);
        jdbc.sql("INSERT INTO zalo_test_number (phone, note, created_by) VALUES ('+84900000001', 'test', 'test')").update();
        sender.approveCampaign(campaign, "test");
    }
}
