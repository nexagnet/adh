package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Service
public class ZaloGroupAddAttempts {
    private final JdbcClient jdbc;
    private final GroupService groups;
    private final InviteService invites;
    private final ZaloSharedGroupGrants grants;
    private final ZaloAutoSettings settings;
    private final TestMode testMode;
    private final ContactService contacts;
    private final ZaloRestoreFence restoreFence;
    private final Clock clock;

    public ZaloGroupAddAttempts(JdbcClient jdbc, GroupService groups, InviteService invites, ZaloSharedGroupGrants grants,
                                ZaloAutoSettings settings, TestMode testMode, ContactService contacts, Clock clock, ZaloRestoreFence restoreFence) {
        this.restoreFence = restoreFence;
        this.jdbc = jdbc;
        this.groups = groups;
        this.invites = invites;
        this.grants = grants;
        this.settings = settings;
        this.testMode = testMode;
        this.contacts = contacts;
        this.clock = clock;
    }

    record Attempt(long id, long invitationId, long groupId, long accountId, long logId, String bridgeGroupId,
                   boolean shared, String previousState) {}
    private record Candidate(Long heldGroup, Long assignedGroup, boolean reserved, OffsetDateTime clickedAt, String state) {}
    public record Review(long id, Long invitationId, long accountId, String groupName, OffsetDateTime dispatchedAt,
                         String result, String providerOutcome, boolean canConfirm) {
        public java.time.LocalDateTime localDispatchedAt() {
            return dispatchedAt.atZoneSameInstant(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDateTime();
        }
    }

    @Transactional
    public Attempt dispatch(long invitation, long customer, String uid, String expectedState, GroupService.Group selected,
                            ZaloSharedGroupGrants.Grant selectedGrant, boolean overflow) {
        GroupService.lockAllocation(jdbc);
        long account = ZaloAccountContext.current();
        ZaloMode mode = settings.effectiveMode();
        if (hasDispatch() || !mode.adds() || (mode != ZaloMode.AUTO && !"QUEUED".equals(expectedState)) || !settings.mayAddNow()
                || !contacts.stillEligible(customer)) {
            return null;
        }
        Candidate row = jdbc.sql("""
                        SELECT i.zalo_add_state, i.group_id, i.first_clicked_at,
                            (i.reserved_group_id = :group AND i.reserved_until > now()
                                AND i.status = 'FRIENDED' AND i.first_clicked_at IS NULL) AS reserved,
                            coalesce(i.group_id,
                            CASE WHEN i.reserved_until > now() THEN i.reserved_group_id END) AS held
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.id = :id AND i.customer_id = :customer AND i.account_id = :account AND i.zalo_uid = :uid
                          AND i.status IN ('SENT', 'CLICKED', 'FRIENDED') AND i.zalo_add_state = :state
                          AND (i.tin1_blocked_at IS NULL OR i.tin1_resent_at IS NOT NULL)
                          AND NOT EXISTS (SELECT 1 FROM zalo_group_add_attempt a WHERE a.invitation_id = i.id
                            AND a.state IN ('DISPATCHED', 'REVIEW'))
                          AND NOT EXISTS (SELECT 1 FROM zalo_group_proposal_member m
                            JOIN zalo_group_proposal p ON p.id = m.proposal_id
                            WHERE m.invitation_id = i.id AND p.state = 'CREATING')
                        """ + testMode.onlyTestSql("c.phone") + " FOR UPDATE OF i")
                .param("id", invitation).param("customer", customer).param("account", account).param("group", selected.id())
                .param("uid", uid).param("state", expectedState)
                .query((rs, n) -> new Candidate(rs.getObject("held", Long.class), rs.getObject("group_id", Long.class),
                        rs.getBoolean("reserved"), rs.getObject("first_clicked_at", OffsetDateTime.class), rs.getString("zalo_add_state")))
                .optional().orElse(null);
        var group = groups.find(selected.id()).orElse(null);
        if (row == null || group == null || group.status() != GroupStatus.OPEN || !group.hasLink() || !group.hasZaloId()
                || !Objects.equals(group.inviteLink(), selected.inviteLink())
                || !Objects.equals(group.zaloGroupId(), selected.zaloGroupId())
                || !Objects.equals(group.scope(), selected.scope())
                || (row.heldGroup() != null && row.heldGroup() != group.id())
                || !groups.hasAddSeat(group, row.reserved(), Objects.equals(row.assignedGroup(), group.id()) ? row.clickedAt() : null, overflow)) {
            return null;
        }
        String bridgeGroup = group.zaloGroupId();
        if (group.accountId() != account) {
            if (selectedGrant == null || grants.grant(group.id(), account).filter(selectedGrant::equals).isEmpty()
                    || !testMode.sharedGroupTargetAllowed(account, invitation, group.id(), selectedGrant.bridgeGroupId())) {
                return null;
            }
            bridgeGroup = selectedGrant.bridgeGroupId();
        } else if (selectedGrant != null) {
            return null;
        }
        jdbc.sql("UPDATE invitation SET zalo_add_state = 'FAILED', zalo_add_code = 'DANG_GOI', zalo_add_at = :at WHERE id = :id")
                .param("id", invitation).param("at", now()).update();
        long log = jdbc.sql("""
                        INSERT INTO zalo_auto_log (at, action, invitation_id, group_id, result, actor, account_id)
                        VALUES (:at, :action, :invitation, :group, 'DANG_GOI', 'system', :account) RETURNING id
                        """).param("at", now()).param("action", ZaloAutoSettings.A_ADD).param("invitation", invitation)
                .param("group", group.id()).param("account", account).query(Long.class).single();
        long id = jdbc.sql("""
                        INSERT INTO zalo_group_add_attempt
                          (invitation_id, group_id, account_id, log_id, bridge_group_id, grant_revision, state, previous_state)
                        VALUES (:invitation, :group, :account, :log, :bridgeGroup, :revision, 'DISPATCHED', :previous)
                        RETURNING id
                        """).param("invitation", invitation).param("group", group.id()).param("account", account)
                .param("log", log).param("bridgeGroup", bridgeGroup)
                .param("revision", selectedGrant == null ? null : selectedGrant.revision(), java.sql.Types.BIGINT)
                .param("previous", row.state()).query(Long.class).single();
        return new Attempt(id, invitation, group.id(), account, log, bridgeGroup, selectedGrant != null, row.state());
    }

    boolean hasDispatch() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM zalo_group_add_attempt WHERE account_id = :account AND state = 'DISPATCHED')")
                .param("account", ZaloAccountContext.current()).query(Boolean.class).single();
    }

    @Transactional
    public boolean expireDispatches() {
        GroupService.lockAllocation(jdbc);
        List<Attempt> expired = jdbc.sql("""
                        SELECT * FROM zalo_group_add_attempt WHERE account_id = :account
                            AND state = 'DISPATCHED' AND dispatched_at < now() - interval '2 minutes'
                        """).param("account", ZaloAccountContext.current()).query(ZaloGroupAddAttempts::mapAttempt).list();
        for (Attempt attempt : expired) {
            lockInvitation(attempt.invitationId());
            finish(attempt, "REVIEW", "INTERRUPTED", null, null);
            writeLog(attempt, "REVIEW", "INTERRUPTED");
            setInvitation(attempt, "FAILED", "CAN_DOI_CHIEU");
        }
        return !expired.isEmpty();
    }

    @Transactional
    public boolean complete(Attempt attempt, ZaloGateway.AddResult result) {
        GroupService.lockAllocation(jdbc);
        boolean invitationPresent = lockInvitation(attempt.invitationId());
        jdbc.sql("UPDATE zalo_group_add_attempt SET provider_outcome = :result, provider_code = :code WHERE id = :id AND provider_outcome IS NULL")
                .param("result", result.outcome().name()).param("code", result.code(), java.sql.Types.VARCHAR)
                .param("id", attempt.id()).update();
        if (!isDispatched(attempt.id())) {
            return false;
        }
        boolean consistent = true;
        switch (result.outcome()) {
            case OK -> {
                consistent = invitationPresent && confirmJoined(attempt);
                finish(attempt, consistent ? "JOINED" : "REVIEW", "OK", null, null);
                writeLog(attempt, consistent ? "OK" : "REVIEW", consistent ? null : invitationPresent ? "NHOM_KHONG_KHOP" : "LOI_MOI_DA_XOA");
                setInvitation(attempt, consistent ? "ADDED" : "FAILED", consistent ? null : "CAN_DOI_CHIEU");
            }
            case UNREACHABLE -> {
                finish(attempt, "NOT_ADDED", "UNREACHABLE", null, null);
                writeLog(attempt, "CHUA_GOI", result.code());
                setInvitation(attempt, attempt.previousState(), null);
            }
            case PERSON_ERROR, GROUP_ERROR -> {
                boolean uncertainMembership = attempt.shared() && result.outcome() == ZaloGateway.Outcome.PERSON_ERROR;
                finish(attempt, uncertainMembership ? "REVIEW" : "NOT_ADDED", result.outcome().name(), null, null);
                writeLog(attempt, result.outcome().name(), result.code());
                setInvitation(attempt, result.outcome() == ZaloGateway.Outcome.GROUP_ERROR
                        ? attempt.previousState() : "FAILED", result.outcome() == ZaloGateway.Outcome.GROUP_ERROR
                        ? null : uncertainMembership ? "CAN_DOI_CHIEU" : Objects.requireNonNullElse(result.code(), "LOI_NGUOI"));
            }
            default -> {
                finish(attempt, "REVIEW", result.outcome().name(), null, null);
                writeLog(attempt, result.outcome().name(), result.code());
                setInvitation(attempt, "FAILED", "CAN_DOI_CHIEU");
            }
        }
        return consistent;
    }

    public List<Review> reviews() {
        return jdbc.sql("""
                        SELECT a.id, a.invitation_id, a.account_id, g.name, a.dispatched_at, a.result, a.provider_outcome,
                            (a.invitation_id IS NOT NULL AND (a.state = 'REVIEW' OR a.dispatched_at < now() - interval '2 minutes')) AS can_confirm
                        FROM zalo_group_add_attempt a JOIN zalo_group g ON g.id = a.group_id
                        WHERE a.state IN ('DISPATCHED', 'REVIEW')
                        ORDER BY a.dispatched_at, a.id
                        """).query((rs, n) -> new Review(rs.getLong("id"), rs.getObject("invitation_id", Long.class),
                        rs.getLong("account_id"), rs.getString("name"), rs.getObject("dispatched_at", OffsetDateTime.class),
                        rs.getString("result"), rs.getString("provider_outcome"), rs.getBoolean("can_confirm"))).list();
    }

    @Transactional
    public boolean resolve(long id, boolean joined, String rawNote, String actor) {
        String note = requireNote(rawNote);
        GroupService.lockAllocation(jdbc);
        Attempt attempt = jdbc.sql("""
                        SELECT * FROM zalo_group_add_attempt WHERE id = :id AND account_id = :account
                          AND (state = 'REVIEW' OR (state = 'DISPATCHED' AND dispatched_at < now() - interval '2 minutes'))
                        """).param("id", id).param("account", ZaloAccountContext.current()).query(ZaloGroupAddAttempts::mapAttempt).optional()
                .orElseThrow(() -> new ImportException("Lượt này chưa sẵn sàng hoặc đã được xác nhận."));
        if (!lockInvitation(attempt.invitationId())) {
            throw new ImportException("Lời mời đã xóa. Hãy kiểm đếm nhóm trên Zalo để chốt lượt này.");
        }
        if (joined && !confirmJoined(attempt)) {
            // Giữ kết quả Zalo và trạng thái hiện tại khi hai nhóm xung đột. Vẫn giữ chỗ để người vận hành xử lý.
            finish(attempt, "REVIEW", "CONFIRMED_CONFLICT", note, actor);
            writeLog(attempt, "REVIEW", "NHOM_KHONG_KHOP");
            return false;
        }
        finish(attempt, joined ? "JOINED" : "NOT_ADDED", joined ? "CONFIRMED_JOINED" : "CONFIRMED_NOT_ADDED", note, actor);
        writeLog(attempt, joined ? "OK" : "KHONG_THEM", "XAC_NHAN_TAY");
        setInvitation(attempt, joined ? "ADDED" : "FAILED", joined ? null : "XAC_NHAN_CHUA_VAO");
        return true;
    }

    /** Khi khôi phục DB cũ, OWNER có thể ghi lại tư cách thành viên đã xác minh mà không phát lệnh ra Zalo. */
    @Transactional
    public void recordRestoredMembership(long invitation, long group, long account, String rawNote, String actor) {
        if (!restoreFence.locked()) {
            throw new ImportException("Chỉ ghi lại kết quả thiếu trong bản sao khi khóa phục hồi đang bật.");
        }
        String note = requireNote(rawNote);
        GroupService.lockAllocation(jdbc);
        boolean expected = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM invitation i JOIN zalo_group g ON g.id = :group
                          WHERE i.id = :invitation AND i.account_id = :account
                            AND i.status IN ('SENT', 'CLICKED', 'FRIENDED')
                            AND (i.group_id IS NULL OR i.group_id = g.id)
                            AND NOT EXISTS (SELECT 1 FROM zalo_group_add_attempt a WHERE a.invitation_id = i.id
                              AND a.state IN ('DISPATCHED', 'REVIEW', 'JOINED')))
                        """).param("group", group).param("invitation", invitation).param("account", account)
                .query(Boolean.class).single();
        if (!expected) {
            throw new ImportException("Lời mời hoặc nhóm không khớp, khách đã được ghi nhận, hoặc đã có lượt cần đối chiếu.");
        }
        long log = jdbc.sql("""
                        INSERT INTO zalo_auto_log (action, invitation_id, group_id, result, actor, account_id)
                        VALUES ('KHOI_PHUC_NHOM', :invitation, :group, 'XAC_NHAN_TAY', :actor, :account) RETURNING id
                        """).param("invitation", invitation).param("group", group).param("actor", actor)
                .param("account", account).query(Long.class).single();
        long id = jdbc.sql("""
                        INSERT INTO zalo_group_add_attempt (invitation_id, group_id, account_id, log_id, state, restored_evidence)
                        VALUES (:invitation, :group, :account, :log, 'REVIEW', true) RETURNING id
                        """).param("invitation", invitation).param("group", group).param("log", log)
                .param("account", account).query(Long.class).single();
        var attempt = new Attempt(id, invitation, group, account, log, null, true, null);
        if (!confirmJoined(attempt)) {
            throw new ImportException("Trạng thái lời mời đã thay đổi. Hãy kiểm tra lại trước khi ghi nhận.");
        }
        finish(attempt, "JOINED", "CONFIRMED_RESTORED", note, actor);
        jdbc.sql("UPDATE invitation SET zalo_add_state = 'ADDED', zalo_add_code = NULL, zalo_add_at = :at WHERE id = :id")
                .param("at", now()).param("id", invitation).update();
    }

    private static String requireNote(String rawNote) {
        String note = rawNote == null ? "" : rawNote.strip();
        if (note.isEmpty() || note.length() > 300) {
            throw new ImportException("Ghi lý do xác nhận từ 1 đến 300 ký tự.");
        }
        return note;
    }

    private boolean confirmJoined(Attempt attempt) {
        return invites.finishDispatchedGroupAdd(attempt.id(), attempt.invitationId(), attempt.groupId(),
                attempt.accountId(), attempt.shared());
    }

    private boolean lockInvitation(long id) {
        return jdbc.sql("SELECT id FROM invitation WHERE id = :id FOR UPDATE")
                .param("id", id).query(Long.class).optional().isPresent();
    }

    private boolean isDispatched(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM zalo_group_add_attempt WHERE id = :id AND state = 'DISPATCHED')")
                .param("id", id).query(Boolean.class).single();
    }

    private void finish(Attempt attempt, String state, String result, String note, String actor) {
        jdbc.sql("""
                        UPDATE zalo_group_add_attempt SET state = :state, result = :result,
                          finished_at = CASE WHEN :state = 'REVIEW' THEN NULL ELSE now() END,
                          resolution_note = :note, resolved_by = :actor WHERE id = :id
                        """).param("state", state).param("result", result).param("id", attempt.id())
                .param("note", note, java.sql.Types.VARCHAR).param("actor", actor, java.sql.Types.VARCHAR).update();
    }

    private void writeLog(Attempt attempt, String result, String code) {
        jdbc.sql("UPDATE zalo_auto_log SET result = :result, code = :code WHERE id = :id")
                .param("result", result).param("code", code, java.sql.Types.VARCHAR).param("id", attempt.logId()).update();
    }

    private void setInvitation(Attempt attempt, String state, String code) {
        jdbc.sql("""
                        UPDATE invitation SET zalo_add_state = :state, zalo_add_code = :code, zalo_add_at = :at
                        WHERE id = :id AND account_id = :account AND zalo_add_state = 'FAILED'
                          AND zalo_add_code IN ('DANG_GOI', 'CAN_DOI_CHIEU')
                          AND (status IN ('SENT', 'CLICKED', 'FRIENDED')
                            OR (:state = 'ADDED' AND status = 'JOINED' AND group_id = :group))
                        """).param("state", state).param("code", code, java.sql.Types.VARCHAR).param("at", now())
                .param("id", attempt.invitationId()).param("account", attempt.accountId()).param("group", attempt.groupId()).update();
    }

    private static Attempt mapAttempt(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Attempt(rs.getLong("id"), rs.getLong("invitation_id"), rs.getLong("group_id"), rs.getLong("account_id"),
                rs.getLong("log_id"), rs.getString("bridge_group_id"), rs.getObject("grant_revision") != null,
                rs.getString("previous_state"));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
