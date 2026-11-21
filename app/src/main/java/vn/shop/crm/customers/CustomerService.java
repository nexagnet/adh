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
                              Segment segment, int successfulPurchases, boolean needsReview, String reviewReasons) {
        public String displayPhone() {
            return vn.shop.crm.common.PhoneNormalizer.toDisplay(phone);
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

    public CustomerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        CustomerClassifier.Classification result = CustomerClassifier.classify(facts);

        Comparator<ShipmentWithContact> newestLast = Comparator
                .comparing((ShipmentWithContact r) -> r.shipment().createdOn(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(r -> r.shipment().id());
        List<ShipmentWithContact> sorted = rows.stream().sorted(newestLast).toList();
        String name = latestNonBlank(sorted, ShipmentWithContact::name);
        String address = latestNonBlank(sorted, ShipmentWithContact::address);
        LocalDateTime lastOrder = rows.stream().map(r -> r.shipment().createdOn())
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        String reasons = result.reasons().stream().map(Enum::name).sorted().collect(Collectors.joining(","));

        jdbc.sql("""
                        INSERT INTO customer (phone, name, address, last_order_on, segment, successful_purchases,
                                              needs_review, review_reasons, calculated_at)
                        VALUES (:phone, :name, :address, :lastOrder, :segment, :count, :review, :reasons, now())
                        ON CONFLICT (phone) DO UPDATE SET
                            name = EXCLUDED.name, address = EXCLUDED.address,
                            last_order_on = EXCLUDED.last_order_on, segment = EXCLUDED.segment,
                            successful_purchases = EXCLUDED.successful_purchases,
                            needs_review = EXCLUDED.needs_review, review_reasons = EXCLUDED.review_reasons,
                            calculated_at = now()
                        """)
                .param("phone", phone)
                .param("name", name, Types.VARCHAR)
                .param("address", address, Types.VARCHAR)
                .param("lastOrder", lastOrder == null ? null : Timestamp.valueOf(lastOrder), Types.TIMESTAMP)
                .param("segment", result.segment().name())
                .param("count", result.successfulPurchases())
                .param("review", result.needsReview())
                .param("reasons", reasons)
                .update();
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
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (segment != null) {
            where.append(" AND segment = :segment");
            params.put("segment", segment.name());
        }
        if (reviewOnly) {
            where.append(" AND needs_review");
        }
        if (query != null && !query.isBlank()) {
            String q = query.strip();
            String digits = q.replaceAll("\\D", "");
            where.append(" AND (");
            if (!digits.isEmpty()) {
                String intl = digits.startsWith("0") ? "84" + digits.substring(1) : digits;
                where.append("replace(phone, '+', '') LIKE :phoneLike OR ");
                params.put("phoneLike", "%" + intl + "%");
            }
            where.append("name ILIKE :nameLike ESCAPE '\\')");
            params.put("nameLike", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        long total = bind(jdbc.sql("SELECT count(*) FROM customer" + where), params).query(Long.class).single();
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        String pageSql = """
                SELECT id, phone, name, address, last_order_on, segment, successful_purchases, needs_review,
                       review_reasons FROM customer""" + where
                + " ORDER BY last_order_on DESC NULLS LAST, id LIMIT " + safeSize
                + " OFFSET " + ((long) (safePage - 1) * safeSize);
        List<CustomerRow> items = bind(jdbc.sql(pageSql), params)
                .query(CustomerService::mapCustomer).list();
        return new Page<>(items, total, safePage, safeSize);
    }

    /** Toàn bộ khách theo bộ lọc, dùng để xuất CSV. */
    public List<CustomerRow> listAll(Segment segment, boolean reviewOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, phone, name, address, last_order_on, segment, successful_purchases, needs_review,
                       review_reasons FROM customer WHERE 1=1""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (segment != null) {
            sql.append(" AND segment = :segment");
            params.put("segment", segment.name());
        }
        if (reviewOnly) {
            sql.append(" AND needs_review");
        }
        sql.append(" ORDER BY last_order_on DESC NULLS LAST, id");
        return bind(jdbc.sql(sql.toString()), params).query(CustomerService::mapCustomer).list();
    }

    public Detail detail(long id) {
        CustomerRow customer = jdbc.sql("""
                        SELECT id, phone, name, address, last_order_on, segment, successful_purchases, needs_review,
                               review_reasons FROM customer WHERE id = :id""")
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
        var classification = CustomerClassifier.classify(shipments.stream().map(CustomerService::toFact).toList());
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
                rs.getBoolean("needs_review"), rs.getString("review_reasons"));
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
