package vn.shop.crm.inbox;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.contact.ContactService.LiftUndo;
import vn.shop.crm.inbox.RefusalClassifier.Label;
import vn.shop.crm.inbox.RefusalClassifier.Outcome;
import vn.shop.crm.inbox.RefusalClassifier.Verdict;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Khách TỪNG TỪ CHỐI nhưng nay TỰ nhắn tới muốn mua lại (chủ shop chốt 23/11/2026, docs/THIET-KE-KHACH-QUAY-LAI.md).
 *
 * <p>Quy tắc dữ liệu: số điện thoại và dòng sổ từ chối (mã băm) KHÔNG bao giờ bị xóa. Gỡ "không nhắn nữa" chỉ là đánh dấu đã gỡ kèm giờ
 * ({@link ContactService#liftOptOut}); khách từ chối lần nữa thì dòng được kích hoạt lại; dòng ERASED không bao giờ gỡ được.
 *
 * <p>Ba nấc ({@link Mode}), mặc định TẮT. TẮT: không đọc gì thêm, không gọi AI cho người từng từ chối. CHỈ TẠO VIỆC: AI gắn nhãn, việc nổi
 * bật cho mẹ kèm đơn đã mua, không đổi gì trong sổ. TỰ ĐỘNG: AI chắc từ ngưỡng (0,85) là MUON_MUA thì tự gỡ cờ, đưa khách vào hàng chờ vào
 * nhóm (cùng đường ống thêm nhóm, hạn mức và cầu dao như khách thường) và xếp MỘT tin mẫu cố định ({@code ZaloWelcomeSender}). Chỉ khi chính khách
 * nhắn trước; tối đa MỘT lần tự gỡ cho một khách trong 30 ngày; luôn có nút Hoàn tác. TU_CHOI thì giữ nguyên cờ, không trả lời.
 * Trích đoạn nhắn của khách (đã che, tối đa 300 ký tự) chỉ có ở việc đang chờ mẹ, xóa khi mẹ xử lý hoặc sau 30 ngày. Không có số điện thoại,
 * tên hay mã Zalo trong log.
 */
@Service
public class ComebackService {

    public static final String MODE_KEY = "comeback.mode";
    public static final String TASKS_TRIED_KEY = "comeback.tasks_tried_at";
    static final int EXCERPT_MAX = 300;
    static final int KEEP_DAYS = 30;
    /** Tối đa một lần tự gỡ cho một khách trong chừng này ngày (kể cả lần đã hoàn tác). */
    static final int AUTO_LIFT_DAYS = 30;
    /** Cầu nối chỉ cho trả lời người vừa nhắn trong 24 giờ; nhỏ hơn một chút để không chạm biên. */
    public static final int WELCOME_WINDOW_HOURS = 23;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    public enum Mode {
        OFF("Tắt", "Không làm gì với khách từng từ chối."),
        TASKS("Chỉ tạo việc", "AI đọc tin, mẹ thấy việc nổi bật kèm đơn đã mua rồi tự quyết. Hệ thống không đổi gì."),
        AUTO("Tự động", "AI chắc khách muốn mua thì tự gỡ cờ, thêm vào nhóm và gửi một tin mẫu. Có nút Hoàn tác.");

        private final String label;
        private final String hint;

        Mode(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        public String label() {
            return label;
        }

        public String hint() {
            return hint;
        }

        static Mode parse(String v) {
            try {
                return v == null ? OFF : valueOf(v.strip());
            } catch (IllegalArgumentException e) {
                return OFF;
            }
        }

        /** Nấc người dùng chọn cho chiến dịch; rỗng = theo hệ thống (null). Giá trị lạ là lỗi. */
        public static Mode ofCampaign(String v) {
            if (v == null || v.isBlank()) {
                return null;
            }
            try {
                return valueOf(v.strip());
            } catch (IllegalArgumentException e) {
                throw new ImportException("Nấc khách quay lại không đúng.");
            }
        }

        /** Nấc THẤP hơn trong hai nấc (OFF &lt; TASKS &lt; AUTO). */
        static Mode lower(Mode a, Mode b) {
            return a.ordinal() <= b.ordinal() ? a : b;
        }
    }

    public enum Reason {
        AI_CHAC("AI chắc khách muốn mua"),
        CHI_TAO_VIEC("Khách muốn mua (đang ở nấc chỉ tạo việc)"),
        CHUA_CHAC("AI nghĩ khách muốn mua nhưng chưa đủ chắc"),
        NHAN_KHAC("Khách nhắn lại (hỏi hoặc phàn nàn)"),
        AI_LOI("Chưa nhờ được AI đọc tin này"),
        AI_SAI_KHUON("AI trả lời không đúng khuôn"),
        KHONG_DU_DIEU_KIEN("Khách chưa đủ điều kiện tự thêm vào nhóm"),
        DA_GO_GAN_DAY("Khách này mới được gỡ cờ trong 30 ngày qua");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Khách từng từ chối (cờ còn hiệu lực) vừa tự nhắn tới. */
    public record Candidate(long invitationId, long customerId, String uid) {
    }

    public enum Result {
        /** Đã tự gỡ cờ và xếp vào hàng chờ vào nhóm. */
        LIFTED,
        /** Đã tạo việc nổi bật cho mẹ (hoặc đã có việc đang chờ). */
        TASK,
        /** Không làm gì (TU_CHOI, tin không liên quan). */
        IGNORED
    }

    public record Row(long id, long customerId, String customerName, String state, Reason reason, String label, Double confidence,
                      String excerpt, LocalDateTime createdAt, String liftedBy, String welcomeState, List<String> orders) {
    }

    public record Welcome(long id, String uid) {
    }

    /** Để Hoàn tác: cơ sở liên hệ cũ và trạng thái lời mời trước khi bị đổi. */
    record Undo(LiftUndo lift, String status, String cancelledReason, String zaloUid, String addState, String addCode) {
    }

    /** Chặn việc tự gỡ: giữ nguyên mọi thứ, chuyển thành việc cho mẹ. */
    private static final class Blocked extends RuntimeException {
        final Reason reason;

        Blocked(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }

    private final JdbcClient jdbc;
    private final ContactService contacts;
    private final AppSettings settings;
    private final TransactionTemplate tx;

    public ComebackService(JdbcClient jdbc, ContactService contacts, AppSettings settings, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.settings = settings;
        this.tx = tx;
    }

    // ---------- nấc ----------

    public Mode mode() {
        return Mode.parse(settings.get(MODE_KEY).orElse(null));
    }

    /** Nấc riêng của chiến dịch (null = theo hệ thống). */
    public Mode campaignMode(long campaignId) {
        return Mode.ofCampaign(jdbc.sql("SELECT comeback_mode FROM invitation_campaign WHERE id = :id").param("id", campaignId)
                .query(String.class).optional().orElse(null));
    }

    /** Người dùng đặt nấc riêng cho chiến dịch (null = theo hệ thống). Không nới được nấc chung: nấc có hiệu lực luôn là nấc thấp hơn. */
    public void saveCampaignMode(long campaignId, Mode mode, String by) {
        int n = jdbc.sql("UPDATE invitation_campaign SET comeback_mode = :m WHERE id = :id")
                .param("m", mode == null ? null : mode.name(), java.sql.Types.VARCHAR).param("id", campaignId).update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
    }

    /**
     * Nấc có hiệu lực cho khách của một lời mời: THẤP hơn của hệ thống và của chiến dịch; chiến dịch chọn "chỉ gửi link nhóm" (không tự thêm
     * vào nhóm) thì tối đa là CHỈ TẠO VIỆC. Chiến dịch chỉ làm chặt hơn, không bao giờ nới.
     */
    public Mode effective(long invitationId) {
        Mode result = mode();
        var row = jdbc.sql("""
                        SELECT k.comeback_mode, k.group_delivery FROM invitation i JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.id = :id""")
                .param("id", invitationId).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
        if (row.isEmpty()) {
            return result;
        }
        Mode own = Mode.ofCampaign(row.get()[0]);
        if (own != null) {
            result = Mode.lower(result, own);
        }
        if ("LINK_ONLY".equals(row.get()[1])) {
            result = Mode.lower(result, Mode.TASKS);
        }
        return result;
    }

    public boolean tasksTried() {
        return settings.get(TASKS_TRIED_KEY).isPresent();
    }

    /** Đổi nấc. TỰ ĐỘNG chỉ được chọn sau khi đã chạy nấc CHỈ TẠO VIỆC và người chọn tick xác nhận đã xem các việc. */
    public void setMode(Mode mode, boolean confirmed, String by) {
        if (mode == Mode.TASKS) {
            settings.set(TASKS_TRIED_KEY, java.time.Instant.now().toString(), by);
        }
        if (mode == Mode.AUTO) {
            if (!tasksTried()) {
                throw new ImportException("Hãy chạy nấc \"Chỉ tạo việc\" một thời gian và xem các việc AI tạo trước khi bật tự động.");
            }
            if (!confirmed) {
                throw new ImportException("Hãy tick ô xác nhận đã xem các việc ở nấc \"Chỉ tạo việc\" trước khi bật tự động.");
            }
        }
        settings.set(MODE_KEY, mode.name(), by);
    }

    // ---------- nhận diện ----------

    /**
     * Người có mã Zalo này có phải khách từng từ chối (cờ CÒN hiệu lực) trên một lời mời đã hủy không. Không tìm khách đã yêu cầu xóa dữ liệu
     * (ERASED), không tìm khách tạm dừng chờ mẹ (chưa có cờ).
     */
    public Optional<Candidate> candidateFor(String uid) {
        record Hit(long invitationId, long customerId, String phone, String zaloUid) {
        }
        return jdbc.sql("""
                        SELECT i.id, i.customer_id, c.phone, i.zalo_uid
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE (i.zalo_uid = :u OR i.zalo_target_uid = :u) AND i.status = 'CANCELLED'
                        ORDER BY (i.zalo_uid = :u) DESC, i.id DESC LIMIT 1""")
                .param("u", uid)
                .query((rs, n) -> new Hit(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("phone"), rs.getString("zalo_uid")))
                .optional()
                .filter(h -> contacts.hasActiveOptOut(h.phone()))
                .map(h -> new Candidate(h.invitationId(), h.customerId(), uid));
    }

    /**
     * Quyết định theo kết quả AI. {@code canAdd}: bộ nối đang ở nấc cho phép thêm nhóm (nếu không thì không tự gỡ, chỉ tạo việc).
     * Không bao giờ ném lỗi vì "bị chặn": thành việc cho mẹ.
     */
    public Result handle(Candidate c, Verdict v, String text, boolean canAdd, double threshold) {
        Mode mode = effective(c.invitationId());
        if (mode == Mode.OFF) {
            return Result.IGNORED;
        }
        if (v.outcome() == Outcome.UNAVAILABLE) {
            return task(c, Reason.AI_LOI, null, null, text);
        }
        if (v.outcome() == Outcome.INVALID) {
            return task(c, Reason.AI_SAI_KHUON, null, null, text);
        }
        Label label = v.label();
        if (label == Label.TU_CHOI || label == Label.KHAC) {
            return Result.IGNORED; // vẫn từ chối (hoặc không liên quan): giữ cờ, không làm gì, không trả lời
        }
        if (label != Label.MUON_MUA) {
            return task(c, Reason.NHAN_KHAC, label.name(), v.confidence(), text);
        }
        if (mode == Mode.TASKS) {
            return task(c, Reason.CHI_TAO_VIEC, label.name(), v.confidence(), text);
        }
        if (v.confidence() < threshold) {
            return task(c, Reason.CHUA_CHAC, label.name(), v.confidence(), text);
        }
        if (!canAdd) {
            return task(c, Reason.CHI_TAO_VIEC, label.name(), v.confidence(), text);
        }
        if (liftedRecently(c.customerId())) {
            return task(c, Reason.DA_GO_GAN_DAY, label.name(), v.confidence(), text);
        }
        try {
            tx.executeWithoutResult(s -> lift(c, ContactService.AI_ACTOR, null, Reason.AI_CHAC, label.name(), v.confidence(), true));
            return Result.LIFTED;
        } catch (Blocked b) {
            return task(c, b.reason, label.name(), v.confidence(), text);
        } catch (DataIntegrityViolationException e) {
            return task(c, Reason.KHONG_DU_DIEU_KIEN, label.name(), v.confidence(), text); // mã Zalo đã gắn lời mời khác
        }
    }

    private boolean liftedRecently(long customerId) {
        return jdbc.sql("""
                        SELECT count(*) FROM comeback WHERE customer_id = :c AND lifted_by = :ai
                          AND lifted_at > now() - make_interval(days => :d)""")
                .param("c", customerId).param("ai", ContactService.AI_ACTOR).param("d", AUTO_LIFT_DAYS).query(Long.class).single() > 0;
    }

    private Result task(Candidate c, Reason reason, String label, Double confidence, String text) {
        String cut = text == null || text.isBlank() ? null : (text.length() <= EXCERPT_MAX ? text : text.substring(0, EXCERPT_MAX - 1) + "…");
        jdbc.sql("""
                        INSERT INTO comeback (customer_id, invitation_id, state, reason, label, confidence, excerpt)
                        VALUES (:c, :i, 'TASK', :r, :l, :cf, :e)
                        ON CONFLICT (customer_id) WHERE state = 'TASK' DO NOTHING""")
                .param("c", c.customerId()).param("i", c.invitationId()).param("r", reason.name())
                .param("l", label, java.sql.Types.VARCHAR)
                .param("cf", confidence == null ? null : java.math.BigDecimal.valueOf(confidence).setScale(3, java.math.RoundingMode.HALF_UP),
                        java.sql.Types.NUMERIC)
                .param("e", cut, java.sql.Types.VARCHAR).update();
        return Result.TASK;
    }

    // ---------- gỡ cờ ----------

    /**
     * Trong MỘT giao dịch: gỡ cờ (đánh dấu, không xóa dòng), đưa lời mời về hàng chờ vào nhóm, kiểm lại điều kiện. Việc nào không được thì ném
     * {@link Blocked} và mọi thứ quay lại như cũ. {@code taskId} khác null = mẹ gỡ tay từ một việc có sẵn.
     */
    private void lift(Candidate c, String by, Long taskId, Reason reason, String label, Double confidence, boolean welcome) {
        String[] prior = jdbc.sql("""
                        SELECT status, cancelled_reason, zalo_uid, zalo_add_state, zalo_add_code FROM invitation WHERE id = :id FOR UPDATE""")
                .param("id", c.invitationId())
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)})
                .optional().orElseThrow(() -> new Blocked(Reason.KHONG_DU_DIEU_KIEN));
        if (!"CANCELLED".equals(prior[0])) {
            throw new Blocked(Reason.KHONG_DU_DIEU_KIEN); // lời mời đã được xử lý ở chỗ khác
        }
        LiftUndo lift;
        try {
            lift = contacts.liftOptOut(c.customerId(), by);
        } catch (ImportException e) {
            throw new Blocked(Reason.KHONG_DU_DIEU_KIEN); // không còn cờ để gỡ (đã gỡ, hoặc đã yêu cầu xóa dữ liệu)
        }
        int n = jdbc.sql("""
                        UPDATE invitation SET status = 'FRIENDED', cancelled_reason = NULL, zalo_uid = coalesce(zalo_uid, :u),
                               zalo_add_state = 'QUEUED', zalo_add_code = NULL, friended_at = coalesce(friended_at, now())
                        WHERE id = :id AND status = 'CANCELLED'""")
                .param("u", c.uid()).param("id", c.invitationId()).update();
        if (n != 1 || !contacts.stillEligible(c.customerId())) {
            throw new Blocked(Reason.KHONG_DU_DIEU_KIEN); // chỉ khách mua một lần, không cần kiểm tra mới vào nhóm (luật nhóm hiện hành)
        }
        String undo = JSON.writeValueAsString(new Undo(lift, prior[0], prior[1], prior[2], prior[3], prior[4]));
        if (taskId == null) {
            jdbc.sql("""
                            INSERT INTO comeback (customer_id, invitation_id, state, reason, label, confidence, undo_state, welcome_state,
                                                  lifted_at, lifted_by)
                            VALUES (:c, :i, 'LIFTED', :r, :l, :cf, :u, :w, now(), :by)""")
                    .param("c", c.customerId()).param("i", c.invitationId()).param("r", reason.name())
                    .param("l", label, java.sql.Types.VARCHAR)
                    .param("cf", confidence == null ? null : java.math.BigDecimal.valueOf(confidence).setScale(3, java.math.RoundingMode.HALF_UP),
                            java.sql.Types.NUMERIC)
                    .param("u", undo).param("w", welcome ? "PENDING" : null, java.sql.Types.VARCHAR).param("by", by).update();
        } else {
            jdbc.sql("""
                            UPDATE comeback SET state = 'LIFTED', undo_state = :u, welcome_state = NULL, excerpt = NULL, lifted_at = now(),
                                   lifted_by = :by, resolved_at = now(), resolved_by = :by
                            WHERE id = :id AND state = 'TASK'""")
                    .param("u", undo).param("by", by).param("id", taskId).update();
        }
    }

    /** Mẹ xem việc rồi quyết gỡ cờ và cho khách vào nhóm. Không có tin mẫu (mẹ tự nhắn trên Zalo). Quy tắc nhóm vẫn áp dụng. */
    public void liftByHand(long id) {
        Row r = open(id, "TASK");
        Candidate c = candidateOfTask(id);
        try {
            tx.executeWithoutResult(s -> lift(c, ContactService.actor(), id, Reason.valueOf(r.reason().name()), r.label(), r.confidence(), false));
        } catch (Blocked b) {
            throw new ImportException("Chưa gỡ được: " + b.reason.label() + ". Khách có thể đã được xử lý ở chỗ khác hoặc không đủ điều kiện vào nhóm (chỉ khách mua một lần, không cần kiểm tra).");
        } catch (DataIntegrityViolationException e) {
            throw new ImportException("Chưa gỡ được: mã Zalo của khách đã gắn với một lời mời khác.");
        }
    }

    /** Mẹ xem rồi, giữ nguyên cờ "không nhắn nữa". */
    public void dismiss(long id) {
        open(id, "TASK");
        jdbc.sql("""
                        UPDATE comeback SET state = 'DONE', excerpt = NULL, resolved_at = now(), resolved_by = :by
                        WHERE id = :id AND state = 'TASK'""")
                .param("by", ContactService.actor()).param("id", id).update();
    }

    private Candidate candidateOfTask(long id) {
        return jdbc.sql("""
                        SELECT k.invitation_id, k.customer_id, coalesce(i.zalo_uid, i.zalo_target_uid) FROM comeback k
                        JOIN invitation i ON i.id = k.invitation_id WHERE k.id = :id""")
                .param("id", id).query((rs, n) -> new Candidate(rs.getLong(1), rs.getLong(2), rs.getString(3))).single();
    }

    /**
     * Hoàn tác một lần gỡ cờ: đánh dấu hiệu lực lại, đưa lời mời về như cũ NẾU khách chưa được thêm vào nhóm (đã thêm rồi thì hệ thống không có
     * cách đưa ra khỏi nhóm; mẹ tự mời ra nếu cần). Tin mẫu chưa gửi thì bỏ. Trả false nếu lời mời không khôi phục được.
     */
    public boolean undo(long id) {
        Row r = open(id, "LIFTED");
        String json = jdbc.sql("SELECT undo_state FROM comeback WHERE id = :id").param("id", id).query(String.class).single();
        if (json == null || json.isBlank()) {
            throw new ImportException("Không còn thông tin để hoàn tác việc này.");
        }
        Undo u = JSON.readValue(json, Undo.class);
        long invitationId = jdbc.sql("SELECT invitation_id FROM comeback WHERE id = :id").param("id", id).query(Long.class).single();
        Boolean restored = tx.execute(s -> {
            contacts.undoLiftOptOut(r.customerId(), u.lift(), r.liftedBy());
            int n = jdbc.sql("""
                            UPDATE invitation SET status = :st, cancelled_reason = :cr, zalo_uid = :uid, zalo_add_state = :ast, zalo_add_code = :ac
                            WHERE id = :id AND status = 'FRIENDED' AND zalo_add_state = 'QUEUED'""")
                    .param("st", u.status()).param("cr", u.cancelledReason(), java.sql.Types.VARCHAR)
                    .param("uid", u.zaloUid(), java.sql.Types.VARCHAR).param("ast", u.addState(), java.sql.Types.VARCHAR)
                    .param("ac", u.addCode(), java.sql.Types.VARCHAR).param("id", invitationId).update();
            jdbc.sql("""
                            UPDATE comeback SET state = 'UNDONE', undo_state = NULL, resolved_at = now(), resolved_by = :by,
                                   welcome_state = CASE WHEN welcome_state IN ('PENDING') THEN 'SKIPPED' ELSE welcome_state END
                            WHERE id = :id""")
                    .param("by", ContactService.actor()).param("id", id).update();
            return n == 1;
        });
        return Boolean.TRUE.equals(restored);
    }

    // ---------- tin mẫu (gửi ở ZaloWelcomeSender) ----------

    /** Tin mẫu đến lượt gửi: tin mới nhất còn trong khung 23 giờ kể từ lúc khách nhắn. Tin quá hạn bị bỏ (cầu nối cũng không cho trả lời). */
    public Optional<Welcome> nextWelcome() {
        jdbc.sql("""
                        UPDATE comeback SET welcome_state = 'SKIPPED' WHERE state = 'LIFTED' AND welcome_state = 'PENDING'
                          AND lifted_at < now() - make_interval(hours => :h)""")
                .param("h", WELCOME_WINDOW_HOURS).update();
        return jdbc.sql("""
                        SELECT k.id, i.zalo_uid FROM comeback k JOIN invitation i ON i.id = k.invitation_id
                        WHERE k.state = 'LIFTED' AND k.welcome_state = 'PENDING' AND i.zalo_uid IS NOT NULL
                        """ + contacts.onlyTestCustomerSql("k.customer_id") + " ORDER BY k.id LIMIT 1")
                .query((rs, n) -> new Welcome(rs.getLong(1), rs.getString(2))).optional();
    }

    /** Khóa trước khi gọi để hai nhịp chồng nhau không gửi trùng. Trả false nếu người khác đã lấy. */
    public boolean claimWelcome(long id) {
        return jdbc.sql("UPDATE comeback SET welcome_state = 'SENDING' WHERE id = :id AND state = 'LIFTED' AND welcome_state = 'PENDING'")
                .param("id", id).update() == 1;
    }

    /** Chưa chạm Zalo (bị chặn, bộ nối bận): trả về hàng chờ để thử lại nhịp sau. */
    public void restoreWelcome(long id) {
        jdbc.sql("UPDATE comeback SET welcome_state = 'PENDING' WHERE id = :id AND welcome_state = 'SENDING'").param("id", id).update();
    }

    public void finishWelcome(long id, String state, String code) {
        jdbc.sql("UPDATE comeback SET welcome_state = :s, welcome_code = :c WHERE id = :id AND welcome_state = 'SENDING'")
                .param("s", state).param("c", code, java.sql.Types.VARCHAR).param("id", id).update();
    }

    // ---------- xem ----------

    private static final String SELECT = """
            SELECT k.id, k.customer_id, c.name, c.phone, k.state, k.reason, k.label, k.confidence, k.excerpt, k.created_at, k.lifted_by, k.welcome_state
            FROM comeback k JOIN customer c ON c.id = k.customer_id
            """;

    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.math.BigDecimal cf = rs.getBigDecimal("confidence");
        return new Row(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("name"), rs.getString("state"),
                Reason.valueOf(rs.getString("reason")), rs.getString("label"), cf == null ? null : cf.doubleValue(), rs.getString("excerpt"),
                rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(VN).toLocalDateTime(), rs.getString("lifted_by"),
                rs.getString("welcome_state"), orders(rs.getString("phone")));
    }

    /** Tối đa 3 đơn gần nhất của khách: ngày, hàng hóa, trạng thái. Chỉ để mẹ xem, không đưa cho AI. */
    private List<String> orders(String phone) {
        return jdbc.sql("""
                        SELECT created_on, goods, carrier, canonical_status FROM shipment WHERE receiver_phone = :p
                        ORDER BY created_on DESC NULLS LAST, id DESC LIMIT 3""")
                .param("p", phone)
                .query((rs, n) -> {
                    java.sql.Timestamp t = rs.getTimestamp("created_on");
                    String goods = rs.getString("goods");
                    return (t == null ? "?" : t.toLocalDateTime().toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                            + " · " + (goods == null || goods.isBlank() ? "đơn " + rs.getString("carrier") : goods)
                            + " · " + rs.getString("canonical_status");
                }).list();
    }

    private Row open(long id, String state) {
        return jdbc.sql(SELECT + " WHERE k.id = :id").param("id", id).query((rs, n) -> row(rs)).optional()
                .filter(r -> state.equals(r.state())).orElseThrow(() -> new ImportException("Việc này đã được xử lý."));
    }

    /** Việc nổi bật đang chờ mẹ. */
    public List<Row> tasks() {
        return jdbc.sql(SELECT + "WHERE k.state = 'TASK' ORDER BY k.created_at DESC LIMIT 100").query((rs, n) -> row(rs)).list();
    }

    /** Những lần đã gỡ cờ trong {@code days} ngày gần đây (cả mẹ gỡ tay), còn nút Hoàn tác. */
    public List<Row> recentLifted(int days) {
        return jdbc.sql(SELECT + "WHERE k.state = 'LIFTED' AND k.lifted_at > now() - make_interval(days => :d) ORDER BY k.lifted_at DESC LIMIT 100")
                .param("d", days).query((rs, n) -> row(rs)).list();
    }

    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM comeback WHERE state = 'TASK'").query(Long.class).single();
    }

    /** Xóa trích đoạn quá {@value #KEEP_DAYS} ngày. */
    public int purgeOld() {
        return jdbc.sql("UPDATE comeback SET excerpt = NULL WHERE excerpt IS NOT NULL AND created_at < now() - make_interval(days => :d)")
                .param("d", KEEP_DAYS).update();
    }
}
