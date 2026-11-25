package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.InviteService;
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
 * Khi Zalo báo danh sách lời mời kết bạn đã đầy (Z223, 25/11/2026) thì {@link ZaloFriendRequestSender} tạm dừng; trong lúc đó khách đã nhận Tin 1 mà chưa
 * được mời kết bạn nhận thêm MỘT tin nhờ họ chủ động kết bạn lại với tài khoản phụ (người dùng chốt: "em không kết bạn được với bác, bác kết bạn lại
 * với em nhé"). NƠI DUY NHẤT dùng {@code Kind.ASK_FRIEND}. Mỗi nhịp tối đa MỘT tin, qua {@link ZaloMessageService} (công tắc, khung giờ, khoảng nghỉ
 * cố định, cầu dao dùng chung, trần mỗi ngày dùng chung với lời mời kết bạn; không tính vào số khách được nhắn Tin 1) rồi bộ nối (chỉ theo mã Zalo của người
 * ĐÃ nhận Tin 1 từ chính bộ nối, mỗi người một lần).
 *
 * <p>Chỉ gửi cho lời mời còn đúng trạng thái "đã gửi Tin 1" và CHƯA được gửi lời mời kết bạn: chưa kết bạn, chưa vào nhóm, khách chưa nhắn riêng lại,
 * chưa hết hạn, Tin 1 không bị Zalo chặn (khách chưa nhận Tin 1 thì không có gì để "nhờ kết bạn lại"), chiến dịch còn được duyệt tự gửi và còn trong lịch
 * riêng, khách còn đủ điều kiện liên hệ, và (chế độ thử) số nằm trong danh sách thử. Nội dung do chiến dịch sửa được (mặc định {@link #DEFAULT_TEXT});
 * còn dấu {@code {...}} chưa thay được thì KHÔNG gửi. Ghi dấu {@code friend_ask_at} TRƯỚC khi gọi (outcome UNKNOWN): không rõ kết quả thì không bao giờ
 * tự gửi lại. Không có số ngẫu nhiên.
 */
@Service
public class ZaloFriendAskSender {

    private static final Logger log = LoggerFactory.getLogger(ZaloFriendAskSender.class);
    private static final int SCAN = 25;
    public static final int MAX_TEXT = 300;
    public static final String DEFAULT_TEXT = "Dạ em chưa kết bạn được với bác {ten} ạ. Bác bấm kết bạn lại với em giúp em nhé, bác đồng ý là em thêm bác vào nhóm liền ạ.";

    public enum Step { NOT_ACTIVE, BLOCKED, NOTHING, SENT, FAILED_ONE, RETRY, TRIPPED }

    private record Task(long invitationId, long campaignId, long customerId, String phone, String uid, String name, String province,
                        String token, String template) {
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

    public ZaloFriendAskSender(JdbcClient jdbc, AppSettings settings, ZaloMessageService messages, ContactService contacts,
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

    /** Một nhịp: gửi tối đa MỘT tin nhờ kết bạn lại, chỉ khi lời mời kết bạn đang tạm dừng vì Zalo báo danh sách đầy. */
    public Step step() {
        if (!messages.enabled() || settings.paused()) {
            return Step.NOT_ACTIVE;
        }
        if (messages.friendPausedUntil().isEmpty()) {
            return Step.NOTHING;
        }
        var general = messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.ASK_FRIEND, null, null);
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
            String text = text(t);
            if (text == null) {
                log.warn("Chiến dịch {} có mẫu tin nhờ kết bạn lại còn chỗ điền chưa thay được; bỏ qua, không gửi", t.campaignId());
                continue;
            }
            if (messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.ASK_FRIEND, t.invitationId(), null, null).isPresent()) {
                continue;
            }
            return send(t, text);
        }
        return general.isPresent() ? Step.BLOCKED : Step.NOTHING;
    }

    /** Nội dung đã ghép, hoặc null nếu còn dấu {...} chưa thay (hoặc quá dài). */
    private String text(Task t) {
        String template = t.template() == null || t.template().isBlank() ? DEFAULT_TEXT : t.template();
        String text = invites.render(template, t.name(), invites.linkFor(t.token()), VnGeo.byCode(t.province()).orElse(null))
                .replaceAll("[ \\t]{2,}", " ").strip();
        return text.isEmpty() || text.length() > MAX_TEXT || InviteService.hasUnresolved(text) ? null : text;
    }

    private List<Task> due() {
        return jdbc.sql("""
                        SELECT i.id, i.campaign_id, i.customer_id, c.phone, c.name, c.province, i.token, i.zalo_target_uid, k.friend_ask_template
                        FROM invitation i
                        JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.status = 'SENT' AND i.sent_channel = :ch AND i.zalo_target_uid IS NOT NULL AND i.tin1_blocked_at IS NULL
                          AND i.friend_request_at IS NULL AND i.friend_ask_at IS NULL
                          AND i.friended_at IS NULL AND i.joined_at IS NULL AND i.inbound_at IS NULL
                          AND i.expires_at > :now AND k.auto_send_approved_at IS NOT NULL
                        """ + testMode.onlyTestSql("c.phone") + " ORDER BY i.sent_at, i.id LIMIT :n")
                .param("ch", ZaloInviteSender.CHANNEL).param("now", OffsetDateTime.now(clock)).param("n", SCAN)
                .query((rs, n) -> new Task(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"), rs.getString("phone"),
                        rs.getString("zalo_target_uid"), rs.getString("name"), rs.getString("province"), rs.getString("token"),
                        rs.getString("friend_ask_template")))
                .list();
    }

    private Step send(Task t, String text) {
        int claimed = jdbc.sql("""
                        UPDATE invitation SET friend_ask_at = :at, friend_ask_outcome = 'UNKNOWN'
                        WHERE id = :id AND friend_ask_at IS NULL AND friend_request_at IS NULL AND status = 'SENT' AND friended_at IS NULL
                          AND joined_at IS NULL AND inbound_at IS NULL""")
                .param("id", t.invitationId()).param("at", OffsetDateTime.now(clock)).update();
        if (claimed == 0) {
            return Step.NOTHING;
        }
        Delivery d;
        try {
            d = messages.sendByUid(ZaloMessageService.SCOPE_REAL, Kind.ASK_FRIEND, t.invitationId(), t.uid(), text, null, null);
        } catch (RuntimeException e) {
            log.warn("Gửi tin nhờ kết bạn lại báo lỗi: {}", e.getClass().getSimpleName());
            unknown(t, "LOI_HE_THONG", text);
            return Step.TRIPPED;
        }
        switch (d.status()) {
            case SENT -> {
                setOutcome(t, "SENT");
                sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "SENT",
                        "Tin nhờ khách kết bạn lại (lời mời kết bạn đang tạm dừng vì Zalo báo danh sách đầy)", text);
                contacts.event(t.customerId(), t.phone(), "INVITATION_FRIEND_ASK", "Tin nhờ kết bạn lại");
                return Step.SENT;
            }
            case PERSON_ERROR -> {
                String code = d.code() == null ? "LOI_NGUOI" : d.code();
                setOutcome(t, "FAILED");
                sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "SKIPPED",
                        "Tin nhờ kết bạn lại không gửi được (mã " + code + ")", text);
                return Step.FAILED_ONE;
            }
            case RETRY, BLOCKED -> {
                release(t);
                return Step.RETRY;
            }
            default -> {
                if (d.certainlyNotSent()) {
                    release(t);
                } else {
                    unknown(t, d.code(), text);
                }
                return Step.TRIPPED;
            }
        }
    }

    /** Khách không còn gửi được: đóng (FAILED) để không bị xem lại mỗi nhịp. */
    private void close(Task t) {
        jdbc.sql("UPDATE invitation SET friend_ask_at = :at, friend_ask_outcome = 'FAILED' WHERE id = :id AND friend_ask_at IS NULL")
                .param("id", t.invitationId()).param("at", OffsetDateTime.now(clock)).update();
    }

    /** Không biết tin đã đi chưa: giữ UNKNOWN (không bao giờ tự gửi lại) và để người kiểm tra trên Zalo. */
    private void unknown(Task t, String code, String text) {
        sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "FAILED",
                "Không rõ tin nhờ kết bạn lại đã gửi chưa (" + (code == null ? "lỗi" : code) + "). Hãy kiểm tra trên Zalo", text);
    }

    private void setOutcome(Task t, String outcome) {
        jdbc.sql("UPDATE invitation SET friend_ask_outcome = :o WHERE id = :id").param("o", outcome).param("id", t.invitationId()).update();
    }

    /** Chỉ dùng khi CHẮC CHẮN chưa chạm Zalo: trả chỗ để thử lại. */
    private void release(Task t) {
        jdbc.sql("UPDATE invitation SET friend_ask_at = NULL, friend_ask_outcome = NULL WHERE id = :id AND friend_ask_outcome = 'UNKNOWN'")
                .param("id", t.invitationId()).update();
    }
}
