package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tự gửi TIN NHẮC (lần 2 và lần 3) của chiến dịch nhiều lần gửi (D21 PR 5b, thiết kế mục 5.3). NƠI DUY NHẤT dùng {@code Kind.FOLLOWUP*}.
 * Mỗi nhịp tối đa MỘT tin, qua {@link ZaloMessageService} (công tắc, giờ, hạn mức ngày tính cả tin nhắc, khoảng nghỉ cố định, cầu dao dùng chung)
 * rồi bộ nối (chỉ theo mã Zalo của người ĐÃ nhận Tin 1 qua chính bộ nối, và chỉ khi bộ nối đang nghe tin riêng).
 *
 * <p>Chốt chặn riêng của tin nhắc, tất cả kiểm lại NGAY trước mỗi lần gửi (bằng một truy vấn duy nhất, không dùng dữ liệu cũ):
 * <ul>
 *   <li>phần đọc tin riêng của AI phải đang chạy (không nghe được khách xin ngừng thì không nhắc);</li>
 *   <li>chiến dịch còn được duyệt tự gửi, không tạm dừng tất cả, còn trong lịch riêng ({@link CampaignPolicy}, chỉ chặt hơn trần hệ thống),
 *       và còn cho phép lần gửi đó ({@code max_touches}, có tin nhắc đã cấu hình);</li>
 *   <li>lời mời còn đúng trạng thái "đã gửi Tin 1": chưa kết bạn, chưa vào nhóm, chưa bị hủy hay tạm dừng (khách từ chối, AI nghi từ chối),
 *       chưa hết hạn; khách chưa TỪNG nhắn riêng lại bất cứ điều gì ({@code inbound_at}); khách còn đủ điều kiện liên hệ (chưa từ chối, chưa bị chặn);</li>
 *   <li>đã qua đủ số ngày (sàn 3 ngày) kể từ lần gửi thành công liền trước; lần ba cần lần hai đã gửi thành công;</li>
 *   <li>trần cứng 3 lần mỗi khách: khóa chính {@code (lời mời, lần)} ở sổ đếm {@code invitation_touch}.</li>
 * </ul>
 * Ghi sổ đếm TRƯỚC khi gọi (outcome UNKNOWN): sập giữa chừng, hết giờ chờ hay Zalo không xác nhận thì dòng đó ở lại UNKNOWN, KHÔNG BAO GIỜ tự gửi lại
 * và các lần sau của khách đó cũng dừng. Chỉ khi chắc chắn chưa chạm Zalo (bị chặn, cầu nối từ chối trước) thì mới xóa dòng để thử lại ở nhịp sau.
 * Không có số ngẫu nhiên; nội dung do mã thêm câu xin ngừng ({@link CampaignSteps#STOP_FOOTER}).
 */
@Service
public class ZaloFollowupSender {

    private static final Logger log = LoggerFactory.getLogger(ZaloFollowupSender.class);
    /** Số tin nhắc xem xét mỗi nhịp để bỏ qua (và đóng) khách không còn gửi được mà không làm tắc hàng. */
    private static final int SCAN = 25;

    public enum Step { NOT_ACTIVE, BLOCKED, NOTHING, SENT, FAILED_ONE, RETRY, TRIPPED }

    private record Task(long invitationId, long campaignId, long customerId, String phone, String uid, String name, String province,
                        String token, int stepNo, String template) {
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
    private final ZaloInboxService inbox;
    private final RainHold rain;

    public ZaloFollowupSender(JdbcClient jdbc, AppSettings settings, ZaloMessageService messages, ContactService contacts,
                              InviteService invites, SendService sends, Clock clock, TestMode testMode, CampaignPolicy policy,
                              ZaloInboxService inbox, RainHold rain) {
        this.rain = rain;
        this.jdbc = jdbc;
        this.settings = settings;
        this.messages = messages;
        this.contacts = contacts;
        this.invites = invites;
        this.sends = sends;
        this.clock = clock;
        this.testMode = testMode;
        this.policy = policy;
        this.inbox = inbox;
    }

    /** Một nhịp: gửi tối đa MỘT tin nhắc nếu được phép. */
    public Step step() {
        if (!messages.enabled() || settings.paused() || !inbox.running()) {
            return Step.NOT_ACTIVE;
        }
        // Lần 2 và lần 3 cùng luật chặn nên kiểm một lần cho nhẹ; deliver() còn kiểm lại theo đúng loại tin ngay trước khi gọi.
        if (messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.FOLLOWUP2, null, null).isPresent()) {
            return Step.BLOCKED;
        }
        List<Task> due = due();
        if (due.isEmpty()) {
            return Step.NOTHING;
        }
        Map<Long, Boolean> open = new HashMap<>();
        for (Task t : due) {
            // Lịch và nhịp riêng của chiến dịch chỉ để BỎ QUA chiến dịch chưa tới lượt, không bao giờ để gửi thêm.
            if (!open.computeIfAbsent(t.campaignId(), id -> policy.blocker(id).isEmpty())) {
                continue;
            }
            if (!contacts.stillEligible(t.customerId())) {
                close(t, "Không còn đủ điều kiện liên hệ");
                continue;
            }
            if (InviteService.hasUnresolved(invites.render(t.template(), t.name(), invites.linkFor(t.token()),
                    VnGeo.byCode(t.province()).orElse(null)))) {
                log.warn("Chiến dịch {} có tin nhắc còn chỗ điền chưa thay được; bỏ qua, không gửi", t.campaignId());
                continue;
            }
            return send(t);
        }
        return Step.NOTHING;
    }

    /**
     * Các tin nhắc đã đến hạn. Một truy vấn duy nhất gom mọi điều kiện dừng, nên đây cũng chính là lần kiểm lại ngay trước khi gửi
     * (nhịp gọi {@link #send} ngay sau đó, và việc giành chỗ ở {@code invitation_touch} chặn hai nhịp chồng nhau).
     */
    private List<Task> due() {
        return jdbc.sql("""
                        SELECT i.id, i.campaign_id, i.customer_id, c.phone, i.zalo_target_uid, c.name, c.province, i.token,
                               s.step_no, s.template
                        FROM invitation i
                        JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        JOIN campaign_step s ON s.campaign_id = k.id AND s.step_no <= k.max_touches
                        LEFT JOIN invitation_touch prev ON prev.invitation_id = i.id AND prev.step_no = s.step_no - 1
                        WHERE i.status = 'SENT' AND i.sent_channel = :ch AND i.zalo_target_uid IS NOT NULL
                          AND i.friended_at IS NULL AND i.joined_at IS NULL AND i.inbound_at IS NULL
                          AND i.tin1_blocked_at IS NULL
                          AND i.expires_at > :now
                          AND k.auto_send_approved_at IS NOT NULL
                          AND (k.hold_on_rain = false OR c.province IS NULL OR NOT (c.province = ANY(:rainy)))
                          AND NOT EXISTS (SELECT 1 FROM invitation_touch t WHERE t.invitation_id = i.id AND t.step_no = s.step_no)
                          AND ((s.step_no = 2 AND i.sent_at <= :now - s.gap_days * interval '1 day')
                            OR (s.step_no = 3 AND prev.outcome = 'SENT' AND prev.sent_at <= :now - s.gap_days * interval '1 day'))
                        """ + testMode.onlyTestSql("c.phone") + " AND " + ZaloAccountContext.sql("i.account_id") + " ORDER BY i.sent_at, s.step_no, i.id LIMIT :n")
                .param("ch", ZaloInviteSender.CHANNEL).param("now", OffsetDateTime.now(clock)).param("n", SCAN)
                .param("rainy", rain.rainyProvinces().toArray(new String[0]))
                .query((rs, n) -> new Task(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"), rs.getString("phone"),
                        rs.getString("zalo_target_uid"), rs.getString("name"), rs.getString("province"), rs.getString("token"),
                        rs.getInt("step_no"), rs.getString("template")))
                .list();
    }

    private Step send(Task t) {
        String text = invites.render(t.template(), t.name(), invites.linkFor(t.token()), VnGeo.byCode(t.province()).orElse(null));
        if (!text.contains(CampaignSteps.STOP_FOOTER)) {
            text = text + "\n" + CampaignSteps.STOP_FOOTER;
        }
        Kind kind = t.stepNo() == 2 ? Kind.FOLLOWUP2 : Kind.FOLLOWUP3;
        // Giành chỗ TRƯỚC khi gọi: khóa chính (lời mời, lần) nên hai nhịp chồng nhau không thể gửi trùng, và sập giữa chừng thì dòng ở lại UNKNOWN.
        // Việc giành chỗ cũng kiểm lại trạng thái lời mời trong CHÍNH câu lệnh (khách vừa kết bạn, vào nhóm, bị hủy hay vừa nhắn tới giữa
        // lúc chọn và lúc gửi thì không giành được chỗ, nên không gửi).
        int claimed = jdbc.sql("""
                        INSERT INTO invitation_touch (invitation_id, step_no, sent_at, outcome)
                        SELECT i.id, :no, :at, 'UNKNOWN' FROM invitation i
                        WHERE i.id = :id AND i.status = 'SENT' AND i.friended_at IS NULL AND i.joined_at IS NULL AND i.inbound_at IS NULL
                        ON CONFLICT DO NOTHING""")
                .param("id", t.invitationId()).param("no", t.stepNo()).param("at", OffsetDateTime.now(clock)).update();
        if (claimed == 0) {
            return Step.NOTHING;
        }
        Delivery d;
        try {
            d = messages.sendByUid(ZaloMessageService.SCOPE_REAL, kind, t.invitationId(), t.uid(), text, null, null);
        } catch (RuntimeException e) {
            log.warn("Gửi tin nhắc báo lỗi: {}", e.getClass().getSimpleName());
            unknown(t, text, "LOI_HE_THONG");
            return Step.TRIPPED;
        }
        switch (d.status()) {
            case SENT -> {
                setOutcome(t, "SENT");
                sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "SENT",
                        "Tin nhắc lần " + t.stepNo() + " tự gửi qua bộ nối Zalo", text);
                contacts.event(t.customerId(), t.phone(), "INVITATION_FOLLOWUP", "Lần " + t.stepNo());
                return Step.SENT;
            }
            case PERSON_ERROR -> {
                return personError(t, text, d);
            }
            case RETRY, BLOCKED -> {
                release(t); // chắc chắn chưa chạm Zalo: trả chỗ để nhịp sau thử lại
                return Step.RETRY;
            }
            default -> {
                if (d.certainlyNotSent()) {
                    release(t);
                } else {
                    unknown(t, text, d.code());
                }
                return Step.TRIPPED;
            }
        }
    }

    private Step personError(Task t, String text, Delivery d) {
        String code = d.code() == null ? "LOI_NGUOI" : d.code();
        if ("CUSTOMER_REPLIED".equals(code)) {
            // bộ nối vừa thấy khách nhắn tới mà Adh chưa kịp đọc: coi như khách đã lên tiếng, các tin nhắc dừng hẳn
            jdbc.sql("UPDATE invitation SET inbound_at = now() WHERE id = :id AND inbound_at IS NULL").param("id", t.invitationId()).update();
            release(t);
            return Step.FAILED_ONE;
        }
        setOutcome(t, "FAILED");
        String reason = "Tin nhắc lần " + t.stepNo() + " không gửi được (mã " + code + ")";
        boolean common = "NO_INVITE".equals(code) || "NO_PREVIOUS".equals(code) || "DUPLICATE".equals(code) || "NOT_FOUND".equals(code)
                || code.startsWith("BAD_");
        sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, common ? "SKIPPED" : "FAILED", reason, text);
        if (!common) {
            sends.evaluateAutoStop();
        }
        return Step.FAILED_ONE;
    }

    /** Khách không còn gửi được: đóng lần này (FAILED) để không bị xem lại mỗi nhịp, và các lần sau cũng không bao giờ đến lượt. */
    private void close(Task t, String reason) {
        int n = jdbc.sql("""
                        INSERT INTO invitation_touch (invitation_id, step_no, sent_at, outcome) VALUES (:id, :no, :at, 'FAILED')
                        ON CONFLICT DO NOTHING""")
                .param("id", t.invitationId()).param("no", t.stepNo()).param("at", OffsetDateTime.now(clock)).update();
        if (n > 0) {
            sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "SKIPPED",
                    "Tin nhắc lần " + t.stepNo() + ": " + reason, t.template());
        }
    }

    /** Không biết tin đã đi hay chưa: giữ UNKNOWN (không bao giờ tự gửi lại) và để người kiểm tra trên Zalo. */
    private void unknown(Task t, String text, String code) {
        String reason = "Không rõ tin nhắc lần " + t.stepNo() + " đã gửi chưa (" + (code == null ? "lỗi" : code) + "). Hãy kiểm tra trên Zalo";
        sends.recordExternal(t.invitationId(), t.campaignId(), t.customerId(), t.phone(), ZaloInviteSender.CHANNEL, "FAILED", reason, text);
        sends.evaluateAutoStop();
    }

    private void setOutcome(Task t, String outcome) {
        jdbc.sql("UPDATE invitation_touch SET outcome = :o, sent_at = :at WHERE invitation_id = :id AND step_no = :no")
                .param("o", outcome).param("at", OffsetDateTime.now(clock)).param("id", t.invitationId()).param("no", t.stepNo()).update();
    }

    /** Chỉ dùng khi CHẮC CHẮN chưa có tin nào đi: xóa dòng giành chỗ (đang UNKNOWN) để thử lại. */
    private void release(Task t) {
        jdbc.sql("DELETE FROM invitation_touch WHERE invitation_id = :id AND step_no = :no AND outcome = 'UNKNOWN'")
                .param("id", t.invitationId()).param("no", t.stepNo()).update();
    }
}
