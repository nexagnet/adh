package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.invite.GroupService;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ánh xạ mã nhóm theo phiên cho đúng cặp nhóm và tài khoản có quyền thêm khách. */
@Service
public class ZaloSharedGroupGrants {
    private static final Logger log = LoggerFactory.getLogger(ZaloSharedGroupGrants.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String ELIGIBLE = """
            FROM zalo_group_add_grant a
            JOIN zalo_group g ON g.id = a.group_id
            JOIN zalo_account actor ON actor.id = a.account_id
            JOIN zalo_account owner ON owner.id = g.account_id
            WHERE a.account_id = :account AND a.state = 'APPROVED'
                AND g.shared_add_enabled AND a.account_id <> g.account_id
                AND a.account_tag = actor.account_tag AND a.owner_tag = owner.account_tag
                AND actor.enabled AND owner.enabled
                AND a.verified_at >= now() - interval '7 days'
                AND NOT EXISTS (SELECT 1 FROM zalo_group other WHERE other.id <> g.id AND other.invite_link = g.invite_link)
            """;

    public record Grant(long groupId, long accountId, String bridgeGroupId, String accountTag, long revision) {
    }

    public record View(long accountId, String accountLabel, String bridgeGroupId, long revision, String state,
                       String approvedBy, LocalDateTime verifiedAt, boolean expired) {
    }

    public record GroupView(boolean sharing, List<View> grants) {
    }

    private record EvidenceTarget(long ownerId, String ownerGroupId, String canonicalLink, String ownerTag,
                                  String actorTag, long groupRevision, long ownerRevision, long actorRevision,
                                  long grantRevision, boolean enabledAccounts) {
    }

    private final JdbcClient jdbc;
    private final ZaloLink bridge;
    private final ZaloAccountRegistry accounts;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public ZaloSharedGroupGrants(JdbcClient jdbc, ZaloLink bridge, ZaloAccountRegistry accounts, TransactionTemplate tx,
                                 @Value("${app.zalo.shared-groups.enabled:${APP_ZALO_SHARED_GROUPS_ENABLED:false}}")
                                 boolean enabled) {
        this.jdbc = jdbc;
        this.bridge = bridge;
        this.accounts = accounts;
        this.tx = tx;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<Grant> grant(long groupId, long accountId) {
        if (!enabled) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT a.group_id, a.account_id, a.bridge_group_id, a.account_tag, a.revision " + ELIGIBLE
                        + " AND a.group_id = :group")
                .param("account", accountId).param("group", groupId)
                .query((rs, row) -> new Grant(rs.getLong("group_id"), rs.getLong("account_id"),
                        rs.getString("bridge_group_id"), rs.getString("account_tag"), rs.getLong("revision"))).optional();
    }

    public List<Long> grantedGroupIds(long accountId) {
        if (!enabled) {
            return List.of();
        }
        return jdbc.sql("SELECT a.group_id " + ELIGIBLE + " ORDER BY a.group_id")
                .param("account", accountId).query(Long.class).list();
    }

    public GroupView view(long groupId) {
        boolean sharing = jdbc.sql("SELECT shared_add_enabled FROM zalo_group WHERE id = :group")
                .param("group", groupId).query(Boolean.class).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        var rows = jdbc.sql("""
                        SELECT a.*, z.label, a.verified_at < now() - interval '7 days' AS expired
                        FROM zalo_group_add_grant a JOIN zalo_account z ON z.id = a.account_id
                        WHERE a.group_id = :group ORDER BY a.account_id
                        """).param("group", groupId)
                .query((rs, row) -> new View(rs.getLong("account_id"), rs.getString("label"), rs.getString("bridge_group_id"),
                        rs.getLong("revision"), rs.getString("state"), rs.getString("approved_by"),
                        rs.getObject("verified_at", OffsetDateTime.class).atZoneSameInstant(VN).toLocalDateTime(), rs.getBoolean("expired"))).list();
        return new GroupView(sharing, rows);
    }

    public void setGroupEnabled(long groupId, boolean sharing) {
        tx.executeWithoutResult(status -> {
            GroupService.lockAllocation(jdbc);
            int changed = jdbc.sql("UPDATE zalo_group SET shared_add_enabled = :sharing WHERE id = :group")
                    .param("sharing", sharing).param("group", groupId).update();
            if (changed != 1) {
                throw new ImportException("Không tìm thấy nhóm.");
            }
        });
    }

    public void approve(long groupId, long accountId, String rawGroupId, String approvedBy) {
        String groupCode = rawGroupId == null ? "" : rawGroupId.strip();
        if (!groupCode.matches("[0-9]{1,32}")) {
            throw new ImportException("Nhập mã nhóm đúng như tài khoản được cấp quyền nhìn thấy.");
        }
        EvidenceTarget target = evidenceTarget(groupId, accountId);
        requireVerifiable(target, accountId);
        var owner = inspect(target.ownerId(), target.ownerGroupId(), target.ownerTag());
        var actor = inspect(accountId, groupCode, target.actorTag());
        if (!owner.present() || !actor.canAdd()) {
            throw new ImportException("Kiểm tra tài khoản chủ còn ở trong nhóm và tài khoản được cấp có quyền thêm người.");
        }
        if (!validEvidenceLink(target.canonicalLink())
                || !target.canonicalLink().equals(owner.inviteLink())
                || !target.canonicalLink().equals(actor.inviteLink())) {
            throw new ImportException("Hai phiên Zalo chưa xác nhận cùng link mời đang lưu ở Adh. Hãy kiểm tra lại nhóm.");
        }

        tx.executeWithoutResult(status -> {
            GroupService.lockAllocation(jdbc);
            if (!target.equals(evidenceTarget(groupId, accountId))) {
                throw new ImportException("Nhóm hoặc tài khoản đã đổi trong lúc kiểm tra. Hãy duyệt lại.");
            }
            boolean duplicate = jdbc.sql("SELECT EXISTS (SELECT 1 FROM zalo_group WHERE id <> :group AND invite_link = :link)")
                    .param("group", groupId).param("link", target.canonicalLink()).query(Boolean.class).single();
            if (duplicate) {
                throw new ImportException("Link này đã có trong một bản ghi ADH khác. Hãy rà soát nhóm trùng trước khi cấp quyền.");
            }
            checkLegacyMapping(jdbc, groupId, accountId, groupCode);
            jdbc.sql("""
                            INSERT INTO zalo_group_add_grant
                                (group_id, account_id, bridge_group_id, account_tag, owner_tag, state,
                                 approved_by, verified_at, changed_by)
                            VALUES (:group, :account, :bridge, :tag, :owner, 'APPROVED', :by, now(), :by)
                            ON CONFLICT (group_id, account_id) DO UPDATE SET
                                bridge_group_id = excluded.bridge_group_id, account_tag = excluded.account_tag,
                                owner_tag = excluded.owner_tag, state = excluded.state, approved_by = excluded.approved_by,
                                verified_at = excluded.verified_at, changed_by = excluded.changed_by, changed_at = now(),
                                revision = zalo_group_add_grant.revision + 1
                            """)
                    .param("group", groupId).param("account", accountId).param("bridge", groupCode)
                    .param("tag", target.actorTag()).param("owner", target.ownerTag()).param("by", approvedBy).update();
        });
        log.info("Đã xác minh quyền thêm nhóm Adh {} cho tài khoản {}", groupId, accountId);
    }

    public void revoke(long groupId, long accountId, long revision, String actor) {
        if (!changeState(groupId, accountId, revision, "REVOKED", actor)) {
            throw new ImportException("Quyền đã thay đổi. Hãy tải lại trang để kiểm tra.");
        }
    }

    public void suspend(long groupId, long accountId, long revision) {
        if (changeState(groupId, accountId, revision, "SUSPENDED", "system")) {
            log.warn("Đã tạm ngưng quyền thêm nhóm Adh {} của tài khoản {}", groupId, accountId);
        }
    }

    private boolean changeState(long groupId, long accountId, long revision, String state, String actor) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            GroupService.lockAllocation(jdbc);
            return jdbc.sql("""
                            UPDATE zalo_group_add_grant
                            SET state = :state, revision = revision + 1, changed_by = :by, changed_at = now()
                            WHERE group_id = :group AND account_id = :account AND revision = :revision
                              AND state <> 'REVOKED' AND (:state = 'REVOKED' OR state = 'APPROVED')
                            """).param("group", groupId).param("account", accountId).param("revision", revision)
                    .param("state", state).param("by", actor).update() == 1;
        }));
    }

    /**
     * Cả gán mã nhóm gốc và cấp quyền cùng kiểm chéo hai nguồn sau khi giữ khóa cấp chỗ PostgreSQL.
     * Cho phép lưu lại chính ánh xạ đang có. Chủ nhóm và người được cấp quyền không bao giờ dùng trùng một tài khoản.
     */
    public static void checkLegacyMapping(JdbcClient jdbc, long groupId, long accountId, String bridgeGroupId) {
        if (bridgeGroupId == null || bridgeGroupId.isBlank()) {
            return;
        }
        boolean conflict = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM zalo_group
                                       WHERE account_id = :account AND zalo_group_id = :bridge AND id <> :group)
                            OR EXISTS (SELECT 1 FROM zalo_group_add_grant
                                       WHERE account_id = :account AND bridge_group_id = :bridge
                                         AND group_id <> :group AND state = 'APPROVED')
                        """).param("account", accountId).param("bridge", bridgeGroupId).param("group", groupId)
                .query(Boolean.class).single();
        if (conflict) {
            throw new ImportException("Mã này của tài khoản đã được dùng cho nhóm Adh khác. Hãy rà soát nhóm trùng.");
        }
    }

    private EvidenceTarget evidenceTarget(long groupId, long accountId) {
        return jdbc.sql("""
                        SELECT g.account_id, g.zalo_group_id, g.invite_link, owner.account_tag AS owner_tag,
                               actor.account_tag AS actor_tag, g.shared_identity_revision AS group_revision,
                               owner.shared_identity_revision AS owner_revision, actor.shared_identity_revision AS actor_revision,
                               coalesce(a.revision, 0) AS grant_revision, owner.enabled AND actor.enabled AS enabled
                        FROM zalo_group g JOIN zalo_account owner ON owner.id = g.account_id
                        JOIN zalo_account actor ON actor.id = :account
                        LEFT JOIN zalo_group_add_grant a ON a.group_id = g.id AND a.account_id = actor.id
                        WHERE g.id = :group
                        """).param("account", accountId).param("group", groupId)
                .query((rs, row) -> new EvidenceTarget(rs.getLong("account_id"), rs.getString("zalo_group_id"),
                        rs.getString("invite_link"), rs.getString("owner_tag"), rs.getString("actor_tag"),
                        rs.getLong("group_revision"), rs.getLong("owner_revision"), rs.getLong("actor_revision"),
                        rs.getLong("grant_revision"), rs.getBoolean("enabled"))).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy nhóm hoặc tài khoản phụ."));
    }

    private void requireVerifiable(EvidenceTarget target, long accountId) {
        if (target.ownerId() == accountId) {
            throw new ImportException("Tài khoản chủ nhóm đã có đường thêm khách riêng.");
        }
        if (!target.enabledAccounts() || !accounts.selectable(accountId) || !accounts.selectable(target.ownerId())
                || target.actorTag() == null || target.ownerTag() == null || Objects.equals(target.actorTag(), target.ownerTag())
                || target.ownerGroupId() == null || !target.ownerGroupId().matches("[0-9]{1,32}")
                || !validEvidenceLink(target.canonicalLink())) {
            throw new ImportException("Cần hai tài khoản đã ghim phiên và bật hoạt động, mã nhóm gốc và link mời nhóm hợp lệ.");
        }
    }

    private ZaloSession.GroupInspection inspect(long account, String groupCode, String expectedTag) {
        var result = ZaloAccountContext.call(account, () -> bridge.inspectGroup(groupCode));
        if (result == null || !result.ok() || !expectedTag.equals(result.accountTag())) {
            throw new ImportException("Chưa lấy được bằng chứng nhóm qua đúng phiên Zalo. Hãy kiểm tra phiên rồi thử lại.");
        }
        return result;
    }

    private static boolean validEvidenceLink(String link) {
        return link != null && link.matches("https://zalo\\.me/g/[a-zA-Z0-9_-]{1,100}");
    }
}
