package vn.shop.crm.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.contact.CustomerFilter;
import vn.shop.crm.zalo.ZaloAccountContext;

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

    /**
     * Tin 1 (chủ shop chốt lại 24/11/2026): ngắn, xưng "em", gọi khách là "bác", nói rõ ưu đãi và cách vào nhóm. {ten} là tên khách
     * (khách chưa có tên thì bỏ hẳn chữ này, thành "Dạ em chào bác, ..."). Khách đồng ý kết bạn thì được thêm vào nhóm; không cần thì bỏ qua.
     */
    public static final String DEFAULT_TEMPLATE = "Dạ em chào bác {ten}, em bên shop áo điều hòa Minh Hiệp ạ.\n"
            + "Bác từng mua áo bên em nên em mời bác vào nhóm khách hàng: mua trong nhóm giảm 10% so với TikTok, miễn ship ạ.\n"
            + "Bác bấm \"Chấp nhận\" lời mời kết bạn của em, em thêm bác vào nhóm liền ạ. Bác chưa cần thì cứ bỏ qua nhé.";

    /** Tin 1 cho chiến dịch gửi NHIỀU lần (D21): mẫu mặc định không có câu "sẽ không nhắn lại" nên dùng được cho cả hai. */
    public static final String MULTI_TEMPLATE = DEFAULT_TEMPLATE;

    /** Chỗ điền trong mẫu tin: {ten}, {Tên}, { TEN }... đều được (bỏ dấu, không phân biệt hoa thường). */
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\s*([^{}\\n]{1,40}?)\\s*}");
    private static final java.util.regex.Pattern NAME_SLOT = java.util.regex.Pattern.compile("[ \\t]*\\{\\s*([^{}\\n]{1,40}?)\\s*}");

    private static String slotKey(String raw) {
        return FollowupValidator.fold(raw).replace(' ', '_');
    }

    private static boolean isNameKey(String k) {
        return k.equals("ten") || k.equals("ten_khach");
    }

    private static boolean isKnownKey(String k) {
        return isNameKey(k) || k.equals("cua_hang") || k.equals("link") || k.equals("mien");
    }

    /** Mẫu tin dùng chỗ điền lạ (ví dụ {tên_khách_hàng}) thì báo lỗi; rỗng là hợp lệ. */
    public static java.util.Optional<String> checkTemplate(String template) {
        java.util.regex.Matcher m = PLACEHOLDER.matcher(template == null ? "" : template);
        while (m.find()) {
            if (!isKnownKey(slotKey(m.group(1)))) {
                return java.util.Optional.of("Mẫu tin có chỗ điền không dùng được: {" + m.group(1)
                        + "}. Chỉ dùng {ten}, {cua_hang}, {mien}, {link}.");
            }
        }
        return java.util.Optional.empty();
    }

    /** Tin đã ghép mà còn dấu {...} chưa thay thì KHÔNG được gửi. */
    public static boolean hasUnresolved(String text) {
        return text != null && PLACEHOLDER.matcher(text).find();
    }

    static final String SETTING_TEMPLATE = "invite.template";
    static final int MAX_TEMPLATE = 800;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Khóa tư vấn Postgres để hai lượt bấm cùng lúc không cùng chọn một nhóm đã gần đầy. */
    static final long GROUP_PICK_LOCK = 7_301_001L;

    public record Campaign(long id, String name, String template, int expiresDays, String createdBy,
                           LocalDateTime createdAt, long total, long ready, long sent, long clicked, long cancelled,
                           long friended, long joined, String geoScope, LocalDateTime archivedAt, java.time.LocalDate endOn) {
        /** Đã lưu trữ (25/11/2026): không tự gửi, không tính là đang chạy khi AI đề xuất. */
        public boolean archived() {
            return archivedAt != null;
        }

        /** Chưa lưu trữ nhưng đã quá ngày kết thúc của chiến dịch: hệ thống không gửi nữa, nên gợi ý lưu trữ. */
        public boolean pastEnd() {
            return archivedAt == null && endOn != null && endOn.isBefore(java.time.LocalDate.now(VN));
        }

        /** Khu vực của chiến dịch: rỗng = mọi nơi; "U" = khách chưa rõ khu vực. */
        public String scopeLabel() {
            if (geoScope == null || geoScope.isBlank()) {
                return "Mọi khu vực";
            }
            if (vn.shop.crm.geo.GeoArea.UNKNOWN.equals(geoScope)) {
                return "Chưa rõ khu vực";
            }
            return vn.shop.crm.geo.GeoArea.parse(geoScope).map(vn.shop.crm.geo.GeoArea::label).orElse("Mọi khu vực");
        }
    }

    public record InvitationRow(long id, long customerId, long accountId, String name, String status, String link, String groupName,
                                int clickCount, LocalDateTime expiresAt, boolean expired, LocalDateTime sentAt,
                                String sentChannel, String message, long groupId, String groupLink, boolean suggested,
                                java.time.LocalDateTime friendedAt, java.time.LocalDateTime joinedAt,
                                String provinceName, long campaignId, String campaignName,
                                LocalDateTime groupLinkSentAt, boolean autoHold,
                                LocalDateTime reservedUntil, boolean overdue, boolean noReply, boolean waitingGroup,
                                LocalDateTime leftAt, boolean tin1Blocked, boolean tin1Resent, String lastError) {
    }

    /** Kết quả đánh dấu "đã kết bạn": nhóm gợi ý và đã giữ chỗ, hoặc chưa có nhóm hợp (khách vào "chờ nhóm", R7). */
    public record FriendedResult(String groupName, String groupLink, boolean reserved) {
        public boolean waiting() {
            return groupName == null;
        }
    }

    /** Số việc nhắc trên màn Tổng quan (R6, R7, R10). */
    public record FriendStats(long friended, long overdue, long waitingGroup, long readyToAdd, long noReply) {
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
    private final int maxCampaign;
    private final int reserveHours;
    private final int overdueDays;
    private final int noReplyDays;

    public InviteService(JdbcClient jdbc, ContactService contacts, GroupService groups, AppSettings settings,
                         @Value("${app.public-base-url:http://localhost:8080}") String baseUrl,
                         @Value("${app.shop-name:Cửa hàng}") String shopName,
                         @Value("${app.invitations.max-batch:20}") int maxBatch,
                         @Value("${app.invitations.max-campaign:300}") int maxCampaign,
                         @Value("${app.friend-flow.reserve-hours:24}") int reserveHours,
                         @Value("${app.friend-flow.overdue-days:2}") int overdueDays,
                         @Value("${app.friend-flow.no-reply-days:14}") int noReplyDays) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.groups = groups;
        this.settings = settings;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.shopName = shopName;
        this.maxBatch = maxBatch;
        this.maxCampaign = maxCampaign;
        this.reserveHours = reserveHours;
        this.overdueDays = overdueDays;
        this.noReplyDays = noReplyDays;
    }

    public int reserveHours() {
        return reserveHours;
    }

    public int overdueDays() {
        return overdueDays;
    }

    public int noReplyDays() {
        return noReplyDays;
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
        checkTemplate(tpl).ifPresent(msg -> {
            throw new ImportException(msg);
        });
        settings.set(SETTING_TEMPLATE, tpl, ContactService.actor());
    }

    public int maxBatch() {
        return maxBatch;
    }

    /** Một chiến dịch có tối đa bao nhiêu khách; mỗi ngày chỉ nhắn theo hạn mức ngày/giờ, không phải cả chiến dịch một lúc. */
    public int maxCampaign() {
        return maxCampaign;
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
        return createCampaign(name, template, expiresDays, count, null);
    }

    /**
     * Như trên, nhưng chỉ lấy khách ở một khu vực: mã {@code R:NAM} (miền), {@code S:...} (vùng), {@code P:...}
     * (tỉnh) hoặc {@code U} (chưa rõ). Rỗng = mọi nơi. Ví dụ trời rét thì tạo chiến dịch chỉ cho khách một miền.
     */
    @Transactional
    public long createCampaign(String name, String template, int expiresDays, int count, String area) {
        return createCampaign(name, template, expiresDays, count, area, ContactService.Pick.NONE);
    }

    /** Như trên, kèm cách chọn khách (thứ tự lấy, độ mới của lần mua); chỉ làm hẹp tập khách, xem {@link ContactService.Pick}. */
    @Transactional
    public long createCampaign(String name, String template, int expiresDays, int count, String area, ContactService.Pick pick) {
        return createCampaign(name, template, expiresDays, count, area, pick.toFilter());
    }

    /**
     * Như trên với bộ lọc khách đầy đủ ({@link CustomerFilter}); bộ lọc được lưu vào chiến dịch để xem lại và để chế độ tự nhận khách mới
     * dùng đúng bộ lọc đó. Bộ lọc chỉ làm hẹp tập khách đủ điều kiện mời.
     */
    @Transactional
    public long createCampaign(String name, String template, int expiresDays, int count, String area, CustomerFilter filter) {
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
        checkTemplate(tpl).ifPresent(msg -> {
            throw new ImportException(msg);
        });
        if (expiresDays < 1 || expiresDays > 90) {
            throw new ImportException("Link có hiệu lực từ 1 đến 90 ngày.");
        }
        if (count < 1 || count > maxCampaign) {
            throw new ImportException("Mỗi chiến dịch chỉ được từ 1 đến " + maxCampaign
                    + " khách. Mỗi ngày hệ thống chỉ đưa ra một lô nhỏ để nhắn (mục Lô nhắn hôm nay).");
        }
        String scope = normalizeArea(area);
        List<ContactService.Invitee> picked = contacts.eligibleNotInvited(count, scope, filter);
        if (picked.isEmpty()) {
            throw new ImportException(scope == null
                    ? "Chưa có khách nào đủ điều kiện mời và chưa từng được mời. Hãy duyệt khách ở mục Danh sách mời trước."
                    : "Chưa có khách nào ở khu vực này đủ điều kiện mời và chưa từng được mời. "
                    + "Hãy duyệt thêm khách ở mục Danh sách mời, hoặc chọn khu vực khác.");
        }
        long campaignId = jdbc.sql("""
                        INSERT INTO invitation_campaign (name, message_template, expires_days, created_by, geo_scope, customer_filter, max_purchases)
                        VALUES (:name, :tpl, :days, :by, :scope, CAST(:filter AS jsonb), :maxP) RETURNING id""")
                .param("name", name.strip()).param("tpl", tpl).param("days", expiresDays)
                .param("by", ContactService.actor()).param("scope", scope, java.sql.Types.VARCHAR)
                .param("filter", filter.any() ? filter.toJson() : null, java.sql.Types.VARCHAR)
                .param("maxP", filter.maxPurchases())
                .query(Long.class).single();
        if (filter.multi()) {
            // Khách mua nhiều lần chưa có cơ sở liên hệ được duyệt NGAY tại đây, ghi rõ chiến dịch và người tạo.
            contacts.approveForCampaign(picked.stream().map(ContactService.Invitee::id).toList(), campaignId, filter.maxPurchases(),
                    ContactService.actor());
        }
        addInvitations(campaignId, expiresDays, name.strip(), picked);
        return campaignId;
    }

    /** Bộ lọc khách đã lưu của chiến dịch (không lọc gì nếu chiến dịch tạo trước khi có bộ lọc). */
    public CustomerFilter campaignFilter(long campaignId) {
        return CustomerFilter.fromJson(jdbc.sql("SELECT customer_filter::text FROM invitation_campaign WHERE id = :id")
                .param("id", campaignId).query(String.class).optional().orElse(null));
    }

    /** Tạo lời mời READY cho các khách này trong chiến dịch (dùng cả khi tạo chiến dịch lẫn khi chiến dịch tự nhận khách mới). */
    public void addInvitations(long campaignId, int expiresDays, String campaignName, List<ContactService.Invitee> picked) {
        for (ContactService.Invitee c : picked) {
            jdbc.sql("""
                            INSERT INTO invitation (campaign_id, customer_id, token, expires_at)
                            VALUES (:cid, :cust, :token, now() + make_interval(days => :days))""")
                    .param("cid", campaignId).param("cust", c.id()).param("token", newToken())
                    .param("days", expiresDays).update();
            contacts.event(c.id(), c.phone(), "INVITATION_CREATED", "Chiến dịch: " + campaignName);
        }
    }

    /** Mã khu vực hợp lệ ("U" = chưa rõ) hoặc null nếu để trống; mã lạ báo lỗi. */
    static String normalizeArea(String area) {
        if (area == null || area.isBlank()) {
            return null;
        }
        String a = area.strip();
        if (vn.shop.crm.geo.GeoArea.UNKNOWN.equals(a)) {
            return a;
        }
        return vn.shop.crm.geo.GeoArea.parse(a).map(vn.shop.crm.geo.GeoArea::code)
                .orElseThrow(() -> new ImportException("Khu vực không hợp lệ."));
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
            SELECT c.id, c.name, c.message_template, c.expires_days, c.created_by, c.created_at, c.geo_scope, c.archived_at, c.end_on,
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
                rs.getLong("cancelled"), rs.getLong("friended"), rs.getLong("joined"), rs.getString("geo_scope"),
                local(rs.getObject("archived_at", OffsetDateTime.class)), rs.getObject("end_on", java.time.LocalDate.class));
    }

    public List<InvitationRow> invitations(long campaignId, String template) {
        return loadRows("WHERE i.campaign_id = :id", campaignId, template);
    }

    /** Các lời mời theo mã, giữ đúng thứ tự đã cho; nội dung tin lấy từ mẫu của chiến dịch chứa lời mời. */
    public List<InvitationRow> invitationsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<InvitationRow> rows = loadRows("WHERE i.id IN (:ids)", ids, null);
        java.util.Map<Long, InvitationRow> byId = new java.util.HashMap<>();
        rows.forEach(r -> byId.put(r.id(), r));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private List<InvitationRow> loadRows(String where, Object param, String templateOverride) {
        var groupsByAccount = groups.list().stream()
                .collect(java.util.stream.Collectors.groupingBy(GroupService.Group::accountId));
        var spec = jdbc.sql("""
                        SELECT i.id, i.customer_id, i.account_id, c.name, c.phone, c.province, i.status, i.token, g.id AS group_id,
                               g.name AS group_name, g.invite_link AS group_link,
                               i.click_count, i.expires_at, i.sent_at, i.sent_channel, i.friended_at, i.joined_at,
                               i.group_link_sent_at, i.zalo_add_state, i.reserved_group_id, i.reserved_until, i.left_at,
                               i.tin1_blocked_at, i.tin1_resent_at, i.last_error,
                               k.id AS campaign_id, k.name AS campaign_name, k.message_template
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        LEFT JOIN zalo_group g ON g.id = i.group_id
                        """ + where + " ORDER BY i.id");
        if (param instanceof List<?>) {
            spec = spec.param("ids", param);
        } else if (param != null) {
            spec = spec.param("id", param);
        }
        return spec.query((rs, n) -> {
                    long accountId = rs.getLong("account_id");
                    var ownGroups = groupsByAccount.getOrDefault(accountId, List.of());
                    OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                    String link = linkFor(rs.getString("token"));
                    String status = rs.getString("status");
                    String groupName = rs.getString("group_name");
                    String groupLink = rs.getString("group_link");
                    long groupId = rs.getLong("group_id");
                    boolean suggested = false;
                    var province = vn.shop.crm.geo.VnGeo.byCode(rs.getString("province")).orElse(null);
                    OffsetDateTime reservedUntilAt = rs.getObject("reserved_until", OffsetDateTime.class);
                    Long reservedGroup = (Long) rs.getObject("reserved_group_id");
                    boolean reservationValid = "FRIENDED".equals(status) && reservedGroup != null && reservedUntilAt != null
                            && reservedUntilAt.isAfter(OffsetDateTime.now());
                    boolean heldShown = false;
                    if (groupName == null && reservationValid) {
                        // nhóm đã giữ chỗ cho khách này (R4): hiện đúng nhóm đó, không gợi ý lại
                        var held = ownGroups.stream().filter(x -> x.id() == reservedGroup && x.hasLink()
                                && x.status() == GroupStatus.OPEN).findFirst().orElse(null);
                        if (held != null) {
                            groupName = held.name();
                            groupLink = held.inviteLink();
                            groupId = held.id();
                            suggested = true;
                            heldShown = true;
                        }
                    }
                    if (groupName == null && !"CANCELLED".equals(status) && !"FAILED".equals(status)) {
                        // chưa có nhóm: gợi ý nhóm cùng khu vực (chỉ đọc; chỗ chỉ được giữ khi người gửi bấm Đã kết bạn)
                        var g = groups.pickGroup(ownGroups, province).orElse(null);
                        if (g != null) {
                            groupName = g.name();
                            groupLink = g.inviteLink();
                            groupId = g.id();
                            suggested = true;
                        }
                    }
                    String template = templateOverride != null ? templateOverride : rs.getString("message_template");
                    LocalDateTime linkSentAt = local(rs.getObject("group_link_sent_at", OffsetDateTime.class));
                    String addState = rs.getString("zalo_add_state");
                    boolean autoHold = "WAITING_APPROVAL".equals(addState) || "QUEUED".equals(addState) || "FAILED".equals(addState);
                    return new InvitationRow(rs.getLong("id"), rs.getLong("customer_id"), accountId, rs.getString("name"),
                            status, link, groupName, rs.getInt("click_count"),
                            local(exp), exp.isBefore(OffsetDateTime.now()),
                            local(rs.getObject("sent_at", OffsetDateTime.class)), rs.getString("sent_channel"),
                            render(template, rs.getString("name"), link, province), groupId, groupLink, suggested,
                            local(rs.getObject("friended_at", OffsetDateTime.class)),
                            local(rs.getObject("joined_at", OffsetDateTime.class)),
                            province == null ? "Chưa rõ khu vực" : province.name(),
                            rs.getLong("campaign_id"), rs.getString("campaign_name"), linkSentAt, autoHold,
                            heldShown ? local(reservedUntilAt) : null,
                            isOverdue(status, rs.getObject("friended_at", OffsetDateTime.class)),
                            isNoReply(status, rs.getObject("sent_at", OffsetDateTime.class)),
                            "FRIENDED".equals(status) && groupName == null,
                            local(rs.getObject("left_at", OffsetDateTime.class)),
                            rs.getObject("tin1_blocked_at", OffsetDateTime.class) != null,
                            rs.getObject("tin1_resent_at", OffsetDateTime.class) != null,
                            rs.getString("last_error"));
                }).list();
    }

    /** Tên tỉnh của mã tỉnh (rỗng nếu chưa rõ khu vực). */
    public String areaLabel(String provinceCode) {
        return vn.shop.crm.geo.VnGeo.byCode(provinceCode).map(vn.shop.crm.geo.Province::name).orElse("");
    }

    /** Tên nhóm nên thêm vào cho khách ở tỉnh này (chỉ đọc, không giữ chỗ); rỗng nếu chưa có nhóm hợp. */
    public Optional<String> suggestGroupName(String provinceCode) {
        return groups.pickGroupOfAccount(vn.shop.crm.geo.VnGeo.byCode(provinceCode).orElse(null), ZaloAccountContext.DEFAULT_ACCOUNT)
                .map(GroupService.Group::name);
    }

    /** Các nhóm mẹ có thể chọn khi báo "đã vào nhóm" (đang nhận hoặc tạm dừng, đã có link): mẹ đã thêm khách thật vào đó. */
    public List<GroupService.Group> joinableGroups() {
        return groups.list().stream().filter(g -> g.hasLink() && (g.status() == GroupStatus.OPEN || g.status() == GroupStatus.PAUSED))
                .toList();
    }

    /** Khách đã kết bạn quá {@code overdueDays} ngày mà chưa vào nhóm (R6). Chỉ là nhắc trên web, không nhắn khách. */
    private boolean isOverdue(String status, OffsetDateTime friendedAt) {
        return "FRIENDED".equals(status) && friendedAt != null
                && friendedAt.isBefore(OffsetDateTime.now().minusDays(overdueDays));
    }

    /** Đã nhắn quá {@code noReplyDays} ngày mà chưa kết bạn (R10): chỉ hiện nhãn, KHÔNG nhắn lại. */
    private boolean isNoReply(String status, OffsetDateTime sentAt) {
        return ("SENT".equals(status) || "CLICKED".equals(status)) && sentAt != null
                && sentAt.isBefore(OffsetDateTime.now().minusDays(noReplyDays));
    }

    /** Khách đã kết bạn mà chưa vào nhóm, mọi chiến dịch, cũ nhất trước (trang "Chờ vào nhóm"). */
    public List<InvitationRow> friendedQueue() {
        return loadRows("WHERE i.status = 'FRIENDED'", null, null).stream()
                .sorted(java.util.Comparator.comparing(InvitationRow::friendedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
    }

    /** Khách đã nhắn quá {@code noReplyDays} ngày mà chưa kết bạn (R10), cũ nhất trước. */
    public List<InvitationRow> noReplyQueue() {
        return loadRows("WHERE i.status IN ('SENT', 'CLICKED')", null, null).stream().filter(InvitationRow::noReply)
                .sorted(java.util.Comparator.comparing(InvitationRow::sentAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
    }

    public FriendStats friendStats() {
        List<InvitationRow> friended = friendedQueue();
        long waiting = friended.stream().filter(InvitationRow::waitingGroup).count();
        long overdue = friended.stream().filter(InvitationRow::overdue).count();
        long noReply = count("SELECT count(*) FROM invitation WHERE status IN ('SENT', 'CLICKED') AND sent_at < now() - make_interval(days => "
                + noReplyDays + ")");
        return new FriendStats(friended.size(), overdue, waiting, friended.size() - waiting, noReply);
    }

    /** Ghép nội dung tin từ mẫu. Không đưa SĐT/địa chỉ vào tin. {mien} chưa biết khu vực thì thành "khu vực của mình". */
    public String render(String template, String customerName, String link) {
        return render(template, customerName, link, null);
    }

    /** Như trên, có tỉnh của khách để điền {mien} (Miền Bắc/Trung/Nam). */
    public String render(String template, String customerName, String link, vn.shop.crm.geo.Province province) {
        boolean noName = customerName == null || customerName.isBlank();
        String who = noName ? "" : customerName.strip();
        String region = province == null ? "khu vực của mình" : province.region().label();
        // Khách chưa có tên: bỏ hẳn chỗ {ten} cùng khoảng trắng đứng trước ("Dạ em chào bác {ten}," thành "Dạ em chào bác,"), không để chữ thô.
        java.util.regex.Matcher m = (noName ? NAME_SLOT : PLACEHOLDER).matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = slotKey(m.group(1));
            String value;
            if (isNameKey(key)) {
                value = who;
            } else if (key.equals("cua_hang")) {
                value = shopName;
            } else if (key.equals("link")) {
                value = link == null ? "" : link;
            } else if (key.equals("mien")) {
                value = region;
            } else {
                value = m.group(); // chỗ điền lạ: để nguyên, bước kiểm hasUnresolved sẽ chặn không cho gửi
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---------- đường nhắn bằng tài khoản Zalo phụ (D13): người gửi tay đánh dấu từng bước ----------

    private record StepRow(long customerId, long accountId, String phone, String status, Long groupId, String province, boolean clicked,
                           boolean linkSent, Long reservedGroupId, boolean reservationValid, boolean left, boolean countedAdd) {
    }

    private StepRow stepRow(long invitationId) {
        return loadStepRow(invitationId).orElseThrow(() -> new ImportException("Không tìm thấy lời mời."));
    }

    private Optional<StepRow> loadStepRow(long invitationId) {
        return jdbc.sql("""
                        SELECT i.customer_id, i.account_id, c.phone, i.status, i.group_id, c.province, i.first_clicked_at IS NOT NULL AS clicked,
                               i.group_link_sent_at IS NOT NULL AS link_sent, i.reserved_group_id,
                               coalesce(i.reserved_until > now(), false) AS reservation_valid, i.left_at IS NOT NULL AS is_left, i.zalo_group_add_counted
                        FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE i.id = :id""")
                .param("id", invitationId)
                .query((rs, n) -> new StepRow(rs.getLong("customer_id"), rs.getLong("account_id"), rs.getString("phone"), rs.getString("status"),
                        (Long) rs.getObject("group_id"), rs.getString("province"), rs.getBoolean("clicked"),
                        rs.getBoolean("link_sent"), (Long) rs.getObject("reserved_group_id"),
                        rs.getBoolean("reservation_valid"), rs.getBoolean("is_left"), rs.getBoolean("zalo_group_add_counted")))
                .optional();
    }

    /**
     * Khách đã đồng ý kết bạn với tài khoản phụ. Chỉ đánh dấu được sau khi đã nhắn (đã xuất danh sách). Gợi ý nhóm cùng
     * khu vực NGAY và giữ một chỗ trong nhóm đó {@code app.friend-flow.reserve-hours} giờ (R3, R4); chưa có nhóm hợp thì
     * khách vào "chờ nhóm" (R7), không đẩy sang nhóm miền khác.
     */
    @Transactional
    public FriendedResult markFriended(long invitationId) {
        return markFriended(invitationId, "đánh dấu tay", true);
    }

    /** Như {@link #markFriended(long)}; {@code via} ghi vào lịch sử khách ("đánh dấu tay", "bộ nối Zalo"...). */
    @Transactional
    public FriendedResult markFriended(long invitationId, String via) {
        return markFriended(invitationId, via, true);
    }

    /**
     * {@code reserve = false} cho bộ nối Zalo (D16): bộ nối tự chọn nhóm có mã Zalo khi thêm, một chỗ giữ sẵn ở nhóm khác sẽ
     * chỉ làm nhóm đó trông đầy, nên không giữ chỗ.
     */
    @Transactional
    public FriendedResult markFriended(long invitationId, String via, boolean reserve) {
        GroupService.lockAllocation(jdbc);
        StepRow r = stepRow(invitationId);
        if (!List.of("SENT", "CLICKED").contains(r.status())) {
            throw new ImportException("Chỉ đánh dấu \"đã kết bạn\" cho khách đã được nhắn (đã xuất danh sách để gửi).");
        }
        jdbc.sql("UPDATE invitation SET status = 'FRIENDED', friended_at = now() WHERE id = :id")
                .param("id", invitationId).update();
        GroupService.Group held = null;
        if (reserve && !groupAddUnresolved(invitationId)) {
            Long existingGroup = r.groupId() != null ? r.groupId() : r.reservationValid() ? r.reservedGroupId() : null;
            held = existingGroup == null
                    ? groups.pickGroupOfAccount(vn.shop.crm.geo.VnGeo.byCode(r.province()).orElse(null), r.accountId()).orElse(null)
                    : groups.findOfAccount(existingGroup, r.accountId())
                            .filter(g -> g.hasLink() && g.status() == GroupStatus.OPEN).orElse(null);
            if (held != null && !r.reservationValid()) {
                jdbc.sql("""
                                UPDATE invitation SET reserved_group_id = :g, reserved_until = now() + make_interval(hours => :h)
                                WHERE id = :id""")
                        .param("g", held.id()).param("h", reserveHours).param("id", invitationId).update();
            }
        }
        contacts.event(r.customerId(), r.phone(), "FRIENDED", "Khách đồng ý kết bạn Zalo (" + via + ")"
                + (held != null ? "; giữ chỗ nhóm " + held.name() + " " + reserveHours + " giờ" : reserve ? "; chưa có nhóm hợp, chờ nhóm" : ""));
        return new FriendedResult(held == null ? null : held.name(), held == null ? null : held.inviteLink(), held != null);
    }

    /**
     * Một lần bấm cho người gửi đã thêm khách vào nhóm ngay trong lúc làm (R5): ghi đã kết bạn rồi đã vào nhóm. Khách chưa
     * kết bạn bằng tay thì cũng ghi kết bạn. Nếu chưa có nhóm hợp thì báo lỗi và KHÔNG ghi gì (bấm Đã kết bạn để vào "chờ nhóm").
     */
    @Transactional
    public void markFriendedAndJoined(long invitationId) {
        markFriendedAndJoined(invitationId, null);
    }

    /** Như trên, người gửi chọn đúng nhóm đã thêm khách vào ({@code null} = nhóm gợi ý). */
    @Transactional
    public void markFriendedAndJoined(long invitationId, Long chosenGroupId) {
        GroupService.lockAllocation(jdbc);
        StepRow r = stepRow(invitationId);
        if (List.of("SENT", "CLICKED").contains(r.status())) {
            markFriended(invitationId);
        }
        markJoined(invitationId, chosenGroupId);
    }

    /**
     * Khách đã ra khỏi nhóm (R8, Q6): người gửi báo tay. Số khách thật của nhóm giảm 1 nếu khách đó đã được cộng vào số khách
     * thật; khách vào bằng lượt bấm link nằm ở "lượt bấm chờ" nên chỉ ghi lại, mẹ đếm lại nhóm là khớp. Mỗi khách một lần.
     */
    @Transactional
    public void markLeft(long invitationId) {
        GroupService.lockAllocation(jdbc);
        StepRow r = stepRow(invitationId);
        if (!"JOINED".equals(r.status())) {
            throw new ImportException("Chỉ báo \"đã rời nhóm\" cho khách đã vào nhóm.");
        }
        if (r.left()) {
            throw new ImportException("Đã ghi khách này rời nhóm rồi.");
        }
        Long groupId = jdbc.sql("UPDATE invitation SET left_at = now() WHERE id = :id AND left_at IS NULL RETURNING group_id")
                .param("id", invitationId).query(Long.class).optional().orElse(null);
        if (groupId != null && (!r.clicked() || r.countedAdd())) {
            jdbc.sql("UPDATE zalo_group SET actual_customers = greatest(actual_customers - 1, 0) WHERE id = :g")
                    .param("g", groupId).update();
        }
        contacts.event(r.customerId(), r.phone(), "LEFT_GROUP", "Khách đã rời nhóm (người gửi báo tay)");
    }

    /**
     * Người gửi đã thêm khách vào nhóm Zalo cùng khu vực. Dùng nhóm đã gợi ý (cùng tỉnh, vùng, miền); chưa có nhóm nào
     * còn chỗ thì báo lỗi, không ghi gì. Tăng số khách thật của nhóm thêm 1 (người đó chính là người thêm vào).
     */
    @Transactional
    public void markJoined(long invitationId) {
        markJoined(invitationId, null);
    }

    /**
     * Như {@link #markJoined(long)}, nhưng người gửi chọn đúng nhóm mình đã thêm khách vào ({@code chosenGroupId}), khác
     * nhóm gợi ý. Nhóm phải có link và đang nhận hoặc tạm dừng; không kiểm khu vực hay chỗ trống vì khách ĐÃ ở trong nhóm đó
     * thật (người gửi quyết). {@code null} = dùng nhóm gợi ý như thường.
     */
    @Transactional
    public void markJoined(long invitationId, Long chosenGroupId) {
        GroupService.lockAllocation(jdbc);
        StepRow r = stepRow(invitationId);
        if (!List.of("SENT", "CLICKED", "FRIENDED").contains(r.status())) {
            throw new ImportException("Chỉ đánh dấu \"đã vào nhóm\" cho khách đã được nhắn và chưa bị hủy.");
        }
        if (chosenGroupId != null) {
            GroupService.Group chosen = groups.findOfAccount(chosenGroupId, r.accountId())
                    .filter(g -> g.hasLink() && (g.status() == GroupStatus.OPEN || g.status() == GroupStatus.PAUSED))
                    .orElseThrow(() -> new ImportException("Nhóm đã chọn không thuộc tài khoản của khách, đã đóng hoặc chưa có link."));
            finishJoin(invitationId, r, chosen, "đánh dấu tay, người gửi chọn nhóm");
            return;
        }
        if (groupAddUnresolved(invitationId)) {
            throw new ImportException("Lượt thêm vào nhóm đang cần đối chiếu. Hãy chọn đúng nhóm trên Zalo hoặc xử lý ở mục Đối chiếu thêm nhóm.");
        }
        GroupService.Group group = null;
        if (r.groupId() != null) {
            // nhóm đã cấp phải còn nhận khách (đang mở, có link); khách chưa từng bấm link còn cần nhóm chưa đầy.
            // Khách đã được gửi link nhóm này thì có thể đã vào đúng nhóm đó dù nhóm vừa đầy: ghi đúng nhóm đó.
            boolean committed = r.clicked() || r.linkSent();
            group = groups.findOfAccount(r.groupId(), r.accountId()).filter(g -> g.hasLink() && g.status() == GroupStatus.OPEN
                    && (committed || !g.full())).orElse(null);
        }
        if (r.groupId() == null && r.reservedGroupId() != null && r.reservationValid()) {
            // chỗ đã giữ cho chính khách này (R4): dùng đúng nhóm đó dù giờ nhóm đã tính đủ chỗ vì chính chỗ giữ đó
            group = groups.findOfAccount(r.reservedGroupId(), r.accountId())
                    .filter(g -> g.hasLink() && g.status() == GroupStatus.OPEN).orElse(null);
        }
        if (group == null && (r.groupId() != null || (r.reservedGroupId() != null && r.reservationValid()))) {
            throw new ImportException("Nhóm đã gắn hoặc giữ chỗ hiện chưa dùng được. Hãy kiểm tra nhóm trước khi xác nhận.");
        }
        if (group == null) {
            group = groups.pickGroupOfAccount(vn.shop.crm.geo.VnGeo.byCode(r.province()).orElse(null), r.accountId()).orElse(null);
        }
        if (group == null) {
            throw new ImportException("Chưa có nhóm Zalo nào còn chỗ cho khu vực của khách này. Hãy mở thêm nhóm ở mục Nhóm Zalo.");
        }
        finishJoin(invitationId, r, group, "đánh dấu tay");
    }

    /**
     * Bộ nối Zalo vừa thêm khách vào ĐÚNG nhóm này (D16). Khác {@link #markJoined}: không chọn lại nhóm, vì khách đã ở
     * trong nhóm Zalo đó thật rồi; ghi đúng nhóm đã thêm.
     */
    @Transactional
    public void markJoinedExact(long invitationId, long groupId) {
        GroupService.lockAllocation(jdbc);
        StepRow r = stepRow(invitationId);
        if (!List.of("SENT", "CLICKED", "FRIENDED").contains(r.status())) {
            return; // đã vào nhóm (người bấm tay trước) hoặc đã hủy: không ghi đè
        }
        GroupService.Group group = groups.findOfAccount(groupId, r.accountId())
                .orElseThrow(() -> new ImportException("Không tìm thấy nhóm thuộc tài khoản của khách."));
        finishJoin(invitationId, r, group, "bộ nối Zalo");
    }

    /** Ghi kết quả chỉ khi tác vụ bền trước đó đã khóa đúng khách, tài khoản và nhóm đích. */
    @Transactional
    public boolean finishDispatchedGroupAdd(long attemptId, long invitationId, long groupId, long accountId, boolean shared) {
        GroupService.lockAllocation(jdbc);
        boolean authorized = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM zalo_group_add_attempt WHERE id = :attempt
                          AND invitation_id = :invitation AND group_id = :group AND account_id = :account
                          AND state IN ('DISPATCHED', 'REVIEW') AND (grant_revision IS NOT NULL OR restored_evidence) = :shared)
                        """).param("attempt", attemptId).param("invitation", invitationId).param("group", groupId)
                .param("account", accountId).param("shared", shared).query(Boolean.class).single();
        if (!authorized) {
            throw new IllegalStateException("Group add attempt does not match the invitation");
        }
        Optional<StepRow> loaded = loadStepRow(invitationId);
        if (loaded.isEmpty()) {
            return false;
        }
        StepRow row = loaded.get();
        if (row.accountId() != accountId || row.left() || (row.groupId() != null && row.groupId() != groupId)) {
            return false;
        }
        if ("JOINED".equals(row.status())) {
            return row.groupId() != null;
        }
        if (!List.of("SENT", "CLICKED", "FRIENDED").contains(row.status())) {
            return false;
        }
        GroupService.Group group = groups.find(groupId).orElseThrow();
        return finishJoin(invitationId, row, group, "bộ nối Zalo, tài khoản " + accountId, shared);
    }

    private void finishJoin(long invitationId, StepRow r, GroupService.Group group, String via) {
        finishJoin(invitationId, r, group, via, false);
    }

    private boolean finishJoin(long invitationId, StepRow r, GroupService.Group group, String via, boolean countActual) {
        int changed = jdbc.sql("""
                        UPDATE invitation SET status = 'JOINED', group_id = :g, joined_at = now(),
                               friended_at = coalesce(friended_at, now()), zalo_group_add_counted = :counted
                        WHERE id = :id AND status IN ('SENT', 'CLICKED', 'FRIENDED')""")
                .param("g", group.id()).param("id", invitationId).param("counted", countActual).update();
        if (changed == 0) {
            return false; // bấm hai lần liền: lần trước đã ghi rồi, không tăng số khách của nhóm thêm lần nữa
        }
        if (countActual || !r.clicked()) {
            // khách đã từng bấm link thì đã được tính ở "lượt bấm chờ" của nhóm (dù trạng thái giờ là đã kết bạn), không tính hai lần
            groups.addMember(group.id());
        }
        contacts.event(r.customerId(), r.phone(), "JOINED_GROUP", "Đã thêm vào nhóm: " + group.name() + " (" + via + ")");
        return true;
    }

    /** Khách từ chối hoặc nhắn xin ngừng nhận tin: ghi vào danh sách từ chối vĩnh viễn, lời mời đã gửi chuyển thành "đã hủy". */
    @Transactional
    public void markDeclined(long invitationId) {
        GroupService.lockAllocation(jdbc);
        StepRow r = stepRow(invitationId);
        if (!List.of("SENT", "CLICKED", "FRIENDED", "JOINED").contains(r.status())) {
            throw new ImportException("Chỉ ghi \"khách từ chối\" cho khách đã được nhắn. Muốn chặn khách chưa nhắn, dùng nút chặn ở trang khách.");
        }
        // Khách đã vào nhóm mà xin ngừng: ghi vào danh sách từ chối; lời mời giữ nguyên "đã vào nhóm", người gửi tự mời họ ra khỏi nhóm.
        contacts.suppressAs(r.customerId(), "Khách từ chối khi được nhắn bằng tài khoản Zalo phụ", ContactService.actor());
        if ("JOINED".equals(r.status()) && !r.left()) {
            markLeft(invitationId); // khách xin ngừng mà đang ở trong nhóm: mẹ mời ra tay, số khách của nhóm giảm 1 (R8)
        }
    }

    // ---------- trang khách bấm link ----------

    /** Lời mời còn dùng được không (chỉ đọc, không đổi gì). */
    public boolean usable(String token) {
        return loadForClick(token).filter(this::stillValid).isPresent();
    }

    private boolean groupAddUnresolved(long invitationId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM zalo_group_add_attempt WHERE invitation_id = :id
                          AND state IN ('DISPATCHED', 'REVIEW')) OR EXISTS (SELECT 1 FROM zalo_group_proposal_member m
                            JOIN zalo_group_proposal p ON p.id = m.proposal_id
                            WHERE m.invitation_id = :id AND p.state = 'CREATING')
                        """).param("id", invitationId).query(Boolean.class).single();
    }

    private record ClickRow(long id, long customerId, long accountId, String status, OffsetDateTime expiresAt, Long groupId) {
    }

    private Optional<ClickRow> loadForClick(String token) {
        if (token == null || token.length() > 64) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT id, customer_id, account_id, status, expires_at, coalesce(group_id, CASE WHEN reserved_until > now() THEN reserved_group_id END) AS group_id FROM invitation WHERE token = :t")
                .param("t", token)
                .query((rs, i) -> new ClickRow(rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("account_id"), rs.getString("status"),
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
        GroupService.lockAllocation(jdbc);
        Optional<ClickRow> loaded = loadForClick(token);
        if (loaded.isEmpty()) {
            return new GoResult(Outcome.UNAVAILABLE, null);
        }
        ClickRow row = loaded.get();
        if (!stillValid(row)) {
            return new GoResult(Outcome.UNAVAILABLE, null);
        }
        if (groupAddUnresolved(row.id())) {
            return new GoResult(Outcome.NO_GROUP, null);
        }
        GroupService.Group group = null;
        if (row.groupId() != null) {
            group = groups.find(row.groupId())
                    .filter(g -> g.hasLink() && g.status() != GroupStatus.CLOSED && g.status() != GroupStatus.PREPARING)
                    .orElse(null);
        }
        if (group == null && row.groupId() != null) {
            return new GoResult(Outcome.NO_GROUP, null);
        }
        if (group == null) {
            // Khách đã biết (có trong danh sách): xếp vào nhóm cùng khu vực theo tỉnh đoán từ địa chỉ đã có.
            vn.shop.crm.geo.Province province = jdbc.sql("SELECT province FROM customer WHERE id = :id")
                    .param("id", row.customerId()).query(String.class).optional()
                    .flatMap(vn.shop.crm.geo.VnGeo::byCode).orElse(null);
            group = groups.pickGroupOfAccount(province, row.accountId()).orElse(null);
        }
        if (group == null) {
            return new GoResult(Outcome.NO_GROUP, null);
        }
        if ("JOINED".equals(row.status()) || ("FRIENDED".equals(row.status()) && row.groupId() != null)) {
            return new GoResult(Outcome.REDIRECT, group.inviteLink());
        }
        if ("FRIENDED".equals(row.status())) {
            int reserved = jdbc.sql("""
                            UPDATE invitation SET reserved_group_id = :group, reserved_until = now() + make_interval(hours => :hours)
                            WHERE id = :id AND status = 'FRIENDED' AND group_id IS NULL
                              AND (reserved_group_id IS NULL OR reserved_until IS NULL OR reserved_until <= now())
                            """).param("group", group.id()).param("hours", reserveHours).param("id", row.id()).update();
            return reserved == 1 ? new GoResult(Outcome.REDIRECT, group.inviteLink()) : new GoResult(Outcome.UNAVAILABLE, null);
        }
        int clicked = jdbc.sql("""
                        UPDATE invitation SET status = 'CLICKED', group_id = :g, click_count = click_count + 1,
                               first_clicked_at = coalesce(first_clicked_at, now()), last_clicked_at = now()
                        WHERE id = :id AND account_id = :acct AND (status = :prior OR status = 'CLICKED')""")
                .param("g", group.id()).param("id", row.id()).param("acct", row.accountId()).param("prior", row.status()).update();
        if (clicked == 0) {
            return new GoResult(Outcome.UNAVAILABLE, null);
        }
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
