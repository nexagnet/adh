package vn.shop.crm.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.geo.Province;

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
                        vn.shop.crm.geo.GeoArea scope) {
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

    public GroupService(JdbcClient jdbc, @Value("${app.invitations.assumed-accept-rate:0.5}") double acceptRate,
                        @Value("${app.grouping.allow-other-region:false}") boolean allowOtherRegion) {
        this.jdbc = jdbc;
        this.acceptRate = acceptRate;
        this.allowOtherRegion = allowOtherRegion;
    }

    public List<Group> list() {
        return jdbc.sql(SELECT + " ORDER BY g.id").query((rs, i) -> map(rs)).list();
    }

    public Optional<Group> find(long id) {
        return jdbc.sql(SELECT + " WHERE g.id = :id").param("id", id).query((rs, i) -> map(rs)).optional();
    }

    private static final String SELECT = """
            SELECT g.id, g.name, g.invite_link, g.target_customers, g.actual_customers, g.counted_at, g.status, g.note, g.geo_scope,
                   (SELECT count(*) FROM invitation i
                     WHERE i.group_id = g.id AND i.first_clicked_at IS NOT NULL
                       AND (g.counted_at IS NULL OR i.first_clicked_at > g.counted_at))
                   + (SELECT count(*) FROM open_entry o
                       WHERE o.group_id = g.id AND (g.counted_at IS NULL OR o.created_at > g.counted_at)) AS pending
            FROM zalo_group g""";

    private Group map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int actual = rs.getInt("actual_customers");
        long pending = rs.getLong("pending");
        return new Group(rs.getLong("id"), rs.getString("name"), rs.getString("invite_link"),
                rs.getInt("target_customers"), actual, rs.getObject("counted_at", OffsetDateTime.class),
                GroupStatus.valueOf(rs.getString("status")), rs.getString("note"), pending,
                actual + pending * acceptRate, vn.shop.crm.geo.GeoArea.parse(rs.getString("geo_scope")).orElse(null));
    }

    @Transactional
    public long create(String name, String link, Integer target) {
        return create(name, link, target, null);
    }

    @Transactional
    public long create(String name, String link, Integer target, String scope) {
        String n = requireName(name);
        String l = validateLink(link);
        int t = validateTarget(target);
        return jdbc.sql("""
                        INSERT INTO zalo_group (name, invite_link, target_customers, geo_scope)
                        VALUES (:name, :link, :target, :scope) RETURNING id""")
                .param("name", n).param("link", l).param("target", t).param("scope", validateScope(scope), java.sql.Types.VARCHAR)
                .query(Long.class).single();
    }

    @Transactional
    public void update(long id, String name, String link, Integer target, String note) {
        Group existing = find(id).orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        update(id, name, link, target, note, existing.scopeCode());
    }

    @Transactional
    public void update(long id, String name, String link, Integer target, String note, String scope) {
        Group g = find(id).orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        String l = validateLink(link);
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

    /** Mẹ/quản trị đếm lại số khách thật trong nhóm; đặt lại mốc "đang chờ". */
    @Transactional
    public void recount(long id, Integer actual) {
        if (actual == null || actual < 0 || actual > 1000) {
            throw new ImportException("Số khách phải từ 0 đến 1000 (không tính tài khoản mẹ và bot).");
        }
        int n = jdbc.sql("UPDATE zalo_group SET actual_customers = :a, counted_at = now() WHERE id = :id")
                .param("a", actual).param("id", id).update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy nhóm.");
        }
    }

    @Transactional
    public void setStatus(long id, GroupStatus status) {
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
     * lượt bấm chờ × tỷ lệ giả định} thấp nhất. Khách chưa rõ khu vực: nhóm chung trước, rồi mọi nhóm. Không có nhóm
     * phù hợp thì trả rỗng (landing page báo "chưa có chỗ", không chuyển hướng sai).
     */
    public Optional<Group> pickGroup(Province province) {
        return list().stream()
                .filter(g -> g.status() == GroupStatus.OPEN && g.hasLink() && !g.full())
                .filter(g -> rank(g, province) >= 0)
                .min(java.util.Comparator.comparingInt((Group g) -> rank(g, province)).thenComparingDouble(Group::score)
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
        return jdbc.sql("""
                        SELECT province, count(*) AS n FROM open_entry
                        WHERE group_id IS NULL AND province IS NOT NULL AND created_at > now() - interval '30 days'
                        GROUP BY province ORDER BY n DESC, province""")
                .query((rs, i) -> Map.entry(rs.getString("province"), rs.getLong("n"))).list().stream()
                .flatMap(e -> vn.shop.crm.geo.VnGeo.byCode(e.getKey()).stream()
                        .map(p -> new Waiting(p.name(), p.region().label(), e.getValue())))
                .toList();
    }

    // ---------- kiểm tra đầu vào ----------

    /** Link chỉ được là https tới zalo.me: chặn chuyển hướng ra trang lạ nếu nhập nhầm hoặc bị sửa. */
    static String validateLink(String link) {
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
