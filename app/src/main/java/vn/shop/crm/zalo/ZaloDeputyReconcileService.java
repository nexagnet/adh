package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.safety.TestMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Đối chiếu nhóm đang mở bằng tài khoản sở hữu, có bằng chứng Zalo trước và sau khi bổ nhiệm. */
@Service
public class ZaloDeputyReconcileService implements ZaloTickHook {
    static final String ENABLED = "zalo.deputy.reconcile_enabled";
    private static final String NEXT_SCAN = "zalo.deputy.reconcile_next_at";
    private static final Duration SCAN_GAP = Duration.ofMinutes(10);
    private static final Duration VERIFY_GAP = Duration.ofMinutes(15);
    private static final Duration WRITE_GAP = Duration.ofHours(1);
    private static final Duration VERIFIED_GAP = Duration.ofDays(1);
    private static final int MAX_WRITES = 3;
    private static final Set<String> BEFORE_WRITE = Set.of("WINDOW", "BUSY", "NOT_ACTIVE", "NOT_CONNECTED", "LOGIN_REQUIRED",
            "NO_OWNER", "ROLE_UNVERIFIED", "MEMBERSHIP_CHANGED", "INSPECT_FAILED", "STALE_TARGET");

    public record GroupStatus(long groupId, String name, String status, String code, OffsetDateTime checkedAt) {
    }

    private record Config(String uid, String tag, String blocker) {
    }

    private record Target(long id, String name, String groupCode, long revision, String storedHash,
                          String state, String code, OffsetDateTime checkedAt, OffsetDateTime nextAt,
                          OffsetDateTime lastWriteAt, int attempts, boolean ambiguous) {
        String hash(Config cfg) {
            try {
                String value = cfg.uid() + ":" + cfg.tag() + ":" + groupCode + ":" + revision;
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        boolean due(Config cfg, OffsetDateTime now) {
            return validGroup() && !ambiguous && (!hash(cfg).equals(storedHash) || !nextAt.isAfter(now));
        }

        boolean validGroup() {
            return groupCode != null && groupCode.matches("[0-9]{1,32}");
        }
    }

    private record Job(Target group, Config config, long account, String hash, OffsetDateTime lease,
                       OffsetDateTime lastWrite, int attempts) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AppSettings settings;
    private final ZaloAutoSettings auto;
    private final ZaloGroupSettings groupSettings;
    private final ZaloAccountRegistry accounts;
    private final ZaloBridgeIdentity identity;
    private final ZaloLink link;
    private final TestMode testMode;
    private final Clock clock;

    ZaloDeputyReconcileService(JdbcClient jdbc, TransactionTemplate tx, AppSettings settings, ZaloAutoSettings auto,
                              ZaloGroupSettings groupSettings, ZaloAccountRegistry accounts, ZaloBridgeIdentity identity,
                              ZaloLink link, TestMode testMode, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.auto = auto;
        this.groupSettings = groupSettings;
        this.accounts = accounts;
        this.identity = identity;
        this.link = link;
        this.testMode = testMode;
        this.clock = clock;
    }

    public boolean enabled() {
        return settings.flag(ENABLED);
    }

    boolean managesBackfill() {
        return settings.get(ENABLED).isPresent();
    }

    public String configurationBlocker() {
        return config(ZaloAccountContext.current()).blocker();
    }

    public void setEnabled(boolean on, String by) {
        settings.set(ENABLED, Boolean.toString(on), by);
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "PHO_NHOM_DOI_CHIEU", on ? "ON" : "OFF", by);
    }

    public List<GroupStatus> groupStatuses() {
        long account = ZaloAccountContext.current();
        Config cfg = config(account);
        boolean on = enabled();
        boolean active = on && accounts.active().contains(account);
        return targets(account).stream().map(g -> status(g, cfg, on, active)).toList();
    }

    private GroupStatus status(Target g, Config cfg, boolean on, boolean active) {
        if (!g.validGroup()) return new GroupStatus(g.id(), g.name(), "NO_GROUP_ID", null, null);
        if (g.ambiguous()) return new GroupStatus(g.id(), g.name(), "BLOCKED", "DUPLICATE_GROUP", null);
        boolean matching = g.hash(cfg).equals(g.storedHash());
        OffsetDateTime checked = matching ? g.checkedAt() : null;
        if (!on) return new GroupStatus(g.id(), g.name(), "DISABLED", null, checked);
        if (cfg.blocker() != null) return new GroupStatus(g.id(), g.name(), cfg.blocker(), null, checked);
        if (!active) return new GroupStatus(g.id(), g.name(), "BLOCKED", "ACCOUNT_DISABLED", checked);
        return matching ? new GroupStatus(g.id(), g.name(), g.state(), g.code(), checked)
                : new GroupStatus(g.id(), g.name(), "PENDING", null, null);
    }

    @Override
    public void tick() {
        long account = ZaloAccountContext.current();
        if (!mayRun(account) || !scanDue()) return;
        Config cfg = config(account);
        if (cfg.blocker() != null) return;
        Job job = tx.execute(status -> claim(account, cfg));
        if (job != null) inspect(job);
    }

    private boolean mayRun(long account) {
        LocalTime now = LocalTime.now(clock);
        return enabled() && auto.props().enabled() && !auto.tripped()
                && !now.isBefore(groupSettings.props().windowStart()) && now.isBefore(groupSettings.props().windowEnd())
                && accounts.active().contains(account);
    }

    private Config config(long account) {
        String uidKey = ZaloAccountContext.settingKey(ZaloDeputyService.UID);
        String tagKey = ZaloAccountContext.settingKey(ZaloDeputyService.ACCT);
        var configured = jdbc.sql("SELECT key, value FROM app_setting WHERE key IN (:uid, :tag)")
                .param("uid", uidKey).param("tag", tagKey)
                .query((rs, n) -> java.util.Map.entry(rs.getString(1), rs.getString(2))).list();
        String uid = configured.stream().filter(row -> uidKey.equals(row.getKey())).map(java.util.Map.Entry::getValue).findFirst().orElse("").strip();
        String tag = configured.stream().filter(row -> tagKey.equals(row.getKey())).map(java.util.Map.Entry::getValue).findFirst().orElse("").strip();
        if (!uid.matches("[0-9]{1,32}")) return new Config(uid, tag, "NO_CONFIG");
        String pinned = identity.pinnedTag(account);
        if (!tag.matches("[0-9a-f]{12}") || pinned == null) return new Config(uid, tag, "UNVERIFIED_CONFIG");
        return new Config(uid, tag, tag.equals(pinned) ? null : "STALE_CONFIG");
    }

    private List<Target> targets(long account) {
        return jdbc.sql("""
                SELECT g.id, g.name, g.zalo_group_id, g.shared_identity_revision, p.*,
                       EXISTS (SELECT 1 FROM zalo_group other WHERE other.id <> g.id
                               AND other.account_id = g.account_id AND other.zalo_group_id = g.zalo_group_id) AS ambiguous
                FROM zalo_group g LEFT JOIN zalo_group_deputy_check p ON p.group_id = g.id
                WHERE g.account_id = :account AND g.status = 'OPEN'
                ORDER BY CASE WHEN g.account_id = 2 AND lower(g.name) LIKE '%tây ninh - 01' THEN 0 ELSE 1 END, g.id
                """).param("account", account).query((rs, row) -> target(rs)).list();
    }

    private static Target target(ResultSet rs) throws SQLException {
        return new Target(rs.getLong("id"), rs.getString("name"), rs.getString("zalo_group_id"),
                rs.getLong("shared_identity_revision"), rs.getString("target_hash"), rs.getString("state"), rs.getString("code"),
                rs.getObject("checked_at", OffsetDateTime.class), rs.getObject("next_check_at", OffsetDateTime.class),
                rs.getObject("last_write_at", OffsetDateTime.class), rs.getInt("write_attempts"), rs.getBoolean("ambiguous"));
    }

    private boolean scanDue() {
        return settings.get(NEXT_SCAN).map(Instant::parse).map(next -> !clock.instant().isBefore(next)).orElse(true);
    }

    private Job claim(long account, Config cfg) {
        // Khóa theo tài khoản để hai tiến trình không cùng nhận một nhịp; thả khóa trước khi gọi mạng.
        if (jdbc.sql("SELECT id FROM zalo_account WHERE id = :id FOR UPDATE").param("id", account)
                .query(Long.class).optional().isEmpty() || !scanDue() || !enabled() || !cfg.equals(config(account))) return null;
        OffsetDateTime now = now();
        // Khi cùng chưa đọc, giữ thứ tự ưu tiên từ truy vấn. Sau đó chọn lần đọc cũ nhất để nhóm cuối vẫn tới lượt.
        Target g = targets(account).stream().filter(t -> t.due(cfg, now))
                .min(Comparator.comparing(t -> t.hash(cfg).equals(t.storedHash()) ? t.checkedAt() : null,
                        Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
        settings.set(NEXT_SCAN, now.plus(SCAN_GAP).toInstant().toString(), "system");
        if (g == null) return null;
        String hash = g.hash(cfg);
        boolean same = hash.equals(g.storedHash());
        OffsetDateTime lease = now.plus(SCAN_GAP);
        jdbc.sql("""
                INSERT INTO zalo_group_deputy_check (group_id, target_hash, state, next_check_at)
                VALUES (:id, :hash, 'PENDING', :lease)
                ON CONFLICT (group_id) DO UPDATE SET target_hash = EXCLUDED.target_hash, next_check_at = EXCLUDED.next_check_at,
                    state = CASE WHEN :same THEN zalo_group_deputy_check.state ELSE 'PENDING' END,
                    code = CASE WHEN :same THEN zalo_group_deputy_check.code END,
                    checked_at = CASE WHEN :same THEN zalo_group_deputy_check.checked_at END,
                    last_write_at = CASE WHEN :same THEN zalo_group_deputy_check.last_write_at END,
                    write_attempts = CASE WHEN :same THEN zalo_group_deputy_check.write_attempts ELSE 0 END
                """).param("id", g.id()).param("hash", hash).param("lease", lease).param("same", same).update();
        return new Job(g, cfg, account, hash, lease, same ? g.lastWriteAt() : null, same ? g.attempts() : 0);
    }

    private void inspect(Job job) {
        ZaloGateway.Status current = link.status();
        if (!ZaloBridgeIdentity.usable(current) || !job.config().tag().equals(current.accountTag())) {
            finish(job, "CHECK_FAILED", "BRIDGE_NOT_READY", WRITE_GAP, job.attempts());
            return;
        }
        var evidence = link.inspectDeputy(job.group().groupCode(), job.config().uid(), job.config().tag());
        if (!"OK".equals(evidence.outcome()) || !job.group().groupCode().equals(evidence.groupId())) {
            finish(job, "CHECK_FAILED", code(evidence.code(), "INSPECT_FAILED"), WRITE_GAP, job.attempts());
        } else if ("ADMIN".equals(evidence.role())) {
            finish(job, "VERIFIED", null, VERIFIED_GAP, 0);
        } else if (!evidence.owner()) {
            finish(job, "BLOCKED", "NO_OWNER", VERIFIED_GAP, job.attempts());
        } else if ("MEMBER".equals(evidence.role()) || "ABSENT".equals(evidence.role())) {
            apply(job, "MEMBER".equals(evidence.role()));
        } else {
            finish(job, "CHECK_FAILED", "ROLE_UNVERIFIED", WRITE_GAP, job.attempts());
        }
    }

    private void apply(Job job, boolean member) {
        if (job.attempts() >= MAX_WRITES) {
            finish(job, "LIMIT_REACHED", job.group().code(), VERIFIED_GAP, job.attempts());
            return;
        }
        if (!testMode.live()) {
            finish(job, "MISSING", "TEST_MODE", WRITE_GAP, job.attempts());
            return;
        }
        if (job.lastWrite() != null && now().isBefore(job.lastWrite().plus(WRITE_GAP))) {
            finish(job, "PENDING_VERIFY", job.group().code(), WRITE_GAP, job.attempts());
            return;
        }
        if (!stillEligible(job) || claimWrite(job) != 1) return;
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "PHO_NHOM_GOI", "GROUP_" + job.group().id(), "system");
        var result = link.reconcileDeputy(job.group().groupCode(), job.config().uid(), member, job.config().tag(), () -> stillEligible(job));
        String fallback = result.outcome() == ZaloGroupMaker.GroupOutcome.OK ? "DEPUTY_FAILED" : result.outcome().name();
        String code = code(result.code(), result.ok() ? null : fallback);
        boolean skipped = result.outcome() == ZaloGroupMaker.GroupOutcome.UNREACHABLE
                || result.outcome() == ZaloGroupMaker.GroupOutcome.RATE_LIMITED
                || (code != null && BEFORE_WRITE.contains(code));
        finish(job, skipped ? "BLOCKED" : "PENDING_VERIFY", code, skipped ? WRITE_GAP : VERIFY_GAP,
                skipped ? job.attempts() : job.attempts() + 1);
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "PHO_NHOM_KET_QUA", "GROUP_" + job.group().id() + ":" + (code == null ? "CHO_DOI_CHIEU" : code), "system");
        if (!skipped && Set.of(ZaloGroupMaker.GroupOutcome.SESSION_LOST, ZaloGroupMaker.GroupOutcome.RESTRICTED,
                ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR).contains(result.outcome())) {
            try {
                auto.trip(result.outcome().name() + ":" + code, "system");
            } finally {
                link.stop();
            }
        }
    }

    private boolean stillEligible(Job job) {
        return now().isBefore(job.lease()) && mayRun(job.account()) && testMode.live() && job.config().equals(config(job.account()))
                && jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM zalo_group g JOIN zalo_group_deputy_check p ON p.group_id = g.id
                            WHERE g.id = :id AND g.account_id = :account AND g.status = 'OPEN'
                              AND g.zalo_group_id = :groupCode AND g.shared_identity_revision = :revision
                              AND p.target_hash = :hash AND p.next_check_at = :lease
                              AND NOT EXISTS (SELECT 1 FROM zalo_group other WHERE other.id <> g.id
                                              AND other.account_id = g.account_id AND other.zalo_group_id = g.zalo_group_id)
                        )
                        """).param("id", job.group().id()).param("account", job.account())
                        .param("groupCode", job.group().groupCode()).param("revision", job.group().revision())
                        .param("hash", job.hash()).param("lease", job.lease()).query(Boolean.class).single();
    }

    private int claimWrite(Job job) {
        return jdbc.sql("""
                UPDATE zalo_group_deputy_check SET state = 'PENDING_VERIFY', code = NULL, checked_at = :now,
                    last_write_at = :now, write_attempts = write_attempts + 1
                WHERE group_id = :id AND target_hash = :hash AND next_check_at = :lease AND write_attempts = :count
                """).param("id", job.group().id()).param("hash", job.hash()).param("lease", job.lease())
                .param("now", now()).param("count", job.attempts()).update();
    }

    private void finish(Job job, String state, String code, Duration gap, int attempts) {
        OffsetDateTime checked = now();
        jdbc.sql("""
                UPDATE zalo_group_deputy_check SET state = :state, code = :code, checked_at = :checked,
                    next_check_at = :next, write_attempts = :count
                WHERE group_id = :id AND target_hash = :hash AND next_check_at = :lease
                """).param("id", job.group().id()).param("hash", job.hash()).param("lease", job.lease())
                .param("state", state).param("code", code, java.sql.Types.VARCHAR)
                .param("checked", checked).param("next", checked.plus(gap)).param("count", attempts).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String code(String raw, String fallback) {
        String safe = ZcaBridgeGateway.safeCode(raw);
        return safe == null ? fallback : safe;
    }
}
