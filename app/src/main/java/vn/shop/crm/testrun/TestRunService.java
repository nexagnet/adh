package vn.shop.crm.testrun;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneNormalizer;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.InviteService;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chạy thử trên tài khoản Zalo thật, TRƯỚC KHI chạy chiến dịch thật.
 *
 * <p>Chỉ gồm vài số điện thoại do người dùng tự nhập (số của mình hoặc người quen đã đồng ý nhận tin thử). Dữ liệu
 * nằm ở bảng riêng ({@code test_run}, {@code test_target}), không liên kết với khách thật, nên: không đụng tới trạng thái
 * "đã nhắn" của khách thật, không ghi nhật ký gửi, không ghi danh sách từ chối, không tăng số khách của nhóm Zalo.
 * Hệ thống KHÔNG tự gửi tin và KHÔNG đăng nhập Zalo: người dùng tự nhắn bằng tài khoản phụ rồi bấm các nút đánh dấu.
 */
@Service
public class TestRunService {

    static final int MAX_TARGETS = 10;
    static final int MAX_RUNS = 20;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Một dòng người dùng nhập: số điện thoại, tên (tùy chọn), mã tỉnh (tùy chọn, để thử gợi ý nhóm theo khu vực). */
    public record Entry(String phone, String name, String province) {
    }

    public record Run(long id, String name, String template, String createdBy, LocalDateTime createdAt, long total,
                      long sent, long friended, long joined, long declined) {
    }

    public record Target(long id, String phone, String name, String provinceName, String status, String groupName,
                         String groupLink, boolean groupSuggested, String message, LocalDateTime sentAt) {
        /** Số hiển thị dạng 09... để người dùng tìm trong Zalo. */
        public String displayPhone() {
            return PhoneNormalizer.toDisplay(phone).replaceAll("\\s", "");
        }
    }

    private final JdbcClient jdbc;
    private final InviteService invites;
    private final GroupService groups;

    public TestRunService(JdbcClient jdbc, InviteService invites, GroupService groups) {
        this.jdbc = jdbc;
        this.invites = invites;
        this.groups = groups;
    }

    // ---------- tạo ----------

    @Transactional
    public long create(String name, String template, List<Entry> entries) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new ImportException("Hãy đặt tên cho đợt chạy thử (tối đa 100 ký tự).");
        }
        String tpl = template == null || template.isBlank() ? invites.defaultTemplate() : template.strip();
        if (tpl.length() > 800) {
            throw new ImportException("Nội dung tin quá dài (tối đa 800 ký tự).");
        }
        List<Entry> clean = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry e : entries == null ? List.<Entry>of() : entries) {
            if (e == null || e.phone() == null || e.phone().isBlank()) {
                continue;
            }
            String phone = PhoneNormalizer.toE164(e.phone())
                    .orElseThrow(() -> new ImportException("Số điện thoại \"" + e.phone().strip() + "\" không hợp lệ."));
            if (!seen.add(phone)) {
                throw new ImportException("Số " + e.phone().strip() + " bị nhập hai lần.");
            }
            String province = e.province() == null || e.province().isBlank() ? null : e.province().strip();
            if (province != null && VnGeo.byCode(province).isEmpty()) {
                throw new ImportException("Tỉnh/thành không hợp lệ.");
            }
            String nm = e.name() == null || e.name().isBlank() ? null : e.name().strip();
            if (nm != null && nm.length() > 100) {
                throw new ImportException("Tên quá dài (tối đa 100 ký tự).");
            }
            clean.add(new Entry(phone, nm, province));
        }
        if (clean.isEmpty()) {
            throw new ImportException("Hãy nhập ít nhất một số điện thoại để thử.");
        }
        if (clean.size() > MAX_TARGETS) {
            throw new ImportException("Chạy thử chỉ nên có tối đa " + MAX_TARGETS + " số.");
        }
        if (count("SELECT count(*) FROM test_run") >= MAX_RUNS) {
            throw new ImportException("Đã có " + MAX_RUNS + " đợt chạy thử. Hãy xóa bớt đợt cũ trước.");
        }
        long runId = jdbc.sql("""
                        INSERT INTO test_run (name, message_template, created_by) VALUES (:n, :t, :by) RETURNING id""")
                .param("n", name.strip()).param("t", tpl).param("by", ContactService.actor())
                .query(Long.class).single();
        for (Entry e : clean) {
            jdbc.sql("INSERT INTO test_target (run_id, phone, name, province) VALUES (:r, :p, :n, :pr)")
                    .param("r", runId).param("p", e.phone()).param("n", e.name(), java.sql.Types.VARCHAR)
                    .param("pr", e.province(), java.sql.Types.VARCHAR).update();
        }
        return runId;
    }

    /** Xóa cả đợt thử và các số đã nhập (không còn lưu số điện thoại thử). */
    @Transactional
    public void delete(long runId) {
        jdbc.sql("DELETE FROM test_run WHERE id = :id").param("id", runId).update();
    }

    // ---------- xem ----------

    public List<Run> runs() {
        return jdbc.sql(RUN_SELECT + " GROUP BY r.id ORDER BY r.id DESC").query((rs, i) -> run(rs)).list();
    }

    public Optional<Run> run(long id) {
        return jdbc.sql(RUN_SELECT + " WHERE r.id = :id GROUP BY r.id").param("id", id).query((rs, i) -> run(rs))
                .optional();
    }

    private static final String RUN_SELECT = """
            SELECT r.id, r.name, r.message_template, r.created_by, r.created_at,
                   count(t.id) AS total,
                   count(t.id) FILTER (WHERE t.sent_at IS NOT NULL) AS sent,
                   count(t.id) FILTER (WHERE t.friended_at IS NOT NULL) AS friended,
                   count(t.id) FILTER (WHERE t.joined_at IS NOT NULL) AS joined,
                   count(t.id) FILTER (WHERE t.status = 'DECLINED') AS declined
            FROM test_run r LEFT JOIN test_target t ON t.run_id = r.id""";

    private static Run run(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Run(rs.getLong("id"), rs.getString("name"), rs.getString("message_template"),
                rs.getString("created_by"), local(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getLong("total"), rs.getLong("sent"), rs.getLong("friended"), rs.getLong("joined"),
                rs.getLong("declined"));
    }

    public List<Target> targets(long runId) {
        Run run = run(runId).orElseThrow(() -> new ImportException("Không tìm thấy đợt chạy thử."));
        List<GroupService.Group> all = groups.list();
        return jdbc.sql("""
                        SELECT t.id, t.phone, t.name, t.province, t.status, t.sent_at, g.name AS group_name,
                               g.invite_link AS group_link
                        FROM test_target t LEFT JOIN zalo_group g ON g.id = t.group_id
                        WHERE t.run_id = :r ORDER BY t.id""")
                .param("r", runId)
                .query((rs, n) -> {
                    Province province = VnGeo.byCode(rs.getString("province")).orElse(null);
                    String groupName = rs.getString("group_name");
                    String groupLink = rs.getString("group_link");
                    boolean suggested = false;
                    if (groupName == null && !"DECLINED".equals(rs.getString("status"))) {
                        var g = groups.pickGroup(all, province).orElse(null);
                        if (g != null) {
                            groupName = g.name();
                            groupLink = g.inviteLink();
                            suggested = true;
                        }
                    }
                    return new Target(rs.getLong("id"), rs.getString("phone"), rs.getString("name"),
                            province == null ? "Chưa chọn khu vực" : province.name(), rs.getString("status"),
                            groupName, groupLink, suggested,
                            invites.render(run.template(), rs.getString("name"), "(link riêng, chỉ có ở chiến dịch thật)", province),
                            local(rs.getObject("sent_at", OffsetDateTime.class)));
                }).list();
    }

    // ---------- đánh dấu tay (không đụng tới dữ liệu thật) ----------

    @Transactional
    public void markSent(long targetId) {
        change(targetId, "SENT", "status = 'READY'", "sent_at = now()",
                "Số này đã được đánh dấu nhắn rồi.");
    }

    @Transactional
    public void markFriended(long targetId) {
        change(targetId, "FRIENDED", "status = 'SENT'", "friended_at = now()",
                "Chỉ đánh dấu \"đã kết bạn\" sau khi đã nhắn.");
    }

    /** Đã thêm vào nhóm: ghi lại nhóm đã gợi ý theo khu vực, nhưng KHÔNG tăng số khách thật của nhóm. */
    @Transactional
    public void markJoined(long targetId) {
        var row = jdbc.sql("SELECT province, status FROM test_target WHERE id = :id").param("id", targetId)
                .query((rs, n) -> new String[]{rs.getString("province"), rs.getString("status")}).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy số thử."));
        if (!Set.of("SENT", "FRIENDED").contains(row[1])) {
            throw new ImportException("Chỉ đánh dấu \"đã vào nhóm\" sau khi đã nhắn.");
        }
        var group = groups.pickGroup(VnGeo.byCode(row[0]).orElse(null)).orElseThrow(() -> new ImportException(
                "Chưa có nhóm Zalo nào còn chỗ cho khu vực này. Hãy mở nhóm ở mục Nhóm Zalo."));
        jdbc.sql("""
                        UPDATE test_target SET status = 'JOINED', group_id = :g, joined_at = now(),
                               friended_at = coalesce(friended_at, now())
                        WHERE id = :id AND status IN ('SENT', 'FRIENDED')""")
                .param("g", group.id()).param("id", targetId).update();
    }

    /** Từ chối: chỉ ghi trong đợt thử, KHÔNG vào danh sách từ chối thật. */
    @Transactional
    public void markDeclined(long targetId) {
        int n = jdbc.sql("UPDATE test_target SET status = 'DECLINED' WHERE id = :id AND status IN ('SENT', 'FRIENDED', 'JOINED')")
                .param("id", targetId).update();
        if (n == 0) {
            throw new ImportException("Chỉ ghi \"từ chối\" sau khi đã nhắn.");
        }
    }

    /** Đưa cả đợt về trạng thái chưa nhắn để thử lại từ đầu. */
    @Transactional
    public void reset(long runId) {
        jdbc.sql("""
                        UPDATE test_target SET status = 'READY', group_id = NULL, sent_at = NULL, friended_at = NULL,
                               joined_at = NULL WHERE run_id = :r""").param("r", runId).update();
    }

    private void change(long targetId, String to, String fromCond, String setExtra, String conflictMessage) {
        boolean exists = count("SELECT count(*) FROM test_target WHERE id = " + targetId) > 0;
        if (!exists) {
            throw new ImportException("Không tìm thấy số thử.");
        }
        int n = jdbc.sql("UPDATE test_target SET status = :to, " + setExtra + " WHERE id = :id AND " + fromCond)
                .param("to", to).param("id", targetId).update();
        if (n == 0) {
            throw new ImportException(conflictMessage);
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(VN).toLocalDateTime();
    }
}
