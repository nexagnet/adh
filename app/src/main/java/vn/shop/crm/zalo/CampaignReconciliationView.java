package vn.shop.crm.zalo;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Sổ gửi của ADH và kết quả đọc từ đúng tài khoản, đúng lượt gửi. */
@Service
public class CampaignReconciliationView {
    static final int PAGE_SIZE = 50;

    public record Page(List<Row> items, int number, boolean hasPrevious, boolean hasNext) {
    }

    public record Row(String who, String accountLabel, String historyLabel, LocalDateTime attemptAt,
                      String stateLabel, LocalDateTime observedAt, String lastConfirmedStateLabel,
                      LocalDateTime lastConfirmedAt, String reasonCode) {
    }

    private final JdbcClient jdbc;

    public CampaignReconciliationView(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page rows(long campaignId, int page) {
        int number = Math.max(0, page);
        List<Row> rows = jdbc.sql("""
                SELECT c.name, c.phone, a.label AS account_label, i.friend_request_outcome, i.friend_request_at,
                       o.state, o.observed_at, o.last_confirmed_state, o.last_confirmed_at, o.reason_code,
                       o.outgoing_verified_at = o.observed_at AS direction_verified,
                       o.outgoing_verified_at = o.last_confirmed_at AS last_confirmed_direction_verified
                FROM invitation i
                JOIN customer c ON c.id = i.customer_id
                JOIN zalo_account a ON a.id = i.account_id
                LEFT JOIN zalo_friend_observation o ON o.invitation_id = i.id AND o.account_id = i.account_id
                    AND o.observed_at >= i.friend_request_at
                    AND (o.attempt_at IS NULL OR o.attempt_at = i.friend_request_at)
                WHERE i.campaign_id = :campaign AND i.friend_request_at IS NOT NULL
                ORDER BY i.id
                LIMIT :limit OFFSET :offset
                """).param("campaign", campaignId).param("limit", PAGE_SIZE + 1).param("offset", (long) number * PAGE_SIZE)
                .query((rs, n) -> {
                    String state = rs.getString("state");
                    boolean unavailable = "UNAVAILABLE".equals(state);
                    OffsetDateTime attempt = rs.getObject("friend_request_at", OffsetDateTime.class);
                    OffsetDateTime lastConfirmed = rs.getObject("last_confirmed_at", OffsetDateTime.class);
                    boolean hasConfirmation = unavailable && lastConfirmed != null && !lastConfirmed.isBefore(attempt);
                    return new Row(ManualAttention.who(rs.getString("name"), rs.getString("phone")),
                            rs.getString("account_label"), historyLabel(rs.getString("friend_request_outcome")),
                            ManualAttention.localTime(attempt),
                            ManualAttention.observationLabel(state, rs.getBoolean("direction_verified")),
                            ManualAttention.localTime(rs.getObject("observed_at", OffsetDateTime.class)),
                            hasConfirmation ? ManualAttention.observationLabel(rs.getString("last_confirmed_state"),
                                    rs.getBoolean("last_confirmed_direction_verified")) : null,
                            hasConfirmation ? ManualAttention.localTime(lastConfirmed) : null, rs.getString("reason_code"));
                }).list();
        return new Page(List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))),
                number, number > 0, rows.size() > PAGE_SIZE);
    }

    private static String historyLabel(String outcome) {
        return switch (outcome) {
            case "SENT" -> "Đã gửi lời mời kết bạn";
            case "WITHDRAWN" -> "ADH đã thu hồi lời mời";
            case "FAILED" -> "Gửi lời mời thất bại";
            case "UNKNOWN" -> "Chưa rõ đã gửi lời mời hay chưa";
            case null, default -> "Chưa có kết quả gửi";
        };
    }
}
