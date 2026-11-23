package vn.shop.crm.shipping;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.customers.CustomerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bảng ánh xạ trạng thái gốc của từng hãng -> trạng thái chuẩn.
 * Trạng thái chưa có trong bảng luôn là UNKNOWN và KHÔNG bao giờ được tính là giao thành công.
 */
@Service
public class StatusMappingService {

    public record MappingRow(Carrier carrier, String statusKey, String sampleText, CanonicalStatus status) {
    }

    public record UnmappedRow(Carrier carrier, String statusKey, String sampleText, long shipments) {
    }

    /** Đơn kẹt: hãng báo "đang chạy" quá {@link StaleOrders#AFTER_DAYS} ngày mà không cập nhật thêm. */
    public record StuckRow(Carrier carrier, String sampleText, CanonicalStatus status, long shipments,
                           java.time.LocalDate oldest, java.time.LocalDate newest) {
    }

    private final JdbcClient jdbc;
    private final CustomerService customers;
    private final java.time.Clock clock;

    public StatusMappingService(JdbcClient jdbc, CustomerService customers, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.clock = clock;
    }

    public Map<String, CanonicalStatus> mappingsFor(Carrier carrier) {
        Map<String, CanonicalStatus> result = new HashMap<>();
        jdbc.sql("SELECT status_key, canonical_status FROM status_mapping WHERE carrier = :carrier")
                .param("carrier", carrier.name())
                .query((rs, i) -> Map.entry(rs.getString("status_key"),
                        CanonicalStatus.valueOf(rs.getString("canonical_status"))))
                .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public List<MappingRow> all() {
        return jdbc.sql("SELECT carrier, status_key, sample_text, canonical_status FROM status_mapping "
                        + "ORDER BY carrier, canonical_status, sample_text")
                .query((rs, i) -> new MappingRow(Carrier.valueOf(rs.getString("carrier")),
                        rs.getString("status_key"), rs.getString("sample_text"),
                        CanonicalStatus.valueOf(rs.getString("canonical_status"))))
                .list();
    }

    /** Trạng thái đã xuất hiện trong dữ liệu nhưng chưa được ánh xạ. */
    public List<UnmappedRow> unmapped() {
        return jdbc.sql("""
                        SELECT carrier, status_key, min(raw_status) AS sample_text, count(*) AS n
                        FROM shipment WHERE canonical_status = 'UNKNOWN'
                        GROUP BY carrier, status_key ORDER BY n DESC""")
                .query((rs, i) -> new UnmappedRow(Carrier.valueOf(rs.getString("carrier")),
                        rs.getString("status_key"), rs.getString("sample_text"), rs.getLong("n")))
                .list();
    }

    /** Đơn kẹt (xem {@link StaleOrders}), gom theo hãng và trạng thái gốc; không tính là thành công, không tính là hủy. */
    public List<StuckRow> stuck() {
        return jdbc.sql("""
                        SELECT carrier, status_key, min(raw_status) AS sample_text, canonical_status, count(*) AS n,
                               min(created_on) AS oldest, max(created_on) AS newest
                        FROM shipment WHERE canonical_status IN (:open) AND created_on < :cutoff
                        GROUP BY carrier, status_key, canonical_status ORDER BY n DESC, carrier""")
                .param("open", StaleOrders.openNames())
                .param("cutoff", java.sql.Timestamp.valueOf(StaleOrders.cutoff(java.time.LocalDateTime.now(clock))))
                .query((rs, i) -> new StuckRow(Carrier.valueOf(rs.getString("carrier")), rs.getString("sample_text"),
                        CanonicalStatus.valueOf(rs.getString("canonical_status")), rs.getLong("n"),
                        rs.getTimestamp("oldest").toLocalDateTime().toLocalDate(),
                        rs.getTimestamp("newest").toLocalDateTime().toLocalDate()))
                .list();
    }

    /** Gán (hoặc xóa, nếu UNKNOWN) ánh xạ, cập nhật các vận đơn đã có và tính lại các khách bị ảnh hưởng. */
    @Transactional
    public void assign(Carrier carrier, String sampleText, CanonicalStatus status) {
        String key = StatusKey.of(sampleText);
        if (key.isEmpty()) {
            throw new ImportException("Trạng thái không được để trống.");
        }
        if (status == CanonicalStatus.UNKNOWN) {
            jdbc.sql("DELETE FROM status_mapping WHERE carrier = :c AND status_key = :k")
                    .param("c", carrier.name()).param("k", key).update();
        } else {
            jdbc.sql("""
                            INSERT INTO status_mapping (carrier, status_key, sample_text, canonical_status)
                            VALUES (:c, :k, :sample, :s)
                            ON CONFLICT (carrier, status_key) DO UPDATE SET
                                canonical_status = EXCLUDED.canonical_status, sample_text = EXCLUDED.sample_text""")
                    .param("c", carrier.name()).param("k", key).param("sample", sampleText.strip())
                    .param("s", status.name()).update();
        }
        List<String> phones = jdbc.sql("SELECT DISTINCT receiver_phone FROM shipment "
                        + "WHERE carrier = :c AND status_key = :k")
                .param("c", carrier.name()).param("k", key).query(String.class).list();
        // đơn đã được file đối soát chứng minh giao thành công thì giữ nguyên, trừ khi nghĩa mới là hủy/hoàn
        jdbc.sql("""
                        UPDATE shipment SET canonical_status = :s, updated_at = now(),
                            settled_delivered = settled_delivered AND :s NOT IN ('CANCELLED', 'RETURNING', 'RETURNED')
                        WHERE carrier = :c AND status_key = :k
                          AND (NOT settled_delivered OR :s IN ('CANCELLED', 'RETURNING', 'RETURNED'))""")
                .param("s", status.name()).param("c", carrier.name()).param("k", key).update();
        customers.recalculate(phones);
    }
}
