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
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.zalo.ZaloGroupCreationService;
import vn.shop.crm.zalo.ZaloMessageService;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chiến dịch THỬ trên tài khoản Zalo thật, TRƯỚC KHI chạy chiến dịch thật (màn hình tạm thời, xong việc thì xóa).
 *
 * <p>Chỉ gồm vài số điện thoại do người dùng tự nhập (số của mình hoặc người quen đã đồng ý nhận tin thử). Dữ liệu
 * nằm ở bảng riêng ({@code test_run}, {@code test_target}), không liên kết với khách thật, nên: không đụng tới trạng thái
 * "đã nhắn" của khách thật, không ghi nhật ký gửi, không ghi danh sách từ chối, không tăng số khách của nhóm Zalo.
 *
 * <p>Từ D18 đợt thử chạy qua đúng bộ nối thật (xem {@link TestRunAutomation}): Tin 1, kết bạn, tự thêm vào nhóm đích, Tin 2, với
 * cùng công tắc, hạn mức, khoảng nghỉ cố định, khung giờ và cầu dao như chiến dịch thật; cấu hình (nội dung tin, nhóm đích,
 * hạn mức riêng chỉ có thể thấp hơn hạn mức chung) đặt ngay trên giao diện. Các nút đánh dấu tay vẫn dùng được khi bộ nối tắt.
 */
@Service
public class TestRunService {

    static final int MAX_TARGETS = 10;
    static final int MAX_RUNS = 20;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Một dòng người dùng nhập: số điện thoại, tên (tùy chọn), mã tỉnh (tùy chọn, để thử gợi ý nhóm theo khu vực). */
    public record Entry(String phone, String name, String province) {
    }

    /** Cấu hình đợt thử đặt trên giao diện. {@code null} ở các số = dùng mặc định của hạn mức chung. */
    public record Config(String name, String template, String linkTemplate, Integer dailyLimit, Integer gapSeconds,
                         Long zaloGroupId, boolean sendLink) {
    }

    public record Run(long id, String name, String template, String createdBy, LocalDateTime createdAt, long total,
                      long sent, long friended, long joined, long declined, String linkTemplate, int dailyLimit,
                      int gapSeconds, Long zaloGroupId, String zaloGroupName, boolean sendLink, boolean running) {
    }

    public record Target(long id, String phone, String name, String provinceName, String status, String groupName,
                         String groupLink, boolean groupSuggested, String message, LocalDateTime sentAt, String addState,
                         String linkState, String lastCode) {
        /** Số hiển thị dạng 09... để người dùng tìm trong Zalo. */
        public String displayPhone() {
            return PhoneNormalizer.toDisplay(phone).replaceAll("\\s", "");
        }
    }

    private final JdbcClient jdbc;
    private final InviteService invites;
    private final GroupService groups;
    private final ZaloMessageService messages;
    private final TestMode testMode;
    private final ZaloGroupCreationService creation;

    public TestRunService(JdbcClient jdbc, InviteService invites, GroupService groups, ZaloMessageService messages, TestMode testMode,
                          ZaloGroupCreationService creation) {
        this.testMode = testMode;
        this.creation = creation;
        this.jdbc = jdbc;
        this.invites = invites;
        this.groups = groups;
        this.messages = messages;
    }

    /** Cấu hình mặc định điền sẵn trên giao diện: nội dung tin mặc định, hạn mức chung. */
    public Config defaults() {
        ZaloMessageService.Limits l = messages.limits();
        return new Config("", invites.defaultTemplate(), invites.groupLinkTemplate(), Math.min(l.daily(), 10), l.gapSeconds(), null, true);
    }

    // ---------- tạo ----------

    @Transactional
    public long create(String name, String template, List<Entry> entries) {
        Config d = defaults();
        return create(new Config(name, template, d.linkTemplate(), d.dailyLimit(), d.gapSeconds(), null, true), entries);
    }

    /** Kiểm và chuẩn hóa cấu hình; ném {@link ImportException} nếu sai. Hạn mức riêng chỉ có thể THẤP hơn hạn mức chung. */
    private Config validate(Config c) {
        if (c.name() == null || c.name().isBlank() || c.name().strip().length() > 100) {
            throw new ImportException("Hãy đặt tên cho đợt chạy thử (tối đa 100 ký tự).");
        }
        String tpl = c.template() == null || c.template().isBlank() ? invites.defaultTemplate() : c.template().strip();
        String link = c.linkTemplate() == null || c.linkTemplate().isBlank() ? invites.groupLinkTemplate() : c.linkTemplate().strip();
        if (tpl.length() > 800 || link.length() > 800) {
            throw new ImportException("Nội dung tin quá dài (tối đa 800 ký tự).");
        }
        ZaloMessageService.Limits l = messages.limits();
        int daily = c.dailyLimit() == null ? Math.min(l.daily(), 10) : c.dailyLimit();
        if (daily < 1 || daily > l.daily()) {
            throw new ImportException("Số tin mỗi ngày của đợt thử phải từ 1 đến " + l.daily()
                    + " (không được vượt hạn mức chung ở trang Zalo tài khoản phụ).");
        }
        int gap = c.gapSeconds() == null ? l.gapSeconds() : c.gapSeconds();
        if (gap < l.gapSeconds() || gap > 3600) {
            throw new ImportException("Khoảng nghỉ giữa hai tin của đợt thử phải từ " + l.gapSeconds()
                    + " (khoảng nghỉ chung) đến 3600 giây.");
        }
        Long group = c.zaloGroupId();
        if (group != null) {
            var g = groups.find(group).orElseThrow(() -> new ImportException("Không tìm thấy nhóm đích."));
            if (!g.hasZaloId()) {
                throw new ImportException("Nhóm đích chưa có mã nhóm Zalo (gán ở trang Zalo tài khoản phụ, mục Nhóm Zalo).");
            }
        }
        return new Config(c.name().strip(), tpl, link, daily, gap, group, c.sendLink());
    }

    @Transactional
    public long create(Config config, List<Entry> entries) {
        Config cfg = validate(config);
        String name = cfg.name();
        String tpl = cfg.template();
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
            // Chạy thử không bao giờ dùng số của khách thật: số trùng khách, trùng danh sách từ chối, hoặc (chế độ thử) chưa khai báo thì từ chối
            testMode.requireUsableForTestRun(phone);
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
                        INSERT INTO test_run (name, message_template, created_by, link_template, daily_limit, gap_seconds,
                                              zalo_group_id, send_link)
                        VALUES (:n, :t, :by, :lt, :d, :g, :zg, :sl) RETURNING id""")
                .param("n", name).param("t", tpl).param("by", ContactService.actor()).param("lt", cfg.linkTemplate())
                .param("d", cfg.dailyLimit()).param("g", cfg.gapSeconds()).param("zg", cfg.zaloGroupId(), java.sql.Types.BIGINT)
                .param("sl", cfg.sendLink()).query(Long.class).single();
        for (Entry e : clean) {
            jdbc.sql("INSERT INTO test_target (run_id, phone, name, province) VALUES (:r, :p, :n, :pr)")
                    .param("r", runId).param("p", e.phone()).param("n", e.name(), java.sql.Types.VARCHAR)
                    .param("pr", e.province(), java.sql.Types.VARCHAR).update();
        }
        return runId;
    }

    /** Sửa cấu hình ngay trên giao diện. Đang chạy thì phải dừng trước (không đổi luật giữa chừng). */
    @Transactional
    public void updateConfig(long runId, Config config) {
        Run run = run(runId).orElseThrow(() -> new ImportException("Không tìm thấy đợt chạy thử."));
        if (run.running()) {
            throw new ImportException("Hãy bấm Dừng trước khi sửa cấu hình.");
        }
        Config cfg = validate(config);
        jdbc.sql("""
                        UPDATE test_run SET name = :n, message_template = :t, link_template = :lt, daily_limit = :d,
                                            gap_seconds = :g, zalo_group_id = :zg, send_link = :sl WHERE id = :id""")
                .param("n", cfg.name()).param("t", cfg.template()).param("lt", cfg.linkTemplate()).param("d", cfg.dailyLimit())
                .param("g", cfg.gapSeconds()).param("zg", cfg.zaloGroupId(), java.sql.Types.BIGINT).param("sl", cfg.sendLink())
                .param("id", runId).update();
    }

    /**
     * Bắt đầu tự chạy qua bộ nối thật. Chưa chọn nhóm đích thì hệ thống TỰ TẠO nhóm thử khi có người kết bạn (cần việc "Tự tạo nhóm Zalo"
     * đang bật); không bắt người dùng tạo nhóm tay. Các chốt chặn chung vẫn áp dụng.
     */
    @Transactional
    public void start(long runId) {
        Run run = run(runId).orElseThrow(() -> new ImportException("Không tìm thấy đợt chạy thử."));
        if (run.zaloGroupId() == null && !creation.enabled()) {
            throw new ImportException("Chưa chọn nhóm đích nên hệ thống sẽ tự tạo nhóm thử khi có người kết bạn, nhưng việc "
                    + "\"Tự tạo nhóm Zalo\" đang tắt. Hãy bật nó ở trang Zalo tài khoản phụ (nấc Xác nhận) rồi bấm Bắt đầu lại, "
                    + "hoặc chọn một nhóm có sẵn làm nhóm đích.");
        }
        // Kiểm lại lúc bắt đầu: từ lúc tạo đến giờ số có thể đã trở thành khách (nhập đơn) hoặc bị bỏ khỏi danh sách thử.
        for (String phone : jdbc.sql("SELECT phone FROM test_target WHERE run_id = :r").param("r", runId).query(String.class).list()) {
            testMode.requireUsableForTestRun(phone);
        }
        jdbc.sql("UPDATE test_run SET running = TRUE WHERE id = :id").param("id", runId).update();
    }

    @Transactional
    public void stop(long runId) {
        jdbc.sql("UPDATE test_run SET running = FALSE WHERE id = :id").param("id", runId).update();
    }

    /** Các nhóm chọn làm nhóm đích: nhóm đã có mã nhóm Zalo. */
    public List<GroupService.Group> targetGroups() {
        return groups.list().stream().filter(GroupService.Group::hasZaloId).toList();
    }

    /** Xóa cả đợt thử và các số đã nhập (không còn lưu số điện thoại thử). */
    @Transactional
    public void delete(long runId) {
        jdbc.sql("DELETE FROM test_run WHERE id = :id").param("id", runId).update();
    }

    // ---------- xem ----------

    public List<Run> runs() {
        return jdbc.sql(RUN_SELECT + " GROUP BY r.id, g.name ORDER BY r.id DESC").query((rs, i) -> run(rs)).list();
    }

    public Optional<Run> run(long id) {
        return jdbc.sql(RUN_SELECT + " WHERE r.id = :id GROUP BY r.id, g.name").param("id", id).query((rs, i) -> run(rs))
                .optional();
    }

    private static final String RUN_SELECT = """
            SELECT r.id, r.name, r.message_template, r.created_by, r.created_at, r.link_template, r.daily_limit, r.gap_seconds,
                   r.zalo_group_id, g.name AS group_name, r.send_link, r.running,
                   count(t.id) AS total,
                   count(t.id) FILTER (WHERE t.sent_at IS NOT NULL) AS sent,
                   count(t.id) FILTER (WHERE t.friended_at IS NOT NULL) AS friended,
                   count(t.id) FILTER (WHERE t.joined_at IS NOT NULL) AS joined,
                   count(t.id) FILTER (WHERE t.status = 'DECLINED') AS declined
            FROM test_run r LEFT JOIN test_target t ON t.run_id = r.id LEFT JOIN zalo_group g ON g.id = r.zalo_group_id""";

    private static Run run(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Run(rs.getLong("id"), rs.getString("name"), rs.getString("message_template"),
                rs.getString("created_by"), local(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getLong("total"), rs.getLong("sent"), rs.getLong("friended"), rs.getLong("joined"),
                rs.getLong("declined"), rs.getString("link_template"), rs.getInt("daily_limit"), rs.getInt("gap_seconds"),
                (Long) rs.getObject("zalo_group_id"), rs.getString("group_name"), rs.getBoolean("send_link"),
                rs.getBoolean("running"));
    }

    public List<Target> targets(long runId) {
        Run run = run(runId).orElseThrow(() -> new ImportException("Không tìm thấy đợt chạy thử."));
        List<GroupService.Group> all = groups.list();
        return jdbc.sql("""
                        SELECT t.id, t.phone, t.name, t.province, t.status, t.sent_at, g.name AS group_name,
                               g.invite_link AS group_link, t.add_state, t.link_state, t.last_code
                        FROM test_target t LEFT JOIN zalo_group g ON g.id = t.group_id
                        WHERE t.run_id = :r ORDER BY t.id""")
                .param("r", runId)
                .query((rs, n) -> {
                    Province province = VnGeo.byCode(rs.getString("province")).orElse(null);
                    String groupName = rs.getString("group_name");
                    String groupLink = rs.getString("group_link");
                    boolean suggested = false;
                    if (groupName == null && !"DECLINED".equals(rs.getString("status"))) {
                        var g = run.zaloGroupId() != null ? groups.find(run.zaloGroupId()).orElse(null)
                                : groups.pickGroup(all, province).orElse(null);
                        if (g != null) {
                            groupName = g.name();
                            groupLink = g.inviteLink();
                            suggested = true;
                        }
                    }
                    return new Target(rs.getLong("id"), rs.getString("phone"), rs.getString("name"),
                            province == null ? "Chưa chọn khu vực" : province.name(), rs.getString("status"),
                            groupName, groupLink, suggested,
                            TestRunAutomation.renderInvite(invites, run.template(), rs.getString("name"), province),
                            local(rs.getObject("sent_at", OffsetDateTime.class)), rs.getString("add_state"),
                            rs.getString("link_state"), rs.getString("last_code"));
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
                               joined_at = NULL, zalo_uid = NULL, add_state = NULL, link_state = NULL, last_code = NULL
                        WHERE run_id = :r""").param("r", runId).update();
        jdbc.sql("UPDATE test_run SET running = FALSE WHERE id = :r").param("r", runId).update();
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
