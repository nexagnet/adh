package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.invite.InviteService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Chiến dịch TỰ NHẬN khách mới (chủ shop chốt 23/11/2026): "bỏ duyệt từng người, thay bằng tiêu chí duyệt sẵn của chiến dịch".
 *
 * <p>Khi bật cho MỘT chiến dịch đã duyệt tự gửi, mỗi nhịp hệ thống lấy khách MỚI (nhập đơn sau thời điểm bật, {@code first_seen_at}) thỏa
 * tiêu chí duyệt sẵn: chỉ mua một lần, không cần kiểm tra, chưa từ chối, chưa duyệt hay chặn, chưa từng được mời, đúng khu vực của chiến dịch.
 * Các khách đó được duyệt cơ sở liên hệ (ghi rõ "tự duyệt theo chiến dịch") và có lời mời READY; Tin 1 đi sau đó qua {@link ZaloInviteSender}
 * với đủ chốt chặn cũ (công tắc, nấc, giờ gửi, hạn mức 50 khách/ngày, khoảng nghỉ, cầu dao, một Tin 1 mỗi khách).
 *
 * <p>Chỉ nhận khách có mặt SAU lúc bật: khách nhập từ trước (kể cả khách chưa duyệt mà mẹ chủ ý giữ lại) không bị tự duyệt. Hàng chờ có trần
 * (không quá {@code max-campaign}, và không quá số khách gửi được trước khi lời mời hết hạn) để không dồn đống rồi hết hạn. Tắt mặc định.
 * Bỏ duyệt chiến dịch, tạm dừng tất cả hoặc tắt công tắc tự gửi thì ngừng nhận ngay.
 */
@Service
public class ZaloCampaignIntake {

    private static final Logger log = LoggerFactory.getLogger(ZaloCampaignIntake.class);
    public static final String ACTOR = "chien-dich-tu-nhan";
    static final String STATEMENT = "Tự duyệt theo tiêu chí của chiến dịch (khách mới, mua một lần, chưa từ chối)";
    /** Mỗi nhịp nhận tối đa chừng này khách. */
    static final int PER_TICK = 50;

    public record Status(boolean active, Instant since, String by, long ready, long taken, boolean otherActive) {
    }

    private final JdbcClient jdbc;
    private final ContactService contacts;
    private final InviteService invites;
    private final ZaloMessageService messages;
    private final AppSettings settings;
    private final TransactionTemplate tx;

    public ZaloCampaignIntake(JdbcClient jdbc, ContactService contacts, InviteService invites, ZaloMessageService messages,
                              AppSettings settings, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.invites = invites;
        this.messages = messages;
        this.settings = settings;
        this.tx = tx;
    }

    // ---------- bật / tắt ----------

    /** Bật tự nhận cho chiến dịch. Chỉ khi chiến dịch đã được duyệt cho tự gửi, và chưa có chiến dịch nào khác đang tự nhận. */
    public void enable(long campaignId, String by) {
        Boolean approved = jdbc.sql("SELECT auto_send_approved_at IS NOT NULL FROM invitation_campaign WHERE id = :id")
                .param("id", campaignId).query(Boolean.class).optional().orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
        if (!approved) {
            throw new ImportException("Hãy duyệt chiến dịch cho hệ thống tự gửi trước, rồi mới bật tự nhận khách mới.");
        }
        Optional<Long> other = jdbc.sql("SELECT id FROM invitation_campaign WHERE auto_intake_since IS NOT NULL AND id <> :id")
                .param("id", campaignId).query(Long.class).optional();
        if (other.isPresent()) {
            throw new ImportException("Chiến dịch số " + other.get() + " đang tự nhận khách mới. Mỗi lúc chỉ một chiến dịch tự nhận; hãy tắt chiến dịch đó trước.");
        }
        int n = jdbc.sql("""
                        UPDATE invitation_campaign SET auto_intake_since = now(), auto_intake_by = :by
                        WHERE id = :id AND auto_intake_since IS NULL""").param("by", by).param("id", campaignId).update();
        if (n > 0) {
            messages.audit("TU_NHAN_BAT", campaignId, by);
        }
    }

    public void disable(long campaignId, String by) {
        int n = jdbc.sql("""
                        UPDATE invitation_campaign SET auto_intake_since = NULL, auto_intake_by = NULL
                        WHERE id = :id AND auto_intake_since IS NOT NULL""").param("id", campaignId).update();
        if (n > 0) {
            messages.audit("TU_NHAN_TAT", campaignId, by);
        }
    }

    public Status status(long campaignId) {
        Instant since = jdbc.sql("SELECT auto_intake_since FROM invitation_campaign WHERE id = :id").param("id", campaignId)
                .query((rs, i) -> {
                    OffsetDateTime t = rs.getObject(1, OffsetDateTime.class);
                    return t == null ? null : t.toInstant();
                }).optional().orElse(null);
        String by = jdbc.sql("SELECT auto_intake_by FROM invitation_campaign WHERE id = :id").param("id", campaignId)
                .query(String.class).optional().orElse(null);
        long ready = jdbc.sql("SELECT count(*) FROM invitation WHERE campaign_id = :id AND status = 'READY'").param("id", campaignId)
                .query(Long.class).single();
        long taken = jdbc.sql("""
                        SELECT count(*) FROM invitation i JOIN contact_basis b ON b.customer_id = i.customer_id AND b.recorded_by = :a
                        WHERE i.campaign_id = :id""").param("a", ACTOR).param("id", campaignId).query(Long.class).single();
        boolean other = jdbc.sql("SELECT count(*) FROM invitation_campaign WHERE auto_intake_since IS NOT NULL AND id <> :id")
                .param("id", campaignId).query(Long.class).single() > 0;
        return new Status(since != null, since, by, ready, taken, other);
    }

    // ---------- mỗi nhịp ----------

    private record Active(long id, Instant since, String scope, int expiresDays, String name, vn.shop.crm.contact.CustomerFilter filter) {
    }

    /** Nhận khách mới của chiến dịch đang tự nhận (nếu có). Trả số khách vừa nhận. Lỗi thì không ném ra: nhịp gửi vẫn chạy. */
    public int intake() {
        try {
            return doIntake();
        } catch (RuntimeException e) {
            log.warn("Tự nhận khách mới gặp lỗi: {}", e.getClass().getSimpleName());
            return 0;
        }
    }

    private int doIntake() {
        if (!messages.enabled() || settings.paused()) {
            return 0;
        }
        Optional<Active> a = jdbc.sql("""
                        SELECT id, auto_intake_since, geo_scope, expires_days, name, customer_filter::text AS customer_filter FROM invitation_campaign
                        WHERE auto_intake_since IS NOT NULL AND auto_send_approved_at IS NOT NULL""")
                .query((rs, i) -> new Active(rs.getLong("id"), rs.getObject("auto_intake_since", OffsetDateTime.class).toInstant(),
                        rs.getString("geo_scope"), rs.getInt("expires_days"), rs.getString("name"),
                        vn.shop.crm.contact.CustomerFilter.fromJson(rs.getString("customer_filter")))).optional();
        if (a.isEmpty()) {
            return 0;
        }
        Active c = a.get();
        long ready = jdbc.sql("SELECT count(*) FROM invitation WHERE campaign_id = :id AND status = 'READY'").param("id", c.id())
                .query(Long.class).single();
        // Hàng chờ không quá trần chiến dịch, và không quá số khách gửi được trước khi lời mời hết hạn (hạn mức ngày x số ngày còn lại).
        long reachable = (long) messages.limits().daily() * Math.max(1, c.expiresDays() - 1);
        long room = Math.min(invites.maxCampaign(), reachable) - ready;
        if (room <= 0) {
            return 0;
        }
        List<ContactService.Invitee> picked = contacts.newCandidates(c.since(), c.scope(), (int) Math.min(room, PER_TICK), c.filter());
        if (picked.isEmpty()) {
            return 0;
        }
        Integer taken = tx.execute(s -> {
            List<Long> ids = picked.stream().map(ContactService.Invitee::id).toList();
            int approved = contacts.approveByIds(ids, STATEMENT, ACTOR);
            if (approved == 0) {
                return 0;
            }
            // chỉ tạo lời mời cho những khách vừa được duyệt (điều kiện được kiểm lại ở bước duyệt)
            List<ContactService.Invitee> ok = picked.stream().filter(p -> approvedBy(p.id())).toList();
            invites.addInvitations(c.id(), c.expiresDays(), c.name(), ok);
            return ok.size();
        });
        int n = taken == null ? 0 : taken;
        if (n > 0) {
            messages.audit("TU_NHAN " + n, c.id(), ACTOR);
        }
        return n;
    }

    private boolean approvedBy(long customerId) {
        return jdbc.sql("SELECT count(*) FROM contact_basis WHERE customer_id = :c AND recorded_by = :a AND status = 'ELIGIBLE'")
                .param("c", customerId).param("a", ACTOR).query(Long.class).single() > 0
                && jdbc.sql("SELECT count(*) FROM invitation WHERE customer_id = :c").param("c", customerId).query(Long.class).single() == 0;
    }
}
