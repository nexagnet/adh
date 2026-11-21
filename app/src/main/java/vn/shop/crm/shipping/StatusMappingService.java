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

    private final JdbcClient jdbc;
    private final CustomerService customers;

    public StatusMappingService(JdbcClient jdbc, CustomerService customers) {
        this.jdbc = jdbc;
        this.customers = customers;
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
        jdbc.sql("UPDATE shipment SET canonical_status = :s, updated_at = now() WHERE carrier = :c AND status_key = :k")
                .param("s", status.name()).param("c", carrier.name()).param("k", key).update();
        customers.recalculate(phones);
    }
}
