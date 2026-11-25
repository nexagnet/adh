package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.send.SendService;
import vn.shop.crm.zalo.ZaloMessageService.Delivery;
import vn.shop.crm.zalo.ZaloMessenger.Kind;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tự NHẬN lời mời kết bạn của khách (25/11/2026, chủ dự án duyệt: "làm cả cơ chế chấp nhận lời mời kết bạn của khách"): khách đã nhận Tin 1 từ chính bộ nối mà
 * tự gửi lời mời kết bạn tới tài khoản phụ thì được nhận, rồi đường cũ (sự kiện kết bạn hoặc đối chiếu danh sách bạn, mỗi 20 phút) thêm họ vào nhóm. NƠI DUY NHẤT
 * dùng {@code Kind.ACCEPT_FRIEND}. Người lạ (không phải khách của hệ thống) không bao giờ được nhận: bộ nối chỉ nhớ mã người đã nhận Tin 1, Adh kiểm lại bằng lời mời
 * trong sổ.
 *
 * <p>Mỗi nhịp tối đa MỘT lần nhận, qua {@link ZaloMessageService} (công tắc, khung giờ, khoảng nghỉ cố định như tin trả lời, cầu dao dùng chung, trần nhận mỗi ngày
 * riêng; không tính vào số khách được nhắn Tin 1) rồi bộ nối (chỉ người trong danh sách lời mời đến, mỗi người một lần). Chỉ nhận khi khách còn đủ điều kiện liên hệ
 * (chưa từ chối, chưa xóa), chiến dịch còn được duyệt tự gửi và còn trong lịch riêng, và (chế độ thử) số nằm trong danh sách thử. Ghi dấu {@code friend_accept_at}
 * TRƯỚC khi gọi (UNKNOWN): không rõ kết quả thì không bao giờ tự nhận lại. Không gửi tin nào cho khách. Không có số ngẫu nhiên.
 */
@Service
public class ZaloFriendAcceptSender {

    private static final Logger log = LoggerFactory.getLogger(ZaloFriendAcceptSender.class);

    private static final String LABEL = "Nhận lời mời kết bạn của khách";

    public enum Step { NOT_ACTIVE, BLOCKED, NOTHING, DONE, FAILED_ONE, RETRY, TRIPPED }

    private record Task(long invitationId, long campaignId, long customerId, String phone, String uid) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final ZaloMessageService messages;
    private final ContactService contacts;
    private final SendService sends;
    private final Clock clock;
    private final TestMode testMode;
    private final CampaignPolicy policy;

    public ZaloFriendAcceptSender(JdbcClient jdbc, AppSettings settings, ZaloMessageService messages, ContactService contacts, SendService sends,
                                  Clock clock, TestMode testMode, CampaignPolicy policy) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.messages = messages;
        this.contacts = contacts;
        this.sends = sends;
        this.clock = clock;
        this.testMode = testMode;
        this.policy = policy;
    }

    /** Một nhịp: nhận tối đa MỘT lời mời kết bạn của khách đã nhận Tin 1. */
    public Step step() {
        if (!messages.enabled() || settings.paused()) {
            return Step.NOT_ACTIVE;
        }
        var general = messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.ACCEPT_FRIEND, null, null);
        if (general.isPresent() && general.get() != ZaloMessageService.Block.GAP) {
            return Step.BLOCKED;
        }
        List<String> incoming = messages.incomingRequests();
        if (incoming.isEmpty()) {
            return Step.NOTHING;
        }
        Map<Long, Boolean> open = new HashMap<>();
        for (Task t : due(incoming)) {
            if (!open.computeIfAbsent(t.campaignId(), id -> policy.blocker(id).isEmpty())) {
                continue;
            }
            if (!contacts.stillEligible(t.customerId())) {
                close(t);
                continue;
            }
            if (messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.ACCEPT_FRIEND, t.invitationId(), null, null).isPresent()) {
                continue;
            }
            return send(t);
        }
        return general.isPresent() ? Step.BLOCKED : Step.NOTHING;
    }

    private List<Task> due(List<String> uids) {
        List<String> clean = uids.stream().filter(u -> u != null && u.matches("\\d{1,32}")).distinct().limit(100).toList();
        if (clean.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT i.id, i.campaign_id, i.customer_id, c.phone, i.zalo_target_uid
                        FROM invitation i
                        JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.status = 'SENT' AND i.sent_channel = :ch AND i.zalo_target_uid IN (:uids)
                          AND i.friend_accept_at IS NULL AND i.friended_at IS NULL AND i.joined_at IS NULL
                          AND i.expires_at > :now AND k.auto_send_approved_at IS NOT NULL
                        """ + testMode.onlyTestSql("c.phone") + " ORDER BY i.sent_at, i.id LIMIT 25")
                .param("ch", ZaloInviteSender.CHANNEL).param("now", OffsetDateTime.now(clock)).param("uids", clean)
                .query((rs, n) -> new Task(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"), rs.getString("phone"),
                        rs.getString("zalo_target_uid")))
                .list();
    }

    private Step send(Task t) {
        int claimed = jdbc.sql("""
                        UPDATE invitation SET friend_accept_at = :at, friend_accept_outcome = 'UNKNOWN'
                        WHERE id = :id AND friend_accept_at IS NULL AND status = 'SENT' AND friended_at IS NULL AND joined_at IS NULL""")
                .param("id", t.invitationId()).param("at", OffsetDateTime.now(clock)).update();
        if (claimed == 0) {
            return Step.NOTHING;
        }
        Delivery d;
        try {
            d = messages.acceptFriendRequest(ZaloMessageService.SCOPE_REAL, t.invitationId(), t.uid());
        } catch (RuntimeException e) {
            log.warn("Nhận lời mời kết bạn báo lỗi: {}", e.getClass().getSimpleName());
            return Step.TRIPPED; // dòng ở lại UNKNOWN, không nhận lại
        }
        switch (d.status()) {
            case SENT -> {
                setOutcome(t, "DONE");
                sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "SENT",
                        "Khách tự gửi lời mời kết bạn, tài khoản phụ đã nhận (sẽ thêm vào nhóm khi Zalo báo đã là bạn)", LABEL);
                contacts.event(t.customerId(), t.phone(), "INVITATION_FRIEND_ACCEPTED", "Nhận lời mời kết bạn của khách");
                return Step.DONE;
            }
            case PERSON_ERROR -> {
                String code = d.code() == null ? "LOI_NGUOI" : d.code();
                setOutcome(t, "FAILED");
                sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "SKIPPED",
                        "Không nhận được lời mời kết bạn của khách (mã " + code + "). Hãy kiểm tra trên Zalo", LABEL);
                return Step.FAILED_ONE;
            }
            case RETRY, BLOCKED -> {
                release(t);
                return Step.RETRY;
            }
            default -> {
                if (d.certainlyNotSent()) {
                    release(t);
                }
                return Step.TRIPPED;
            }
        }
    }

    /** Khách không còn đủ điều kiện liên hệ: đóng (FAILED) để không bị xem lại mỗi nhịp, KHÔNG nhận. */
    private void close(Task t) {
        jdbc.sql("UPDATE invitation SET friend_accept_at = :at, friend_accept_outcome = 'FAILED' WHERE id = :id AND friend_accept_at IS NULL")
                .param("id", t.invitationId()).param("at", OffsetDateTime.now(clock)).update();
    }

    private void setOutcome(Task t, String outcome) {
        jdbc.sql("UPDATE invitation SET friend_accept_outcome = :o WHERE id = :id").param("o", outcome).param("id", t.invitationId()).update();
    }

    /** Chỉ dùng khi CHẮC CHẮN chưa chạm Zalo: trả chỗ để thử lại. */
    private void release(Task t) {
        jdbc.sql("UPDATE invitation SET friend_accept_at = NULL, friend_accept_outcome = NULL WHERE id = :id AND friend_accept_outcome = 'UNKNOWN'")
                .param("id", t.invitationId()).update();
    }
}
