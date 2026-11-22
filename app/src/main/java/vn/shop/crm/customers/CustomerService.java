package vn.shop.crm.customers;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Tính lại phân loại khách từ các vận đơn, và truy vấn danh sách/chi tiết khách. */
@Service
public class CustomerService {

    public record CustomerRow(long id, String phone, String name, String address, LocalDateTime lastOrderOn,
                              Segment segment, int successfulPurchases, boolean needsReview, String reviewReasons,
                              String province) {
        public String displayPhone() {
            return vn.shop.crm.common.PhoneNormalizer.toDisplay(phone);
        }

        public java.util.Optional<vn.shop.crm.geo.Province> provinceInfo() {
            return vn.shop.crm.geo.VnGeo.byCode(province);
        }

        /** Tên tỉnh để hiển thị; "Chưa rõ" khi không đoán được từ địa chỉ. */
        public String provinceLabel() {
            return provinceInfo().map(vn.shop.crm.geo.Province::name).orElse("Chưa rõ");
        }

        public String regionLabel() {
            return provinceInfo().map(p -> p.region().label()).orElse("Chưa rõ");
        }

        public List<String> reviewReasonLabels() {
            if (reviewReasons == null || reviewReasons.isBlank()) {
                return List.of();
            }
            List<String> labels = new ArrayList<>();
            for (String code : reviewReasons.split(",")) {
                try {
                    labels.add(CustomerClassifier.ReviewReason.valueOf(code).label());
                } catch (IllegalArgumentException e) {
                    labels.add(code);
                }
            }
            return labels;
        }
    }

    public record ShipmentRow(long id, Carrier carrier, String trackingNo, String orderRef, String rawStatus,
                              CanonicalStatus status, LocalDateTime createdOn, LocalDateTime deliveredOn) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
        public int totalPages() {
            return (int) Math.max(1, (total + size - 1) / size);
        }

        public boolean hasPrevious() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < totalPages();
        }
    }

    public record Detail(CustomerRow customer, CustomerClassifier.Classification classification,
                         Map<Long, ShipmentRow> shipments) {
    }

    private record ShipmentWithContact(ShipmentRow shipment, String name, String address) {
    }

    private final JdbcClient jdbc;
    private final java.time.Clock clock;

    public CustomerService(JdbcClient jdbc, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ---------- tính lại ----------

    @Transactional
    public void recalculate(Collection<String> phones) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String phone : phones) {
            if (phone != null && !phone.isBlank()) {
                distinct.add(phone);
            }
        }
        distinct.forEach(this::recalculateOne);
    }

    @Transactional
    public int recalculateAll() {
        List<String> phones = jdbc.sql("SELECT DISTINCT receiver_phone FROM shipment")
                .query(String.class).list();
        recalculate(phones);
        jdbc.sql("DELETE FROM customer WHERE phone NOT IN (SELECT receiver_phone FROM shipment)").update();
        return phones.size();
    }

    private void recalculateOne(String phone) {
        List<ShipmentWithContact> rows = jdbc.sql("""
                        SELECT id, carrier, tracking_no, order_ref, raw_status, canonical_status,
                               created_on, delivered_on, receiver_name, receiver_address
                        FROM shipment WHERE receiver_phone = :phone
                        """)
                .param("phone", phone)
                .query(CustomerService::mapShipmentWithContact).list();
        if (rows.isEmpty()) {
            jdbc.sql("DELETE FROM customer WHERE phone = :phone").param("phone", phone).update();
            return;
        }
        List<CustomerClassifier.ShipmentFact> facts = rows.stream().map(r -> toFact(r.shipment())).toList();
        CustomerClassifier.Classification result = CustomerClassifier.classify(facts, now());

        Comparator<ShipmentWithContact> newestLast = Comparator
                .comparing((ShipmentWithContact r) -> r.shipment().createdOn(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(r -> r.shipment().id());
        List<ShipmentWithContact> sorted = rows.stream().sorted(newestLast).toList();
        String name = latestNonBlank(sorted, ShipmentWithContact::name);
        String address = latestNonBlank(sorted, ShipmentWithContact::address);
        // "Mua gần nhất" = ngày của đơn giao thành công gần nhất (không tính đơn hủy/hoàn/kẹt); chưa có thì để trống.
        LocalDateTime lastOrder = result.lastSuccessfulOn();
        String reasons = result.reasons().stream().map(Enum::name).sorted().collect(Collectors.joining(","));

        jdbc.sql("""
                        INSERT INTO customer (phone, name, address, last_order_on, segment, successful_purchases,
                                              needs_review, review_reasons, calculated_at, province, geo_version,
                                              calc_version)
                        VALUES (:phone, :name, :address, :lastOrder, :segment, :count, :review, :reasons, now(),
                                :province, :geoVersion, :calcVersion)
                        ON CONFLICT (phone) DO UPDATE SET
                            name = EXCLUDED.name, address = EXCLUDED.address,
                            last_order_on = EXCLUDED.last_order_on, segment = EXCLUDED.segment,
                            successful_purchases = EXCLUDED.successful_purchases,
                            needs_review = EXCLUDED.needs_review, review_reasons = EXCLUDED.review_reasons,
                            province = EXCLUDED.province, geo_version = EXCLUDED.geo_version,
                            calc_version = EXCLUDED.calc_version, calculated_at = now()
                        """)
                .param("phone", phone)
                .param("name", name, Types.VARCHAR)
                .param("address", address, Types.VARCHAR)
                .param("lastOrder", lastOrder == null ? null : Timestamp.valueOf(lastOrder), Types.TIMESTAMP)
                .param("segment", result.segment().name())
                .param("count", result.successfulPurchases())
                .param("review", result.needsReview())
                .param("reasons", reasons)
                .param("province", vn.shop.crm.geo.VnGeo.detect(address).map(vn.shop.crm.geo.Province::code).orElse(null),
                        Types.VARCHAR)
                .param("geoVersion", vn.shop.crm.geo.VnGeo.VERSION)
                .param("calcVersion", CALC_VERSION)
                .update();
    }

    /**
     * Phiên bản cách tính khách (cột "Mua gần nhất", đơn kẹt...). Tăng số này khi đổi cách tính để khi khởi động
     * hệ thống tính lại các khách còn ở phiên bản cũ.
     */
    public static final int CALC_VERSION = 2;

    /**
     * Tính lại khách cần tính lại vì: (1) còn ở phiên bản cách tính cũ, (2) có đơn "đang chạy" vừa quá ngưỡng kẹt
     * (thời gian trôi qua làm trạng thái đổi dù không có file mới), (3) đang bị gắn cờ kẹt mà không còn đơn kẹt.
     *
     * @return số khách đã tính lại
     */
    @Transactional
    public int refreshStale() {
        List<String> openNames = vn.shop.crm.shipping.StaleOrders.openNames();
        Set<String> phones = new LinkedHashSet<>(jdbc.sql("SELECT phone FROM customer WHERE calc_version < :v")
                .param("v", CALC_VERSION).query(String.class).list());
        phones.addAll(jdbc.sql("""
                        SELECT DISTINCT s.receiver_phone FROM shipment s
                        LEFT JOIN customer c ON c.phone = s.receiver_phone
                        WHERE s.canonical_status IN (:open) AND s.created_on < :cutoff
                          AND (c.id IS NULL OR c.review_reasons NOT LIKE :flag)""")
                .param("open", openNames).param("cutoff", Timestamp.valueOf(vn.shop.crm.shipping.StaleOrders.cutoff(now())))
                .param("flag", "%" + CustomerClassifier.ReviewReason.STALE_OPEN_ORDER.name() + "%")
                .query(String.class).list());
        phones.addAll(jdbc.sql("SELECT phone FROM customer WHERE review_reasons LIKE :flag")
                .param("flag", "%" + CustomerClassifier.ReviewReason.STALE_OPEN_ORDER.name() + "%")
                .query(String.class).list());
        recalculate(phones);
        return phones.size();
    }

    private static String latestNonBlank(List<ShipmentWithContact> oldestFirst,
                                         java.util.function.Function<ShipmentWithContact, String> getter) {
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            String value = getter.apply(oldestFirst.get(i));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static CustomerClassifier.ShipmentFact toFact(ShipmentRow s) {
        return new CustomerClassifier.ShipmentFact(s.id(), s.carrier(), s.trackingNo(), s.orderRef(),
                s.status(), s.createdOn());
    }

    // ---------- truy vấn ----------

    public Page<CustomerRow> search(Segment segment, boolean reviewOnly, String query, int page, int size) {
        return search(segment, reviewOnly, null, query, page, size);
    }

    /**
     * @param area mã khu vực ({@code R:NAM}, {@code S:...}, {@code P:...}) hoặc {@code U} (chưa rõ); rỗng/sai = tất cả
     */
    public Page<CustomerRow> search(Segment segment, boolean reviewOnly, String area, String query, int page, int size) {
        return search(CustomerQuery.of(segment, reviewOnly, area, query, page), size);
    }

    private static final String SELECT_COLUMNS = """
            SELECT c.id, c.phone, c.name, c.address, c.last_order_on, c.segment, c.successful_purchases,
                   c.needs_review, c.review_reasons, c.province\n""";

    /** Khách + cơ sở liên hệ + lời mời + ghi chú. Mỗi bảng nối có tối đa một dòng cho mỗi khách nên không nhân dòng. */
    private static final String FROM_JOINED = """
             FROM customer c
             LEFT JOIN contact_basis b ON b.customer_id = c.id AND b.purpose = '%s'
             LEFT JOIN invitation i ON i.customer_id = c.id
             LEFT JOIN customer_note n ON n.customer_id = c.id""".formatted(vn.shop.crm.contact.ContactService.PURPOSE);

    /** Trạng thái duyệt như chữ hiện trên bảng: sổ từ chối thắng, rồi tới cơ sở liên hệ, không có dòng thì chờ duyệt. */
    private static final String APPROVAL_EXPR = """
            CASE WHEN EXISTS (SELECT 1 FROM suppression s WHERE s.phone_hash = encode(sha256(convert_to(c.phone, 'UTF8')), 'hex'))
                      THEN 'REFUSED'
                 WHEN b.id IS NULL THEN 'PENDING'
                 WHEN b.status = 'ELIGIBLE' THEN 'ELIGIBLE'
                 WHEN b.status = 'BLOCKED' THEN 'BLOCKED'
                 ELSE 'REFUSED' END""";

    private static final String INVITE_EXPR =
            "CASE WHEN i.status IN ('READY','SENT','CLICKED','FRIENDED','JOINED','CANCELLED') THEN i.status ELSE 'NONE' END";

    /** Thứ tự tỉnh khi sắp xếp theo khu vực: theo miền rồi theo tên; chưa rõ (NULL) luôn xuống cuối. */
    private static final String AREA_RANK_EXPR = areaRankExpr();

    private static String areaRankExpr() {
        List<vn.shop.crm.geo.Province> provinces = new ArrayList<>(vn.shop.crm.geo.VnGeo.all());
        provinces.sort(Comparator.comparing((vn.shop.crm.geo.Province p) -> p.region().ordinal())
                .thenComparing(vn.shop.crm.geo.Province::name));
        StringBuilder sb = new StringBuilder("CASE c.province");
        for (int i = 0; i < provinces.size(); i++) {
            sb.append(" WHEN '").append(provinces.get(i).code().replace("'", "''")).append("' THEN ").append(i);
        }
        return sb.append(" END").toString();
    }

    private static String rankExpr(String expr, List<String> orderedCodes) {
        StringBuilder sb = new StringBuilder("CASE ").append(expr);
        for (int i = 0; i < orderedCodes.size(); i++) {
            sb.append(" WHEN '").append(orderedCodes.get(i)).append("' THEN ").append(i);
        }
        return sb.append(" END").toString();
    }

    private static String orderBy(CustomerQuery q) {
        String expr = switch (q.effectiveSort()) {
            case PHONE -> "c.phone";
            case NAME -> vn.shop.crm.common.VnText.sqlFold("NULLIF(c.name, '')");
            case ADDRESS -> vn.shop.crm.common.VnText.sqlFold("NULLIF(c.address, '')");
            case AREA -> AREA_RANK_EXPR;
            case PURCHASES -> "c.successful_purchases";
            case LAST -> "c.last_order_on";
            case SEGMENT -> rankExpr("c.segment", java.util.Arrays.stream(Segment.values()).map(Enum::name).toList());
            case APPROVAL -> "CASE WHEN c.needs_review THEN 0 ELSE 1 + ("
                    + rankExpr(APPROVAL_EXPR, java.util.Arrays.stream(CustomerQuery.Approval.values())
                    .filter(a -> a != CustomerQuery.Approval.REVIEW).map(Enum::name).toList()) + ") END";
            case INVITE -> rankExpr(INVITE_EXPR, java.util.Arrays.stream(CustomerQuery.Invite.values())
                    .map(Enum::name).toList());
            case NOTE -> vn.shop.crm.common.VnText.sqlFold("NULLIF(n.note, '')");
        };
        // Trống luôn xuống cuối dù tăng hay giảm; cùng giá trị thì theo mã khách để thứ tự ổn định giữa các trang.
        return " ORDER BY " + expr + (q.effectiveDesc() ? " DESC" : " ASC") + " NULLS LAST, c.id";
    }

    private static String where(CustomerQuery q, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        appendArea(where, params, q.area());
        if (q.segment() != null) {
            where.append(" AND c.segment = :segment");
            params.put("segment", q.segment().name());
        }
        if (q.review()) {
            where.append(" AND c.needs_review");
        }
        if (q.q() != null) {
            String digits = q.q().replaceAll("\\D", "");
            where.append(" AND (");
            if (!digits.isEmpty()) {
                where.append("replace(c.phone, '+', '') LIKE :qPhone OR ");
                params.put("qPhone", "%" + intlDigits(digits) + "%");
            }
            where.append(vn.shop.crm.common.VnText.sqlFold("c.name")).append(" LIKE :qName ESCAPE '\\')");
            params.put("qName", "%" + vn.shop.crm.common.VnText.likeEscape(vn.shop.crm.common.VnText.fold(q.q())) + "%");
        }
        if (q.phone() != null) {
            String digits = q.phone().replaceAll("\\D", "");
            if (digits.isEmpty()) {
                where.append(" AND FALSE");
            } else {
                where.append(" AND replace(c.phone, '+', '') LIKE :phone");
                params.put("phone", "%" + intlDigits(digits) + "%");
            }
        }
        if (q.name() != null) {
            where.append(" AND ").append(vn.shop.crm.common.VnText.sqlFold("c.name")).append(" LIKE :name ESCAPE '\\'");
            params.put("name", "%" + vn.shop.crm.common.VnText.likeEscape(vn.shop.crm.common.VnText.fold(q.name())) + "%");
        }
        if (q.address() != null) {
            where.append(" AND ").append(vn.shop.crm.common.VnText.sqlFold("c.address")).append(" LIKE :address ESCAPE '\\'");
            params.put("address", "%" + vn.shop.crm.common.VnText.likeEscape(vn.shop.crm.common.VnText.fold(q.address())) + "%");
        }
        if (q.buyFrom() != null) {
            where.append(" AND c.successful_purchases >= :buyFrom");
            params.put("buyFrom", q.buyFrom());
        }
        if (q.buyTo() != null) {
            where.append(" AND c.successful_purchases <= :buyTo");
            params.put("buyTo", q.buyTo());
        }
        if (q.lastFrom() != null) {
            where.append(" AND c.last_order_on >= :lastFrom");
            params.put("lastFrom", Timestamp.valueOf(q.lastFrom().atStartOfDay()));
        }
        if (q.lastTo() != null) {
            where.append(" AND c.last_order_on < :lastTo");
            params.put("lastTo", Timestamp.valueOf(q.lastTo().plusDays(1).atStartOfDay()));
        }
        if (q.approval() == CustomerQuery.Approval.REVIEW) {
            where.append(" AND c.needs_review");
        } else if (q.approval() != null) {
            // Khách cần kiểm tra hiện "Cần kiểm tra" thay cho trạng thái duyệt nên không nằm trong các nhóm còn lại.
            where.append(" AND NOT c.needs_review AND (").append(APPROVAL_EXPR).append(") = :approval");
            params.put("approval", q.approval().name());
        }
        if (q.invite() != null) {
            where.append(" AND (").append(INVITE_EXPR).append(") = :invite");
            params.put("invite", q.invite().name());
        }
        if (q.note() == CustomerQuery.NoteFilter.HAS) {
            where.append(" AND COALESCE(n.note, '') <> ''");
        } else if (q.note() == CustomerQuery.NoteFilter.NONE) {
            where.append(" AND COALESCE(n.note, '') = ''");
        }
        return where.toString();
    }

    /** Số điện thoại nhập kiểu 0912... khớp với dạng lưu 84912... (E.164 bỏ dấu +). */
    private static String intlDigits(String digits) {
        return digits.startsWith("0") ? "84" + digits.substring(1) : digits;
    }

    /** Lọc, sắp xếp và phân trang ngay trong cơ sở dữ liệu, nên đúng với hàng nghìn khách. */
    public Page<CustomerRow> search(CustomerQuery query, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(query, params);
        long total = bind(jdbc.sql("SELECT count(*)" + FROM_JOINED + where), params).query(Long.class).single();
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(query.page(), 1);
        String pageSql = SELECT_COLUMNS + FROM_JOINED + where + orderBy(query)
                + " LIMIT " + safeSize + " OFFSET " + ((long) (safePage - 1) * safeSize);
        List<CustomerRow> items = bind(jdbc.sql(pageSql), params).query(CustomerService::mapCustomer).list();
        return new Page<>(items, total, safePage, safeSize);
    }

    /** Toàn bộ khách theo bộ lọc, dùng để xuất CSV. */
    public List<CustomerRow> listAll(Segment segment, boolean reviewOnly) {
        return listAll(segment, reviewOnly, null);
    }

    public List<CustomerRow> listAll(Segment segment, boolean reviewOnly, String area) {
        return listAll(CustomerQuery.of(segment, reviewOnly, area, null, 1));
    }

    public List<CustomerRow> listAll(CustomerQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = SELECT_COLUMNS + FROM_JOINED + where(query, params) + orderBy(query);
        return bind(jdbc.sql(sql), params).query(CustomerService::mapCustomer).list();
    }

    public Detail detail(long id) {
        CustomerRow customer = jdbc.sql("""
                        SELECT id, phone, name, address, last_order_on, segment, successful_purchases, needs_review,
                               review_reasons, province FROM customer WHERE id = :id""")
                .param("id", id).query(CustomerService::mapCustomer).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy khách."));
        List<ShipmentRow> shipments = jdbc.sql("""
                        SELECT id, carrier, tracking_no, order_ref, raw_status, canonical_status,
                               created_on, delivered_on, receiver_name, receiver_address
                        FROM shipment WHERE receiver_phone = :phone""")
                .param("phone", customer.phone())
                .query(CustomerService::mapShipmentWithContact).list()
                .stream().map(ShipmentWithContact::shipment).toList();
        Map<Long, ShipmentRow> byId = new LinkedHashMap<>();
        shipments.forEach(s -> byId.put(s.id(), s));
        var classification = CustomerClassifier.classify(shipments.stream().map(CustomerService::toFact).toList(), now());
        return new Detail(customer, classification, byId);
    }

    public Map<Segment, Long> countBySegment() {
        Map<Segment, Long> result = new EnumMap<>(Segment.class);
        for (Segment s : Segment.values()) {
            result.put(s, 0L);
        }
        jdbc.sql("SELECT segment, count(*) AS n FROM customer GROUP BY segment")
                .query((rs, i) -> Map.entry(Segment.valueOf(rs.getString("segment")), rs.getLong("n")))
                .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public long countNeedsReview() {
        return jdbc.sql("SELECT count(*) FROM customer WHERE needs_review").query(Long.class).single();
    }

    /** Số khách theo miền; khóa null = chưa rõ. */
    public Map<vn.shop.crm.geo.Region, Long> countByRegion() {
        Map<vn.shop.crm.geo.Region, Long> result = new EnumMap<>(vn.shop.crm.geo.Region.class);
        for (vn.shop.crm.geo.Region r : vn.shop.crm.geo.Region.values()) {
            result.put(r, 0L);
        }
        jdbc.sql("SELECT province, count(*) AS n FROM customer WHERE province IS NOT NULL GROUP BY province")
                .query((rs, i) -> Map.entry(rs.getString("province"), rs.getLong("n"))).list()
                .forEach(e -> vn.shop.crm.geo.VnGeo.byCode(e.getKey())
                        .ifPresent(p -> result.merge(p.region(), e.getValue(), Long::sum)));
        return result;
    }

    public long countUnknownRegion() {
        return jdbc.sql("SELECT count(*) FROM customer WHERE province IS NULL").query(Long.class).single();
    }

    /** Số khách theo từng tỉnh (chỉ số tổng hợp, không kèm SĐT hay địa chỉ). */
    public Map<String, Long> countByProvince() {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql("SELECT province, count(*) AS n FROM customer WHERE province IS NOT NULL GROUP BY province ORDER BY n DESC")
                .query((rs, i) -> Map.entry(rs.getString("province"), rs.getLong("n"))).list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /**
     * Tính lại tỉnh cho khách có phiên bản cách đoán cũ (khách mới nhập đã có sẵn phiên bản mới). Chỉ đọc địa chỉ
     * đã có trong bảng khách, không gọi dịch vụ bên ngoài.
     */
    @Transactional
    public int refreshProvinces(boolean all) {
        record Row(long id, String address) {
        }
        List<Row> rows = jdbc.sql("SELECT id, address FROM customer" + (all ? "" : " WHERE geo_version < :v"))
                .param("v", vn.shop.crm.geo.VnGeo.VERSION).query((rs, i) -> new Row(rs.getLong("id"), rs.getString("address")))
                .list();
        for (Row r : rows) {
            jdbc.sql("UPDATE customer SET province = :p, geo_version = :v WHERE id = :id")
                    .param("p", vn.shop.crm.geo.VnGeo.detect(r.address()).map(vn.shop.crm.geo.Province::code).orElse(null),
                            Types.VARCHAR)
                    .param("v", vn.shop.crm.geo.VnGeo.VERSION).param("id", r.id()).update();
        }
        return rows.size();
    }

    private static void appendArea(StringBuilder where, Map<String, Object> params, String area) {
        if (area == null || area.isBlank()) {
            return;
        }
        if (vn.shop.crm.geo.GeoArea.UNKNOWN.equals(area)) {
            where.append(" AND c.province IS NULL");
            return;
        }
        vn.shop.crm.geo.GeoArea.parse(area).ifPresent(a -> {
            where.append(" AND c.province IN (:areaCodes)");
            params.put("areaCodes", vn.shop.crm.geo.VnGeo.in(a).stream().map(vn.shop.crm.geo.Province::code).toList());
        });
    }

    // ---------- helpers ----------

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec;
    }

    private static CustomerRow mapCustomer(ResultSet rs, int i) throws SQLException {
        return new CustomerRow(rs.getLong("id"), rs.getString("phone"), rs.getString("name"),
                rs.getString("address"), toLocal(rs.getTimestamp("last_order_on")),
                Segment.valueOf(rs.getString("segment")), rs.getInt("successful_purchases"),
                rs.getBoolean("needs_review"), rs.getString("review_reasons"), rs.getString("province"));
    }

    private static ShipmentWithContact mapShipmentWithContact(ResultSet rs, int i) throws SQLException {
        ShipmentRow row = new ShipmentRow(rs.getLong("id"), Carrier.valueOf(rs.getString("carrier")),
                rs.getString("tracking_no"), rs.getString("order_ref"), rs.getString("raw_status"),
                CanonicalStatus.valueOf(rs.getString("canonical_status")),
                toLocal(rs.getTimestamp("created_on")), toLocal(rs.getTimestamp("delivered_on")));
        return new ShipmentWithContact(row, rs.getString("receiver_name"), rs.getString("receiver_address"));
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
