package vn.shop.crm.bot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** "Việc cần mẹ xử lý": bot báo, mẹ nhận (ACKNOWLEDGED) rồi xử lý xong (RESOLVED) trên Zalo thật. */
@Service
public class HandoffService {

    public enum Reason {
        ORDER("Khách muốn đặt hàng / hỏi giá"),
        COMPLAINT("Khiếu nại, hàng lỗi, đổi trả"),
        HUMAN("Khách muốn gặp người thật"),
        UNSURE("Trợ lý AI chưa chắc câu trả lời");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Row(long id, long groupId, String groupName, Reason reason, String excerpt, String status,
                      LocalDateTime createdAt, String ackedBy, long accountId, String assignedTo) {
    }

    private final JdbcClient jdbc;
    private final boolean stage2;

    public HandoffService(JdbcClient jdbc, @org.springframework.beans.factory.annotation.Value("${APP_ADH_STAGE2_ENABLED:false}") boolean stage2) {
        this.jdbc = jdbc;
        this.stage2 = stage2;
    }

    public boolean teamEnabled() {
        return stage2;
    }

    /** Tạo việc mới nếu nhóm chưa có việc đang mở. Trả true nếu có tạo mới. */
    @Transactional
    public boolean open(long groupId, String reason, String excerpt) {
        jdbc.sql("SELECT id FROM zalo_group WHERE id = :g FOR UPDATE")
                .param("g", groupId).query(Long.class).single();
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM handoff WHERE group_id = :g AND status <> 'RESOLVED')")
                .param("g", groupId).query(Boolean.class).single();
        if (exists) {
            return false;
        }
        long id = jdbc.sql("INSERT INTO handoff (group_id, reason, excerpt) VALUES (:g, :r, :e) RETURNING id")
                .param("g", groupId).param("r", reason).param("e", excerpt).query(Long.class).single();
        if (stage2) {
            jdbc.sql("INSERT INTO handoff_notification (handoff_id) VALUES (:id)").param("id", id).update();
        }
        return true;
    }

    public List<Row> list(boolean onlyActive) {
        return list(onlyActive ? "WHERE h.status <> 'RESOLVED' " : "");
    }

    public List<Row> resolved() {
        return list("WHERE h.status = 'RESOLVED' ");
    }

    private List<Row> list(String filter) {
        return jdbc.sql("""
                        SELECT h.id, h.group_id, g.name, h.reason, h.excerpt, h.status, h.created_at, h.acked_by,
                               g.account_id, h.assigned_to
                        FROM handoff h JOIN zalo_group g ON g.id = h.group_id
                        """ + filter
                        + "ORDER BY (h.status = 'RESOLVED'), h.created_at DESC LIMIT 100")
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getLong("group_id"), rs.getString("name"),
                        Reason.valueOf(rs.getString("reason")), rs.getString("excerpt"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh"))
                                .toLocalDateTime(), rs.getString("acked_by"), rs.getLong("account_id"), rs.getString("assigned_to")))
                .list();
    }

    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM handoff WHERE status = 'OPEN'").query(Long.class).single();
    }

    public long unresolvedCount() {
        return jdbc.sql("SELECT count(*) FROM handoff WHERE status <> 'RESOLVED'").query(Long.class).single();
    }

    public long resolvedCount() {
        return jdbc.sql("SELECT count(*) FROM handoff WHERE status = 'RESOLVED'").query(Long.class).single();
    }

    @Transactional
    public void acknowledge(long id) {
        int n = jdbc.sql("""
                        UPDATE handoff SET status = 'ACKNOWLEDGED', acked_at = now(), acked_by = :by
                        WHERE id = :id AND status = 'OPEN'""")
                .param("by", ContactService.actor()).param("id", id).update();
        if (n == 0) {
            throw new ImportException("Việc này đã được nhận hoặc đã xử lý xong.");
        }
    }

    @Transactional
    public void resolve(long id) {
        int n = jdbc.sql("""
                        UPDATE handoff SET status = 'RESOLVED', resolved_at = now(), resolved_by = :by,
                               acked_at = coalesce(acked_at, now()), acked_by = coalesce(acked_by, :by)
                        WHERE id = :id AND status <> 'RESOLVED'""")
                .param("by", ContactService.actor()).param("id", id).update();
        if (n == 0) {
            throw new ImportException("Việc này đã xử lý xong hoặc không tồn tại.");
        }
    }
}
