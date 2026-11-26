package vn.shop.crm.customers;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CustomerZaloHistory {

    private static final int HISTORY_LIMIT = 50;
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcClient jdbc;

    public CustomerZaloHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Delivery(long customerId, LocalDateTime at, String accountLabel, String messageLabel, boolean partial) {
    }

    public List<Delivery> recent(long customerId) {
        return deliveries(List.of(customerId), HISTORY_LIMIT);
    }

    public Map<Long, Delivery> latest(List<Long> customerIds) {
        return deliveries(customerIds, 1).stream().collect(Collectors.toUnmodifiableMap(Delivery::customerId, entry -> entry));
    }

    private List<Delivery> deliveries(List<Long> customerIds, int perCustomerLimit) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        // ref_id có thể trùng giữa lời mời, khách quay lại và bản nháp trả lời. Loại tin quyết định bảng tham chiếu.
        return jdbc.sql("""
                WITH recipient_refs AS (
                    SELECT customer_id, id AS ref_id, account_id, 'INVITATION' AS ref_type
                    FROM invitation WHERE customer_id IN (:ids)
                    UNION ALL
                    SELECT c.customer_id, c.id, i.account_id, 'COMEBACK'
                    FROM comeback c JOIN invitation i ON i.id = c.invitation_id AND i.customer_id = c.customer_id
                    WHERE c.customer_id IN (:ids)
                    UNION ALL
                    SELECT customer_id, id, account_id, 'REPLY_DRAFT'
                    FROM reply_draft WHERE customer_id IN (:ids)
                ),
                sent AS (
                    SELECT r.customer_id, l.at, l.kind, l.code, a.label,
                           row_number() OVER (PARTITION BY r.customer_id ORDER BY l.at DESC, l.id DESC) AS recency
                    FROM zalo_msg_log l
                    JOIN recipient_refs r ON l.ref_id = r.ref_id AND l.account_id = r.account_id AND r.ref_type = CASE l.kind
                        WHEN 'WELCOME' THEN 'COMEBACK'
                        WHEN 'REPLY' THEN 'REPLY_DRAFT'
                        WHEN 'INVITE' THEN 'INVITATION'
                        WHEN 'LINK' THEN 'INVITATION'
                        WHEN 'FOLLOWUP2' THEN 'INVITATION'
                        WHEN 'FOLLOWUP3' THEN 'INVITATION'
                        WHEN 'FRIEND' THEN 'INVITATION'
                        WHEN 'ASK_FRIEND' THEN 'INVITATION'
                    END
                    JOIN zalo_account a ON a.id = l.account_id
                    WHERE l.scope = 'REAL' AND l.outcome = 'OK'
                )
                SELECT customer_id, at, kind, code, label FROM sent
                WHERE recency <= :limit ORDER BY customer_id, recency
                """).param("ids", customerIds).param("limit", perCustomerLimit)
                .query((rs, n) -> new Delivery(rs.getLong("customer_id"),
                        rs.getObject("at", OffsetDateTime.class).atZoneSameInstant(VIETNAM).toLocalDateTime(),
                        rs.getString("label"), messageLabel(rs.getString("kind")), "PARTIAL".equals(rs.getString("code"))))
                .list();
    }

    private static String messageLabel(String kind) {
        return switch (kind) {
            case "INVITE" -> "Tin 1 mời khách";
            case "LINK" -> "Tin gửi link nhóm";
            case "WELCOME" -> "Tin mẫu trả lời";
            case "REPLY" -> "Tin trả lời AI";
            case "FOLLOWUP2" -> "Tin nhắc lần 2";
            case "FOLLOWUP3" -> "Tin nhắc lần 3";
            case "FRIEND" -> "Lời mời kết bạn kèm lời nhắn";
            case "ASK_FRIEND" -> "Tin nhờ khách kết bạn lại";
            default -> throw new IllegalArgumentException("Loại tin Zalo không được hỗ trợ: " + kind);
        };
    }
}
