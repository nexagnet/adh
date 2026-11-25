package vn.shop.crm.contact;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneHash;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Sổ cơ sở liên hệ và danh sách từ chối (mục 3.2 file 03).
 *
 * <p>Quy tắc nền: không có dòng cơ sở liên hệ thì KHÔNG được liên hệ. Một khách chỉ nằm trong danh sách mời khi
 * đồng thời: phân loại {@code ONE_TIME}, không bị gắn "cần kiểm tra", có cơ sở liên hệ {@code ELIGIBLE}, và SĐT
 * không nằm trong danh sách từ chối. Khách {@code REPEAT} không bao giờ vào danh sách này, kể cả khi có dòng
 * {@code ELIGIBLE} (điều kiện nằm ngay trong truy vấn, không dựa vào việc "đừng duyệt").
 */
@Service
public class ContactService {

    public static final String PURPOSE = "GROUP_INVITE_POSTSALE";
    /** D3: chủ cửa hàng tự chấp nhận trách nhiệm; ghi rõ vào từng bản ghi để có thể xem lại. */
    public static final String STATEMENT = "Chủ cửa hàng chịu trách nhiệm: khách đã mua và nhận hàng; liên hệ chăm sóc "
            + "sau bán hàng, không phải quảng cáo sản phẩm. Chưa có xác nhận pháp lý riêng.";

    private static final String SUPPRESSED_HASH = "encode(sha256(convert_to(c.phone, 'UTF8')), 'hex')";
    /** Điều kiện của "ứng viên": một lần mua, không cần kiểm tra, chưa từ chối, chưa có dòng cơ sở liên hệ nào. */
    private static final String CANDIDATE_WHERE = """
            c.segment = 'ONE_TIME' AND c.needs_review = FALSE
            AND NOT EXISTS (SELECT 1 FROM suppression s WHERE s.phone_hash = %s AND s.lifted_at IS NULL)
            AND NOT EXISTS (SELECT 1 FROM contact_basis b WHERE b.customer_id = c.id AND b.purpose = '%s')
            """.formatted(SUPPRESSED_HASH, PURPOSE);

    public record Stats(long oneTime, long eligible, long candidates, long blocked, long optedOut, long erased,
                        long oneTimeNeedsReview) {
    }

    public record InviteRow(long id, String phone, String name, String address, LocalDateTime lastOrderOn,
                            String evidenceRef, LocalDateTime approvedAt, String province) {
        public String provinceLabel() {
            return vn.shop.crm.geo.VnGeo.byCode(province).map(vn.shop.crm.geo.Province::name).orElse("Chưa rõ");
        }
    }

    public record Basis(String basis, ContactStatus status, String evidenceRef, String statement, String note,
                        String recordedBy, LocalDateTime recordedAt) {
    }

    public record Event(String event, String detail, String recordedBy, LocalDateTime recordedAt) {
    }

    public record InvitePage(List<InviteRow> items, long total, int page, int size) {
        public int totalPages() {
            return (int) Math.max(1, Math.ceil(total / (double) size));
        }

        public boolean hasPrevious() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < totalPages();
        }
    }

    private final JdbcClient jdbc;
    private final TestMode testMode;

    public ContactService(JdbcClient jdbc, TestMode testMode) {
        this.jdbc = jdbc;
        this.testMode = testMode;
    }

    /** Tên người đang đăng nhập, để ghi "ai làm". */
    public static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    // ---------- thống kê & danh sách ----------

    public Stats stats() {
        long oneTime = count("SELECT count(*) FROM customer c WHERE c.segment = 'ONE_TIME'");
        long eligible = count(inviteCountSql());
        long candidates = count("SELECT count(*) FROM customer c WHERE " + candidateWhere());
        long blocked = count("SELECT count(*) FROM contact_basis WHERE purpose = '" + PURPOSE
                + "' AND status = 'BLOCKED'");
        long optedOut = count("SELECT count(*) FROM suppression WHERE reason = 'OPT_OUT' AND lifted_at IS NULL");
        long erased = count("SELECT count(*) FROM suppression WHERE reason = 'ERASED'");
        long review = count("SELECT count(*) FROM customer c WHERE c.segment = 'ONE_TIME' AND c.needs_review");
        return new Stats(oneTime, eligible, candidates, blocked, optedOut, erased, review);
    }

    /** Trạng thái duyệt mời và lời mời của một khách, dạng chữ tiếng Việt để hiện trong danh sách khách. */
    public record InviteState(String approval, String invitation) {
    }

    /**
     * Gộp trạng thái duyệt mời (cơ sở liên hệ, sổ từ chối) và lời mời của nhiều khách bằng vài truy vấn, không truy vấn
     * từng dòng. Khách không có trong kết quả thì chưa duyệt và chưa được mời.
     */
    public java.util.Map<Long, InviteState> inviteStates(java.util.Collection<Long> ids) {
        java.util.Map<Long, InviteState> result = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        java.util.Map<Long, String> basis = new java.util.HashMap<>();
        jdbc.sql("SELECT customer_id, status FROM contact_basis WHERE purpose = :p AND customer_id IN (:ids)")
                .param("p", PURPOSE).param("ids", ids)
                .query((rs, i) -> java.util.Map.entry(rs.getLong("customer_id"), rs.getString("status")))
                .list().forEach(e -> basis.put(e.getKey(), e.getValue()));
        java.util.Set<Long> suppressed = new java.util.HashSet<>(jdbc.sql(
                        "SELECT c.id FROM customer c WHERE c.id IN (:ids) AND EXISTS (SELECT 1 FROM suppression s "
                                + "WHERE s.phone_hash = " + SUPPRESSED_HASH + " AND s.lifted_at IS NULL)")
                .param("ids", ids).query(Long.class).list());
        java.util.Map<Long, String> invites = new java.util.HashMap<>();
        jdbc.sql("SELECT customer_id, status FROM invitation WHERE customer_id IN (:ids)").param("ids", ids)
                .query((rs, i) -> java.util.Map.entry(rs.getLong("customer_id"), rs.getString("status")))
                .list().forEach(e -> invites.put(e.getKey(), e.getValue()));
        for (Long id : ids) {
            String approval;
            if (suppressed.contains(id)) {
                approval = "Không được liên hệ";
            } else if (!basis.containsKey(id)) {
                approval = "Chưa duyệt";
            } else {
                approval = switch (basis.get(id)) {
                    case "ELIGIBLE" -> "Đã duyệt";
                    case "BLOCKED" -> "Bị chặn";
                    default -> "Không được liên hệ";
                };
            }
            String invitation = switch (invites.getOrDefault(id, "")) {
                case "READY" -> "Chưa gửi";
                case "SENT" -> "Đã gửi";
                case "CLICKED" -> "Đã bấm link";
                case "FRIENDED" -> "Đã kết bạn";
                case "JOINED" -> "Đã vào nhóm";
                case "CANCELLED" -> "Đã hủy";
                default -> "Chưa mời";
            };
            result.put(id, new InviteState(approval, invitation));
        }
        return result;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** Điều kiện của "ứng viên" cộng giới hạn chế độ thử: đang ở chế độ thử thì chỉ khách có số trong danh sách thử. */
    private String candidateWhere() {
        return CANDIDATE_WHERE + testMode.onlyTestSql("c.phone");
    }

    /** Đủ điều kiện mời theo nghiệp vụ, KHÔNG xét chế độ thử (dùng cho {@link #stillEligible}). */
    private static String inviteFromAll() {
        return """
                FROM customer c
                JOIN contact_basis b ON b.customer_id = c.id AND b.purpose = '%s' AND b.status = 'ELIGIBLE'
                WHERE c.segment = 'ONE_TIME' AND c.needs_review = FALSE
                  AND NOT EXISTS (SELECT 1 FROM suppression s WHERE s.phone_hash = %s AND s.lifted_at IS NULL)
                """.formatted(PURPOSE, SUPPRESSED_HASH);
    }

    /**
     * Nguồn của MỌI danh sách mời và chiến dịch. Đang ở chế độ thử (mặc định) thì chỉ có khách thử (số khai báo ở trang Zalo), nên khách
     * thật không bao giờ lọt vào danh sách, chiến dịch hay hàng chờ tự gửi, kể cả khi công tắc tự gửi đang bật.
     */
    private String inviteFrom() {
        return inviteFromAll() + testMode.onlyTestSql("c.phone");
    }

    private String inviteCountSql() {
        return "SELECT count(*) " + inviteFrom();
    }

    /**
     * Số khách đủ điều kiện mời mà chưa từng được mời, theo mã tỉnh (chuỗi rỗng = chưa rõ tỉnh). Chỉ là số tổng hợp, dùng đúng
     * điều kiện của {@link #eligibleNotInvited}; phục vụ bản tổng hợp ẩn danh cho phần đề xuất chiến dịch.
     */
    /**
     * Các hàm dành cho bản tổng hợp ẩn danh của AI đề xuất chiến dịch (eligibleNotInvited..., candidatesByProvince, optedOutAfterInvite...) luôn BỎ khách thuộc
     * danh sách số thử: chỉ tính khách và chiến dịch thật.
     */
    private static final String NOT_TEST = " AND c.phone NOT IN (SELECT t.phone FROM zalo_test_number t)";

    public java.util.Map<String, Long> eligibleNotInvitedByProvince() {
        return countsByProvince("SELECT coalesce(c.province, '') AS p, count(*) AS n " + inviteFrom()
                + " AND NOT EXISTS (SELECT 1 FROM invitation i WHERE i.customer_id = c.id)" + NOT_TEST + " GROUP BY 1");
    }

    /** Một dòng hàng đã giao thành công của khách đủ điều kiện mời mà chưa từng được mời (chỉ để đếm theo nhóm hàng; không ra khỏi hệ thống). */
    public record Purchase(String province, long customerId, String goods) {
    }

    public List<Purchase> eligibleNotInvitedPurchases() {
        return jdbc.sql("SELECT coalesce(c.province, '') AS p, c.id AS id, s.goods AS goods FROM customer c "
                        + "JOIN shipment s ON s.receiver_phone = c.phone AND s.canonical_status = 'DELIVERED' "
                        + "WHERE c.id IN (SELECT c.id " + inviteFrom()
                        + " AND NOT EXISTS (SELECT 1 FROM invitation i WHERE i.customer_id = c.id)" + NOT_TEST + ")")
                .query((rs, n) -> new Purchase(rs.getString("p"), rs.getLong("id"), rs.getString("goods"))).list();
    }

    /** Số khách "chờ duyệt" (ứng viên chưa có dòng cơ sở liên hệ nào) theo mã tỉnh; chuỗi rỗng = chưa rõ tỉnh. */
    public java.util.Map<String, Long> candidatesByProvince() {
        return countsByProvince("SELECT coalesce(c.province, '') AS p, count(*) AS n FROM customer c WHERE "
                + CANDIDATE_WHERE + NOT_TEST + " GROUP BY 1");
    }

    /** Số khách đã được nhắn mà nay đang nằm trong sổ từ chối (còn hiệu lực), theo mã tỉnh; chuỗi rỗng = chưa rõ tỉnh. */
    public java.util.Map<String, Long> optedOutAfterInviteByProvince() {
        return countsByProvince("SELECT coalesce(c.province, '') AS p, count(DISTINCT c.id) AS n FROM customer c "
                + "JOIN invitation i ON i.customer_id = c.id AND i.sent_at IS NOT NULL "
                + "WHERE EXISTS (SELECT 1 FROM suppression s WHERE s.phone_hash = " + SUPPRESSED_HASH
                + " AND s.reason = 'OPT_OUT' AND s.lifted_at IS NULL)" + NOT_TEST + " GROUP BY 1");
    }

    /** Số khách đã được nhắn trong từng chiến dịch mà nay đang nằm trong sổ từ chối (còn hiệu lực), theo mã chiến dịch. */
    public java.util.Map<Long, Long> optedOutAfterInviteByCampaign() {
        java.util.Map<Long, Long> out = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT i.campaign_id AS k, count(DISTINCT c.id) AS n FROM customer c "
                        + "JOIN invitation i ON i.customer_id = c.id AND i.sent_at IS NOT NULL "
                        + "WHERE EXISTS (SELECT 1 FROM suppression s WHERE s.phone_hash = " + SUPPRESSED_HASH
                        + " AND s.reason = 'OPT_OUT' AND s.lifted_at IS NULL)" + NOT_TEST + " GROUP BY 1")
                .query((rs, i) -> java.util.Map.entry(rs.getLong("k"), rs.getLong("n"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private java.util.Map<String, Long> countsByProvince(String sql) {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.sql(sql).query((rs, i) -> java.util.Map.entry(rs.getString("p"), rs.getLong("n"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Danh sách "đủ điều kiện mời". Đây là nguồn DUY NHẤT cho mọi chiến dịch mời (mốc 1C trở đi). */
    public InvitePage inviteList(int page, int size) {
        int p = Math.max(1, page);
        long total = count(inviteCountSql());
        List<InviteRow> items = jdbc.sql("""
                        SELECT c.id, c.phone, c.name, c.address, c.last_order_on, b.evidence_ref, b.recorded_at, c.province
                        """ + inviteFrom() + " ORDER BY b.recorded_at, c.id LIMIT :limit OFFSET :offset")
                .param("limit", size).param("offset", (long) (p - 1) * size)
                .query((rs, i) -> new InviteRow(rs.getLong("id"), rs.getString("phone"), rs.getString("name"),
                        rs.getString("address"), rs.getObject("last_order_on", LocalDateTime.class),
                        rs.getString("evidence_ref"), local(rs.getObject("recorded_at", OffsetDateTime.class)),
                        rs.getString("province")))
                .list();
        return new InvitePage(items, total, p, size);
    }

    public record Invitee(long id, String phone, String name) {
    }

    /**
     * Khách đủ điều kiện mời mà CHƯA từng được mời (mỗi khách một lần). Đây là cửa duy nhất để tạo lời mời: dùng
     * đúng điều kiện của danh sách mời, nên khách REPEAT, cần kiểm tra, bị chặn hay từ chối không bao giờ lọt vào.
     */
    public List<Invitee> eligibleNotInvited(int limit) {
        return eligibleNotInvited(limit, null);
    }

    /**
     * Như {@link #eligibleNotInvited(int)} nhưng chỉ lấy khách ở một khu vực (mã {@code R:NAM}, {@code S:...},
     * {@code P:...}, hoặc {@code U} = chưa rõ khu vực). Mã rỗng = mọi nơi. Khách chưa rõ tỉnh không lọt vào khi đã chọn
     * một khu vực cụ thể.
     */
    public List<Invitee> eligibleNotInvited(int limit, String area) {
        return eligibleNotInvited(limit, area, Pick.NONE);
    }

    /**
     * Cách chọn khách khi tạo chiến dịch: thứ tự lấy ({@code order}) và độ mới của lần mua gần nhất (số ngày từ {@code minDays} đến {@code maxDays};
     * null là không giới hạn đầu đó). Chỉ LÀM HẸP tập khách đủ điều kiện, không bao giờ mở rộng: khách từ chối, bị chặn, cần kiểm tra vẫn bị loại.
     * Có lọc độ mới thì khách chưa rõ ngày mua không được chọn.
     */
    public record Pick(String order, Integer minDays, Integer maxDays, ProductGroup product) {
        public Pick(String order, Integer minDays, Integer maxDays) {
            this(order, minDays, maxDays, null);
        }

        public static final String OLDEST_APPROVED = "OLDEST_APPROVED";
        public static final String NEWEST_PURCHASE = "NEWEST_PURCHASE";
        public static final String OLDEST_PURCHASE = "OLDEST_PURCHASE";
        public static final Pick NONE = new Pick(OLDEST_APPROVED, null, null, null);

        public Pick {
            order = order == null || order.isBlank() ? OLDEST_APPROVED : order;
            if (!List.of(OLDEST_APPROVED, NEWEST_PURCHASE, OLDEST_PURCHASE).contains(order)) {
                throw new vn.shop.crm.common.ImportException("Thứ tự lấy khách không hợp lệ.");
            }
            if (product != null && !product.filterable()) {
                throw new vn.shop.crm.common.ImportException("Nhóm hàng này không dùng để lọc khách.");
            }
            if ((minDays != null && minDays < 0) || (maxDays != null && maxDays < 0) || (minDays != null && maxDays != null && maxDays < minDays)) {
                throw new vn.shop.crm.common.ImportException("Độ mới của lần mua không hợp lệ.");
            }
        }

        public boolean any() {
            return !OLDEST_APPROVED.equals(order) || minDays != null || maxDays != null || product != null;
        }
    }

    private static final String PURCHASE_DAY = "(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')";

    /** Các dòng tên hàng đang có trong đơn thuộc nhóm hàng này (để lọc khách đã mua nhóm đó). */
    List<String> goodsOf(ProductGroup group) {
        return jdbc.sql("SELECT DISTINCT goods FROM shipment WHERE goods IS NOT NULL AND btrim(goods) <> ''")
                .query(String.class).list().stream().filter(g -> ProductGroup.of(g).contains(group)).toList();
    }

    private void appendPickFilter(StringBuilder sql, Pick pick) {
        if (pick.product() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM shipment ps WHERE ps.receiver_phone = c.phone AND ps.canonical_status = 'DELIVERED' "
                    + "AND ps.goods = ANY(:pickGoods))");
        }
        if (pick.minDays() != null) {
            sql.append(" AND c.last_order_on <= ").append(PURCHASE_DAY).append(" - make_interval(days => :minDays)");
        }
        if (pick.maxDays() != null) {
            sql.append(" AND c.last_order_on >= ").append(PURCHASE_DAY).append(" - make_interval(days => :maxDays)");
        }
    }

    private static String pickOrderSql(Pick pick) {
        return switch (pick.order()) {
            case Pick.NEWEST_PURCHASE -> " ORDER BY c.last_order_on DESC NULLS LAST, b.recorded_at, c.id";
            case Pick.OLDEST_PURCHASE -> " ORDER BY c.last_order_on ASC NULLS LAST, b.recorded_at, c.id";
            default -> " ORDER BY b.recorded_at, c.id";
        };
    }

    private org.springframework.jdbc.core.simple.JdbcClient.StatementSpec bindPick(
            org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec, Pick pick) {
        if (pick.product() != null) {
            spec.param("pickGoods", goodsOf(pick.product()).toArray(new String[0]));
        }
        if (pick.minDays() != null) {
            spec.param("minDays", pick.minDays());
        }
        if (pick.maxDays() != null) {
            spec.param("maxDays", pick.maxDays());
        }
        return spec;
    }

    public List<Invitee> eligibleNotInvited(int limit, String area, Pick pick) {
        StringBuilder sql = new StringBuilder("SELECT c.id, c.phone, c.name " + inviteFrom()
                + " AND NOT EXISTS (SELECT 1 FROM invitation i WHERE i.customer_id = c.id)");
        var areaCodes = appendArea(sql, area);
        appendPickFilter(sql, pick);
        sql.append(pickOrderSql(pick)).append(" LIMIT :limit");
        var spec = bindPick(jdbc.sql(sql.toString()).param("limit", limit), pick);
        if (areaCodes != null) {
            spec = spec.param("areaCodes", areaCodes);
        }
        return spec.query((rs, i) -> new Invitee(rs.getLong("id"), rs.getString("phone"), rs.getString("name")))
                .list();
    }

    /**
     * Khách MỚI (xuất hiện từ {@code since}) đủ tiêu chí để chiến dịch tự nhận: một lần mua, không cần kiểm tra, chưa từ chối, chưa có dòng cơ sở
     * liên hệ nào (chưa duyệt, chưa chặn), chưa từng được mời, đúng khu vực. Đây là tiêu chí duyệt sẵn thay cho duyệt từng người.
     */
    public List<Invitee> newCandidates(java.time.Instant since, String area, int limit) {
        StringBuilder sql = new StringBuilder("SELECT c.id, c.phone, c.name FROM customer c WHERE " + candidateWhere()
                + " AND c.first_seen_at >= :since AND NOT EXISTS (SELECT 1 FROM invitation i WHERE i.customer_id = c.id)");
        var areaCodes = appendArea(sql, area);
        sql.append(" ORDER BY c.first_seen_at, c.id LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("since", OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC)).param("limit", limit);
        if (areaCodes != null) {
            spec = spec.param("areaCodes", areaCodes);
        }
        return spec.query((rs, i) -> new Invitee(rs.getLong("id"), rs.getString("phone"), rs.getString("name"))).list();
    }

    /** Duyệt cơ sở liên hệ cho đúng những khách này (kiểm lại mọi điều kiện ứng viên). Trả số khách được duyệt. */
    @Transactional
    public int approveByIds(List<Long> ids, String statement, String by) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql(approveSql(" AND c.id IN (:ids)")).param("ids", ids).param("statement", statement).param("by", by).update();
    }

    /** Thêm điều kiện khu vực vào câu truy vấn; trả danh sách mã tỉnh cần gán vào {@code :areaCodes} (hoặc null). */
    private static List<String> appendArea(StringBuilder sql, String area) {
        if (area == null || area.isBlank()) {
            return null;
        }
        if (vn.shop.crm.geo.GeoArea.UNKNOWN.equals(area)) {
            sql.append(" AND c.province IS NULL");
            return null;
        }
        var parsed = vn.shop.crm.geo.GeoArea.parse(area)
                .orElseThrow(() -> new vn.shop.crm.common.ImportException("Khu vực không hợp lệ."));
        sql.append(" AND c.province IN (:areaCodes)");
        return vn.shop.crm.geo.VnGeo.in(parsed).stream().map(vn.shop.crm.geo.Province::code).toList();
    }

    /** Số khách đủ điều kiện mời mà chưa từng được mời, ở một khu vực (rỗng = mọi nơi). */
    public long countEligibleNotInvited(String area) {
        return countEligibleNotInvited(area, Pick.NONE);
    }

    public long countEligibleNotInvited(String area, Pick pick) {
        StringBuilder sql = new StringBuilder("SELECT count(*) " + inviteFrom()
                + " AND NOT EXISTS (SELECT 1 FROM invitation i WHERE i.customer_id = c.id)");
        var areaCodes = appendArea(sql, area);
        appendPickFilter(sql, pick);
        var spec = bindPick(jdbc.sql(sql.toString()), pick);
        if (areaCodes != null) {
            spec = spec.param("areaCodes", areaCodes);
        }
        return spec.query(Long.class).single();
    }

    /** Khách này CÒN đủ điều kiện mời ngay lúc này không (kiểm lại ở thời điểm khách bấm link và trước khi gửi). */
    public boolean stillEligible(long customerId) {
        return count("SELECT count(*) " + inviteFromAll() + " AND c.id = " + customerId) > 0;
    }

    /** Đoạn SQL lọc dòng theo chế độ thử cho một cột mã khách (rỗng khi chạy thật). Cho các hàng chờ gửi ở gói khác. */
    public String onlyTestCustomerSql(String customerIdColumn) {
        return testMode.onlyTestCustomerSql(customerIdColumn);
    }

    /**
     * {@link #stillEligible} VÀ được phép liên hệ lúc này: đang ở chế độ thử thì chỉ khách thử. Mọi chỗ sắp gọi Zalo cho một khách
     * (thêm nhóm, tạo nhóm, gửi tin) dùng hàm này thay vì {@link #stillEligible}.
     */
    public boolean mayContact(long customerId) {
        return testMode.customerAllowed(customerId) && stillEligible(customerId);
    }

    // ---------- duyệt / chặn / từ chối ----------

    /** Duyệt MỘT lần cho mọi khách "ứng viên". Trả số khách vừa được duyệt. */
    @Transactional
    public int approveAllCandidates() {
        return jdbc.sql(approveSql("")).param("statement", STATEMENT).param("by", actor()).update();
    }

    /** Duyệt một khách; báo lỗi rõ ràng nếu khách không đủ điều kiện. */
    @Transactional
    public void approve(long customerId) {
        int n = jdbc.sql(approveSql(" AND c.id = :id")).param("id", customerId)
                .param("statement", STATEMENT).param("by", actor()).update();
        if (n == 0) {
            throw new ImportException("Khách này chưa đủ điều kiện để duyệt: chỉ khách mua 1 lần, không cần kiểm tra, "
                    + "chưa từ chối và chưa được duyệt/chặn trước đó.");
        }
    }

    private String approveSql(String extraWhere) {
        return """
                WITH ins AS (
                    INSERT INTO contact_basis (customer_id, purpose, basis, status, evidence_ref, statement, recorded_by)
                    SELECT c.id, '%s', 'TRANSACTION_POSTSALE', 'ELIGIBLE',
                           (SELECT sh.carrier || ':' || sh.tracking_no FROM shipment sh
                             WHERE sh.receiver_phone = c.phone AND sh.canonical_status = 'DELIVERED'
                             ORDER BY sh.created_on DESC NULLS LAST, sh.id DESC LIMIT 1),
                           :statement, :by
                    FROM customer c WHERE %s %s
                    ON CONFLICT (customer_id, purpose) DO NOTHING
                    RETURNING customer_id)
                INSERT INTO contact_event (customer_id, phone_tail, event, detail, recorded_by)
                SELECT ins.customer_id, '***' || right(c.phone, 3), 'BASIS_APPROVED', 'Duyệt cơ sở liên hệ sau bán hàng', :by
                FROM ins JOIN customer c ON c.id = ins.customer_id
                """.formatted(PURPOSE, candidateWhere(), extraWhere);
    }

    /** Chặn thủ công một khách (ví dụ khách phàn nàn). Khách đã từ chối thì giữ nguyên. */
    @Transactional
    public void block(long customerId, String note) {
        String phone = phoneOf(customerId);
        jdbc.sql("""
                        INSERT INTO contact_basis (customer_id, purpose, basis, status, statement, note, recorded_by)
                        VALUES (:id, :purpose, 'NONE', 'BLOCKED', :statement, :note, :by)
                        ON CONFLICT (customer_id, purpose) DO UPDATE
                           SET status = 'BLOCKED', note = EXCLUDED.note, recorded_by = EXCLUDED.recorded_by,
                               recorded_at = now()
                           WHERE contact_basis.status <> 'OPTED_OUT'""")
                .param("id", customerId).param("purpose", PURPOSE).param("statement", "Chặn thủ công")
                .param("note", blankToNull(note)).param("by", actor()).update();
        cancelInvitations(customerId, "Khách bị chặn thủ công");
        event(customerId, phone, "BLOCKED", blankToNull(note));
    }

    /** Bỏ chặn: xóa dòng chặn, khách quay về trạng thái "chưa duyệt" (phải duyệt lại). Không đụng tới từ chối. */
    @Transactional
    public void unblock(long customerId) {
        String phone = phoneOf(customerId);
        int n = jdbc.sql("DELETE FROM contact_basis WHERE customer_id = :id AND purpose = :p AND status = 'BLOCKED'")
                .param("id", customerId).param("p", PURPOSE).update();
        if (n > 0) {
            event(customerId, phone, "UNBLOCKED", "Về trạng thái chưa duyệt");
        }
    }

    /**
     * Khách yêu cầu ngừng liên hệ: ghi vào danh sách từ chối (vĩnh viễn, chặn cả sau khi nhập lại file) và đặt
     * cơ sở liên hệ thành {@code OPTED_OUT}. Không có đường nào trong ứng dụng để gỡ từ chối.
     */
    @Transactional
    public void suppress(long customerId, String source) {
        suppressAs(customerId, source, actor());
    }

    /** Như {@link #suppress} nhưng ghi rõ "ai làm" (ví dụ khách tự bấm "ngừng nhận tin" trên trang mời). */
    @Transactional
    public void suppressAs(long customerId, String source, String by) {
        String phone = phoneOf(customerId);
        suppressPhone(phone, "OPT_OUT", source, by);
        jdbc.sql("""
                        INSERT INTO contact_basis (customer_id, purpose, basis, status, statement, note, recorded_by)
                        VALUES (:id, :purpose, 'NONE', 'OPTED_OUT', :statement, :note, :by)
                        ON CONFLICT (customer_id, purpose) DO UPDATE
                           SET basis = 'NONE', status = 'OPTED_OUT', note = EXCLUDED.note,
                               recorded_by = EXCLUDED.recorded_by, recorded_at = now()""")
                .param("id", customerId).param("purpose", PURPOSE).param("statement", "Khách yêu cầu ngừng liên hệ")
                .param("note", blankToNull(source)).param("by", by).update();
        cancelInvitations(customerId, "Khách yêu cầu ngừng liên hệ");
        eventAs(customerId, phone, "OPTED_OUT", blankToNull(source), by);
    }

    /** Tên người ghi trong sổ khi trợ lý AI tự ghi từ chối (để hoàn tác chỉ gỡ đúng phần AI làm). */
    public static final String AI_ACTOR = "tro-ly-ai";

    /**
     * Những gì cần để HOÀN TÁC một lần trợ lý AI tự ghi từ chối: có dòng cơ sở liên hệ từ trước không (và nội dung nó), số điện thoại đã nằm
     * trong danh sách từ chối từ trước chưa (nếu rồi thì hoàn tác không được gỡ).
     */
    public record AiOptOutUndo(boolean suppressionCreated, boolean hadBasis, String basis, String status, String note,
                               String recordedBy, String recordedAt, String priorLiftedAt, String priorLiftedBy) {
        /** Bản ghi cũ (trước V29) chưa có hai trường cuối. */
        public AiOptOutUndo(boolean suppressionCreated, boolean hadBasis, String basis, String status, String note,
                            String recordedBy, String recordedAt) {
            this(suppressionCreated, hadBasis, basis, status, note, recordedBy, recordedAt, null, null);
        }
    }

    /** Như {@link #suppressAs} với người ghi là trợ lý AI, và trả thông tin để {@link #undoAiOptOut} đưa mọi thứ về như trước. */
    @Transactional
    public AiOptOutUndo suppressByAi(long customerId, String source) {
        String phone = phoneOf(customerId);
        // Dòng sổ có từ trước (kể cả dòng đã gỡ) thì hoàn tác KHÔNG được xóa; dòng đã gỡ thì hoàn tác gỡ lại như cũ.
        String[] row = jdbc.sql("SELECT lifted_at, lifted_by FROM suppression WHERE phone_hash = :h")
                .param("h", PhoneHash.of(phone))
                .query((rs, i) -> new String[]{rs.getObject("lifted_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("lifted_at", OffsetDateTime.class).toInstant().toString(), rs.getString("lifted_by")})
                .optional().orElse(null);
        boolean created = row == null;
        String liftedAt = row == null ? null : row[0];
        String liftedBy = row == null ? null : row[1];
        AiOptOutUndo undo = jdbc.sql("""
                        SELECT basis, status, note, recorded_by, recorded_at FROM contact_basis
                        WHERE customer_id = :id AND purpose = :p""")
                .param("id", customerId).param("p", PURPOSE)
                .query((rs, i) -> new AiOptOutUndo(created, true, rs.getString("basis"), rs.getString("status"), rs.getString("note"),
                        rs.getString("recorded_by"), rs.getObject("recorded_at", OffsetDateTime.class).toInstant().toString(),
                        liftedAt, liftedBy))
                .optional().orElse(new AiOptOutUndo(created, false, null, null, null, null, null, liftedAt, liftedBy));
        suppressAs(customerId, source, AI_ACTOR);
        return undo;
    }

    /**
     * Gỡ phần trợ lý AI đã ghi: xóa dòng từ chối do AI tạo (không đụng dòng có từ trước hoặc do người ghi), đưa cơ sở liên hệ về như cũ.
     * Lời mời đã bị hủy theo thì do bên gọi khôi phục (chỉ nó biết trạng thái cũ).
     */
    @Transactional
    public void undoAiOptOut(long customerId, AiOptOutUndo u) {
        String phone = phoneOf(customerId);
        if (u.suppressionCreated()) {
            // Dòng này do chính trợ lý AI vừa tạo và chủ shop bấm Hoàn tác: KHÔNG xóa dòng sổ, chỉ đánh dấu đã gỡ kèm giờ (quy tắc 23/11).
            jdbc.sql("""
                            UPDATE suppression SET lifted_at = now(), lifted_by = :by
                            WHERE phone_hash = :h AND reason = 'OPT_OUT' AND recorded_by = :ai AND lifted_at IS NULL""")
                    .param("by", actor()).param("h", PhoneHash.of(phone)).param("ai", AI_ACTOR).update();
        } else if (u.priorLiftedAt() != null) {
            // Dòng đã gỡ từ trước và bị trợ lý kích hoạt lại: gỡ lại như cũ, giữ nguyên dòng.
            jdbc.sql("UPDATE suppression SET lifted_at = :at, lifted_by = :by WHERE phone_hash = :h AND reason = 'OPT_OUT' AND recorded_by = :ai")
                    .param("at", OffsetDateTime.ofInstant(java.time.Instant.parse(u.priorLiftedAt()), java.time.ZoneOffset.UTC))
                    .param("by", u.priorLiftedBy()).param("h", PhoneHash.of(phone)).param("ai", AI_ACTOR).update();
        }
        if (u.hadBasis()) {
            jdbc.sql("""
                            UPDATE contact_basis SET basis = :basis, status = :status, note = :note, recorded_by = :by,
                                   recorded_at = :at
                            WHERE customer_id = :id AND purpose = :p AND recorded_by = :ai""")
                    .param("basis", u.basis()).param("status", u.status()).param("note", u.note()).param("by", u.recordedBy())
                    .param("at", OffsetDateTime.ofInstant(java.time.Instant.parse(u.recordedAt()), java.time.ZoneOffset.UTC))
                    .param("id", customerId).param("p", PURPOSE).param("ai", AI_ACTOR).update();
        } else {
            jdbc.sql("DELETE FROM contact_basis WHERE customer_id = :id AND purpose = :p AND recorded_by = :ai AND status = 'OPTED_OUT'")
                    .param("id", customerId).param("p", PURPOSE).param("ai", AI_ACTOR).update();
        }
        event(customerId, phone, "OPT_OUT_UNDONE", "Hoàn tác: trợ lý AI nhận nhầm");
    }

    /** Những gì cần để hoàn tác một lần gỡ cờ "không nhắn nữa": cơ sở liên hệ cũ (giờ gỡ nằm ở chính dòng sổ). */
    public record LiftUndo(boolean hadBasis, String basis, String status, String note, String recordedBy, String recordedAt) {
    }

    /**
     * Gỡ cờ "không nhắn nữa" của một khách TỰ rút (OPT_OUT) khi chính khách tự nhắn muốn mua lại. KHÔNG xóa dòng sổ từ chối (mã băm): chỉ đánh dấu
     * đã gỡ kèm giờ và người gỡ, nên có lịch sử và hoàn tác được. Dòng ERASED (khách yêu cầu xóa dữ liệu) không bao giờ gỡ được. Cơ sở liên hệ
     * thành {@code OPT_IN} (khách tự mở lời). Lời mời do bên gọi khôi phục.
     */
    @Transactional
    public LiftUndo liftOptOut(long customerId, String by) {
        String phone = phoneOf(customerId);
        int n = jdbc.sql("""
                        UPDATE suppression SET lifted_at = now(), lifted_by = :by
                        WHERE phone_hash = :h AND reason = 'OPT_OUT' AND lifted_at IS NULL""")
                .param("by", by).param("h", PhoneHash.of(phone)).update();
        if (n == 0) {
            throw new ImportException("Khách này không có dòng \"không nhắn nữa\" còn hiệu lực để gỡ (hoặc đã yêu cầu xóa dữ liệu).");
        }
        LiftUndo undo = jdbc.sql("""
                        SELECT basis, status, note, recorded_by, recorded_at FROM contact_basis
                        WHERE customer_id = :id AND purpose = :p""")
                .param("id", customerId).param("p", PURPOSE)
                .query((rs, i) -> new LiftUndo(true, rs.getString("basis"), rs.getString("status"), rs.getString("note"),
                        rs.getString("recorded_by"), rs.getObject("recorded_at", OffsetDateTime.class).toInstant().toString()))
                .optional().orElse(new LiftUndo(false, null, null, null, null, null));
        jdbc.sql("""
                        INSERT INTO contact_basis (customer_id, purpose, basis, status, statement, note, recorded_by)
                        VALUES (:id, :purpose, 'OPT_IN', 'ELIGIBLE', :statement, :note, :by)
                        ON CONFLICT (customer_id, purpose) DO UPDATE
                           SET basis = 'OPT_IN', status = 'ELIGIBLE', note = EXCLUDED.note,
                               recorded_by = EXCLUDED.recorded_by, recorded_at = now()""")
                .param("id", customerId).param("purpose", PURPOSE).param("statement", "Khách tự nhắn muốn mua lại")
                .param("note", "Khách từng từ chối, nay tự nhắn muốn mua").param("by", by).update();
        eventAs(customerId, phone, "OPT_OUT_LIFTED", "Khách tự nhắn muốn mua lại, đã gỡ cờ không nhắn nữa", by);
        return undo;
    }

    /** Hoàn tác {@link #liftOptOut}: đánh dấu hiệu lực lại (không tạo dòng mới) và đưa cơ sở liên hệ về như cũ. */
    @Transactional
    public void undoLiftOptOut(long customerId, LiftUndo u, String liftedBy) {
        String phone = phoneOf(customerId);
        jdbc.sql("""
                        UPDATE suppression SET lifted_at = NULL, lifted_by = NULL
                        WHERE phone_hash = :h AND reason = 'OPT_OUT' AND lifted_by = :by""")
                .param("h", PhoneHash.of(phone)).param("by", liftedBy).update();
        if (u.hadBasis()) {
            jdbc.sql("""
                            UPDATE contact_basis SET basis = :basis, status = :status, note = :note, recorded_by = :by, recorded_at = :at
                            WHERE customer_id = :id AND purpose = :p AND basis = 'OPT_IN' AND recorded_by = :lifter""")
                    .param("basis", u.basis()).param("status", u.status()).param("note", u.note()).param("by", u.recordedBy())
                    .param("at", OffsetDateTime.ofInstant(java.time.Instant.parse(u.recordedAt()), java.time.ZoneOffset.UTC))
                    .param("id", customerId).param("p", PURPOSE).param("lifter", liftedBy).update();
        }
        eventAs(customerId, phone, "OPT_OUT_RELIFTED", "Hoàn tác: khách lại không được nhắn", actor());
    }

    /** Khách này có dòng "không nhắn nữa" do KHÁCH TỰ RÚT còn hiệu lực không (loại ERASED, loại đã gỡ). */
    public boolean hasActiveOptOut(String phone) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM suppression WHERE phone_hash = :h AND reason = 'OPT_OUT' AND lifted_at IS NULL)")
                .param("h", PhoneHash.of(phone)).query(Boolean.class).single();
    }

    /**
     * Hủy lời mời của khách: lời mời chưa gửi thì xóa hẳn, lời mời đã gửi thì giữ lại và đánh dấu CANCELLED để còn
     * dấu vết. Landing page cũng kiểm lại điều kiện khi khách bấm nên link đã gửi không còn dùng được.
     */
    void cancelInvitations(long customerId, String reason) {
        jdbc.sql("DELETE FROM invitation WHERE customer_id = :id AND status = 'READY'")
                .param("id", customerId).update();
        jdbc.sql("""
                        UPDATE invitation SET status = 'CANCELLED', cancelled_reason = :reason
                        WHERE customer_id = :id AND status IN ('SENT', 'CLICKED', 'FRIENDED')""")
                .param("id", customerId).param("reason", reason).update();
    }

    /**
     * Khách tự xin ngừng nhận tin bằng số điện thoại (trang giấy giới thiệu dùng mã QR chung, không có link riêng).
     * Số hợp lệ nào cũng được ghi vào danh sách từ chối (chỉ lưu băm); nếu số đó đã là khách thì xử lý như
     * "ngừng liên hệ" đầy đủ. Trả lỗi tiếng Việt nếu số không hợp lệ.
     */
    @Transactional
    public void optOutByPhone(String rawPhone, String source) {
        String phone = vn.shop.crm.common.PhoneNormalizer.toE164(rawPhone)
                .orElseThrow(() -> new ImportException("Số điện thoại chưa đúng. Hãy nhập số 10 chữ số, ví dụ 0912345678."));
        Optional<Long> customer = jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", phone)
                .query(Long.class).optional();
        if (customer.isPresent()) {
            suppressAs(customer.get(), source, "khach-tu-bam");
        } else {
            suppressPhone(phone, "OPT_OUT", source, "khach-tu-bam");
        }
    }

    /** Ghi SĐT vào danh sách từ chối (chỉ lưu băm). Gọi lặp lại không sao. */
    void suppressPhone(String phone, String reason, String source) {
        suppressPhone(phone, reason, source, actor());
    }

    void suppressPhone(String phone, String reason, String source, String by) {
        jdbc.sql("""
                        INSERT INTO suppression (phone_hash, reason, source, recorded_by)
                        VALUES (:hash, :reason, :source, :by)
                        ON CONFLICT (phone_hash) DO UPDATE SET
                            reason = CASE WHEN EXCLUDED.reason = 'ERASED' THEN 'ERASED' ELSE suppression.reason END,
                            source = EXCLUDED.source, recorded_by = EXCLUDED.recorded_by, recorded_at = now(),
                            lifted_at = NULL, lifted_by = NULL
                        WHERE suppression.lifted_at IS NOT NULL
                           OR (EXCLUDED.reason = 'ERASED' AND suppression.reason <> 'ERASED')""")
                .param("hash", PhoneHash.of(phone)).param("reason", reason)
                .param("source", blankToNull(source)).param("by", by).update();
    }

    public boolean isSuppressed(String phone) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM suppression WHERE phone_hash = :h AND lifted_at IS NULL)")
                .param("h", PhoneHash.of(phone)).query(Boolean.class).single();
    }

    // ---------- xem ----------

    public Optional<Basis> basisOf(long customerId) {
        return jdbc.sql("""
                        SELECT basis, status, evidence_ref, statement, note, recorded_by, recorded_at
                        FROM contact_basis WHERE customer_id = :id AND purpose = :p""")
                .param("id", customerId).param("p", PURPOSE)
                .query((rs, i) -> new Basis(rs.getString("basis"), ContactStatus.valueOf(rs.getString("status")),
                        rs.getString("evidence_ref"), rs.getString("statement"), rs.getString("note"),
                        rs.getString("recorded_by"), local(rs.getObject("recorded_at", OffsetDateTime.class))))
                .optional();
    }

    public List<Event> events(long customerId) {
        return jdbc.sql("""
                        SELECT event, detail, recorded_by, recorded_at FROM contact_event
                        WHERE customer_id = :id ORDER BY recorded_at DESC, id DESC""")
                .param("id", customerId)
                .query((rs, i) -> new Event(rs.getString("event"), rs.getString("detail"), rs.getString("recorded_by"),
                        local(rs.getObject("recorded_at", OffsetDateTime.class))))
                .list();
    }

    public String note(long customerId) {
        return jdbc.sql("SELECT note FROM customer_note WHERE customer_id = :id").param("id", customerId)
                .query(String.class).optional().orElse("");
    }

    /** Ghi chú của nhiều khách bằng một truy vấn (khách không có ghi chú thì không có trong kết quả). */
    public java.util.Map<Long, String> notes(java.util.Collection<Long> ids) {
        java.util.Map<Long, String> result = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT customer_id, note FROM customer_note WHERE customer_id IN (:ids)").param("ids", ids)
                .query((rs, i) -> java.util.Map.entry(rs.getLong("customer_id"), rs.getString("note")))
                .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    @Transactional
    public void saveNote(long customerId, String note) {
        phoneOf(customerId); // báo lỗi nếu khách không tồn tại
        String text = note == null ? "" : note.strip();
        if (text.length() > 2000) {
            throw new ImportException("Ghi chú quá dài (tối đa 2000 ký tự).");
        }
        if (text.isEmpty()) {
            jdbc.sql("DELETE FROM customer_note WHERE customer_id = :id").param("id", customerId).update();
        } else {
            jdbc.sql("""
                            INSERT INTO customer_note (customer_id, note) VALUES (:id, :note)
                            ON CONFLICT (customer_id) DO UPDATE SET note = EXCLUDED.note, updated_at = now()""")
                    .param("id", customerId).param("note", text).update();
        }
    }

    // ---------- nội bộ ----------

    public String phoneOf(long customerId) {
        return jdbc.sql("SELECT phone FROM customer WHERE id = :id").param("id", customerId)
                .query(String.class).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy khách."));
    }

    public void event(Long customerId, String phone, String event, String detail) {
        eventAs(customerId, phone, event, detail, actor());
    }

    void eventAs(Long customerId, String phone, String event, String detail, String by) {
        jdbc.sql("""
                        INSERT INTO contact_event (customer_id, phone_tail, event, detail, recorded_by)
                        VALUES (:id, :tail, :event, :detail, :by)""")
                .param("id", customerId).param("tail", PhoneHash.tail(phone)).param("event", event)
                .param("detail", detail).param("by", by).update();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDateTime();
    }
}
