package vn.shop.crm.inbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.zalo.ZaloAccountContext;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Sổ những việc trợ lý AI làm khi đọc tin riêng của khách (D19) và những gì mẹ quyết sau đó. Mọi thao tác đều đi một chiều "an toàn":
 * <ul>
 *   <li>{@link #autoRecord}: AI chắc từ ngưỡng nên ghi sổ từ chối ngay (qua {@link ContactService}), KHÔNG giữ nội dung tin; còn nút {@link #undo}.</li>
 *   <li>{@link #hold}: AI không chắc / lỗi: lời mời chuyển sang CANCELLED (mọi hàng đợi Tin 2 và thêm nhóm đã bỏ qua trạng thái này) để
 *       tạm dừng, ghi trạng thái cũ, chờ mẹ {@link #confirm} (từ chối thật) hoặc {@link #release} (cho tiếp tục).</li>
 * </ul>
 * Trích đoạn đã che (tối đa 300 ký tự) chỉ có ở việc đang tạm dừng, xóa khi mẹ quyết xong hoặc sau 30 ngày ({@link #purgeOld}).
 * Số điện thoại, mã Zalo và nội dung tin không vào log.
 */
@Service
public class RefusalReviewService {

    public static final String HOLD_REASON = "Tạm dừng: chờ mẹ xem tin khách trả lời";
    static final int EXCERPT_MAX = 300;
    static final int KEEP_DAYS = 30;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    public enum Reason {
        /** AI chắc từ ngưỡng. */
        AI_CHAC("AI chắc là khách từ chối"),
        /** AI nghĩ có thể là từ chối nhưng chưa đủ chắc. */
        AI_KHONG_CHAC("AI nghi khách từ chối nhưng chưa chắc"),
        /** AI tắt, hết hạn mức hoặc gọi lỗi: chưa đọc được. */
        AI_LOI("Chưa nhờ được AI đọc tin này"),
        /** AI trả lời sai khuôn. */
        AI_SAI_KHUON("AI trả lời không đúng khuôn");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Lời mời còn đang chạy của người vừa nhắn. */
    public record Target(long invitationId, long customerId, String status) {
    }

    public record Row(long id, long customerId, String customerName, String state, Reason reason, String label, Double confidence,
                      String excerpt, LocalDateTime createdAt, String resolvedBy) {
    }

    private final JdbcClient jdbc;
    private final ContactService contacts;

    public RefusalReviewService(JdbcClient jdbc, ContactService contacts) {
        this.jdbc = jdbc;
        this.contacts = contacts;
    }

    /** Lời mời đang chạy (đã gửi, chưa hủy, chưa vào nhóm) của người có mã Zalo này. Rỗng = không phải khách đang trong luồng mời. */
    public Optional<Target> targetFor(String uid) {
        return jdbc.sql("""
                        SELECT id, customer_id, status FROM invitation
                        WHERE (zalo_uid = :u OR zalo_target_uid = :u) AND status IN ('SENT', 'CLICKED', 'FRIENDED')
                          AND account_id = :acct
                        ORDER BY id DESC LIMIT 1""")
                .param("u", uid).param("acct", ZaloAccountContext.current())
                .query((rs, i) -> new Target(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("status"))).optional();
    }

    /**
     * Ghi nhận khách đã tự nhắn riêng (D21): mọi tin nhắc của lời mời này dừng vĩnh viễn. Chỉ ghi giờ lần đầu, không ghi nội dung.
     * Gọi cho MỌI tin riêng của người đang có lời mời, bất kể AI gắn nhãn gì hay AI có lỗi hay không.
     */
    public void noteInbound(long invitationId) {
        jdbc.sql("UPDATE invitation SET inbound_at = now() WHERE id = :id AND inbound_at IS NULL").param("id", invitationId).update();
    }

    // ---------- trợ lý quyết ----------

    /** AI chắc: ghi sổ từ chối, hủy Tin 2 và việc thêm nhóm (lời mời thành CANCELLED). Không lưu nội dung. */
    @Transactional
    public void autoRecord(Target t, String label, double confidence) {
        ContactService.AiOptOutUndo undo = contacts.suppressByAi(t.customerId(), "Trợ lý AI nhận ra khách từ chối trong tin riêng");
        insert(t, "AUTO_RECORDED", Reason.AI_CHAC, label, confidence, null, toJson(undo));
    }

    /** Không chắc / lỗi: tạm dừng mọi tin cho khách này, chờ mẹ. {@code excerpt} là văn bản đã che, sẽ được cắt còn 300 ký tự. */
    @Transactional
    public void hold(Target t, Reason reason, String label, Double confidence, String excerpt) {
        int n = jdbc.sql("""
                        UPDATE invitation SET status = 'CANCELLED', cancelled_reason = :r
                        WHERE id = :id AND status IN ('SENT', 'CLICKED', 'FRIENDED')""")
                .param("r", HOLD_REASON).param("id", t.invitationId()).update();
        if (n == 0) {
            return;
        }
        String cut = excerpt == null || excerpt.isBlank() ? null
                : (excerpt.length() <= EXCERPT_MAX ? excerpt : excerpt.substring(0, EXCERPT_MAX - 1) + "…");
        insert(t, "HELD", reason, label, confidence, cut, null);
        contacts.event(t.customerId(), contacts.phoneOf(t.customerId()), "AI_HOLD", "Tạm dừng tin: " + reason.name());
    }

    private void insert(Target t, String state, Reason reason, String label, Double confidence, String excerpt, String undoJson) {
        jdbc.sql("""
                        INSERT INTO refusal_review (customer_id, invitation_id, state, reason, label, confidence, excerpt, prior_status, undo_state)
                        VALUES (:c, :i, :s, :r, :l, :cf, :e, :p, :u)""")
                .param("c", t.customerId()).param("i", t.invitationId()).param("s", state).param("r", reason.name())
                .param("l", label, java.sql.Types.VARCHAR)
                .param("cf", confidence == null ? null : BigDecimal.valueOf(confidence).setScale(3, java.math.RoundingMode.HALF_UP),
                        java.sql.Types.NUMERIC)
                .param("e", excerpt, java.sql.Types.VARCHAR).param("p", t.status()).param("u", undoJson, java.sql.Types.VARCHAR).update();
    }

    // ---------- mẹ quyết ----------

    /** Hoàn tác việc AI tự ghi từ chối: đưa sổ từ chối, cơ sở liên hệ và lời mời về như trước. Trả false nếu lời mời đã bị đổi ở chỗ khác. */
    @Transactional
    public boolean undo(long id) {
        Row r = open(id, "AUTO_RECORDED", "Việc này đã được hoàn tác hoặc không phải việc AI tự ghi.");
        String json = jdbc.sql("SELECT undo_state FROM refusal_review WHERE id = :id").param("id", id).query(String.class).single();
        ContactService.AiOptOutUndo undo = fromJson(json);
        contacts.undoAiOptOut(r.customerId(), undo);
        boolean restored = restoreInvitation(id, "Khách yêu cầu ngừng liên hệ");
        close(id, "UNDONE");
        return restored;
    }

    /** Mẹ xác nhận khách từ chối thật: ghi sổ từ chối như mẹ tự bấm, bỏ trích đoạn. */
    @Transactional
    public void confirm(long id) {
        Row r = open(id, "HELD", "Việc này đã được xử lý.");
        contacts.suppress(r.customerId(), "Khách từ chối trong tin riêng (mẹ xác nhận)");
        jdbc.sql("""
                        UPDATE invitation SET cancelled_reason = 'Khách yêu cầu ngừng liên hệ'
                        WHERE id = (SELECT invitation_id FROM refusal_review WHERE id = :id) AND status = 'CANCELLED' AND cancelled_reason = :h""")
                .param("id", id).param("h", HOLD_REASON).update();
        close(id, "CONFIRMED");
    }

    /** Mẹ xem rồi: khách không từ chối, cho tiếp tục. Trả false nếu lời mời đã bị đổi ở chỗ khác (không khôi phục). */
    @Transactional
    public boolean release(long id) {
        open(id, "HELD", "Việc này đã được xử lý.");
        boolean restored = restoreInvitation(id, HOLD_REASON);
        close(id, "RELEASED");
        return restored;
    }

    private boolean restoreInvitation(long reviewId, String expectedReason) {
        int n = jdbc.sql("""
                        UPDATE invitation SET status = (SELECT prior_status FROM refusal_review WHERE id = :id), cancelled_reason = NULL
                        WHERE id = (SELECT invitation_id FROM refusal_review WHERE id = :id)
                          AND status = 'CANCELLED' AND cancelled_reason = :why""")
                .param("id", reviewId).param("why", expectedReason).update();
        return n == 1;
    }

    private Row open(long id, String state, String message) {
        return find(id).filter(r -> state.equals(r.state())).orElseThrow(() -> new ImportException(message));
    }

    private void close(long id, String state) {
        jdbc.sql("""
                        UPDATE refusal_review SET state = :s, excerpt = NULL, undo_state = NULL, resolved_at = now(), resolved_by = :by
                        WHERE id = :id""")
                .param("s", state).param("by", ContactService.actor()).param("id", id).update();
    }

    // ---------- xem ----------

    private static final String SELECT = """
            SELECT r.id, r.customer_id, c.name, r.state, r.reason, r.label, r.confidence, r.excerpt, r.created_at, r.resolved_by
            FROM refusal_review r JOIN customer c ON c.id = r.customer_id
            """;

    private Optional<Row> find(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query((rs, i) -> row(rs)).optional();
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal cf = rs.getBigDecimal("confidence");
        return new Row(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("name"), rs.getString("state"),
                Reason.valueOf(rs.getString("reason")), rs.getString("label"), cf == null ? null : cf.doubleValue(), rs.getString("excerpt"),
                rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(VN).toLocalDateTime(), rs.getString("resolved_by"));
    }

    /** Việc đang tạm dừng, chờ mẹ. */
    public List<Row> held() {
        return jdbc.sql(SELECT + "WHERE r.state = 'HELD' ORDER BY r.created_at DESC LIMIT 100").query((rs, i) -> row(rs)).list();
    }

    /** Những lần AI tự ghi từ chối trong {@code days} ngày gần đây, còn nút Hoàn tác. */
    public List<Row> recentAuto(int days) {
        return jdbc.sql(SELECT + "WHERE r.state = 'AUTO_RECORDED' AND r.created_at > now() - make_interval(days => :d) "
                        + "ORDER BY r.created_at DESC LIMIT 100")
                .param("d", days).query((rs, i) -> row(rs)).list();
    }

    /** Số việc chờ mẹ xem ở tin riêng: khách nghi từ chối (đã tạm dừng tin) cộng khách từng từ chối nhắn lại (xem {@link ComebackService}). */
    public long heldCount() {
        return jdbc.sql("SELECT (SELECT count(*) FROM refusal_review WHERE state = 'HELD') "
                + "+ (SELECT count(*) FROM comeback WHERE state = 'TASK')").query(Long.class).single();
    }

    /** Xóa trích đoạn quá {@value #KEEP_DAYS} ngày (chỉ còn nhãn và giờ). */
    public int purgeOld() {
        return jdbc.sql("UPDATE refusal_review SET excerpt = NULL WHERE excerpt IS NOT NULL AND created_at < now() - make_interval(days => :d)")
                .param("d", KEEP_DAYS).update();
    }

    private static String toJson(ContactService.AiOptOutUndo u) {
        return JSON.writeValueAsString(u);
    }

    private static ContactService.AiOptOutUndo fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new ImportException("Không còn thông tin để hoàn tác việc này.");
        }
        return JSON.readValue(json, ContactService.AiOptOutUndo.class);
    }
}
