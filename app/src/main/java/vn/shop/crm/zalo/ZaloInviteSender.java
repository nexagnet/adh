package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.CampaignSteps;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.send.SendService;
import vn.shop.crm.zalo.ZaloMessageService.Delivery;
import vn.shop.crm.zalo.ZaloMessenger.Kind;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Tự gửi tin cho khách của CHIẾN DỊCH THẬT (D18, ADR-0002 mục 12), mỗi nhịp tối đa MỘT tin, qua {@link ZaloMessageService}
 * (công tắc, giờ, hạn mức, khoảng nghỉ cố định, cầu dao dùng chung).
 *
 * <ul>
 *   <li><b>Tin 1</b>: lời mời READY của chiến dịch ĐÃ ĐƯỢC DUYỆT tự gửi, mỗi khách đúng một lần (khóa bằng cập nhật có điều kiện
 *       READY→SENT trước khi gọi). Khách không còn đủ điều kiện liên hệ (đã từ chối, bị chặn, bị xóa) thì không bao giờ được gửi.
 *       Cầu nối tra mã Zalo lúc gửi; mã được lưu để khớp khi khách đồng ý kết bạn mà không cần số điện thoại.</li>
 * </ul>
 * Không biết kết quả (hết giờ chờ, Zalo không xác nhận, sập giữa chừng) thì lời mời ở lại FAILED để người kiểm tra tay,
 * không bao giờ tự gửi lại.
 */
@Service
public class ZaloInviteSender {

    private static final Logger log = LoggerFactory.getLogger(ZaloInviteSender.class);
    public static final String CHANNEL = "ZALO_AUTO";
    /** Số lời mời xem xét mỗi nhịp để bỏ qua (và đóng) những khách không còn gửi được mà không làm tắc hàng. */
    private static final int SCAN = 25;

    public enum Step { NOT_ACTIVE, BLOCKED, NOTHING, INVITE_SENT, FOLLOWUP_SENT, FRIEND_SENT, FAILED_ONE, RETRY, TRIPPED }

    private record Invite(long id, long campaignId, long customerId, String phone, String name, String province, String token,
                          OffsetDateTime expiresAt, String template) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final ZaloMessageService messages;
    private final ContactService contacts;
    private final InviteService invites;
    private final SendService sends;
    private final Clock clock;
    private final ZaloCampaignIntake intake;
    private final TestMode testMode;
    private final CampaignPolicy policy;
    private final CampaignSteps steps;
    private final ZaloFollowupSender followups;
    private final ZaloFriendRequestSender friendRequests;
    private final CampaignAutoStop autoStop;
    private final RainHold rain;

    public ZaloInviteSender(JdbcClient jdbc, AppSettings settings, ZaloMessageService messages, ContactService contacts,
                            InviteService invites, SendService sends, Clock clock,
                            ZaloCampaignIntake intake, TestMode testMode, CampaignPolicy policy, CampaignSteps steps,
                            ZaloFollowupSender followups, ZaloFriendRequestSender friendRequests, CampaignAutoStop autoStop, RainHold rain) {
        this.autoStop = autoStop;
        this.rain = rain;
        this.steps = steps;
        this.followups = followups;
        this.friendRequests = friendRequests;
        this.intake = intake;
        this.policy = policy;
        this.testMode = testMode;
        this.jdbc = jdbc;
        this.settings = settings;
        this.messages = messages;
        this.contacts = contacts;
        this.invites = invites;
        this.sends = sends;
        this.clock = clock;
    }

    /** Một nhịp: gửi tối đa MỘT tin nếu được phép. */
    public Step step() {
        if (!messages.enabled() || settings.paused()) {
            return Step.NOT_ACTIVE;
        }
        // Chiến dịch có khách từ chối nhiều thì bị bỏ duyệt trước (không gọi Zalo); rồi mới tới chuyện nhận khách mới và gửi.
        autoStop.evaluate();
        // Chiến dịch đang tự nhận khách mới thì nhận khách mới đủ tiêu chí vào hàng chờ trước (không gọi Zalo).
        intake.intake();
        // Tin nhắc của chiến dịch nhiều lần gửi (D21 PR 5b): đi trước Tin 1 của khách mới vì có hạn, cùng hạn mức ngày và khoảng nghỉ.
        ZaloFollowupSender.Step follow;
        try {
            follow = followups.step();
        } catch (RuntimeException e) {
            // một lỗi ở phần tin nhắc không được làm kẹt Tin 1 của khách mới
            log.warn("Phần tin nhắc gặp lỗi: {}", e.getClass().getSimpleName());
            follow = ZaloFollowupSender.Step.NOTHING;
        }
        switch (follow) {
            case SENT -> {
                return Step.FOLLOWUP_SENT;
            }
            case FAILED_ONE -> {
                return Step.FAILED_ONE;
            }
            case RETRY -> {
                return Step.RETRY;
            }
            case TRIPPED -> {
                return Step.TRIPPED;
            }
            default -> {
                // không có tin nhắc nào đến hạn hoặc được phép: sang Tin 1
            }
        }
        // Lời mời kết bạn cho người đã nhận Tin 1 (24/11/2026): đi trước Tin 1 của khách mới để khách vừa nhận Tin 1 thấy lời mời sớm; trần riêng mỗi ngày.
        ZaloFriendRequestSender.Step friend;
        try {
            friend = friendRequests.step();
        } catch (RuntimeException e) {
            log.warn("Phần lời mời kết bạn gặp lỗi: {}", e.getClass().getSimpleName());
            friend = ZaloFriendRequestSender.Step.NOTHING;
        }
        switch (friend) {
            case SENT -> {
                return Step.FRIEND_SENT;
            }
            case FAILED_ONE -> {
                return Step.FAILED_ONE;
            }
            case RETRY -> {
                return Step.RETRY;
            }
            case TRIPPED -> {
                return Step.TRIPPED;
            }
            default -> {
                // chưa có lời mời nào đến lượt hoặc được phép: sang Tin 1
            }
        }
        // Tin 1: hạn mức ngày tính theo số tin chủ động (Tin 1 và tin nhắc).
        if (messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.INVITE, null, null).isPresent()) {
            return Step.BLOCKED;
        }
        return sendNextInvite();
    }

    // ---------- Tin 1 ----------

    private Step sendNextInvite() {
        // Lịch và nhịp riêng của chiến dịch (D21 PR 4) chỉ để BỎ QUA chiến dịch chưa tới lượt, không bao giờ để gửi thêm; chiến dịch
        // chưa đặt gì thì không bị kiểm gì. Bỏ qua từ đầu để một chiến dịch đang chờ không làm tắc hàng của chiến dịch khác.
        List<Long> open = jdbc.sql("""
                        SELECT DISTINCT i.campaign_id FROM invitation i JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.status = 'READY' AND k.auto_send_approved_at IS NOT NULL ORDER BY i.campaign_id""")
                .query(Long.class).list().stream().filter(id -> policy.blocker(id).isEmpty()).toList();
        if (open.isEmpty()) {
            return Step.NOTHING;
        }
        // Chiến dịch bật "hoãn khi mưa lớn": khách ở tỉnh đang mưa lớn bị bỏ qua nhịp này (chỉ hoãn); lọc ngay trong truy vấn để
        // những khách bị hoãn không chiếm hết SCAN và làm tắc khách ở tỉnh khác.
        String[] rainy = rain.rainyProvinces().toArray(new String[0]);
        List<Invite> candidates = jdbc.sql("""
                        SELECT i.id, i.campaign_id, i.customer_id, c.phone, c.name, c.province, i.token, i.expires_at, k.message_template
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.status = 'READY' AND k.auto_send_approved_at IS NOT NULL AND i.campaign_id IN (:open)
                          AND (k.hold_on_rain = false OR c.province IS NULL OR NOT (c.province = ANY(:rainy)))
                        """ + testMode.onlyTestSql("c.phone") + " ORDER BY i.campaign_id, i.id LIMIT :n")
                .param("open", open).param("rainy", rainy).param("n", SCAN)
                .query((rs, n) -> new Invite(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"),
                        rs.getString("phone"), rs.getString("name"), rs.getString("province"), rs.getString("token"),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getString("message_template"))).list();
        for (Invite c : candidates) {
            if (c.expiresAt().isBefore(OffsetDateTime.now(clock))) {
                closeUnsendable(c, "Link đã hết hạn");
                continue;
            }
            if (!contacts.stillEligible(c.customerId())) {
                closeUnsendable(c, "Không còn đủ điều kiện liên hệ");
                continue;
            }
            if (InviteService.hasUnresolved(invites.render(c.template(), c.name(), invites.linkFor(c.token()),
                    VnGeo.byCode(c.province()).orElse(null)))) {
                // Mẫu tin còn dấu {...} chưa thay được: không gửi, không đổi trạng thái; người sửa mẫu ở trang chiến dịch.
                log.warn("Chiến dịch {} có mẫu Tin 1 còn chỗ điền chưa thay được; bỏ qua, không gửi", c.campaignId());
                continue;
            }
            return sendInvite(c);
        }
        return Step.NOTHING;
    }

    /** Khách không còn gửi được: đóng lời mời (FAILED, có lý do) để không bị xem lại mỗi nhịp. Không gọi Zalo, không tính hạn mức. */
    private void closeUnsendable(Invite c, String reason) {
        int n = jdbc.sql("UPDATE invitation SET status = 'FAILED', last_error = :e WHERE id = :id AND status = 'READY'")
                .param("e", reason).param("id", c.id()).update();
        if (n > 0) {
            sends.recordExternal(c.id(), c.campaignId(), c.customerId(), c.phone(), CHANNEL, "SKIPPED", reason, c.template());
        }
    }

    private Step sendInvite(Invite c) {
        String text = invites.render(c.template(), c.name(), invites.linkFor(c.token()), VnGeo.byCode(c.province()).orElse(null));
        // Chiến dịch nhiều lần gửi: MÃ tự thêm câu xin ngừng vào cuối mọi lần gửi, kể cả Tin 1 (không phụ thuộc người soạn có nhớ hay không).
        if (steps.plan(c.campaignId()).multiple() && !text.contains(CampaignSteps.STOP_FOOTER)) {
            text = text + "\n" + CampaignSteps.STOP_FOOTER;
        }
        // khóa lời mời trước khi gọi: hai nhịp chồng nhau không thể gửi trùng một khách
        int claimed = jdbc.sql("""
                        UPDATE invitation SET status = 'SENT', sent_at = :at, sent_channel = :ch
                        WHERE id = :id AND status = 'READY'""")
                .param("at", OffsetDateTime.now(clock)).param("ch", CHANNEL).param("id", c.id()).update();
        if (claimed == 0) {
            return Step.NOTHING;
        }
        String digits = c.phone().replaceAll("\\D", "");
        Delivery d;
        try {
            d = messages.sendByPhone(ZaloMessageService.SCOPE_REAL, Kind.INVITE, c.id(), digits, text, null, null);
        } catch (RuntimeException e) {
            log.warn("Gửi Tin 1 báo lỗi: {}", e.getClass().getSimpleName());
            failUnknown(c, "LOI_HE_THONG");
            return Step.TRIPPED;
        }
        switch (d.status()) {
            case SENT -> {
                if (d.uid() != null) {
                    jdbc.sql("UPDATE invitation SET zalo_target_uid = :u WHERE id = :id").param("u", d.uid())
                            .param("id", c.id()).update();
                }
                recordTouch(c.id(), "SENT");
                sends.recordExternal(c.id(), c.campaignId(), c.customerId(), c.phone(), CHANNEL, "SENT", "Tin 1 tự gửi qua bộ nối Zalo", c.template());
                contacts.event(c.customerId(), c.phone(), "INVITATION_SENT", "Kênh: " + vn.shop.crm.common.Labels.channelLabel(CHANNEL));
                return Step.INVITE_SENT;
            }
            case PERSON_ERROR -> {
                return personError(c, d);
            }
            case RETRY, BLOCKED -> {
                revert(c);
                return Step.RETRY;
            }
            default -> {
                if (d.certainlyNotSent()) {
                    revert(c); // cầu dao nhảy nhưng chắc chắn chưa gửi: mở lại thì gửi tiếp
                } else {
                    failUnknown(c, d.code());
                }
                return Step.TRIPPED;
            }
        }
    }

    private Step personError(Invite c, Delivery d) {
        String code = d.code() == null ? "LOI_NGUOI" : d.code();
        if ("DUPLICATE".equals(code)) {
            // cầu nối nhớ đã gửi Tin 1 cho mã Zalo này rồi: giữ SENT, không gửi lại
            if (d.uid() != null) {
                jdbc.sql("UPDATE invitation SET zalo_target_uid = :u WHERE id = :id").param("u", d.uid()).param("id", c.id()).update();
            }
            sends.recordExternal(c.id(), c.campaignId(), c.customerId(), c.phone(), CHANNEL, "SKIPPED",
                    "Cầu nối báo đã gửi Tin 1 cho người này trước đó", c.template());
            return Step.FAILED_ONE;
        }
        String reason = "NOT_FOUND".equals(code)
                ? "Không tìm thấy trên Zalo (số chưa có Zalo hoặc người này không cho tìm bằng số điện thoại)"
                : "Zalo từ chối gửi cho người này (mã " + code + ")";
        jdbc.sql("UPDATE invitation SET status = 'FAILED', last_error = :e WHERE id = :id").param("e", reason).param("id", c.id()).update();
        // "không tìm thấy" là chuyện thường của khách: ghi SKIPPED để không bị tính vào tự dừng vì lỗi gửi liên tiếp
        boolean common = "NOT_FOUND".equals(code) || code.startsWith("BAD_");
        sends.recordExternal(c.id(), c.campaignId(), c.customerId(), c.phone(), CHANNEL, common ? "SKIPPED" : "FAILED", reason, c.template());
        if (!common) {
            sends.evaluateAutoStop();
        }
        return Step.FAILED_ONE;
    }

    private void revert(Invite c) {
        jdbc.sql("""
                        UPDATE invitation SET status = 'READY', sent_at = NULL, sent_channel = NULL
                        WHERE id = :id AND status = 'SENT' AND sent_channel = :ch""").param("id", c.id()).param("ch", CHANNEL).update();
    }

    /** Ghi Tin 1 vào sổ đếm cứng (bước 1) để các tin nhắc tính khoảng cách và trần 3 lần từ một nguồn. */
    private void recordTouch(long invitationId, String outcome) {
        jdbc.sql("""
                        INSERT INTO invitation_touch (invitation_id, step_no, sent_at, outcome) VALUES (:id, 1, :at, :o)
                        ON CONFLICT (invitation_id, step_no) DO UPDATE SET outcome = EXCLUDED.outcome, sent_at = EXCLUDED.sent_at""")
                .param("id", invitationId).param("at", OffsetDateTime.now(clock)).param("o", outcome).update();
    }

    /** Không biết tin đã đi hay chưa: giữ FAILED để người kiểm tra trên Zalo, KHÔNG tự gửi lại. */
    private void failUnknown(Invite c, String code) {
        recordTouch(c.id(), "UNKNOWN");
        String reason = "Không rõ tin đã gửi chưa (" + (code == null ? "lỗi" : code) + "). Hãy kiểm tra trên Zalo rồi xử lý tay";
        jdbc.sql("UPDATE invitation SET status = 'FAILED', last_error = :e WHERE id = :id").param("e", reason).param("id", c.id()).update();
        sends.recordExternal(c.id(), c.campaignId(), c.customerId(), c.phone(), CHANNEL, "FAILED", reason, c.template());
        sends.evaluateAutoStop();
    }

    // ---------- duyệt chiến dịch ----------

    /** Người dùng DUYỆT chiến dịch cho hệ thống tự gửi Tin 1. Chưa duyệt thì không tự gửi gì. */
    public void approveCampaign(long campaignId, String by) {
        int n = jdbc.sql("""
                        UPDATE invitation_campaign SET auto_send_approved_at = now(), auto_send_approved_by = :by,
                               auto_stop_since = CASE WHEN auto_stopped_at IS NOT NULL THEN now() ELSE auto_stop_since END,
                               auto_stopped_at = NULL, auto_stopped_reason = NULL
                        WHERE id = :id AND auto_send_approved_at IS NULL""").param("by", by).param("id", campaignId).update();
        if (n == 0 && !campaignExists(campaignId)) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
        messages.audit("DUYET_CHIEN_DICH", campaignId, by);
    }

    /** Bỏ duyệt: dừng tự gửi cho chiến dịch này ngay (các tin đã gửi giữ nguyên). */
    public void revokeCampaign(long campaignId, String by) {
        int n = jdbc.sql("""
                        UPDATE invitation_campaign SET auto_send_approved_at = NULL, auto_send_approved_by = NULL
                        WHERE id = :id AND auto_send_approved_at IS NOT NULL""").param("id", campaignId).update();
        if (n == 0 && !campaignExists(campaignId)) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
        messages.audit("BO_DUYET_CHIEN_DICH", campaignId, by);
    }

    public boolean approved(long campaignId) {
        return jdbc.sql("SELECT auto_send_approved_at IS NOT NULL FROM invitation_campaign WHERE id = :id").param("id", campaignId)
                .query(Boolean.class).optional().orElse(false);
    }

    private boolean campaignExists(long id) {
        return jdbc.sql("SELECT count(*) FROM invitation_campaign WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }
}
