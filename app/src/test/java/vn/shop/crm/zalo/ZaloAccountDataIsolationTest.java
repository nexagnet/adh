package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2",
        "app.zalo.safety.live=false"})
class ZaloAccountDataIsolationTest extends AbstractPostgresTest {

    private static final String TEST_PHONE = "0900000001";
    private static final String REAL_PHONE = "0900000002";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired GroupService groups;
    @Autowired ZaloAutoService automatic;
    @Autowired ZaloAutoSettings autoSettings;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloGroupCreationService proposals;
    @Autowired ZaloAccountRegistry accounts;
    @Autowired ZaloQueueView queue;
    @Autowired FakeZaloGateway fake;
    @Autowired TestMode testMode;
    @Autowired ZaloTestClock clock;

    @BeforeEach
    void seed() {
        accounts.ensureRows();
        fake.reset();
        clock.set(java.time.LocalDateTime.of(2026, 11, 25, 10, 0));
        testMode.add(TEST_PHONE, null, "admin");
        testMode.add(REAL_PHONE, null, "admin");
        String csv = """
                Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái
                T1,DH1,05/01/2026 09:00:00,Khách thử,0900000001,1 Duong A Xa B Cần Thơ,Giao thành công
                T2,DH2,05/01/2026 09:00:00,Khách thật,0900000002,1 Duong A Xa B Cần Thơ,Giao thành công
                """;
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "accounts.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        invites.createCampaign("Hai tài khoản", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        jdbc.sql("""
                        UPDATE invitation i SET status = 'SENT', account_id = CASE c.phone WHEN :phone THEN 1 ELSE 2 END
                        FROM customer c WHERE i.customer_id = c.id
                        """).param("phone", "+84900000001").update();
        testMode.remove(REAL_PHONE, "admin");
    }

    private long invitationOf(long account) {
        return jdbc.sql("SELECT id FROM invitation WHERE account_id = :acct").param("acct", account).query(Long.class).single();
    }

    @Test
    void testUidFromAnotherAccountDoesNotAuthorizeRealCustomer() {
        jdbc.sql("UPDATE invitation SET zalo_target_uid = '1234'").update();

        assertThat(testMode.uidAllowed("1234")).isTrue();
        assertThat(ZaloAccountContext.call(2, () -> testMode.uidAllowed("1234"))).isFalse();
    }

    @Test
    void manualMatchRejectsInvitationAndEventFromAnotherAccount() {
        long event = jdbc.sql("""
                INSERT INTO zalo_friend_event (account_id, bridge_seq, uid, event_at, received_at, outcome)
                VALUES (2, 1, '1234', now(), now(), 'NO_PHONE') RETURNING id
                """).query(Long.class).single();

        assertThat(ZaloAccountContext.call(2, automatic::candidates)).hasSize(1);
        assertThatThrownBy(() -> ZaloAccountContext.run(2, () -> automatic.resolve(event, invitationOf(1), "admin")))
                .isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> automatic.dismiss(event, "admin")).isInstanceOf(ImportException.class);
        assertThat(jdbc.sql("SELECT outcome FROM zalo_friend_event WHERE id = :id").param("id", event).query(String.class).single())
                .isEqualTo("NO_PHONE");
    }

    @Test
    void landingAndReservationUseTheInvitationAccount() {
        long primaryGroup = groups.create("Nhóm chính", "https://zalo.me/g/primary", 5);
        long secondGroup = groups.create("Nhóm phụ", "https://zalo.me/g/second", 5, null, 2);
        groups.setStatus(primaryGroup, GroupStatus.OPEN);
        groups.setStatus(secondGroup, GroupStatus.OPEN);
        String token = jdbc.sql("SELECT token FROM invitation WHERE account_id = 2").query(String.class).single();

        assertThat(invites.go(token).url()).isEqualTo("https://zalo.me/g/second");
        long invitation = invitationOf(2);
        invites.markFriended(invitation);
        assertThat(jdbc.sql("SELECT reserved_group_id FROM invitation WHERE account_id = 2").query(Long.class).single())
                .isEqualTo(secondGroup);
        assertThatThrownBy(() -> invites.markJoined(invitation, primaryGroup)).isInstanceOf(ImportException.class);
    }

    @Test
    void groupAssignmentAndProposalActionsRejectAnotherAccount() {
        long group = groups.create("Nhóm chính", "https://zalo.me/g/primary", 5);
        long secondGroup = groups.create("Nhóm phụ", "https://zalo.me/g/second", 5, null, 2);
        long proposal = jdbc.sql("""
                INSERT INTO zalo_group_proposal (account_id, scope, group_name, reason, waiting_count, state)
                VALUES (2, 'NORTH', 'Nhóm mới', 'thử', 3, 'PROPOSED') RETURNING id
                """).query(Long.class).single();

        assertThat(ZaloAccountContext.call(2, automatic::adhGroups)).extracting(GroupService.Group::id).containsExactly(secondGroup);
        fake.groupList = new ZaloSession.GroupList(true, null,
                java.util.List.of(new ZaloSession.ZaloGroup("5678", "Nhóm phụ", 5, false, true)));
        assertThatThrownBy(() -> ZaloAccountContext.run(2, () -> automatic.assignZaloGroup(group, "1234")))
                .isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> ZaloAccountContext.run(2, () -> automatic.assignZaloGroup(secondGroup, "1234")))
                .isInstanceOf(ImportException.class);
        ZaloAccountContext.run(2, () -> automatic.assignZaloGroup(secondGroup, "5678"));
        assertThat(groups.findOfAccount(secondGroup, 2).orElseThrow().zaloGroupId()).isEqualTo("5678");
        assertThatThrownBy(() -> proposals.reject(proposal, "admin")).isInstanceOf(ImportException.class);
        assertThat(proposals.recent(10)).isEmpty();
        assertThat(ZaloAccountContext.call(2, () -> proposals.recent(10))).hasSize(1);
    }

    @Test
    void deputyRetryCannotMutateAPausedAccount() {
        assertThatThrownBy(() -> ZaloAccountContext.run(2, () -> proposals.retryDeputy(1, "admin")))
                .isInstanceOf(ImportException.class).hasMessageContaining("tạm ngưng");
        assertThat(fake.deputyCalls).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyForeignGroupGoesToManualReview(boolean reservation) {
        long ownGroup = groups.create("Nhóm chính", "https://zalo.me/g/primary", 5);
        groups.setStatus(ownGroup, GroupStatus.OPEN);
        groups.setZaloGroupId(ownGroup, "9876");
        long foreignGroup = groups.create("Nhóm phụ", "https://zalo.me/g/second", 5, null, 2);
        long id = invitationOf(1);
        autoSettings.setMode(ZaloMode.AUTO, true, "admin");
        jdbc.sql("""
                        UPDATE invitation SET status = 'FRIENDED', zalo_uid = '1234', zalo_add_state = 'QUEUED',
                               group_id = CASE WHEN :reservation THEN NULL ELSE :grp END,
                               reserved_group_id = CASE WHEN :reservation THEN :grp ELSE NULL END,
                               reserved_until = CASE WHEN :reservation THEN now() + interval '1 hour' ELSE NULL END
                        WHERE id = :id""")
                .param("reservation", reservation).param("grp", foreignGroup).param("id", id).update();

        automatic.addNext();

        assertThat(fake.adds).isEmpty();
        assertThat(jdbc.sql("SELECT zalo_add_code FROM invitation WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("NHOM_KHAC_TAI_KHOAN");
        assertThat(automatic.waiting()).singleElement().satisfies(row -> assertThat(row.state()).isEqualTo("FAILED"));
    }

    @Test
    void previewsDoNotShowAnotherAccountsFriendsOrResends() {
        for (long account : new long[]{1, 2}) {
            ZaloAccountContext.run(account, () -> {
                autoSettings.setMode(ZaloMode.AUTO, true, "admin");
                messages.setEnabled(true, "admin");
                messages.setFriendDaily(3, "admin");
            });
        }
        jdbc.sql("UPDATE invitation_campaign SET auto_send_approved_at = now()").update();
        jdbc.sql("UPDATE invitation SET sent_channel = 'ZALO_AUTO', zalo_target_uid = '1234' WHERE account_id = 1").update();

        assertThat(queue.preview().upcoming()).extracting(ZaloQueueView.Item::what).containsExactly("Gửi lời mời kết bạn");
        assertThat(ZaloAccountContext.call(2, queue::preview).upcoming()).isEmpty();

        jdbc.sql("UPDATE invitation SET friended_at = now(), tin1_blocked_at = now() WHERE account_id = 1").update();

        assertThat(queue.preview().first().what()).contains("Gửi lại Tin 1");
        assertThat(ZaloAccountContext.call(2, queue::preview).upcoming()).isEmpty();

        jdbc.sql("UPDATE invitation SET status = 'READY' WHERE account_id = 1").update();
        assertThat(ZaloAccountContext.call(2, queue::preview).upcoming())
                .extracting(ZaloQueueView.Item::what).containsExactly("Tin 1");
    }
}
