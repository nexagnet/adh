package vn.shop.crm.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.geo.Province;
import vn.shop.crm.zalo.ZaloSharedGroupGrants;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Quản lý nhóm Zalo do mẹ tạo (link nhập tay) và chọn nhóm cho khách bấm link (mục 7 file 02).
 *
 * <p>Số khách của nhóm KHÔNG gồm tài khoản mẹ và bot. Số khách thật do mẹ/quản trị nhập tay; lượt bấm sau lần đếm
 * gần nhất chỉ là ước lượng "đang chờ" để điều phối, không bao giờ được coi là số người đã vào nhóm.
 */
@Service
public class GroupService {

    /** Mẹ + bot: hai tài khoản của cửa hàng, hiển thị để không nhầm với khách (tiêu chí 8). */
    public static final int STORE_ACCOUNTS = 2;
    /** Mẹ đếm lại số khách trong nhóm ít nhất mỗi tuần (giả định, chỉnh được ở đây). */
    public static final int RECOUNT_DAYS = 7;

    public record Group(long id, String name, String inviteLink, int target, int actual, OffsetDateTime countedAt,
                        GroupStatus status, String note, long pendingClicks, double score,
                        vn.shop.crm.geo.GeoArea scope, String zaloGroupId, long reserved, long accountId) {
        /** Có mã nhóm trong Zalo để bộ nối (D16) thêm người; không có thì bộ nối không bao giờ thêm vào nhóm này. */
        public boolean hasZaloId() {
            return zaloGroupId != null && !zaloGroupId.isBlank();
        }

        /** Tên khu vực của nhóm; nhóm chung nhận khách mọi nơi. */
        public String scopeLabel() {
            return scope == null ? "Chung (mọi nơi)" : scope.label();
        }

        public String scopeCode() {
            return scope == null ? "" : scope.code();
        }

        public boolean hasLink() {
            return inviteLink != null && !inviteLink.isBlank();
        }

        /** Tổng thành viên dự kiến nếu cả mẹ và bot đều có mặt. */
        public int expectedMembers() {
            return actual + STORE_ACCOUNTS;
        }

        public boolean full() {
            return score >= target;
        }

        /** Đã chạm TRẦN MỀM (số khách tối đa kể cả khách dư lẻ, không bao giờ thấp hơn mục tiêu của chính nhóm). */
        public boolean fullAtCap(int cap) {
            return score >= Math.max(target, cap);
        }

        /** Nhóm đang nhận khách mà chưa đếm lại trong 7 ngày: số "đang chờ" có thể đã lệch nhiều. */
        public boolean needsRecount() {
            return status == GroupStatus.OPEN
                    && (countedAt == null || countedAt.isBefore(OffsetDateTime.now().minusDays(RECOUNT_DAYS)));
        }

        /** Số chỗ còn lại ước lượng (không âm). */
        public int spotsLeft() {
            return (int) Math.max(0, Math.floor(target - score));
        }
    }

    private final JdbcClient jdbc;
    private final double acceptRate;
    private final boolean allowOtherRegion;
    private final AppSettings settings;
    private final int defaultSoftCap;

    /** Khóa cài đặt của trần mềm (chỉnh ở trang Zalo, mục tạo nhóm). */
    public static final String SOFT_CAP_KEY = "zalo.group.soft_cap";
    public static final int MIN_SOFT_CAP = 5;
    public static final int MAX_SOFT_CAP = 50;

    public GroupService(JdbcClient jdbc, AppSettings settings, @Value("${app.invitations.assumed-accept-rate:0.5}") double acceptRate,
                        @Value("${app.grouping.allow-other-region:false}") boolean allowOtherRegion,
                        @Value("${app.grouping.soft-cap:8}") int softCap) {
        this.defaultSoftCap = Math.max(MIN_SOFT_CAP, Math.min(MAX_SOFT_CAP, softCap));
        this.settings = settings;
        this.jdbc = jdbc;
        this.acceptRate = acceptRate;
        this.allowOtherRegion = allowOtherRegion;
    }

    public static void lockAllocation(JdbcClient jdbc) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:k)) AS locked")
                .param("k", InviteService.GROUP_PICK_LOCK).query(Integer.class).single();
    }

    public List<Group> list() {
        return jdbc.sql(SELECT + " ORDER BY g.id").query((rs, i) -> map(rs)).list();
    }

    /** Nhóm của MỘT tài khoản Zalo phụ (D23): mã nhóm Zalo chỉ có nghĩa trong tài khoản đã tạo ra nhóm, nên mỗi tài khoản chỉ thêm khách vào nhóm của chính nó. */
    public List<Group> listOfAccount(long accountId) {
        return jdbc.sql(SELECT + " WHERE g.account_id = :acct ORDER BY g.id").param("acct", accountId).query((rs, i) -> map(rs)).list();
    }

    /** Người gửi vừa thêm một khách vào nhóm bằng tay: tăng số khách thật thêm 1. */
    @Transactional
    public void addMember(long groupId) {
        lockAllocation(jdbc);
        jdbc.sql("UPDATE zalo_group SET actual_customers = actual_customers + 1 WHERE id = :id").param("id", groupId).update();
    }

    public Optional<Group> find(long id) {
        return jdbc.sql(SELECT + " WHERE g.id = :id").param("id", id).query((rs, i) -> map(rs)).optional();
    }

    public Optional<Group> findOfAccount(long id, long accountId) {
        return jdbc.sql(SELECT + " WHERE g.id = :id AND g.account_id = :acct")
                .param("id", id).param("acct", accountId).query((rs, i) -> map(rs)).optional();
    }

    private static final String SELECT = """
            SELECT g.id, g.name, g.invite_link, g.target_customers, g.actual_customers, g.counted_at, g.status, g.note, g.geo_scope, g.zalo_group_id, g.account_id,
                   (SELECT count(*) FROM invitation i
                     WHERE i.group_id = g.id AND i.first_clicked_at IS NOT NULL
                       AND (g.counted_at IS NULL OR i.first_clicked_at > g.counted_at)
                       AND NOT i.zalo_group_add_counted
                       AND NOT EXISTS (SELECT 1 FROM zalo_group_add_attempt a WHERE a.invitation_id = i.id
                           AND a.group_id = g.id AND a.state IN ('DISPATCHED', 'REVIEW')
                           AND i.status <> 'JOINED'))
                   + (SELECT count(*) FROM open_entry o
                       WHERE o.group_id = g.id AND (g.counted_at IS NULL OR o.created_at > g.counted_at)) AS pending,
                   (SELECT count(*) FROM invitation r
                     WHERE r.reserved_group_id = g.id AND r.status = 'FRIENDED' AND r.reserved_until > now()
                       AND r.first_clicked_at IS NULL
                       AND NOT EXISTS (SELECT 1 FROM zalo_group_add_attempt a WHERE a.invitation_id = r.id
                           AND a.group_id = g.id AND a.state IN ('DISPATCHED', 'REVIEW')))
                   + (SELECT count(*) FROM zalo_group_add_attempt a LEFT JOIN invitation i ON i.id = a.invitation_id
                     WHERE a.group_id = g.id AND a.state IN ('DISPATCHED', 'REVIEW')
                       AND (i.status IS DISTINCT FROM 'JOINED' OR i.group_id IS DISTINCT FROM g.id)) AS reserved
            FROM zalo_group g""";

    public boolean hasAddSeat(Group group, boolean reserved, OffsetDateTime clickedAt, boolean overflow) {
        if (reserved) {
            return true;
        }
        return group.score() + addSeatCost(group, clickedAt) <= (overflow ? Math.max(group.target(), softCap()) : group.target());
    }

    public double addSeatCost(Group group, OffsetDateTime clickedAt) {
        return clickedAt != null && (group.countedAt() == null || clickedAt.isAfter(group.countedAt()))
                ? 1 - acceptRate : 1;
    }

    private Group map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int actual = rs.getInt("actual_customers");
        long pending = rs.getLong("pending");
        long reserved = rs.getLong("reserved");
        return new Group(rs.getLong("id"), rs.getString("name"), rs.getString("invite_link"),
                rs.getInt("target_customers"), actual, rs.getObject("counted_at", OffsetDateTime.class),
                GroupStatus.valueOf(rs.getString("status")), rs.getString("note"), pending,
                actual + pending * acceptRate + reserved, vn.shop.crm.geo.GeoArea.parse(rs.getString("geo_scope")).orElse(null),
                rs.getString("zalo_group_id"), reserved, rs.getLong("account_id"));
    }

    @Transactional
    public long create(String name, String link, Integer target) {
        return create(name, link, target, null);
    }

    @Transactional
    public long create(String name, String link, Integer target, String scope) {
        return create(name, link, target, scope, 1L);
    }

    /** Tạo nhóm thuộc một tài khoản Zalo phụ (D23); các đường cũ (nhóm tay, link) thuộc tài khoản 1. */
    @Transactional
    public long create(String name, String link, Integer target, String scope, long accountId) {
        lockAllocation(jdbc);
        String n = requireName(name);
        String l = validateLink(link);
        int t = validateTarget(target);
        return jdbc.sql("""
                        INSERT INTO zalo_group (name, invite_link, target_customers, geo_scope, account_id)
                        VALUES (:name, :link, :target, :scope, :acct) RETURNING id""")
                .param("acct", accountId).param("name", n).param("link", l).param("target", t).param("scope", validateScope(scope), java.sql.Types.VARCHAR)
                .query(Long.class).single();
    }

    @Transactional
    public void update(long id, String name, String link, Integer target, String note) {
        Group existing = find(id).orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        update(id, name, link, target, note, existing.scopeCode());
    }

    @Transactional
    public void update(long id, String name, String link, Integer target, String note, String scope) {
        lockAllocation(jdbc);
        Group g = find(id).orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        String l = validateLink(link);
        if (!java.util.Objects.equals(l, g.inviteLink())) {
            requireNoUnresolvedAdd(id, "Hãy đối chiếu lượt thêm đang xử lý trước khi đổi mã hoặc link nhóm.");
        }
        if (l == null && g.status() == GroupStatus.OPEN) {
            throw new ImportException("Nhóm đang nhận khách phải có link. Hãy tạm dừng nhóm trước khi xóa link.");
        }
        jdbc.sql("""
                        UPDATE zalo_group SET name = :name, invite_link = :link, target_customers = :target,
                                              note = :note, geo_scope = :scope WHERE id = :id""")
                .param("scope", validateScope(scope), java.sql.Types.VARCHAR)
                .param("name", requireName(name)).param("link", l).param("target", validateTarget(target))
                .param("note", blankToNull(note)).param("id", id).update();
    }

    /** Mã nhóm trong Zalo (chuỗi số) cho bộ nối D16; để trống = gỡ mã, bộ nối không thêm vào nhóm này nữa. */
    @Transactional
    public void setZaloGroupId(long id, String raw) {
        Group group = find(id).orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        setZaloGroupId(id, raw, group.accountId());
    }

    @Transactional
    public void setZaloGroupId(long id, String raw, long accountId) {
        String v = raw == null ? "" : raw.strip();
        if (!v.isEmpty() && !v.matches("\\d{1,32}")) {
            throw new ImportException("Mã nhóm Zalo chỉ gồm chữ số (không phải link nhóm).");
        }
        lockAllocation(jdbc);
        Group group = findOfAccount(id, accountId).orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        if (!java.util.Objects.equals(v.isEmpty() ? null : v, group.zaloGroupId())) {
            requireNoUnresolvedAdd(id, "Hãy đối chiếu lượt thêm đang xử lý trước khi đổi mã hoặc link nhóm.");
        }
        if (!v.isEmpty()) {
            ZaloSharedGroupGrants.checkLegacyMapping(jdbc, id, accountId, v);
        }
        int n = jdbc.sql("UPDATE zalo_group SET zalo_group_id = :z WHERE id = :id AND account_id = :acct")
                .param("z", v.isEmpty() ? null : v, java.sql.Types.VARCHAR).param("id", id).param("acct", accountId).update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy nhóm.");
        }
    }

    private void requireNoUnresolvedAdd(long groupId, String reason) {
        boolean unresolved = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM zalo_group_add_attempt WHERE group_id = :group
                          AND state IN ('DISPATCHED', 'REVIEW'))
                        """).param("group", groupId).query(Boolean.class).single();
        if (unresolved) {
            throw new ImportException(reason);
        }
    }

    /** Mẹ/quản trị đếm lại số khách thật trong nhóm; đặt lại mốc "đang chờ". */
    @Transactional
    public void recount(long id, Integer actual) {
        lockAllocation(jdbc);
        if (actual == null || actual < 0 || actual > 1000) {
            throw new ImportException("Số khách phải từ 0 đến 1000 (không tính tài khoản mẹ và trợ lý AI).");
        }
        // Tác vụ của khách đã xóa chỉ còn số chỗ ẩn danh. Kiểm kê trực tiếp trên Zalo chốt các lượt này.
        jdbc.sql("""
                        WITH settled AS (
                            UPDATE zalo_group_add_attempt SET state = 'RECOUNTED', result = 'ERASURE_RECOUNT', finished_at = now()
                            WHERE group_id = :group AND invitation_id IS NULL AND state = 'REVIEW'
                            RETURNING log_id
                        )
                        UPDATE zalo_auto_log SET result = 'ERASURE_RECOUNT', code = NULL
                        WHERE id IN (SELECT log_id FROM settled)
                        """).param("group", id).update();
        requireNoUnresolvedAdd(id, "Hãy đối chiếu lượt thêm đang xử lý trước khi đếm lại số khách.");
        int n = jdbc.sql("UPDATE zalo_group SET actual_customers = :a, counted_at = now() WHERE id = :id")
                .param("a", actual).param("id", id).update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy nhóm.");
        }
    }

    @Transactional
    public void setStatus(long id, GroupStatus status) {
        lockAllocation(jdbc);
        Group g = find(id).orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        if (status == GroupStatus.OPEN && !g.hasLink()) {
            throw new ImportException("Hãy nhập link nhóm Zalo trước khi mở nhóm nhận khách.");
        }
        jdbc.sql("UPDATE zalo_group SET status = :s WHERE id = :id").param("s", status.name()).param("id", id).update();
    }

    /**
     * Chọn nhóm cho một lượt bấm mà chưa biết khu vực của khách (xem {@link #pickGroup(Province)}).
     */
    public Optional<Group> pickGroup() {
        return pickGroup(null);
    }

    /**
     * Chọn nhóm cho một lượt bấm: nhóm đang mở, có link, chưa đầy. Ưu tiên nhóm CÙNG KHU VỰC với khách, theo thứ
     * tự: cùng tỉnh, cùng vùng, cùng miền, rồi nhóm chung (không gắn khu vực). Nhóm của miền khác bị loại (trừ khi
     * bật {@code app.grouping.allow-other-region}). Trong cùng thứ tự ưu tiên chọn nhóm có điểm {@code khách thật +
     * lượt bấm chờ × tỷ lệ giả định + chỗ đang giữ} thấp nhất (nhóm đã có mã nhóm Zalo đứng trước khi cùng thứ tự ưu tiên). Khách chưa rõ khu vực: nhóm chung trước, rồi mọi nhóm. Không có nhóm
     * phù hợp thì trả rỗng (landing page báo "chưa có chỗ", không chuyển hướng sai).
     */
    public Optional<Group> pickGroup(Province province) {
        return pickGroup(list(), province);
    }

    public Optional<Group> pickGroupOfAccount(Province province, long accountId) {
        return pickGroup(listOfAccount(accountId), province);
    }

    /**
     * Trần mềm (24/11/2026, chủ dự án chốt): số KHÁCH tối đa một nhóm nhận khi có khách đồng ý trễ hoặc khách dư lẻ không đủ lập nhóm mới
     * (mặc định 8; mục tiêu thường 5). Không tính tài khoản phụ và mẹ phó nhóm. Chỉnh ở trang Zalo.
     */
    public int softCap() {
        return settings.get(SOFT_CAP_KEY).map(v -> {
            try {
                return Math.max(MIN_SOFT_CAP, Math.min(MAX_SOFT_CAP, Integer.parseInt(v.strip())));
            } catch (NumberFormatException e) {
                return defaultSoftCap;
            }
        }).orElse(defaultSoftCap);
    }

    public void setSoftCap(int cap, String by) {
        if (cap < MIN_SOFT_CAP || cap > MAX_SOFT_CAP) {
            throw new ImportException("Trần mềm số khách mỗi nhóm phải từ " + MIN_SOFT_CAP + " đến " + MAX_SOFT_CAP + ".");
        }
        settings.set(SOFT_CAP_KEY, Integer.toString(cap), by);
    }

    /** Như {@link #pickGroup(Province)} nhưng dùng danh sách nhóm đã tải sẵn (tránh tải lại cho từng khách). */
    public Optional<Group> pickGroup(List<Group> all, Province province) {
        return pickGroup(all, province, false);
    }

    /**
     * Như trên; {@code overflow = true} thì cho cả nhóm đã đủ mục tiêu nhưng còn dưới TRẦN MỀM ({@link #softCap()}). Chỉ dùng cho
     * khách dư lẻ không thể lập nhóm mới (xem {@code ZaloAutoService.addNext}); nhóm còn dưới mục tiêu vẫn được chọn trước nhờ điểm thấp hơn.
     */
    public Optional<Group> pickGroup(List<Group> all, Province province, boolean overflow) {
        return pickGroup(all, province, overflow, Integer.MAX_VALUE);
    }

    /** Như trên, thêm {@code maxRank}: chỉ xét nhóm có thứ tự ưu tiên ({@link #rank}) không lớn hơn số này (0 = chỉ nhóm cùng tỉnh). */
    public Optional<Group> pickGroup(List<Group> all, Province province, boolean overflow, int maxRank) {
        return pickGroup(all, province, overflow, maxRank, false);
    }

    public Optional<Group> pickGroup(List<Group> all, Province province, boolean overflow, int maxRank, boolean fillFirst) {
        int cap = softCap();
        return all.stream()
                .filter(g -> g.status() == GroupStatus.OPEN && g.hasLink() && (overflow ? !g.fullAtCap(cap) : !g.full()))
                .filter(g -> rank(g, province) >= 0 && rank(g, province) <= maxRank)
                // cùng thứ tự ưu tiên thì nhóm đã có mã nhóm Zalo đứng trước: nhóm gợi ý ở tin link thường cũng là nhóm bộ nối tự thêm được
                .min(java.util.Comparator.comparingInt((Group g) -> rank(g, province))
                        .thenComparing((Group g) -> g.hasZaloId() ? 0 : 1)
                        .thenComparingDouble(g -> fillFirst ? g.target() - g.score() : g.score())
                        .thenComparingLong(Group::id));
    }

    /** Thứ tự ưu tiên (số nhỏ = hợp hơn); âm = không dùng được cho khách này. */
    public int rank(Group g, Province p) {
        if (p == null) {
            return g.scope() == null ? 0 : 1;
        }
        if (g.scope() == null) {
            return 3;
        }
        int close = g.scope().closeness(p);
        if (close >= 0) {
            return close;
        }
        return allowOtherRegion ? 4 : -1;
    }

    /** Khu vực của nhóm: rỗng = nhóm chung. */
    static String validateScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        return vn.shop.crm.geo.GeoArea.parse(scope.strip()).map(vn.shop.crm.geo.GeoArea::code)
                .orElseThrow(() -> new ImportException("Khu vực của nhóm không hợp lệ."));
    }

    /** Khu vực có khách đã quét mã nhưng chưa có nhóm hợp (30 ngày gần đây): để mẹ biết cần mở nhóm ở đâu. */
    public record Waiting(String provinceName, String regionLabel, long count) {
    }

    public List<Waiting> waitingByProvince() {
        var byProvince = new java.util.LinkedHashMap<String, Waiting>();
        jdbc.sql("""
                        SELECT province, count(*) AS n FROM open_entry
                        WHERE group_id IS NULL AND province IS NOT NULL AND created_at > now() - interval '30 days'
                        GROUP BY province ORDER BY n DESC, province""")
                .query((rs, i) -> Map.entry(rs.getString("province"), rs.getLong("n"))).list()
                .forEach(e -> vn.shop.crm.geo.VnGeo.byCode(e.getKey())
                        .ifPresent(p -> byProvince.put(p.name(), new Waiting(p.name(), p.region().label(), e.getValue()))));
        // Khách đã kết bạn mà chưa có nhóm hợp (R7): suy ra từ lời mời, không lưu thêm gì, nên mở nhóm mới là hết chờ ngay.
        friendedWaiting().forEach((name, w) -> byProvince.merge(name, w,
                (x, y) -> new Waiting(x.provinceName(), x.regionLabel(), x.count() + y.count())));
        return byProvince.values().stream()
                .sorted(java.util.Comparator.comparingLong(Waiting::count).reversed().thenComparing(Waiting::provinceName)).toList();
    }

    /** Khách đã kết bạn (chưa vào nhóm, chưa được cấp hay giữ nhóm nào) mà hiện không có nhóm hợp khu vực, gộp theo tỉnh. */
    Map<String, Waiting> friendedWaiting() {
        var groupsByAccount = list().stream().collect(java.util.stream.Collectors.groupingBy(Group::accountId));
        record WaitingCustomer(String province, long accountId) {
        }
        var out = new java.util.LinkedHashMap<String, Waiting>();
        jdbc.sql("""
                        SELECT c.province, i.account_id FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.status = 'FRIENDED' AND i.group_id IS NULL
                          AND (i.reserved_group_id IS NULL OR i.reserved_until <= now())""")
                .query((rs, n) -> new WaitingCustomer(rs.getString("province"), rs.getLong("account_id"))).list().forEach(customer -> {
                    var province = vn.shop.crm.geo.VnGeo.byCode(customer.province()).orElse(null);
                    if (pickGroup(groupsByAccount.getOrDefault(customer.accountId(), List.of()), province).isEmpty()) {
                        String name = province == null ? "Chưa rõ khu vực" : province.name();
                        String region = province == null ? "" : province.region().label();
                        out.merge(name, new Waiting(name, region, 1),
                                (x, y) -> new Waiting(x.provinceName(), x.regionLabel(), x.count() + 1));
                    }
                });
        return out;
    }

    // ---------- kiểm tra đầu vào ----------

    /** Link chỉ được là https tới zalo.me: chặn chuyển hướng ra trang lạ nếu nhập nhầm hoặc bị sửa. */
    public static String validateLink(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        String l = link.strip();
        if (l.length() > 300) {
            throw new ImportException("Link quá dài.");
        }
        try {
            URI uri = new URI(l);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            boolean zalo = host.equals("zalo.me") || host.endsWith(".zalo.me");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !zalo || uri.getUserInfo() != null) {
                throw new ImportException("Link nhóm phải bắt đầu bằng https:// và thuộc zalo.me "
                        + "(ví dụ https://zalo.me/g/abcxyz).");
            }
            return l;
        } catch (URISyntaxException e) {
            throw new ImportException("Link nhóm không hợp lệ.");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new ImportException("Hãy đặt tên nhóm (tối đa 100 ký tự).");
        }
        return name.strip();
    }

    private static int validateTarget(Integer target) {
        int t = target == null ? 5 : target;
        if (t < 1 || t > 500) {
            throw new ImportException("Mục tiêu số khách mỗi nhóm phải từ 1 đến 500.");
        }
        return t;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
