package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.safety.TestMode;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class CampaignFriendReconciliation {
    private static final Logger log = LoggerFactory.getLogger(CampaignFriendReconciliation.class);
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String SCOPE = "i.campaign_id = :campaign AND i.account_id = :account AND "
            + ZaloFriendReconciliation.ELIGIBLE;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ZaloAccountRegistry accounts;
    private final TestMode mode;
    private final ZaloLink bridge;
    private final ZaloFriendReconciliation reconciliation;
    private final boolean enabled;

    CampaignFriendReconciliation(JdbcClient jdbc, TransactionTemplate tx, ZaloAccountRegistry accounts,
                                 TestMode mode, ZaloLink bridge, ZaloFriendReconciliation reconciliation,
                                 @Value("${app.zalo.reconcile.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.accounts = accounts;
        this.mode = mode;
        this.bridge = bridge;
        this.reconciliation = reconciliation;
        this.enabled = enabled;
    }

    public record Account(long id, String label, boolean active, long eligible, Status job) {}

    public record Status(boolean running, long total, long processed, long failed, long skipped, OffsetDateTime latestReadAt,
                         OffsetDateTime nextReadAt, String message) {}

    private record Job(long id, long campaign, UUID lease) {}

    private record Item(long id, OffsetDateTime attempt, int retries) {}

    public List<Account> accounts(long campaign) {
        List<Long> active = enabled ? accounts.active() : List.of();
        return accounts.views().stream().map(a -> new Account(a.id(), a.label(), active.contains(a.id()),
                scope("SELECT count(*) FROM invitation i WHERE " + SCOPE, campaign, a.id()).query(Long.class).single(),
                status(campaign, a.id()))).toList();
    }

    public Status status(long campaign, long account) {
        OffsetDateTime lastConfirmed = jdbc.sql("""
                SELECT max(o.last_confirmed_at) AS last_read_at
                FROM invitation i JOIN zalo_friend_observation o ON o.invitation_id = i.id AND o.account_id = i.account_id
                WHERE i.campaign_id = :campaign AND i.account_id = :account
                  AND o.last_confirmed_at >= i.friend_request_at
                  AND (o.attempt_at IS NULL OR o.attempt_at = i.friend_request_at)
                HAVING count(*) > 0
                """).param("campaign", campaign).param("account", account)
                .query((rs, n) -> local(rs.getObject("last_read_at", OffsetDateTime.class))).optional().orElse(null);
        return jdbc.sql("""
                SELECT j.finished_at IS NULL AS running, j.next_at, j.waiting_reason,
                       count(*) AS total, count(t.result) AS processed,
                       count(*) FILTER (WHERE t.result = 'FAILED') AS failed,
                       count(*) FILTER (WHERE t.result = 'SKIPPED') AS skipped
                FROM zalo_friend_read_job j JOIN zalo_friend_read_item t ON t.job_id = j.id
                WHERE j.campaign_id = :campaign AND j.account_id = :account
                GROUP BY j.id
                """).param("campaign", campaign).param("account", account).query((rs, n) -> {
            boolean running = rs.getBoolean("running");
            String wait = rs.getString("waiting_reason");
            return new Status(running, rs.getLong("total"), rs.getLong("processed"), rs.getLong("failed"),
                    rs.getLong("skipped"), lastConfirmed,
                    running && wait != null ? local(rs.getObject("next_at", OffsetDateTime.class)) : null,
                    statusMessage(running, wait, rs.getLong("failed"), rs.getLong("skipped")));
        }).optional().orElse(new Status(false, 0, 0, 0, 0, lastConfirmed, null, "Chưa chạy lượt đối chiếu này."));
    }

    public void start(long campaign, long account) {
        if (!enabled || !accounts.active().contains(account)) {
            throw new ImportException("Đối chiếu chưa được bật cho tài khoản phụ này.");
        }
        try {
            tx.executeWithoutResult(t -> enqueue(campaign, account));
        } catch (DuplicateKeyException exception) {
            if (status(campaign, account).running()) {
                return;
            }
            throw new ImportException("Tài khoản phụ đang đối chiếu chiến dịch khác. Hãy đợi lượt đó hoàn tất.");
        }
    }

    private void enqueue(long campaign, long account) {
        if (status(campaign, account).running()) {
            return;
        }
        if (jdbc.sql("SELECT id FROM invitation_campaign WHERE id = :campaign").param("campaign", campaign)
                .query(Long.class).optional().isEmpty()) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
        jdbc.sql("""
                DELETE FROM zalo_friend_read_job
                WHERE campaign_id = :campaign AND account_id = :account AND finished_at IS NOT NULL
                """).param("campaign", campaign).param("account", account).update();
        long id = jdbc.sql("""
                INSERT INTO zalo_friend_read_job(campaign_id, account_id) VALUES (:campaign, :account) RETURNING id
                """).param("campaign", campaign).param("account", account).query(Long.class).single();
        int added = scope("""
                INSERT INTO zalo_friend_read_item(job_id, invitation_id, attempt_at)
                SELECT :job, i.id, i.friend_request_at FROM invitation i WHERE
                """ + SCOPE, campaign, account).param("job", id).update();
        if (added == 0) {
            throw new ImportException("Chiến dịch chưa có lời mời đủ điều kiện đối chiếu cho tài khoản phụ này.");
        }
    }

    List<Long> dueAccounts() {
        return jdbc.sql("""
                SELECT account_id FROM zalo_friend_read_job WHERE finished_at IS NULL AND next_at <= now() ORDER BY account_id
                """).query(Long.class).list();
    }

    void work(long account) {
        if (!enabled || !accounts.active().contains(account)) {
            return;
        }
        ZaloAccountContext.run(account, () -> claim(account).ifPresent(job -> read(account, job)));
    }

    private java.util.Optional<Job> claim(long account) {
        // Each claim expires if the process stops mid-read. A new claim fences any late result.
        return jdbc.sql("""
                UPDATE zalo_friend_read_job
                SET lease_token = :lease, next_at = now() + interval '90 seconds'
                WHERE account_id = :account AND finished_at IS NULL AND next_at <= now()
                RETURNING id, campaign_id, lease_token
                """).param("lease", UUID.randomUUID()).param("account", account)
                .query((rs, n) -> new Job(rs.getLong("id"), rs.getLong("campaign_id"), rs.getObject("lease_token", UUID.class))).optional();
    }

    private void read(long account, Job job) {
        Item item = jdbc.sql("""
                SELECT invitation_id, attempt_at, retries FROM zalo_friend_read_item
                WHERE job_id = :job AND result IS NULL ORDER BY invitation_id LIMIT 1
                """).param("job", job.id()).query((rs, n) -> new Item(rs.getLong("invitation_id"),
                rs.getObject("attempt_at", OffsetDateTime.class), rs.getInt("retries"))).single();
        try {
            readInvitation(account, job, item);
        } catch (RuntimeException e) {
            log.warn("Lỗi đọc hoặc lưu đối chiếu account={} campaign={} invitation={} error={}",
                    account, job.campaign(), item.id(), e.getClass().getSimpleName());
            tx.executeWithoutResult(t -> recoverFailure(job, item));
        }
    }

    private void readInvitation(long account, Job job, Item item) {
        var candidate = scope("""
                SELECT i.id, i.zalo_target_uid, i.friend_request_outcome, i.friend_request_at, clock_timestamp() AS started_at
                FROM invitation i WHERE i.id = :id AND i.friend_request_at = :attempt AND
                """ + SCOPE, job.campaign(), account).param("id", item.id()).param("attempt", item.attempt())
                .query((rs, n) -> new ZaloFriendReconciliation.Candidate(rs.getLong("id"), rs.getString("zalo_target_uid"),
                        rs.getString("friend_request_outcome"), rs.getObject("friend_request_at", OffsetDateTime.class),
                        rs.getObject("started_at", OffsetDateTime.class))).optional();
        var observation = candidate.map(c -> bridge.friendRequestObservation(c.uid(), true)).orElse(null);
        tx.executeWithoutResult(t -> finishRead(account, job, item, candidate.orElse(null), observation));
    }

    private void finishRead(long account, Job job, Item item, ZaloFriendReconciliation.Candidate candidate,
                            ZaloLink.FriendRequestObservation observation) {
        if (!ownsLease(job)) {
            return;
        }
        String reason = observation == null ? null : observation.reasonCode();
        boolean retry = item.retries() < 3 && retryable(reason);
        String wait = retry ? "now() + interval '30 seconds'" : defer(reason);
        if (wait != null) {
            postpone(job, item, reason, wait, retry);
            return;
        }
        boolean stored = observation != null && reconciliation.record(account, candidate, observation, mode.live());
        String result = !stored ? "SKIPPED"
                : observation.state() == ZaloLink.FriendRequestState.UNAVAILABLE ? "FAILED" : "READ";
        completeItem(job, item, result);
    }

    private boolean ownsLease(Job job) {
        return jdbc.sql("SELECT id FROM zalo_friend_read_job WHERE id = :job AND lease_token = :lease FOR UPDATE")
                .param("job", job.id()).param("lease", job.lease()).query(Long.class).optional().isPresent();
    }

    private void recoverFailure(Job job, Item item) {
        if (!ownsLease(job)) {
            return;
        }
        if (item.retries() < 3) {
            postpone(job, item, "READ_ERROR", "now() + interval '30 seconds'", true);
        } else {
            completeItem(job, item, "FAILED");
        }
    }

    private void postpone(Job job, Item item, String reason, String wait, boolean retry) {
        if (retry) {
            jdbc.sql("""
                    UPDATE zalo_friend_read_item SET retries = retries + 1 WHERE job_id = :job AND invitation_id = :id
                    """).param("job", job.id()).param("id", item.id()).update();
        }
        jdbc.sql("UPDATE zalo_friend_read_job SET next_at = " + wait
                        + ", waiting_reason = :reason, lease_token = NULL WHERE id = :job")
                .param("reason", reason).param("job", job.id()).update();
    }

    private void completeItem(Job job, Item item, String result) {
        jdbc.sql("UPDATE zalo_friend_read_item SET result = :result WHERE job_id = :job AND invitation_id = :id")
                .param("result", result).param("job", job.id()).param("id", item.id()).update();
        jdbc.sql("""
                UPDATE zalo_friend_read_job SET next_at = now() + interval '4 seconds', lease_token = NULL, waiting_reason = NULL,
                    finished_at = CASE WHEN NOT EXISTS (SELECT 1 FROM zalo_friend_read_item WHERE job_id = :job AND result IS NULL)
                        THEN now() END
                WHERE id = :job
                """).param("job", job.id()).update();
    }

    private JdbcClient.StatementSpec scope(String sql, long campaign, long account) {
        // Yêu cầu thủ công của chủ shop chỉ nới phạm vi đọc trong tài khoản đã chạy thật.
        // SafeZaloLink vẫn kiểm lần nữa; cờ danh sách thử của đối chiếu tự động giữ nguyên.
        return jdbc.sql(sql).param("campaign", campaign).param("account", account)
                .param("includeNonTest", ZaloAccountContext.call(account, mode::live));
    }

    private static boolean retryable(String reason) {
        return switch (reason == null ? "" : reason) {
            case "BRIDGE_DOWN", "TIMEOUT", "INTERRUPTED" -> true;
            default -> false;
        };
    }

    private static String defer(String reason) {
        return switch (reason == null ? "" : reason) {
            case "GAP", "BUSY" -> "now() + interval '10 seconds'";
            case "WINDOW" -> "now() + interval '15 minutes'";
            case "NOT_ACTIVE", "SESSION", "RESTRICTED", "ACCOUNT_MISMATCH", "ACCOUNT_UNVERIFIED" ->
                    "now() + interval '2 minutes'";
            case "LIMIT" -> "(((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 1)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')";
            default -> null;
        };
    }

    private static OffsetDateTime local(OffsetDateTime at) {
        return at == null ? null : at.atZoneSameInstant(SHOP_ZONE).toOffsetDateTime();
    }

    private static String statusMessage(boolean running, String wait, long failed, long skipped) {
        if (!running) {
            return failed + skipped > 0 ? "Đã hoàn tất với mục lỗi hoặc bỏ qua. Xem sổ gửi và trạng thái Zalo bên dưới." : "Đã đối chiếu xong.";
        }
        return switch (wait == null ? "" : wait) {
            case "LIMIT" -> "Đã tới hạn mức đọc trong ngày. ADH sẽ đọc tiếp vào ngày hôm sau.";
            case "WINDOW" -> "Đang chờ tới khung giờ bộ nối cho phép đọc.";
            case "GAP", "BUSY" -> "Đang chờ bộ nối Zalo rảnh hoặc đủ khoảng nghỉ.";
            case "NOT_ACTIVE", "SESSION", "RESTRICTED", "ACCOUNT_MISMATCH", "ACCOUNT_UNVERIFIED" ->
                    "Tài khoản Zalo chưa sẵn sàng để đọc. Kiểm tra trạng thái bộ nối và phiên đăng nhập.";
            case "READ_ERROR" -> "ADH gặp lỗi khi đọc hoặc lưu kết quả. ADH sẽ thử lại.";
            case "BRIDGE_DOWN", "TIMEOUT", "INTERRUPTED" ->
                    "Bộ nối Zalo chưa trả lời. ADH sẽ thử đọc lại.";
            default -> "Đang đối chiếu với Zalo.";
        };
    }
}
