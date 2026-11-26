package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.send.SendService;
import vn.shop.crm.zalo.ZaloMessageService.Delivery;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tài khoản phụ CHỦ ĐỘNG gửi lời mời kết bạn ngay sau Tin 1 (chủ shop chốt 24/11/2026): người nhận đồng ý thì {@link ZaloAutoService} nhận ra
 * sự kiện kết bạn và tự thêm vào nhóm. NƠI DUY NHẤT dùng {@code Kind.FRIEND}. Mỗi nhịp tối đa MỘT lời mời, qua {@link ZaloMessageService}
 * (công tắc, giờ, khoảng nghỉ cố định, cầu dao dùng chung, cộng trần lời mời mỗi ngày riêng, mặc định 10), rồi bộ nối (chỉ người ĐÃ nhận
 * Tin 1 từ chính bộ nối, mỗi người một lời mời).
 *
 * <p>Chỉ gửi cho lời mời còn đúng trạng thái "đã gửi Tin 1": chưa kết bạn, chưa vào nhóm, chưa bị hủy hay tạm dừng (khách từ chối, AI nghi từ chối),
 * khách chưa nhắn riêng lại, chưa hết hạn, chiến dịch còn được duyệt tự gửi và còn trong lịch riêng, khách còn đủ điều kiện liên hệ, và
 * (chế độ thử) số nằm trong danh sách thử. Khách đã nhắn lại "dừng" hay từ chối thì không bao giờ nhận lời mời.
 * Cổng gửi ghi dấu lời mời và nhật ký đang gọi cùng một câu lệnh. Nếu app dừng trước khi ghi thành công, lần sau vẫn thấy khách trong hàng chờ.
 * Nếu đã ghi mà không có kết quả xác định, lời mời ở lại UNKNOWN và cần người kiểm tra.
 */
@Service
public class ZaloFriendRequestSender {

    private static final Logger log = LoggerFactory.getLogger(ZaloFriendRequestSender.class);
    private static final int SCAN = 25;
    /** Zalo chỉ nhận câu ngắn kèm lời mời kết bạn. */
    static final int MAX_TEXT = 150;
    static final String DEFAULT_TEXT = "Em là {cua_hang}, vừa nhắn mời anh/chị vào nhóm khách hàng. Anh/chị đồng ý kết bạn để em thêm vào nhóm ạ.";
    static final String FALLBACK_TEXT = "Em vừa nhắn mời anh/chị vào nhóm khách hàng. Anh/chị đồng ý kết bạn để em thêm vào nhóm ạ.";
    /** Cho khách chưa nhận được Tin 1 (Zalo chặn tin người lạ, Z122): không nhắc tới tin đã gửi. Tin 1 gửi lại ngay khi họ đồng ý kết bạn. */
    static final String BLOCKED_TEXT = "Em là {cua_hang}, bên em có nhóm khách hàng mua áo điều hòa được giảm giá. Anh/chị đồng ý kết bạn để em gửi thông tin ạ.";
    static final String BLOCKED_FALLBACK_TEXT = "Bên em có nhóm khách hàng mua áo điều hòa được giảm giá. Anh/chị đồng ý kết bạn để em gửi thông tin ạ.";

    public enum Step { NOT_ACTIVE, BLOCKED, NOTHING, SENT, FAILED_ONE, RETRY, TRIPPED, LIST_FULL }

    private record Task(long invitationId, long campaignId, long customerId, String phone, String uid, boolean blocked) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final ZaloMessageService messages;
    private final ContactService contacts;
    private final InviteService invites;
    private final SendService sends;
    private final Clock clock;
    private final TestMode testMode;
    private final CampaignPolicy policy;

    public ZaloFriendRequestSender(JdbcClient jdbc, AppSettings settings, ZaloMessageService messages, ContactService contacts,
                                   InviteService invites, SendService sends, Clock clock, TestMode testMode, CampaignPolicy policy) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.messages = messages;
        this.contacts = contacts;
        this.invites = invites;
        this.sends = sends;
        this.clock = clock;
        this.testMode = testMode;
        this.policy = policy;
    }

    /** Câu kèm lời mời: tên shop thay vào, quá 150 ký tự thì dùng câu ngắn không có tên shop. */
    String text() {
        String t = invites.render(DEFAULT_TEXT, null, "", null).replaceAll("[ \\t]{2,}", " ").strip();
        return t.length() <= MAX_TEXT ? t : FALLBACK_TEXT;
    }

    /** Câu kèm lời mời cho khách mà Tin 1 bị Zalo chặn (Z122): như {@link #text()} nhưng không nói đã nhắn mời. */
    String blockedText() {
        String t = invites.render(BLOCKED_TEXT, null, "", null).replaceAll("[ \\t]{2,}", " ").strip();
        return t.length() <= MAX_TEXT ? t : BLOCKED_FALLBACK_TEXT;
    }

    /** Một nhịp: gửi tối đa MỘT lời mời kết bạn nếu được phép. */
    public Step step() {
        if (!messages.enabled() || settings.paused()) {
            return Step.NOT_ACTIVE;
        }
        // Chưa đủ khoảng nghỉ chung vẫn xem tiếp danh sách: từng khách kiểm lại theo mã lời mời (khách vừa nhận Tin 1 hoặc vừa bị Zalo chặn Tin 1 (Z122) chờ 7 giây cố định, không chờ nghỉ giữa các khách).
        var general = messages.blocker(ZaloMessageService.SCOPE_REAL, ZaloMessenger.Kind.FRIEND, null, null);
        if (general.isPresent() && general.get() != ZaloMessageService.Block.GAP) {
            return Step.BLOCKED;
        }
        Map<Long, Boolean> open = new HashMap<>();
        for (Task t : due()) {
            if (!open.computeIfAbsent(t.campaignId(), id -> policy.blocker(id).isEmpty())) {
                continue;
            }
            if (!contacts.stillEligible(t.customerId())) {
                close(t);
                continue;
            }
            if (messages.blocker(ZaloMessageService.SCOPE_REAL, ZaloMessenger.Kind.FRIEND, t.invitationId(), null, null).isPresent()) {
                continue;
            }
            return send(t);
        }
        return general.isPresent() ? Step.BLOCKED : Step.NOTHING;
    }

    /** Một truy vấn gom mọi điều kiện dừng; cổng gửi kiểm lại trạng thái dòng khi giành chỗ. */
    private List<Task> due() {
        return jdbc.sql("""
                        SELECT i.id, i.campaign_id, i.customer_id, c.phone, i.zalo_target_uid, i.tin1_blocked_at IS NOT NULL AS blocked
                        FROM invitation i
                        JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.status = 'SENT' AND i.sent_channel = :ch AND i.zalo_target_uid IS NOT NULL
                          AND i.friend_request_at IS NULL AND i.friend_accept_at IS NULL AND i.friended_at IS NULL AND i.joined_at IS NULL AND i.inbound_at IS NULL
                          AND i.expires_at > :now AND k.auto_send_approved_at IS NOT NULL
                        """ + testMode.onlyTestSql("c.phone") + " AND " + ZaloAccountContext.sql("i.account_id") + " ORDER BY i.sent_at, i.id LIMIT :n")
                .param("ch", ZaloInviteSender.CHANNEL).param("now", OffsetDateTime.now(clock)).param("n", SCAN)
                .query((rs, n) -> new Task(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"), rs.getString("phone"),
                        rs.getString("zalo_target_uid"), rs.getBoolean("blocked")))
                .list();
    }

    private Step send(Task t) {
        String text = t.blocked() ? blockedText() : text();
        Delivery d;
        try {
            d = messages.sendFriendRequest(ZaloMessageService.SCOPE_REAL, t.invitationId(), t.uid(), text);
        } catch (RuntimeException e) {
            log.warn("Lỗi xử lý lời mời kết bạn account={} invitation={} error={}",
                    ZaloAccountContext.current(), t.invitationId(), e.getClass().getSimpleName());
            return Step.TRIPPED;
        }
        switch (d.status()) {
            case SENT -> {
                sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "SENT",
                        "Lời mời kết bạn tự gửi qua bộ nối Zalo", text);
                contacts.event(t.customerId(), t.phone(), "INVITATION_FRIEND_REQUEST", "Lời mời kết bạn");
                return Step.SENT;
            }
            case PERSON_ERROR -> {
                if (ZaloMessageService.FRIEND_LIST_FULL.equals(d.code())) {
                    // Zalo báo danh sách lời mời đã đầy (Z223): lời mời CHƯA đi, đây không phải lỗi của khách này. Trả chỗ để gửi sau,
                    // chỉ tạm dừng riêng phần lời mời kết bạn (không dừng cầu dao, Tin 1 và thêm nhóm vẫn chạy).
                    messages.pauseFriendRequests("system");
                    log.warn("Zalo báo danh sách lời mời kết bạn đã đầy (Z223): tạm dừng gửi lời mời kết bạn");
                    return Step.LIST_FULL;
                }
                failed(t, d.code(), text);
                return Step.FAILED_ONE;
            }
            case RETRY, BLOCKED -> {
                return Step.RETRY;
            }
            default -> {
                if (d.sent() != null && d.sent().result() == ZaloMessenger.Result.PERSON_ERROR) {
                    failed(t, d.code(), text);
                } else if (!d.certainlyNotSent()) {
                    unknown(t, d.code());
                }
                return Step.TRIPPED;
            }
        }
    }

    /** Khách không còn gửi được: đóng (FAILED) để không bị xem lại mỗi nhịp. */
    private void close(Task t) {
        jdbc.sql("UPDATE invitation SET friend_request_at = :at, friend_request_outcome = 'FAILED' WHERE id = :id AND friend_request_at IS NULL")
                .param("id", t.invitationId()).param("at", OffsetDateTime.now(clock)).update();
    }

    /** Không biết lời mời đã đi chưa: giữ UNKNOWN (không bao giờ tự gửi lại) và để người kiểm tra trên Zalo. */
    private void unknown(Task t, String code) {
        sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "FAILED",
                "Không rõ lời mời kết bạn đã gửi chưa (" + (code == null ? "lỗi" : code) + "). Hãy kiểm tra trên Zalo", text());
    }

    private void failed(Task t, String code, String text) {
        sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "SKIPPED",
                "Lời mời kết bạn không gửi được (mã " + (code == null ? "LOI_NGUOI" : code) + ")", text);
    }

}
