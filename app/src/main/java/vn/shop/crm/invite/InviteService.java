package vn.shop.crm.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Chiến dịch mời và lời mời có link riêng (mốc 1C, mục 6 file 01, mục 7 file 02).
 *
 * <p>Nguyên tắc: (1) lời mời CHỈ lấy từ danh sách đủ điều kiện mời của {@link ContactService}, mỗi khách một lần;
 * (2) token là chuỗi ngẫu nhiên, không chứa SĐT; (3) lượt bấm chỉ là "lượt bấm", không phải "đã vào nhóm";
 * (4) điều kiện được kiểm lại khi khách bấm, nên khách đã từ chối/bị chặn thì link cũ không còn dùng được.
 * Mốc này KHÔNG gửi tin cho ai.
 */
@Service
public class InviteService {

    public static final String DEFAULT_TEMPLATE = "Chào {ten}, {cua_hang} cảm ơn bạn đã mua hàng. Cửa hàng lập một "
            + "nhóm Zalo nhỏ để hướng dẫn sử dụng và giải đáp thắc mắc cho khách đã mua. Bạn không bắt buộc tham gia. "
            + "Nếu muốn, mời bạn bấm: {link}";

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Khóa tư vấn Postgres để hai lượt bấm cùng lúc không cùng chọn một nhóm đã gần đầy. */
    private static final long GROUP_PICK_LOCK = 7_301_001L;

    public record Campaign(long id, String name, String template, int expiresDays, String createdBy,
                           LocalDateTime createdAt, long total, long ready, long sent, long clicked, long cancelled) {
    }

    public record InvitationRow(long id, long customerId, String name, String status, String link, String groupName,
                                int clickCount, LocalDateTime expiresAt, boolean expired, LocalDateTime sentAt,
                                String sentChannel, String message) {
    }

    public record Stats(long campaigns, long invitations, long sent, long clicked, long openGroups) {
    }

    public enum Outcome { REDIRECT, UNAVAILABLE, NO_GROUP }

    public record GoResult(Outcome outcome, String url) {
    }

    private final JdbcClient jdbc;
    private final ContactService contacts;
    private final GroupService groups;
    private final AppSettings settings;
    private final String baseUrl;
    private final String shopName;
    private final int maxBatch;

    public InviteService(JdbcClient jdbc, ContactService contacts, GroupService groups, AppSettings settings,
                         @Value("${app.public-base-url:http://localhost:8080}") String baseUrl,
                         @Value("${app.shop-name:Cửa hàng}") String shopName,
                         @Value("${app.invitations.max-batch:20}") int maxBatch) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.groups = groups;
        this.settings = settings;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.shopName = shopName;
        this.maxBatch = maxBatch;
    }

    public int maxBatch() {
        return maxBatch;
    }

    public String shopName() {
        return shopName;
    }

    public String linkFor(String token) {
        return baseUrl + "/i/" + token;
    }

    // ---------- tạo chiến dịch ----------

    /** Tạo chiến dịch gồm tối đa {@code count} lời mời cho khách đủ điều kiện, chưa từng được mời. Chưa gửi gì. */
    @Transactional
    public long createCampaign(String name, String template, int expiresDays, int count) {
        if (settings.paused()) {
            throw new ImportException("Đang tạm dừng tất cả chiến dịch. Hãy bỏ tạm dừng trước khi tạo chiến dịch mới.");
        }
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new ImportException("Hãy đặt tên chiến dịch (tối đa 100 ký tự).");
        }
        String tpl = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template.strip();
        if (!tpl.contains("{link}")) {
            throw new ImportException("Nội dung tin phải có {link} để khách bấm vào.");
        }
        if (tpl.length() > 500) {
            throw new ImportException("Nội dung tin quá dài (tối đa 500 ký tự).");
        }
        if (expiresDays < 1 || expiresDays > 90) {
            throw new ImportException("Link có hiệu lực từ 1 đến 90 ngày.");
        }
        if (count < 1 || count > maxBatch) {
            throw new ImportException("Mỗi lô chỉ được từ 1 đến " + maxBatch + " khách (gửi chậm, theo lô nhỏ).");
        }
        List<ContactService.Invitee> picked = contacts.eligibleNotInvited(count);
        if (picked.isEmpty()) {
            throw new ImportException("Chưa có khách nào đủ điều kiện mời và chưa từng được mời. "
                    + "Hãy duyệt khách ở mục Danh sách mời trước.");
        }
        long campaignId = jdbc.sql("""
                        INSERT INTO invitation_campaign (name, message_template, expires_days, created_by)
                        VALUES (:name, :tpl, :days, :by) RETURNING id""")
                .param("name", name.strip()).param("tpl", tpl).param("days", expiresDays)
                .param("by", ContactService.actor()).query(Long.class).single();
        for (ContactService.Invitee c : picked) {
            jdbc.sql("""
                            INSERT INTO invitation (campaign_id, customer_id, token, expires_at)
                            VALUES (:cid, :cust, :token, now() + make_interval(days => :days))""")
                    .param("cid", campaignId).param("cust", c.id()).param("token", newToken())
                    .param("days", expiresDays).update();
            contacts.event(c.id(), c.phone(), "INVITATION_CREATED", "Chiến dịch: " + name.strip());
        }
        return campaignId;
    }

    static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Xóa các lời mời CHƯA gửi của chiến dịch; khách quay về danh sách "chưa được mời". */
    @Transactional
    public int cancelUnsent(long campaignId) {
        return jdbc.sql("DELETE FROM invitation WHERE campaign_id = :id AND status = 'READY'")
                .param("id", campaignId).update();
    }

    // ---------- xem ----------

    public Stats stats() {
        return new Stats(
                count("SELECT count(*) FROM invitation_campaign"),
                count("SELECT count(*) FROM invitation"),
                count("SELECT count(*) FROM invitation WHERE sent_at IS NOT NULL"),
                count("SELECT count(*) FROM invitation WHERE click_count > 0"),
                count("SELECT count(*) FROM zalo_group WHERE status = 'OPEN'"));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    public List<Campaign> campaigns() {
        return jdbc.sql(CAMPAIGN_SELECT + " GROUP BY c.id ORDER BY c.id DESC").query((rs, i) -> campaign(rs)).list();
    }

    public Optional<Campaign> campaign(long id) {
        return jdbc.sql(CAMPAIGN_SELECT + " WHERE c.id = :id GROUP BY c.id").param("id", id).query((rs, i) -> campaign(rs)).optional();
    }

    private static final String CAMPAIGN_SELECT = """
            SELECT c.id, c.name, c.message_template, c.expires_days, c.created_by, c.created_at,
                   count(i.id) AS total,
                   count(i.id) FILTER (WHERE i.status = 'READY') AS ready,
                   count(i.id) FILTER (WHERE i.sent_at IS NOT NULL) AS sent,
                   count(i.id) FILTER (WHERE i.click_count > 0) AS clicked,
                   count(i.id) FILTER (WHERE i.status = 'CANCELLED') AS cancelled
            FROM invitation_campaign c LEFT JOIN invitation i ON i.campaign_id = c.id""";

    private Campaign campaign(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Campaign(rs.getLong("id"), rs.getString("name"), rs.getString("message_template"),
                rs.getInt("expires_days"), rs.getString("created_by"), local(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getLong("total"), rs.getLong("ready"), rs.getLong("sent"), rs.getLong("clicked"),
                rs.getLong("cancelled"));
    }

    public List<InvitationRow> invitations(long campaignId, String template) {
        return jdbc.sql("""
                        SELECT i.id, i.customer_id, c.name, c.phone, i.status, i.token, g.name AS group_name,
                               i.click_count, i.expires_at, i.sent_at, i.sent_channel
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        LEFT JOIN zalo_group g ON g.id = i.group_id
                        WHERE i.campaign_id = :id ORDER BY i.id""")
                .param("id", campaignId)
                .query((rs, n) -> {
                    OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                    String link = linkFor(rs.getString("token"));
                    return new InvitationRow(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("name"),
                            rs.getString("status"), link, rs.getString("group_name"), rs.getInt("click_count"),
                            local(exp), exp.isBefore(OffsetDateTime.now()),
                            local(rs.getObject("sent_at", OffsetDateTime.class)), rs.getString("sent_channel"),
                            render(template, rs.getString("name"), link));
                }).list();
    }

    /** Ghép nội dung tin từ mẫu. Không đưa SĐT/địa chỉ vào tin. */
    public String render(String template, String customerName, String link) {
        String who = customerName == null || customerName.isBlank() ? "bạn" : customerName.strip();
        return template.replace("{ten}", who).replace("{cua_hang}", shopName).replace("{link}", link);
    }

    // ---------- trang khách bấm link ----------

    /** Lời mời còn dùng được không (chỉ đọc, không đổi gì). */
    public boolean usable(String token) {
        return loadForClick(token).filter(this::stillValid).isPresent();
    }

    private record ClickRow(long id, long customerId, String status, OffsetDateTime expiresAt, Long groupId) {
    }

    private Optional<ClickRow> loadForClick(String token) {
        if (token == null || token.length() > 64) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT id, customer_id, status, expires_at, group_id FROM invitation WHERE token = :t")
                .param("t", token)
                .query((rs, i) -> new ClickRow(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("status"),
                        rs.getObject("expires_at", OffsetDateTime.class), (Long) rs.getObject("group_id")))
                .optional();
    }

    private boolean stillValid(ClickRow r) {
        return !"CANCELLED".equals(r.status()) && r.expiresAt().isAfter(OffsetDateTime.now())
                && contacts.stillEligible(r.customerId());
    }

    /**
     * Khách bấm nút trên trang mời: kiểm lại điều kiện, chọn nhóm (giữ nhóm cũ nếu đã cấp), ghi lượt bấm rồi trả
     * link Zalo. Không có nhóm phù hợp thì KHÔNG ghi gì và không chuyển hướng.
     */
    @Transactional
    public GoResult go(String token) {
        Optional<ClickRow> loaded = loadForClick(token);
        if (loaded.isEmpty()) {
            return new GoResult(Outcome.UNAVAILABLE, null);
        }
        ClickRow row = loaded.get();
        if (!stillValid(row)) {
            return new GoResult(Outcome.UNAVAILABLE, null);
        }
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:k)) AS locked").param("k", GROUP_PICK_LOCK)
                .query(Integer.class).single();
        GroupService.Group group = null;
        if (row.groupId() != null) {
            group = groups.find(row.groupId())
                    .filter(g -> g.hasLink() && g.status() != GroupStatus.CLOSED && g.status() != GroupStatus.PREPARING)
                    .orElse(null);
        }
        if (group == null) {
            group = groups.pickGroup().orElse(null);
        }
        if (group == null) {
            return new GoResult(Outcome.NO_GROUP, null);
        }
        jdbc.sql("""
                        UPDATE invitation SET status = 'CLICKED', group_id = :g, click_count = click_count + 1,
                               first_clicked_at = coalesce(first_clicked_at, now()), last_clicked_at = now()
                        WHERE id = :id""")
                .param("g", group.id()).param("id", row.id()).update();
        return new GoResult(Outcome.REDIRECT, group.inviteLink());
    }

    /** Khách bấm "không muốn nhận tin nữa": ghi vào danh sách từ chối vĩnh viễn. Trả false nếu link không dùng được. */
    @Transactional
    public boolean optOut(String token) {
        Optional<ClickRow> loaded = loadForClick(token);
        if (loaded.isEmpty()) {
            return false;
        }
        contacts.suppressAs(loaded.get().customerId(), "Khách bấm 'ngừng nhận tin' trên trang mời", "khach-tu-bam");
        return true;
    }

    private static LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(VN).toLocalDateTime();
    }
}
