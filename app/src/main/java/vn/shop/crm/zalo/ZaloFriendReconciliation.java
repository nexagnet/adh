package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.safety.TestMode;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
class ZaloFriendReconciliation {
    private static final Logger log = LoggerFactory.getLogger(ZaloFriendReconciliation.class);
    private static final String ELIGIBLE = """
            i.status IN ('SENT', 'CLICKED') AND i.sent_channel = 'ZALO_AUTO'
            AND i.friend_request_outcome IN ('SENT', 'UNKNOWN') AND i.zalo_target_uid IS NOT NULL
            AND i.friended_at IS NULL AND i.joined_at IS NULL AND i.expires_at > now()
            AND i.friend_request_at <= now() - interval '2 minutes'
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ZaloLink bridge;
    private final TestMode testMode;

    ZaloFriendReconciliation(JdbcClient jdbc, TransactionTemplate transaction, ZaloLink bridge, TestMode testMode) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.bridge = bridge;
        this.testMode = testMode;
    }

    private record Candidate(long id, String uid, String expected, OffsetDateTime attemptAt, OffsetDateTime startedAt) {}

    void observeOne() {
        long account = ZaloAccountContext.current();
        Optional<Candidate> candidate = next(account);
        if (candidate.isEmpty()) {
            return;
        }
        var target = candidate.get();
        var observation = bridge.friendRequestObservation(target.uid());
        transaction.executeWithoutResult(status -> record(account, target, observation));
    }

    private Optional<Candidate> next(long account) {
        return jdbc.sql("""
                SELECT i.id, i.zalo_target_uid, i.friend_request_outcome, i.friend_request_at, clock_timestamp() AS started_at
                FROM invitation i JOIN customer c ON c.id = i.customer_id
                LEFT JOIN zalo_friend_observation o ON o.account_id = i.account_id AND o.invitation_id = i.id
                WHERE i.account_id = :account AND (o.observed_at IS NULL OR o.observed_at < now() - interval '5 minutes')
                AND
                """ + ELIGIBLE + testMode.onlyTestSql("c.phone")
                + " ORDER BY (i.friend_request_outcome = 'UNKNOWN' AND o.observed_at IS NULL) DESC, o.observed_at NULLS FIRST, i.id LIMIT 1")
                .param("account", account)
                .query((rs, n) -> new Candidate(rs.getLong("id"), rs.getString("zalo_target_uid"),
                        rs.getString("friend_request_outcome"), rs.getObject("friend_request_at", OffsetDateTime.class),
                        rs.getObject("started_at", OffsetDateTime.class))).optional();
    }

    private record Previous(String state, String reason, boolean review) {}

    private void record(long account, Candidate target, ZaloLink.FriendRequestObservation observation) {
        var state = observation.state();
        boolean current = jdbc.sql("""
                SELECT i.id FROM invitation i
                WHERE i.id = :id AND i.account_id = :account AND i.zalo_target_uid = :uid
                  AND i.friend_request_outcome = :expected AND i.friend_request_at = :attempt AND
                """ + ELIGIBLE + " FOR UPDATE").param("id", target.id()).param("account", account).param("uid", target.uid())
                .param("expected", target.expected()).param("attempt", target.attemptAt()).query(Long.class).optional().isPresent();
        if (!current) {
            return;
        }

        var previous = jdbc.sql("""
                SELECT state, reason_code, needs_review FROM zalo_friend_observation
                WHERE account_id = :account AND invitation_id = :id
                  AND (attempt_at = :attempt OR (attempt_at IS NULL AND observed_at >= :attempt))
                """).param("account", account).param("id", target.id()).param("attempt", target.attemptAt())
                .query((rs, n) -> new Previous(rs.getString("state"), rs.getString("reason_code"), rs.getBoolean("needs_review"))).optional();
        boolean confirmed = state != ZaloLink.FriendRequestState.UNAVAILABLE;
        boolean unknownAttempt = "UNKNOWN".equals(target.expected());
        boolean review = confirmed ? state != ZaloLink.FriendRequestState.OUTGOING || unknownAttempt
                : unknownAttempt || actionableReadError(observation.reasonCode()) || previous.map(Previous::review).orElse(false);
        int stored = jdbc.sql("""
                INSERT INTO zalo_friend_observation AS o
                    (account_id, invitation_id, state, observed_at, last_confirmed_state, last_confirmed_at, needs_review, reason_code, attempt_at)
                VALUES (:account, :id, :state, :at, CASE WHEN :confirmed THEN :state END,
                        CASE WHEN :confirmed THEN :at END, :review, :reason, :attempt)
                ON CONFLICT (account_id, invitation_id) DO UPDATE SET state = EXCLUDED.state,
                    observed_at = EXCLUDED.observed_at,
                    last_confirmed_state = COALESCE(EXCLUDED.last_confirmed_state,
                        CASE WHEN o.attempt_at = EXCLUDED.attempt_at OR (o.attempt_at IS NULL AND o.last_confirmed_at >= EXCLUDED.attempt_at)
                            THEN o.last_confirmed_state END),
                    last_confirmed_at = COALESCE(EXCLUDED.last_confirmed_at,
                        CASE WHEN o.attempt_at = EXCLUDED.attempt_at OR (o.attempt_at IS NULL AND o.last_confirmed_at >= EXCLUDED.attempt_at)
                            THEN o.last_confirmed_at END),
                    reason_code = EXCLUDED.reason_code, attempt_at = EXCLUDED.attempt_at,
                    needs_review = EXCLUDED.needs_review
                WHERE o.observed_at < EXCLUDED.observed_at
                """).param("account", account).param("id", target.id()).param("state", state.name())
                .param("at", target.startedAt()).param("confirmed", confirmed).param("review", review)
                .param("reason", observation.reasonCode(), java.sql.Types.VARCHAR).param("attempt", target.attemptAt()).update();
        if (stored == 1 && !previous.equals(Optional.of(new Previous(state.name(), observation.reasonCode(), review)))) {
            recordTransition(account, target, observation, review);
        }
    }

    private static boolean actionableReadError(String code) {
        return code != null && (code.matches("Z[0-9]{1,5}") || switch (code) {
            case "NO_INVITE", "ACCOUNT_MISMATCH", "ACCOUNT_UNVERIFIED", "BAD_RESPONSE", "SESSION", "RESTRICTED" -> true;
            default -> false;
        });
    }

    private void recordTransition(long account, Candidate target, ZaloLink.FriendRequestObservation observation, boolean review) {
        jdbc.sql("""
                INSERT INTO zalo_friend_observation_event
                    (account_id, invitation_id, state, observed_at, expected_outcome, needs_review, reason_code, attempt_at)
                VALUES (:account, :id, :state, :at, :expected, :review, :reason, :attempt)
                """).param("account", account).param("id", target.id()).param("state", observation.state().name())
                .param("at", target.startedAt()).param("expected", target.expected()).param("review", review)
                .param("reason", observation.reasonCode(), java.sql.Types.VARCHAR).param("attempt", target.attemptAt()).update();
        log.info("Đối chiếu kết bạn account={} invitation={} expected={} observed={} reason={} review={}",
                account, target.id(), target.expected(), observation.state(), observation.reasonCode(), review);
    }
}
