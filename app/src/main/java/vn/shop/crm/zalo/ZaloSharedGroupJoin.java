package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.safety.TestMode;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ZaloSharedGroupJoin {
    private static final Logger log = LoggerFactory.getLogger(ZaloSharedGroupJoin.class);

    public record View(long accountId, String state, String code, OffsetDateTime updatedAt) {}
    private record Job(long groupId, long accountId, String code, long groupRevision, long ownerRevision,
                       long actorRevision, long grantRevision, String actor,
                       String state, long attempt) {
        boolean matches(ZaloSharedGroupGrants.EvidenceTarget target) {
            return code.equals(target.ownerGroupId()) && groupRevision == target.groupRevision()
                    && ownerRevision == target.ownerRevision() && actorRevision == target.actorRevision()
                    && grantRevision == target.grantRevision();
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ZaloSharedGroupGrants grants;
    private final ZaloAccountRegistry accounts;
    private final ZaloLink bridge;
    private final TestMode mode;

    public ZaloSharedGroupJoin(JdbcClient jdbc, TransactionTemplate tx, ZaloSharedGroupGrants grants,
                               ZaloAccountRegistry accounts, ZaloLink bridge, TestMode mode) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.grants = grants;
        this.accounts = accounts;
        this.bridge = bridge;
        this.mode = mode;
    }

    public List<View> views(long group) {
        return jdbc.sql("SELECT account_id, state, code, updated_at FROM zalo_shared_group_join WHERE group_id = :group ORDER BY account_id")
                .param("group", group).query((rs, n) -> new View(rs.getLong("account_id"), rs.getString("state"),
                        rs.getString("code"), rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    public void start(long group, long account, boolean activate, String by) {
        if (activate && !grants.enabled()) {
            throw new ImportException("Bật cấu hình nhóm dùng chung toàn hệ thống trước khi yêu cầu tự kích hoạt nhóm.");
        }
        if (!accounts.active().contains(account) || !ZaloAccountContext.call(account, mode::live)) {
            throw new ImportException("Tài khoản được chọn cần đang hoạt động ở chế độ chạy thật.");
        }
        var target = target(group, account);
        verifyOwner(target);
        var current = grants.inspect(account, target.ownerGroupId(), target.actorTag());
        if (current.present() && !current.canAdd()) {
            throw new ImportException("Tài khoản đã ở trong nhóm nhưng chưa có quyền thêm thành viên.");
        }
        if (current.present() && !target.canonicalLink().equals(current.inviteLink())) {
            throw new ImportException("Hai tài khoản chưa xác nhận cùng một nhóm qua link mời.");
        }
        Job job = claim(group, account, activate, by, target);
        if (current.present()) {
            settleVerified(job, target);
            return;
        }
        log.info("Phát yêu cầu tham gia nhóm ADH group={} account={} attempt={}", group, account, job.attempt());
        ZaloSession.JoinGroupResult result;
        try {
            result = ZaloAccountContext.call(account, () -> bridge.joinGroupViaLink(target.ownerGroupId(),
                    target.canonicalLink(), target.actorTag()));
        } catch (RuntimeException e) {
            save(job, "UNKNOWN", "BRIDGE_ERROR");
            return;
        }
        switch (result.outcome()) {
            case NOT_DISPATCHED -> save(job, "NOT_DISPATCHED", result.code());
            case PENDING -> save(job, "PENDING", result.code());
            case UNKNOWN -> save(job, "UNKNOWN", result.code());
            case JOINED -> verifyJob(job);
        }
    }

    private Job claim(long group, long account, boolean activate, String by, ZaloSharedGroupGrants.EvidenceTarget target) {
        return tx.execute(status -> {
            GroupService.lockAllocation(jdbc);
            if (!target.equals(target(group, account))) {
                throw new ImportException("Nhóm hoặc tài khoản đã thay đổi trong lúc xác minh. Hãy mở lại trang nhóm.");
            }
            ZaloSharedGroupGrants.checkLegacyMapping(jdbc, group, account, target.ownerGroupId());
            return jdbc.sql("""
                            INSERT INTO zalo_shared_group_join (group_id, account_id, owner_group_id, group_revision, owner_revision,
                              actor_revision, grant_revision, approved_by, activate, state, next_check_at)
                            VALUES (:group, :account, :code, :groupRevision, :ownerRevision, :actorRevision, :grantRevision,
                              :by, :activate, 'DISPATCHED', now() + interval '2 minutes')
                            ON CONFLICT (group_id, account_id) DO UPDATE SET owner_group_id = excluded.owner_group_id,
                              group_revision = excluded.group_revision, owner_revision = excluded.owner_revision,
                              actor_revision = excluded.actor_revision, grant_revision = excluded.grant_revision,
                              approved_by = excluded.approved_by, activate = excluded.activate, state = 'DISPATCHED',
                              code = NULL, created_at = now(), updated_at = now(), next_check_at = excluded.next_check_at,
                              attempt = zalo_shared_group_join.attempt + 1
                            WHERE zalo_shared_group_join.state IN ('NOT_DISPATCHED', 'VERIFIED', 'CHANGED')
                            RETURNING *
                            """).param("group", group).param("account", account).param("code", target.ownerGroupId())
                    .param("groupRevision", target.groupRevision()).param("ownerRevision", target.ownerRevision())
                    .param("actorRevision", target.actorRevision()).param("grantRevision", target.grantRevision())
                    .param("by", by).param("activate", activate)
                    .query(ZaloSharedGroupJoin::mapJob).optional()
                    .orElseThrow(() -> new ImportException("Đã có yêu cầu tham gia. Hãy xác minh lại tư cách thành viên."));
        });
    }

    public void verify(long group, long account) {
        jdbc.sql("""
                SELECT * FROM zalo_shared_group_join WHERE group_id = :group AND account_id = :account
                  AND (state IN ('PENDING', 'UNKNOWN') OR (state = 'DISPATCHED' AND next_check_at <= now()))
                """).param("group", group).param("account", account).query(ZaloSharedGroupJoin::mapJob).optional()
                .ifPresentOrElse(this::verifyJob, () -> { throw new ImportException("Yêu cầu chưa sẵn sàng để xác minh lại."); });
    }

    @Scheduled(initialDelayString = "${app.zalo.shared-groups.join-check-delay-ms:60000}",
            fixedDelayString = "${app.zalo.shared-groups.join-check-delay-ms:60000}")
    void reconcile() {
        var due = jdbc.sql("""
                SELECT * FROM zalo_shared_group_join WHERE next_check_at <= now()
                ORDER BY next_check_at, group_id, account_id LIMIT 4
                """).query(ZaloSharedGroupJoin::mapJob).list();
        due.forEach(this::verifyJob);
    }

    private void verifyJob(Job job) {
        try {
            var target = target(job.groupId(), job.accountId());
            if (!job.matches(target)) {
                save(job, "CHANGED", "SCOPE_CHANGED");
                return;
            }
            verifyOwner(target);
            var actor = grants.inspect(job.accountId(), job.code(), target.actorTag());
            if (!actor.present()) {
                save(job, "PENDING".equals(job.state()) ? "PENDING" : "UNKNOWN", "MEMBERSHIP_NOT_CONFIRMED");
                return;
            }
            if (!actor.canAdd() || !target.canonicalLink().equals(actor.inviteLink())) {
                save(job, "PENDING", "ADD_PERMISSION_NOT_CONFIRMED");
                return;
            }
            settleVerified(job, target);
        } catch (RuntimeException e) {
            log.warn("Chưa xác minh được lượt tham gia group={} account={} attempt={} type={}",
                    job.groupId(), job.accountId(), job.attempt(), e.getClass().getSimpleName());
            save(job, "PENDING".equals(job.state()) ? "PENDING" : "UNKNOWN", "VERIFY_UNAVAILABLE");
        }
    }

    private void settleVerified(Job job, ZaloSharedGroupGrants.EvidenceTarget target) {
        tx.executeWithoutResult(status -> {
            GroupService.lockAllocation(jdbc);
            if (!job.matches(target(job.groupId(), job.accountId()))) {
                save(job, "CHANGED", "SCOPE_CHANGED");
                return;
            }
            var ready = jdbc.sql("""
                            UPDATE zalo_shared_group_join SET state = 'VERIFIED', code = NULL, next_check_at = NULL, updated_at = now()
                            WHERE group_id = :group AND account_id = :account AND attempt = :attempt
                              AND state IN ('DISPATCHED', 'PENDING', 'UNKNOWN')
                            RETURNING activate
                            """).param("group", job.groupId()).param("account", job.accountId()).param("attempt", job.attempt())
                    .query(Boolean.class).optional();
            if (ready.isEmpty()) {
                return;
            }
            grants.storeApproval(job.groupId(), job.accountId(), job.code(), job.actor(), target);
            if (ready.get() && grants.enabled()) {
                grants.setGroupEnabled(job.groupId(), true);
            }
        });
    }

    void stopActivation(long group) {
        tx.executeWithoutResult(status -> {
            GroupService.lockAllocation(jdbc);
            jdbc.sql("UPDATE zalo_shared_group_join SET activate = false WHERE group_id = :group AND activate")
                    .param("group", group).update();
            grants.setGroupEnabled(group, false);
        });
    }

    private ZaloSharedGroupGrants.EvidenceTarget target(long group, long account) {
        var target = grants.evidenceTarget(group, account);
        grants.requireVerifiable(target, account);
        return target;
    }

    private void verifyOwner(ZaloSharedGroupGrants.EvidenceTarget target) {
        var owner = grants.inspect(target.ownerId(), target.ownerGroupId(), target.ownerTag());
        if (!owner.present() || !target.canonicalLink().equals(owner.inviteLink())) {
            throw new ImportException("Tài khoản chủ chưa xác nhận link mời của đúng nhóm.");
        }
    }

    private void save(Job job, String state, String code) {
        jdbc.sql("""
                UPDATE zalo_shared_group_join SET state = :state, code = :code, updated_at = now(),
                  next_check_at = CASE WHEN :state IN ('PENDING', 'UNKNOWN') AND created_at > now() - interval '24 hours'
                                       THEN now() + interval '2 minutes' END
                WHERE group_id = :group AND account_id = :account AND attempt = :attempt
                  AND state IN ('DISPATCHED', 'PENDING', 'UNKNOWN')
                """).param("group", job.groupId()).param("account", job.accountId()).param("attempt", job.attempt())
                .param("state", state).param("code", code, java.sql.Types.VARCHAR).update();
    }

    private static Job mapJob(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Job(rs.getLong("group_id"), rs.getLong("account_id"), rs.getString("owner_group_id"),
                rs.getLong("group_revision"), rs.getLong("owner_revision"), rs.getLong("actor_revision"),
                rs.getLong("grant_revision"), rs.getString("approved_by"),
                rs.getString("state"), rs.getLong("attempt"));
    }
}
