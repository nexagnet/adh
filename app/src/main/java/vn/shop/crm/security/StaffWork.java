package vn.shop.crm.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
class StaffWork {
    record Job(long id, String groupName, String reason, String excerpt, String status, String assignedTo) {
    }

    private final JdbcClient jdbc;

    StaffWork(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Job> current(String username) {
        return jdbc.sql("""
                        SELECT h.id, g.name AS group_name, h.reason, h.excerpt, h.status, h.assigned_to
                        FROM handoff h JOIN zalo_group g ON g.id = h.group_id
                        JOIN staff_group sg ON sg.group_id = h.group_id
                        JOIN staff_member s ON s.username = sg.username
                        WHERE s.username = :u AND s.active AND
                              (h.status = 'OPEN' OR (h.status = 'ACKNOWLEDGED' AND h.assigned_to = :u))
                        ORDER BY h.created_at, h.id LIMIT 100""")
                .param("u", username).query(Job.class).list();
    }

    void claim(long id, String username) {
        change(id, username, username, "CLAIM", """
                status = 'ACKNOWLEDGED', assigned_to = :assignee, acked_at = now(), acked_by = :actor
                """, "h.status = 'OPEN'");
    }

    void assign(long id, String owner, String username) {
        change(id, owner, username, "ASSIGN", """
                status = 'ACKNOWLEDGED', assigned_to = :assignee,
                acked_at = coalesce(h.acked_at, now()), acked_by = coalesce(h.acked_by, :actor)
                """, "h.status <> 'RESOLVED'");
    }

    void resolve(long id, String username) {
        change(id, username, username, "RESOLVE", "status = 'RESOLVED', resolved_at = now(), resolved_by = :actor",
                "h.status = 'ACKNOWLEDGED' AND h.assigned_to = :assignee");
    }

    private void change(long id, String actor, String assignee, String action, String fields, String allowed) {
        int changed = jdbc.sql("""
                        WITH changed AS (
                            UPDATE handoff h SET %s WHERE h.id = :id AND %s
                            AND EXISTS (
                                SELECT 1 FROM staff_group sg JOIN staff_member s ON s.username = sg.username
                                WHERE sg.group_id = h.group_id AND sg.username = :assignee AND s.active
                            ) RETURNING id
                        )
                        INSERT INTO handoff_assignment_event (handoff_id, actor, assignee, action)
                        SELECT id, :actor, :assignee, :action FROM changed
                        """.formatted(fields, allowed))
                .param("id", id).param("actor", actor).param("assignee", assignee).param("action", action).update();
        if (changed == 0) {
            throw new AccessDeniedException("Việc không được giao cho tài khoản này hoặc đã đổi trạng thái.");
        }
    }
}
