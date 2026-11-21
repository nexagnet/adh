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

    static final String SETTING_TEMPLATE = "invite.template";
    static final int MAX_TEMPLATE = 800;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Khóa tư vấn Postgres để hai lượt bấm cùng lúc không cùng chọn một nhóm đã gần đầy. */
    static final long GROUP_PICK_LOCK = 7_301_001L;

    public record Campaign(long id, String name, String template, int expiresDays, String createdBy,
                           LocalDateTime createdAt, long total, long ready, long sent, long clicked, long cancelled,
                           long friended, long joined) {
    }

    public record InvitationRow(long id, long customerId, String name, String status, String link, String groupName,
                                int clickCount, LocalDateTime expiresAt, boolean expired, LocalDateTime sentAt,
                                String sentChannel, String message, long groupId, String groupLink, boolean suggested,
                                java.time.LocalDateTime friendedAt, java.time.LocalDateTime joinedAt) {
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

    /** Mẫu tin mặc định: do chủ shop soạn ở trang Chiến dịch mời, chưa soạn thì dùng mẫu có sẵn. */
    public String defaultTemplate() {
        return settings.get(SETTING_TEMPLATE).filter(t -> !t.isBlank()).orElse(DEFAULT_TEMPLATE);
    }

    /** Lưu mẫu tin mặc định (để trống = dùng lại mẫu có sẵn). {link} không bắt buộc. */
    public void saveDefaultTemplate(String template) {
        String tpl = template == null ? "" : template.strip();
        if (tpl.length() > MAX_TEMPLATE) {
            throw new ImportException("Nội dung tin quá dài (tối đa " + MAX_TEMPLATE + " ký tự).");
        }
        settings.set(SETTING_TEMPLATE, tpl, ContactService.actor());
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
        String tpl = template == null || template.isBlank() ? defaultTemplate() : template.strip();
        // {link} không bắt buộc: tin nhắn bằng tài khoản phụ không cần link, khách đồng ý kết bạn rồi được thêm vào nhóm.
        if (tpl.length() > MAX_TEMPLATE) {
            throw new ImportException("Nội dung tin quá dài (tối đa " + MAX_TEMPLATE + " ký tự).");
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
                   count(i.id) FILTER (WHERE i.status = 'CANCELLED') AS cancelled,
                   count(i.id) FILTER (WHERE i.friended_at IS NOT NULL) AS friended,
                   count(i.id) FILTER (WHERE i.joined_at IS NOT NULL) AS joined
            FROM invitation_campaign c LEFT JOIN invitation i ON i.campaign_id = c.id""";

    private Campaign campaign(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Campaign(rs.getLong("id"), rs.getString("name"), rs.getString("message_template"),
                rs.getInt("expires_days"), rs.getString("created_by"), local(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getLong("total"), rs.getLong("ready"), rs.getLong("sent"), rs.getLong("clicked"),
                rs.getLong("cancelled"), rs.getLong("friended"), rs.getLong("joined"));
    }

    public List<InvitationRow> invitations(long campaignId, String template) {
        return jdbc.sql("""
                        SELECT i.id, i.customer_id, c.name, c.phone, c.province, i.status, i.token, i.group_id,
                               g.name AS group_name, g.invite_link AS group_link,
                               i.click_count, i.expires_at, i.sent_at, i.sent_channel, i.friended_at, i.joined_at
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        LEFT JOIN zalo_group g ON g.id = i.group_id
                        WHERE i.campaign_id = :id ORDER BY i.id""")
                .param("id", campaignId)
                .query((rs, n) -> {
                    OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                    String link = linkFor(rs.getString("token"));
                    String status = rs.getString("status");
                    String groupName = rs.getString("group_name");
                    String groupLink = rs.getString("group_link");
                    long groupId = rs.getLong("group_id");
                    boolean suggested = false;
                    if (groupName == null && !"CANCELLED".equals(status) && !"FAILED".equals(status)) {
                        // chưa có nhóm: gợi ý nhóm cùng khu vực (chỉ đọc, chưa giữ chỗ)
                        var g = groups.pickGroup(vn.shop.crm.geo.VnGeo.byCode(rs.getString("province")).orElse(null)).orElse(null);
                        if (g != null) {
                            groupName = g.name();
                            groupLink = g.inviteLink();
                            groupId = g.id();
                            suggested = true;
                        }
                    }
                    return new InvitationRow(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("name"),
                            status, link, groupName, rs.getInt("click_count"),
                            local(exp), exp.isBefore(OffsetDateTime.now()),
                            local(rs.getObject("sent_at", OffsetDateTime.class)), rs.getString("sent_channel"),
                            render(template, rs.getString("name"), link), groupId, groupLink, suggested,
                            local(rs.getObject("friended_at", OffsetDateTime.class)),
                            local(rs.getObject("joined_at", OffsetDateTime.class)));
                }).list();
    }

    /** Ghép nội dung tin từ mẫu. Không đưa SĐT/địa chỉ vào tin. */
    public String render(String template, String customerName, String link) {
        String who = customerName == null || customerName.isBlank() ? "bạn" : customerName.strip();
        return template.replace("{ten}", who).replace("{cua_hang}", shopName).replace("{link}", link);
    }

    // ---------- đường nhắn bằng tài khoản Zalo phụ (D13): người gửi tay đánh dấu từng bước ----------

    private record StepRow(long customerId, String phone, String status, Long groupId, String province) {
    }

    private StepRow stepRow(long invitationId) {
        return jdbc.sql("""
                        SELECT i.customer_id, c.phone, i.status, i.group_id, c.province
                        FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE i.id = :id""")
                .param("id", invitationId)
                .query((rs, n) -> new StepRow(rs.getLong("customer_id"), rs.getString("phone"), rs.getString("status"),
                        (Long) rs.getObject("group_id"), rs.getString("province")))
                .optional().orElseThrow(() -> new ImportException("Không tìm thấy lời mời."));
    }

    /** Khách đã đồng ý kết bạn với tài khoản phụ. Chỉ đánh dấu được sau khi đã nhắn (đã xuất danh sách). */
    @Transactional
    public void markFriended(long invitationId) {
        StepRow r = stepRow(invitationId);
        if (!List.of("SENT", "CLICKED").contains(r.status())) {
            throw new ImportException("Chỉ đánh dấu \"đã kết bạn\" cho khách đã được nhắn (đã xuất danh sách để gửi).");
        }
        jdbc.sql("UPDATE invitation SET status = 'FRIENDED', friended_at = now() WHERE id = :id")
                .param("id", invitationId).update();
        contacts.event(r.customerId(), r.phone(), "FRIENDED", "Khách đồng ý kết bạn Zalo (đánh dấu tay)");
    }

    /**
     * Người gửi đã thêm khách vào nhóm Zalo cùng khu vực. Dùng nhóm đã gợi ý (cùng tỉnh, vùng, miền); chưa có nhóm nào
     * còn chỗ thì báo lỗi, không ghi gì. Tăng số khách thật của nhóm thêm 1 (người đó chính là người thêm vào).
     */
    @Transactional
    public void markJoined(long invitationId) {
        StepRow r = stepRow(invitationId);
        if (!List.of("SENT", "CLICKED", "FRIENDED").contains(r.status())) {
            throw new ImportException("Chỉ đánh dấu \"đã vào nhóm\" cho khách đã được nhắn và chưa bị hủy.");
        }
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:k)) AS locked").param("k", GROUP_PICK_LOCK)
                .query(Integer.class).single();
        GroupService.Group group = null;
        if (r.groupId() != null) {
            group = groups.find(r.groupId()).filter(g -> g.hasLink() && g.status() != GroupStatus.CLOSED
                    && g.status() != GroupStatus.PREPARING).orElse(null);
        }
        if (group == null) {
            group = groups.pickGroup(vn.shop.crm.geo.VnGeo.byCode(r.province()).orElse(null)).orElse(null);
        }
        if (group == null) {
            throw new ImportException("Chưa có nhóm Zalo nào còn chỗ cho khu vực của khách này. Hãy mở thêm nhóm ở mục Nhóm Zalo.");
        }
        jdbc.sql("""
                        UPDATE invitation SET status = 'JOINED', group_id = :g, joined_at = now(),
                               friended_at = coalesce(friended_at, now())
                        WHERE id = :id""").param("g", group.id()).param("id", invitationId).update();
        if (!"CLICKED".equals(r.status())) {
            groups.addMember(group.id()); // khách bấm link đã được tính ở "lượt bấm chờ", không tính hai lần
        }
        contacts.event(r.customerId(), r.phone(), "JOINED_GROUP", "Đã thêm vào nhóm: " + group.name() + " (đánh dấu tay)");
    }

    /** Khách từ chối hoặc nhắn xin ngừng nhận tin: ghi vào danh sách từ chối vĩnh viễn, lời mời đã gửi chuyển thành "đã hủy". */
    @Transactional
    public void markDeclined(long invitationId) {
        StepRow r = stepRow(invitationId);
        contacts.suppressAs(r.customerId(), "Khách từ chối khi được nhắn bằng tài khoản Zalo phụ", ContactService.actor());
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
            // Khách đã biết (có trong danh sách): xếp vào nhóm cùng khu vực theo tỉnh đoán từ địa chỉ đã có.
            vn.shop.crm.geo.Province province = jdbc.sql("SELECT province FROM customer WHERE id = :id")
                    .param("id", row.customerId()).query(String.class).optional()
                    .flatMap(vn.shop.crm.geo.VnGeo::byCode).orElse(null);
            group = groups.pickGroup(province).orElse(null);
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
