package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.safety.TestMode;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Xem trước hàng đợi tự gửi để hiện bộ đếm ngược (25/11/2026). CHỈ ĐỌC: không gửi, không giành chỗ, không đổi gì.
 * Thứ tự theo đúng thứ tự {@link ZaloInviteSender#step}: lời mời kết bạn, gửi lại Tin 1, rồi Tin 1 cho khách mới. Tin nhắc lần 2 và 3 chưa xếp vào đây.
 */
@Service
public class ZaloQueueView {

    /** Số việc hiện ra tối đa. */
    static final int SHOW = 5;
    private static final int SCAN = 25;

    /** {@code what}: việc gì; {@code who}: khách nào (tên và đuôi số). */
    public record Item(String what, String who) {
    }

    /** {@code next}: việc đầu hàng đang chờ gì và bao giờ; {@code first}: mô tả việc đó (rỗng nếu hàng trống); {@code notes}: ghi chú thêm. */
    public record Preview(ZaloMessageService.NextSend next, Item first, List<Item> upcoming, List<String> notes) {
    }

    private record Row(long id, long campaignId, long customerId, String name, String phone, boolean friend, String what) {
    }

    private final JdbcClient jdbc;
    private final ZaloMessageService messages;
    private final CampaignPolicy policy;
    private final ContactService contacts;
    private final TestMode testMode;
    private final RainHold rain;
    private final Clock clock;

    public ZaloQueueView(JdbcClient jdbc, ZaloMessageService messages, CampaignPolicy policy, ContactService contacts,
                         TestMode testMode, RainHold rain, Clock clock) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.policy = policy;
        this.contacts = contacts;
        this.testMode = testMode;
        this.rain = rain;
        this.clock = clock;
    }

    public Preview preview() {
        List<String> notes = new ArrayList<>();
        Map<Long, Boolean> open = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        if (messages.friendPausedUntil().isPresent()) {
            notes.add("Zalo báo danh sách lời mời kết bạn đã đầy (Z223): hệ thống tạm dừng gửi lời mời kết bạn, Tin 1 và thêm vào nhóm vẫn chạy.");
        } else if (messages.friendUsedToday() >= messages.friendDaily()) {
            notes.add("Lời mời kết bạn đã đủ " + messages.friendDaily() + " hôm nay: hệ thống chuyển sang Tin 1, lời mời kết bạn tiếp tục vào ngày mai.");
        } else {
            addAll(rows, friendRows(), open);
        }
        addAll(rows, resendRows(), open);
        addAll(rows, newInviteRows(), open);
        if (rows.isEmpty()) {
            return new Preview(messages.nextSend(), null, List.of(), notes);
        }
        // Việc nào gửi trước? Cùng một lúc thì theo thứ tự hàng; nhưng khoảng nghỉ 7 giây chỉ dành cho khách vừa có tin gần nhất, nên khách đó
        // có thể đến lượt sớm hơn khách đứng đầu hàng (người gửi duyệt từng khách và bỏ qua khách chưa đủ nghỉ). Chỉ cần xét đầu hàng và khách ấy.
        Row head = rows.get(0);
        ZaloMessageService.NextSend best = wait(head);
        Long last = messages.lastRefId().orElse(null);
        if (last != null) {
            for (Row r : rows) {
                if (r.id() == last && r != head) {
                    ZaloMessageService.NextSend alt = wait(r);
                    if (best.at() == null || (alt.at() != null && alt.at().isBefore(best.at()))) {
                        head = r;
                        best = alt;
                    }
                    break;
                }
            }
        }
        List<Row> ordered = new ArrayList<>();
        ordered.add(head);
        for (Row r : rows) {
            if (r != head) {
                ordered.add(r);
            }
        }
        List<Item> items = new ArrayList<>();
        for (Row r : ordered.subList(0, Math.min(SHOW, ordered.size()))) {
            items.add(new Item(r.what(), who(r)));
        }
        if (ordered.size() > SHOW) {
            notes.add("Còn " + (ordered.size() - SHOW) + " việc nữa sau các việc ở trên (đếm trong " + SCAN + " việc gần nhất của mỗi loại).");
        }
        return new Preview(best, items.get(0), items, notes);
    }

    private void addAll(List<Row> out, List<Row> candidates, Map<Long, Boolean> open) {
        for (Row r : candidates) {
            if (out.size() >= 3 * SCAN) {
                return;
            }
            if (!open.computeIfAbsent(r.campaignId(), id -> policy.blocker(id).isEmpty())) {
                continue;
            }
            if (!contacts.stillEligible(r.customerId())) {
                continue;
            }
            out.add(r);
        }
    }

    private ZaloMessageService.NextSend wait(Row r) {
        return r.friend() ? messages.waitForFriendRequest(r.id()) : messages.waitFor(ZaloMessenger.Kind.INVITE, r.id());
    }

    private static String who(Row r) {
        String name = r.name() == null || r.name().isBlank() ? "Khách chưa có tên" : r.name().strip();
        String p = r.phone() == null ? "" : r.phone().replaceAll("\\D", "");
        return p.length() >= 4 ? name + " (số …" + p.substring(p.length() - 4) + ")" : name;
    }

    /** Cùng điều kiện với {@link ZaloFriendRequestSender}. */
    private List<Row> friendRows() {
        return jdbc.sql("""
                        SELECT i.id, i.campaign_id, i.customer_id, c.name, c.phone, i.tin1_blocked_at IS NOT NULL AS blocked
                        FROM invitation i
                        JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.account_id = :acct AND i.status = 'SENT' AND i.sent_channel = :ch AND i.zalo_target_uid IS NOT NULL
                          AND i.friend_request_at IS NULL AND i.friended_at IS NULL AND i.joined_at IS NULL AND i.inbound_at IS NULL
                          AND i.expires_at > :now AND k.auto_send_approved_at IS NOT NULL
                        """ + testMode.onlyTestSql("c.phone") + " ORDER BY i.sent_at, i.id LIMIT :n")
                .param("acct", ZaloAccountContext.current()).param("ch", ZaloInviteSender.CHANNEL)
                .param("now", OffsetDateTime.now(clock)).param("n", SCAN)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"), rs.getString("name"),
                        rs.getString("phone"), true, "Gửi lời mời kết bạn"))
                .list();
    }

    /** Cùng điều kiện với {@link ZaloInviteSender} (gửi lại Tin 1 cho khách đã kết bạn sau khi Zalo chặn tin người lạ). */
    private List<Row> resendRows() {
        return jdbc.sql("""
                        SELECT i.id, i.campaign_id, i.customer_id, c.name, c.phone
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.account_id = :acct AND i.tin1_blocked_at IS NOT NULL AND i.tin1_resent_at IS NULL AND i.friended_at IS NOT NULL
                          AND i.status IN ('SENT', 'CLICKED', 'FRIENDED') AND i.zalo_target_uid IS NOT NULL
                          AND i.inbound_at IS NULL AND i.joined_at IS NULL AND i.expires_at > :now AND k.auto_send_approved_at IS NOT NULL
                        """ + testMode.onlyTestSql("c.phone") + " ORDER BY i.friended_at, i.id LIMIT :n")
                .param("acct", ZaloAccountContext.current()).param("now", OffsetDateTime.now(clock)).param("n", SCAN)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"), rs.getString("name"),
                        rs.getString("phone"), false, "Gửi lại Tin 1 (khách vừa đồng ý kết bạn)"))
                .list();
    }

    private List<Row> newInviteRows() {
        String[] rainy = rain.rainyProvinces().toArray(new String[0]);
        return jdbc.sql("""
                        SELECT i.id, i.campaign_id, i.customer_id, c.name, c.phone
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.status = 'READY' AND k.auto_send_approved_at IS NOT NULL
                          AND (k.hold_on_rain = false OR c.province IS NULL OR NOT (c.province = ANY(:rainy)))
                        """ + testMode.onlyTestSql("c.phone") + " ORDER BY i.campaign_id, i.id LIMIT :n")
                .param("rainy", rainy).param("n", SCAN)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"), rs.getString("name"),
                        rs.getString("phone"), false, "Tin 1"))
                .list();
    }
}
