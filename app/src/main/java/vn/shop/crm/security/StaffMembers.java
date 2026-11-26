package vn.shop.crm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class StaffMembers {
    public record Member(String username, String displayName, boolean active) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String owner;

    public StaffMembers(JdbcClient jdbc, PasswordEncoder encoder, @Value("${app.admin.username}") String owner) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.owner = owner;
    }

    UserDetails login(String username) {
        return jdbc.sql("SELECT password_hash, active FROM staff_member WHERE username = :u")
                .param("u", username)
                .query((rs, row) -> User.withUsername(username).password(rs.getString("password_hash"))
                        .roles("TEAM_MEMBER").disabled(!rs.getBoolean("active")).build())
                .optional().orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản."));
    }

    boolean active(String username) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM staff_member WHERE username = :u AND active)")
                .param("u", username).query(Boolean.class).single();
    }

    List<Member> list() {
        return jdbc.sql("SELECT username, display_name, active FROM staff_member ORDER BY username")
                .query(Member.class).list();
    }

    void add(String username, String name, String password) {
        if (!username.matches("[a-z][a-z0-9._-]{2,39}") || owner.equalsIgnoreCase(username)
                || name.isBlank() || name.length() > 100 || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ImportException("Kiểm tra lại tên đăng nhập, tên hiển thị và mật khẩu (ít nhất 12 ký tự, tối đa 72 byte).");
        }
        int changed = jdbc.sql("""
                        INSERT INTO staff_member (username, display_name, password_hash) VALUES (:u, :name, :hash)
                        ON CONFLICT DO NOTHING""")
                .param("u", username).param("name", name.strip()).param("hash", encoder.encode(password)).update();
        if (changed == 0) {
            throw new ImportException("Tên đăng nhập đã được dùng.");
        }
    }

    void setActive(String username, boolean active) {
        if (jdbc.sql("UPDATE staff_member SET active = :active WHERE username = :u")
                .param("u", username).param("active", active).update() == 0) {
            throw new ImportException("Không tìm thấy nhân viên.");
        }
    }

    void grant(String username, long groupId, boolean allowed) {
        if (allowed) {
            jdbc.sql("""
                            INSERT INTO staff_group (username, group_id)
                            SELECT s.username, g.id FROM staff_member s CROSS JOIN zalo_group g
                            WHERE s.username = :u AND s.active AND g.id = :g ON CONFLICT DO NOTHING""")
                    .param("u", username).param("g", groupId).update();
        } else {
            jdbc.sql("DELETE FROM staff_group WHERE username = :u AND group_id = :g")
                    .param("u", username).param("g", groupId).update();
        }
    }
}
