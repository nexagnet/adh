package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab "Cần xử lý tay" của chiến dịch (25/11/2026): gom khách mà hệ thống KHÔNG tự xử lý tiếp được, để người xem rồi quyết định.
 * Gồm (1) khách Tin 1 không gửi được (trạng thái FAILED, kèm lý do), (2) khách lời mời kết bạn hoặc tin nhờ kết bạn lại "không rõ đã gửi chưa" (UNKNOWN).
 * Thêm các kết quả đối chiếu lời mời cần xem. Hai thao tác xử lý tay hiện có đều do người bấm: "Gửi lại kết bạn" (MỘT lần cho mỗi khách) trả lời mời về hàng chờ kết bạn
 * để {@link ZaloFriendRequestSender} gửi qua cổng chung (giờ, khoảng nghỉ, hạn mức, cầu dao); "Đã xử lý" ẩn khách khỏi tab. Không xóa gì, không đụng số điện thoại.
 */
@Service
public class ManualAttention {

    private static final int LIMIT = 200;
    private static final ZoneId ZALO_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public record Row(long id, String who, String accountLabel, List<String> problems, Observation observation,
                      boolean canResendFriend, boolean canMarkDone) {
    }

    public record Observation(String stateLabel, LocalDateTime observedAt, String previousStateLabel, LocalDateTime previousObservedAt,
                              String reasonCode) {
    }

    private final JdbcClient jdbc;
    private final ZaloMessageService messages;
    private final Clock clock;

    public ManualAttention(JdbcClient jdbc, ZaloMessageService messages, Clock clock) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.clock = clock;
    }

    private static final String PENDING_DISPATCH = """
            EXISTS (SELECT 1 FROM zalo_msg_log pending
                    WHERE pending.account_id = i.account_id AND pending.ref_id = i.id
                      AND pending.kind = 'FRIEND' AND pending.outcome = 'DANG_GOI')
            """;

    private static final String MANUAL_PROBLEM = """
            (
                i.status = 'FAILED'
                OR (i.friend_request_outcome = 'UNKNOWN' AND i.friended_at IS NULL AND i.joined_at IS NULL)
                OR (i.friend_request_outcome = 'FAILED' AND i.friend_transport_failures = 4
                    AND i.friended_at IS NULL AND i.joined_at IS NULL)
                OR (i.friend_ask_outcome = 'UNKNOWN' AND i.friended_at IS NULL AND i.joined_at IS NULL)
                OR %s)""".formatted(PENDING_DISPATCH);

    private static final String OBSERVATION_JOIN = """
            LEFT JOIN zalo_friend_observation o ON o.invitation_id = i.id AND o.account_id = i.account_id AND o.needs_review
                AND i.status IN ('SENT', 'CLICKED') AND i.friend_request_outcome IN ('SENT', 'UNKNOWN')
                AND i.friended_at IS NULL AND i.joined_at IS NULL AND o.observed_at >= i.friend_request_at
                AND (o.attempt_at IS NULL OR o.attempt_at = i.friend_request_at)
            """;

    private static final String WHERE = "i.campaign_id = :c AND i.attention_done_at IS NULL AND ("
            + MANUAL_PROBLEM + " OR o.invitation_id IS NOT NULL)";

    private static final String POSITIVE_EVIDENCE = """
            EXISTS (SELECT 1 FROM zalo_friend_observation confirmed
                    WHERE confirmed.account_id = i.account_id AND confirmed.invitation_id = i.id
                      AND confirmed.last_confirmed_at >= i.friend_request_at
                      AND (confirmed.attempt_at IS NULL OR confirmed.attempt_at = i.friend_request_at)
                      AND confirmed.last_confirmed_state IN ('FRIENDS', 'OUTGOING', 'INCOMING'))
            OR EXISTS (SELECT 1 FROM zalo_friend_observation_event evidence
                       WHERE evidence.account_id = i.account_id AND evidence.invitation_id = i.id
                         AND (evidence.attempt_at = i.friend_request_at
                              OR (evidence.attempt_at IS NULL AND evidence.observed_at >= i.friend_request_at))
                         AND evidence.state IN ('FRIENDS', 'OUTGOING', 'INCOMING'))
            """;

    private static final String INCOMPLETE_DISPATCH = """
            EXISTS (SELECT 1 FROM zalo_msg_log attempt
                    WHERE attempt.account_id = i.account_id AND attempt.ref_id = i.id
                      AND attempt.kind = 'FRIEND' AND attempt.outcome IN ('DANG_GOI', 'KHONG_RO_DA_DONG'))
            """;

    public long count(long campaignId) {
        return jdbc.sql("SELECT count(*) FROM invitation i " + OBSERVATION_JOIN + "WHERE " + WHERE)
                .param("c", campaignId).query(Long.class).single();
    }

    public List<Row> rows(long campaignId) {
        return jdbc.sql("""
                        SELECT i.id, c.name, c.phone, i.status, i.last_error, i.friend_request_outcome, i.friend_request_retry_at, i.friend_ask_outcome,
                               i.friended_at, i.joined_at, i.inbound_at, a.label AS account_label, i.friend_transport_failures,
                               i.friend_transport_code, o.state, o.observed_at, o.last_confirmed_state, o.last_confirmed_at, o.reason_code,
                               %s AS manual_problem, (%s OR %s) AS resend_blocked, %s AS pending_call
                        FROM invitation i JOIN customer c ON c.id = i.customer_id JOIN zalo_account a ON a.id = i.account_id
                        """.formatted(MANUAL_PROBLEM, POSITIVE_EVIDENCE, INCOMPLETE_DISPATCH, PENDING_DISPATCH)
                + OBSERVATION_JOIN + "WHERE " + WHERE + " ORDER BY i.id LIMIT :n")
                .param("c", campaignId).param("n", LIMIT)
                .query((rs, n) -> {
                    List<String> problems = new ArrayList<>();
                    if ("FAILED".equals(rs.getString("status"))) {
                        String why = rs.getString("last_error");
                        problems.add("Tin 1 không gửi được" + (why == null || why.isBlank() ? "." : ": " + why.strip()));
                    }
                    boolean waitingForFriend = rs.getObject("friended_at") == null && rs.getObject("joined_at") == null;
                    boolean friendUnknown = waitingForFriend && "UNKNOWN".equals(rs.getString("friend_request_outcome"));
                    boolean retried = rs.getObject("friend_request_retry_at") != null;
                    if (friendUnknown) {
                        problems.add("Chưa rõ lời mời kết bạn đã gửi chưa. Hãy mở Zalo của tài khoản phụ kiểm tra"
                                + (retried ? " (đã gửi lại một lần rồi, không gửi lại thêm được)." : "."));
                    }
                    if (rs.getBoolean("pending_call")) {
                        problems.add("Lượt gọi Zalo chưa có kết quả. Sau khi kiểm tra, bấm Đã xử lý để đóng lượt mà không gửi lại.");
                    }
                    if (waitingForFriend && "FAILED".equals(rs.getString("friend_request_outcome"))
                            && rs.getInt("friend_transport_failures") == 4) {
                        problems.add("Đã hết 3 lượt thử lại kết nối khi gửi lời mời (" + rs.getString("friend_transport_code") + ").");
                    }
                    if (waitingForFriend && "UNKNOWN".equals(rs.getString("friend_ask_outcome"))) {
                        problems.add("Chưa rõ tin nhờ khách kết bạn lại đã gửi chưa. Hãy mở Zalo của tài khoản phụ kiểm tra.");
                    }
                    boolean can = friendUnknown && !retried && "SENT".equals(rs.getString("status"))
                            && rs.getObject("inbound_at") == null && !rs.getBoolean("resend_blocked");
                    String state = rs.getString("state");
                    Observation observation = state == null ? null : new Observation(observationLabel(state),
                            localTime(rs.getObject("observed_at", OffsetDateTime.class)),
                            "UNAVAILABLE".equals(state) ? observationLabel(rs.getString("last_confirmed_state")) : null,
                            "UNAVAILABLE".equals(state) ? localTime(rs.getObject("last_confirmed_at", OffsetDateTime.class)) : null,
                            rs.getString("reason_code"));
                    return new Row(rs.getLong("id"), who(rs.getString("name"), rs.getString("phone")),
                            rs.getString("account_label"), List.copyOf(problems), observation, can, rs.getBoolean("manual_problem"));
                }).list();
    }

    private static LocalDateTime localTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZALO_TIME_ZONE).toLocalDateTime();
    }

    private static String observationLabel(String state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case "FRIENDS" -> "Hai tài khoản đã kết bạn";
            case "OUTGOING" -> "Lời mời đã gửi vẫn đang chờ";
            case "INCOMING" -> "Đang có lời mời kết bạn từ khách";
            case "NONE" -> "Không thấy lời mời đang chờ hoặc quan hệ bạn bè";
            default -> "Chưa đọc được trạng thái từ Zalo";
        };
    }

    /** Tên và bốn số cuối: đủ để nhận ra khách, không lộ cả số điện thoại trên màn hình. */
    private static String who(String name, String phone) {
        String n = name == null || name.isBlank() ? "Khách chưa có tên" : name.strip();
        String p = phone == null ? "" : phone.replaceAll("\\D", "");
        return p.length() >= 4 ? n + " (số …" + p.substring(p.length() - 4) + ")" : n;
    }

    /**
     * Trả lời mời về hàng chờ kết bạn, đúng MỘT lần cho mỗi khách (cột {@code friend_request_retry_at}). Chỉ khi lời mời đang "không rõ" và khách chưa kết bạn,
     * chưa vào nhóm, chưa nhắn riêng lại. Không gọi Zalo ở đây; việc gửi vẫn do nhịp tự gửi lo, theo đủ chốt chặn.
     */
    @org.springframework.transaction.annotation.Transactional
    public void resendFriend(long campaignId, long invitationId, String by) {
        // Cùng khóa với luồng ghi quan sát. UPDATE kế tiếp đọc được bằng chứng vừa commit.
        jdbc.sql("SELECT id FROM invitation WHERE id = :id AND campaign_id = :c FOR UPDATE")
                .param("id", invitationId).param("c", campaignId).query(Long.class).optional();
        int n = jdbc.sql("""
                        UPDATE invitation i SET friend_request_at = NULL, friend_request_outcome = NULL, friend_request_retry_at = :now
                        WHERE id = :id AND campaign_id = :c AND status = 'SENT' AND friend_request_outcome = 'UNKNOWN' AND friend_request_retry_at IS NULL
                          AND friended_at IS NULL AND joined_at IS NULL AND inbound_at IS NULL
                        """ + " AND NOT (" + POSITIVE_EVIDENCE + ") AND NOT " + INCOMPLETE_DISPATCH)
                .param("id", invitationId).param("c", campaignId).param("now", OffsetDateTime.now(clock)).update();
        if (n == 0) {
            throw new ImportException("Không thể xếp gửi lại. Hãy kiểm tra trạng thái lời mời và kết quả đối chiếu mới nhất.");
        }
        messages.audit("GUI_LAI_KET_BAN", invitationId, by);
    }

    /** Đóng lượt gửi đã gián đoạn sau khi kiểm tra, rồi ẩn khách khỏi tab. */
    @org.springframework.transaction.annotation.Transactional
    public void markDone(long campaignId, long invitationId, String by) {
        long account = jdbc.sql("SELECT account_id FROM invitation WHERE id = :id AND campaign_id = :c")
                .param("id", invitationId).param("c", campaignId).query(Long.class).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy khách cần xử lý này."));
        ZaloAccountContext.run(account, () -> closeInterrupted(invitationId, account, by));
        int n = jdbc.sql("""
                UPDATE invitation i SET attention_done_at = :now
                WHERE id = :id AND campaign_id = :c AND account_id = :account AND attention_done_at IS NULL
                """).param("id", invitationId).param("c", campaignId).param("account", account).param("now", OffsetDateTime.now(clock)).update();
        if (n == 1) {
            ZaloAccountContext.run(account, () -> messages.audit("DA_XU_LY_TAY", invitationId, by));
        }
    }

    private void closeInterrupted(long invitationId, long account, String by) {
        var attempts = jdbc.sql("""
                SELECT at FROM zalo_msg_log
                WHERE account_id = :account AND ref_id = :id AND kind = 'FRIEND' AND outcome = 'DANG_GOI' ORDER BY id FOR UPDATE
                """).param("account", account).param("id", invitationId).query(OffsetDateTime.class).list();
        if (attempts.isEmpty()) {
            return;
        }
        var staleBefore = OffsetDateTime.now(clock).minusMinutes(2);
        if (attempts.stream().anyMatch(at -> at.isAfter(staleBefore))) {
            throw new ImportException("Lượt gọi Zalo có thể vẫn đang xử lý. Hãy đợi ít nhất hai phút trước khi đóng.");
        }
        jdbc.sql("""
                UPDATE zalo_msg_log SET outcome = 'KHONG_RO_DA_DONG'
                WHERE account_id = :account AND ref_id = :id AND kind = 'FRIEND' AND outcome = 'DANG_GOI' AND at <= :at
                """).param("account", account).param("id", invitationId).param("at", staleBefore).update();
        messages.audit("DONG_LUOT_KET_BAN_CHUA_RO", invitationId, by);
    }
}
