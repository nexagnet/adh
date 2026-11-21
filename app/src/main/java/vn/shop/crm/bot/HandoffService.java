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
                      LocalDateTime createdAt, String ackedBy) {
    }

    private final JdbcClient jdbc;

    public HandoffService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tạo việc mới nếu nhóm chưa có việc đang mở. Trả true nếu có tạo mới. */
    @Transactional
    public boolean open(long groupId, String reason, String excerpt) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM handoff WHERE group_id = :g AND status <> 'RESOLVED')")
                .param("g", groupId).query(Boolean.class).single();
        if (exists) {
            return false;
        }
        jdbc.sql("INSERT INTO handoff (group_id, reason, excerpt) VALUES (:g, :r, :e)")
                .param("g", groupId).param("r", reason).param("e", excerpt).update();
        return true;
    }

    public List<Row> list(boolean onlyActive) {
        return jdbc.sql("""
                        SELECT h.id, h.group_id, g.name, h.reason, h.excerpt, h.status, h.created_at, h.acked_by
                        FROM handoff h JOIN zalo_group g ON g.id = h.group_id
                        """ + (onlyActive ? "WHERE h.status <> 'RESOLVED' " : "")
                        + "ORDER BY (h.status = 'RESOLVED'), h.created_at DESC LIMIT 100")
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getLong("group_id"), rs.getString("name"),
                        Reason.valueOf(rs.getString("reason")), rs.getString("excerpt"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh"))
                                .toLocalDateTime(), rs.getString("acked_by")))
                .list();
    }

    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM handoff WHERE status = 'OPEN'").query(Long.class).single();
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
