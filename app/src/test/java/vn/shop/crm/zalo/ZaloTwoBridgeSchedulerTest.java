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
import vn.shop.crm.ai.AiClient;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(ZaloTwoBridgeSchedulerTest.Bridges.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.inbox.enabled=true",
        "app.ai.enabled=true",
        "app.ai.api-key=test-only",
        "app.ai.model=test-model",
        "app.zalo.multi.enabled=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2"})
class ZaloTwoBridgeSchedulerTest extends AbstractPostgresTest {

    static class BridgeWithStatusHook extends FakeZaloGateway {
        Runnable beforeNextStatus;
        Instant inboundAt;

        @Override
        public Status status() {
            Runnable hook = beforeNextStatus;
            beforeNextStatus = null;
            if (hook != null) {
                hook.run();
            }
            return super.status();
        }

        @Override
        public List<Inbound> inbound(long afterSeq) {
            List<Inbound> messages = super.inbound(afterSeq);
            return inboundAt == null ? messages : messages.stream()
                    .map(message -> new Inbound(message.seq(), message.uid(), message.text(), inboundAt)).toList();
        }
    }

    record BridgePair(BridgeWithStatusHook first, BridgeWithStatusHook second) {
        void reset() {
            first.reset();
            first.beforeNextStatus = null;
            first.inboundAt = null;
            second.reset();
            second.beforeNextStatus = null;
            second.inboundAt = null;
            first.status = listening(FIRST_TAG, FIRST_SESSION);
            second.status = listening(SECOND_TAG, SECOND_SESSION);
        }
    }

    @TestConfiguration
    static class Bridges {
        @Bean
        BridgePair bridgePair() {
            return new BridgePair(new BridgeWithStatusHook(), new BridgeWithStatusHook());
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

        @Bean
        @Primary
        AiClient fakeInboxAi() {
            return (system, user, json) -> {
                if (system.startsWith("Bạn chỉ làm một việc: xem MỘT tin")) {
                    return "{\"scope\":\"TRONG\",\"confidence\":0.95}";
                }
                if (system.startsWith("Bạn là TRỢ LÝ AI")) {
                    String answer = user.contains(SECOND_QUESTION) ? SECOND_ANSWER : FIRST_ANSWER;
                    return "{\"reply\":\"" + answer + "\"}";
                }
                return "{\"intent\":\"CAU_HOI\",\"confidence\":0.95}";
            };
        }
    }

    private static final Instant FIRST_SESSION = Instant.parse("2026-11-22T00:00:00Z");
    private static final Instant SECOND_SESSION = FIRST_SESSION.plusSeconds(60);
    private static final String FIRST_TAG = "100000000001";
    private static final String SECOND_TAG = "200000000002";
    private static final String SECOND_QUESTION = "Đơn trong nhóm có tính tiền vận chuyển không?";
    private static final String FIRST_ANSWER = "Dạ vào nhóm mua trực tiếp sẽ được giảm 10% so với giá trên TikTok ạ.";
    private static final String SECOND_ANSWER = "Dạ mua trực tiếp trong nhóm được miễn phí vận chuyển ạ.";

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
    @Autowired ZaloMessageService messages;
    @Autowired ZaloDeputyService deputies;
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
    void aDeputyLookupTripDuringFirstBridgeStatusPreventsSendAndLeavesNoPendingAttempt() {
        bridges.second().willFind(ZaloPersonFinder.Found.of(ZaloPersonFinder.FindResult.RESTRICTED, "VERIFY"));
        bridges.first().beforeNextStatus = () -> ZaloAccountContext.run(2, () ->
                assertThat(deputies.lookup("0900000001", "test").status()).isEqualTo(ZaloDeputyService.Status.STOPPED));

        var delivery = messages.sendByPhone(ZaloMessageService.SCOPE_REAL, ZaloMessenger.Kind.INVITE,
                null, "84900000001", "Tin 1", null, null);

        assertThat(delivery.block()).isEqualTo(ZaloMessageService.Block.MODE_OFF);
        assertThat(bridges.first().messages).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_msg_log").query(Long.class).single()).isZero();
        assertThat(auto.sharedHaltSource()).isEqualTo(2L);
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

    @Test
    void schedulerRepliesOnBothBridgesWhenInboundUidAndSequenceMatch() {
        prepareCampaign("T2,DH2,05/01/2026 09:00:00,Khach 2,0900000002,1 Duong A Xa B Hà Nội,Giao thành công\n");
        jdbc.sql("INSERT INTO zalo_test_number (phone, note, created_by) VALUES ('+84900000002', 'test', 'test')").update();
        String sharedUid = "60000001";
        markInvited(1, "+84900000001", sharedUid);
        markInvited(2, "+84900000002", sharedUid);
        replies.setMode(ReplyService.Mode.DRAFT, false, "test");
        replies.setMode(ReplyService.Mode.AUTO, true, "test");
        Instant receivedAt = jdbc.sql("SELECT now()").query(java.time.OffsetDateTime.class).single().toInstant();
        bridges.first().inboundAt = receivedAt;
        bridges.second().inboundAt = receivedAt;
        bridges.first().pushInbound(sharedUid, "Trong nhóm giảm giá thế nào ạ?");
        bridges.second().pushInbound(sharedUid, SECOND_QUESTION);

        scheduler.tick();
        scheduler.tick();

        assertThat(bridges.first().messages).containsExactly(
                new FakeZaloGateway.Msg(ZaloMessenger.Kind.REPLY, null, sharedUid, FIRST_ANSWER));
        assertThat(bridges.second().messages).containsExactly(
                new FakeZaloGateway.Msg(ZaloMessenger.Kind.REPLY, null, sharedUid, SECOND_ANSWER));
        assertThat(jdbc.sql("SELECT account_id FROM reply_draft WHERE state = 'SENT' ORDER BY account_id")
                .query(Long.class).list()).containsExactly(1L, 2L);
        for (long account : new long[]{1, 2}) {
            assertThat(ZaloAccountContext.call(account, () -> settings.get(ZaloInboxService.CURSOR))).contains("1");
        }
    }

    private void markInvited(long account, String phone, String uid) {
        jdbc.sql("""
                        UPDATE invitation i SET account_id = :account, status = 'SENT', sent_at = now(), zalo_target_uid = :uid
                        FROM customer c WHERE c.id = i.customer_id AND c.phone = :phone""")
                .param("account", account).param("uid", uid).param("phone", phone).update();
    }

    private static ZaloGateway.Status listening(String tag, Instant session) {
        return new ZaloGateway.Status(ZaloGateway.State.LISTENING, null, true, null, session, null, tag);
    }

    private void prepareCampaign() {
        prepareCampaign("");
    }

    private void prepareCampaign(String extraRows) {
        String csv = """
                Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái
                T1,DH1,05/01/2026 09:00:00,Khach 1,0900000001,1 Duong A Xa B Hà Nội,Giao thành công
                """ + extraRows;
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        long campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 30, 20);
        jdbc.sql("INSERT INTO zalo_test_number (phone, note, created_by) VALUES ('+84900000001', 'test', 'test')").update();
        sender.approveCampaign(campaign, "test");
    }
}
