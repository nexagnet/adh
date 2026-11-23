package vn.shop.crm.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.inbox.RefusalClassifier.Label;
import vn.shop.crm.inbox.RefusalClassifier.Verdict;
import vn.shop.crm.invite.InviteService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Trợ lý AI tự soạn câu trả lời cho khách TỰ nhắn tới (D20, chủ dự án chốt 23/11/2026, docs/THIET-KE-AI-TRA-LOI.md).
 *
 * <p>Phạm vi hẹp: chỉ "mời vào nhóm" và "ưu đãi khi vào nhóm". Mọi thứ khác (giá, còn hàng, bảo hành, đổi trả, phàn nàn, muốn mua, muốn gặp người thật,
 * tin mơ hồ) chuyển người thật. Đường đi mỗi tin: AI phân loại phạm vi (nghi ngờ = ngoài phạm vi, cần chắc từ {@value #SCOPE_MIN}) → AI soạn câu chỉ từ
 * bảng thông tin cửa hàng → {@link ReplyValidator} kiểm cứng → mới thành nháp hoặc hàng chờ gửi. Không qua bước nào thì thành việc cho người thật.
 *
 * <p>Ba nấc ({@link Mode}), mặc định TẮT. NHÁP: chỉ lưu câu AI soạn cho người thật xem, KHÔNG gửi. TỰ ĐỘNG: gửi qua {@code ZaloReplySender}, mỗi tin
 * đến trả lời một lần; chỉ mở sau khi đã chạy nấc NHÁP và có tick xác nhận. Câu trả lời luôn kèm chữ ký "Trợ lý AI của cửa hàng" và cách gặp người thật.
 *
 * <p>Việc cho "người thật" là HÀNG ĐỢI CHUNG (cột phân công để dành cho giai đoạn 2, đội bán hàng nhiều người), không gắn cứng một người. Văn bản khách và
 * câu AI soạn là văn bản ĐÃ CHE, tự xóa sau {@value #KEEP_DAYS} ngày; không có số điện thoại, tên hay mã Zalo trong log.
 */
@Service
public class ReplyService {

    private static final Logger log = LoggerFactory.getLogger(ReplyService.class);

    public static final String MODE_KEY = "reply.mode";
    public static final String DRAFT_TRIED_KEY = "reply.draft_tried_at";
    /** AI phải chắc chừng này là tin NẰM TRONG phạm vi mới được soạn trả lời. */
    public static final double SCOPE_MIN = 0.8;
    static final int KEEP_DAYS = 30;
    static final int TEXT_MAX = 500;
    /** Cầu nối chỉ cho trả lời người vừa nhắn trong 24 giờ; nhỏ hơn một chút để không chạm biên. */
    public static final int REPLY_WINDOW_HOURS = 23;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    public enum Mode {
        OFF("Tắt", "Trợ lý AI không trả lời khách. Khách nhắn gì hệ thống không làm gì thêm."),
        DRAFT("Chỉ soạn nháp", "AI soạn câu trả lời nhưng KHÔNG gửi. Người thật xem nháp rồi tự trả lời khách trên Zalo nếu thấy đúng."),
        AUTO("Tự động", "AI tự trả lời khách trong phạm vi hẹp (mời vào nhóm, ưu đãi khi vào nhóm), câu nào ngoài phạm vi hoặc không qua kiểm tra thì chuyển người thật.");

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
    }

    public enum Reason {
        NGOAI_PHAM_VI("Khách hỏi ngoài việc mời vào nhóm và ưu đãi"),
        KHACH_MUON_MUA("Khách muốn mua hàng"),
        KHACH_KHIEU_NAI("Khách phàn nàn hoặc hỏi đổi trả"),
        KHACH_TU_CHOI("Khách có vẻ không muốn nhận tin nữa"),
        KHONG_DU_THONG_TIN("Bảng thông tin cửa hàng chưa đủ để trả lời câu này"),
        CAU_BI_CHAN("Câu AI soạn không qua kiểm tra nên không gửi"),
        AI_LOI("Chưa nhờ được AI đọc tin này"),
        AI_SAI_KHUON("AI trả lời không đúng khuôn"),
        QUA_HAN("Quá 23 giờ chưa gửi được nên không trả lời tự động được nữa"),
        GUI_LOI("Gửi tin trả lời không được");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Người nhắn: khách, lời mời (nếu có) và mã Zalo. */
    public record Subject(long customerId, Long invitationId, String uid) {
    }

    public enum Result {
        /** Mode TẮT hoặc đang gửi dở: không làm gì. */
        NONE,
        /** Đã lưu nháp (nấc chỉ soạn nháp). */
        DRAFTED,
        /** Đã xếp vào hàng chờ gửi (nấc tự động). */
        QUEUED,
        /** Chuyển người thật. */
        HANDOFF
    }

    public record Row(long id, long customerId, String customerName, String state, Reason reason, String detail, String inbound, String reply,
                      LocalDateTime createdAt, LocalDateTime sentAt, List<String> contextLabels) {
    }

    public record Ready(long id, String uid, String text) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final ReplyAssistant assistant;
    private final ReplyInfo info;
    private final InviteService invites;
    private final ContactService contacts;
    private final ReplyContext context;

    public ReplyService(JdbcClient jdbc, AppSettings settings, ReplyAssistant assistant, ReplyInfo info, InviteService invites,
                        ContactService contacts, ReplyContext context) {
        this.contacts = contacts;
        this.context = context;
        this.jdbc = jdbc;
        this.settings = settings;
        this.assistant = assistant;
        this.info = info;
        this.invites = invites;
    }

    public ReplyInfo info() {
        return info;
    }

    public ReplyContext context() {
        return context;
    }

    // ---------- nấc ----------

    public Mode mode() {
        return Mode.parse(settings.get(MODE_KEY).orElse(null));
    }

    public boolean draftTried() {
        return settings.get(DRAFT_TRIED_KEY).isPresent();
    }

    /** Đổi nấc. TỰ ĐỘNG chỉ được chọn sau khi đã chạy nấc NHÁP và người chọn tick xác nhận đã xem nháp. */
    public void setMode(Mode mode, boolean confirmed, String by) {
        if (mode == Mode.DRAFT) {
            settings.set(DRAFT_TRIED_KEY, Instant.now().toString(), by);
        }
        if (mode == Mode.AUTO) {
            if (!draftTried()) {
                throw new ImportException("Hãy chạy nấc \"Chỉ soạn nháp\" một thời gian và xem các nháp AI soạn trước khi bật tự động.");
            }
            if (!confirmed) {
                throw new ImportException("Hãy tick ô xác nhận đã xem các nháp ở nấc \"Chỉ soạn nháp\" trước khi bật tự động.");
            }
        }
        settings.set(MODE_KEY, mode.name(), by);
    }

    /** Chữ ký cố định thêm vào MỌI câu trả lời: nói rõ là trợ lý AI và cho khách đường gặp người thật. */
    public String signature() {
        return "\n— Trợ lý AI của " + invites.shopName() + ". Cần gặp người thật, anh/chị cứ nhắn \"gặp người thật\" ạ.";
    }

    // ---------- xử lý một tin đến ----------

    /**
     * Khách ĐÃ vào nhóm (lời mời gần nhất của người có mã Zalo này là JOINED) và KHÔNG có cờ từ chối còn hiệu lực. Khách từng từ chối không bao giờ
     * được trả lời ở đây (họ đi đường khách quay lại).
     */
    public Optional<Subject> joinedSubject(String uid) {
        record Hit(long invitationId, long customerId, String status, String phone) {
        }
        return jdbc.sql("""
                        SELECT i.id, i.customer_id, i.status, c.phone FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.zalo_uid = :u OR i.zalo_target_uid = :u
                        ORDER BY i.id DESC LIMIT 1""")
                .param("u", uid).query((rs, n) -> new Hit(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("status"), rs.getString("phone")))
                .optional()
                .filter(h -> "JOINED".equals(h.status()) && !contacts.hasActiveOptOut(h.phone()))
                .map(h -> new Subject(h.customerId(), h.invitationId(), uid));
    }

    /**
     * @param v    kết quả gắn nhãn của {@link RefusalClassifier} cho đúng tin này
     * @param text văn bản ĐÃ CHE ({@link RefusalClassifier#prepare})
     * @param at   giờ khách nhắn
     */
    public Result handle(Subject s, Verdict v, String text, Instant at) {
        if (mode() == Mode.OFF) {
            return Result.NONE;
        }
        Optional<Open> open = open(s.uid());
        if (open.isPresent()) {
            Open o = open.get();
            if ("SENDING".equals(o.state)) {
                return Result.NONE; // đang gửi dở: không chồng thêm
            }
            if ("HANDOFF".equals(o.state)) {
                // Người thật đã được báo: ghi thêm tin mới vào việc đó, không nhờ AI trả lời chen vào.
                jdbc.sql("UPDATE reply_draft SET inbound_text = :t, updated_at = now() WHERE id = :id")
                        .param("t", tail(join(o.inbound, text))).param("id", o.id).update();
                return Result.HANDOFF;
            }
            // nháp hoặc hàng chờ chưa gửi: thay bằng việc mới, gộp chữ khách để không mất ý
            jdbc.sql("UPDATE reply_draft SET state = 'SUPERSEDED', reply_text = NULL, updated_at = now() WHERE id = :id").param("id", o.id).update();
            text = tail(join(o.inbound, text));
        }
        try {
            return decide(s, v, text, at);
        } catch (DataIntegrityViolationException e) {
            log.warn("Trùng việc trả lời cho một khách: bỏ qua");
            return Result.NONE;
        }
    }

    private Result decide(Subject s, Verdict v, String text, Instant at) {
        switch (v.outcome()) {
            case UNAVAILABLE -> {
                return handoff(s, Reason.AI_LOI, null, null, text, at);
            }
            case INVALID -> {
                return handoff(s, Reason.AI_SAI_KHUON, null, null, text, at);
            }
            default -> {
            }
        }
        Label label = v.label();
        if (label == Label.TU_CHOI) {
            return handoff(s, Reason.KHACH_TU_CHOI, null, v.confidence(), text, at);
        }
        if (label == Label.MUON_MUA) {
            return handoff(s, Reason.KHACH_MUON_MUA, null, v.confidence(), text, at);
        }
        if (label == Label.KHIEU_NAI) {
            return handoff(s, Reason.KHACH_KHIEU_NAI, null, v.confidence(), text, at);
        }
        // Hồ sơ chỉ được dựng (và đọc từ CSDL) khi đã qua bước phân loại phạm vi, và chỉ của đúng khách này.
        Plan p = plan(text, () -> context.forCustomer(s.customerId()));
        if (p.reply == null) {
            return handoff(s, p.reason, p.detail, p.confidence, text, p.fields, at);
        }
        Mode mode = mode();
        String state = mode == Mode.AUTO ? "READY" : "DRAFT";
        insert(s, state, null, null, p.confidence, text, p.reply + signature(), p.fields, at);
        return mode == Mode.AUTO ? Result.QUEUED : Result.DRAFTED;
    }

    /** Kết quả của chuỗi phân loại phạm vi, soạn câu, kiểm cứng. {@code reply} khác null nghĩa là có câu dùng được (chưa kèm chữ ký). */
    private record Plan(String reply, Reason reason, String detail, Double confidence, String fields) {
        static Plan no(Reason r, String detail, Double c) {
            return new Plan(null, r, detail, c, null);
        }

        Plan withFields(String f) {
            return new Plan(reply, reason, detail, confidence, f);
        }
    }

    /** Bước phân loại phạm vi không nhận hồ sơ khách; chỉ bước soạn câu mới nhận (và chỉ khi tin nằm trong phạm vi). */
    private Plan plan(String text, java.util.function.Supplier<ReplyContext.Built> profile) {
        ReplyAssistant.Scope sc = assistant.scope(text);
        if (sc.outcome() == ReplyAssistant.Outcome.UNAVAILABLE) {
            return Plan.no(Reason.AI_LOI, null, null);
        }
        if (sc.outcome() == ReplyAssistant.Outcome.INVALID) {
            return Plan.no(Reason.AI_SAI_KHUON, null, null);
        }
        if (!sc.inScope() || sc.confidence() < SCOPE_MIN) {
            return Plan.no(Reason.NGOAI_PHAM_VI, null, sc.confidence());
        }
        ReplyContext.Built p = profile.get();
        ReplyAssistant.Composed c = assistant.compose(text, p);
        // Từ đây hồ sơ đã được gửi (hoặc đã định gửi) cho AI: ghi lại các trường để kiểm tra sau, kể cả khi câu bị chặn.
        String fields = p.fields().isBlank() ? null : p.fields();
        if (c.outcome() == ReplyAssistant.Outcome.UNAVAILABLE) {
            return Plan.no(Reason.AI_LOI, null, sc.confidence()).withFields(fields);
        }
        if (c.outcome() == ReplyAssistant.Outcome.INVALID) {
            return Plan.no(Reason.AI_SAI_KHUON, null, sc.confidence()).withFields(fields);
        }
        if (c.reply().isBlank()) {
            return Plan.no(Reason.KHONG_DU_THONG_TIN, null, sc.confidence()).withFields(fields);
        }
        Optional<String> blocked = ReplyValidator.check(c.reply(), info.allowedText() + " " + invites.shopName(), p.numbers());
        if (blocked.isPresent()) {
            return Plan.no(Reason.CAU_BI_CHAN, blocked.get(), sc.confidence()).withFields(fields);
        }
        return new Plan(c.reply(), null, null, sc.confidence(), fields);
    }

    /** Kết quả thử một câu (không lưu, không gửi). */
    public record Preview(boolean wouldReply, String reply, String note) {
    }

    /** Thử đúng chuỗi AI soạn câu trả lời với một câu khách giả: KHÔNG lưu gì, KHÔNG gửi gì, bỏ qua bước gắn nhãn từ chối. Tốn lượt AI như thường. */
    public Preview preview(String raw) {
        if (!assistant.aiEnabled()) {
            return new Preview(false, null, "AI đang tắt ở máy chủ (cần APP_AI_ENABLED, khóa và tên mô hình).");
        }
        String text = RefusalClassifier.prepare(raw == null ? "" : raw);
        if (text.isBlank()) {
            return new Preview(false, null, "Hãy gõ một câu khách có thể nhắn.");
        }
        // Thử bằng hồ sơ mẫu GIẢ: không bao giờ đọc hay gửi dữ liệu khách thật ở ô thử.
        Plan p = plan(text, context::sample);
        if (p.reply != null) {
            return new Preview(true, p.reply + signature(), "Hệ thống sẽ trả lời câu này (nấc tự động) hoặc lưu nháp (nấc nháp).");
        }
        return new Preview(false, null, "Sẽ chuyển người thật: " + p.reason.label() + (p.detail == null ? "" : " (" + p.detail + ")") + ".");
    }

    private Result handoff(Subject s, Reason reason, String detail, Double confidence, String text, Instant at) {
        return handoff(s, reason, detail, confidence, text, null, at);
    }

    private Result handoff(Subject s, Reason reason, String detail, Double confidence, String text, String fields, Instant at) {
        insert(s, "HANDOFF", reason, detail, confidence, text, null, fields, at);
        return Result.HANDOFF;
    }

    private void insert(Subject s, String state, Reason reason, String detail, Double confidence, String inbound, String reply, String fields,
                        Instant at) {
        jdbc.sql("""
                        INSERT INTO reply_draft (customer_id, invitation_id, zalo_uid, state, reason, detail, scope_confidence, inbound_text, reply_text,
                                                 context_fields, inbound_at)
                        VALUES (:c, :i, :u, :s, :r, :d, :cf, :in, :re, :cx, :at)""")
                .param("cx", fields, java.sql.Types.VARCHAR)
                .param("c", s.customerId()).param("i", s.invitationId(), java.sql.Types.BIGINT).param("u", s.uid()).param("s", state)
                .param("r", reason == null ? null : reason.name(), java.sql.Types.VARCHAR).param("d", detail, java.sql.Types.VARCHAR)
                .param("cf", confidence == null ? null : java.math.BigDecimal.valueOf(confidence).setScale(3, java.math.RoundingMode.HALF_UP),
                        java.sql.Types.NUMERIC)
                .param("in", inbound, java.sql.Types.VARCHAR).param("re", reply, java.sql.Types.VARCHAR)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).update();
    }

    private record Open(long id, String state, String inbound) {
    }

    private Optional<Open> open(String uid) {
        return jdbc.sql("""
                        SELECT id, state, inbound_text FROM reply_draft
                        WHERE zalo_uid = :u AND state IN ('DRAFT', 'READY', 'SENDING', 'HANDOFF')""")
                .param("u", uid).query((rs, n) -> new Open(rs.getLong("id"), rs.getString("state"), rs.getString("inbound_text"))).optional();
    }

    private static String join(String old, String now) {
        return old == null || old.isBlank() ? now : old + " | " + now;
    }

    /** Giữ phần cuối (tin mới nhất quan trọng hơn) khi quá dài. */
    private static String tail(String s) {
        return s.length() <= TEXT_MAX ? s : s.substring(s.length() - TEXT_MAX);
    }

    // ---------- gửi ----------

    /** Việc sẵn sàng gửi lâu nhất còn trong khung {@value #REPLY_WINDOW_HOURS} giờ. */
    public Optional<Ready> nextReady() {
        return jdbc.sql("""
                        SELECT id, zalo_uid, reply_text FROM reply_draft
                        WHERE state = 'READY' AND reply_text IS NOT NULL AND inbound_at > now() - make_interval(hours => :h)
                        """ + contacts.onlyTestCustomerSql("customer_id") + " ORDER BY id LIMIT 1")
                .param("h", REPLY_WINDOW_HOURS)
                .query((rs, n) -> new Ready(rs.getLong("id"), rs.getString("zalo_uid"), rs.getString("reply_text"))).optional();
    }

    /** Khóa việc trước khi gọi bộ nối để không bao giờ gửi trùng. */
    public boolean claim(long id) {
        return jdbc.sql("UPDATE reply_draft SET state = 'SENDING', updated_at = now() WHERE id = :id AND state = 'READY'")
                .param("id", id).update() == 1;
    }

    /** Chưa chạm Zalo: trả về hàng chờ để thử lại nhịp sau. */
    public void restore(long id) {
        jdbc.sql("UPDATE reply_draft SET state = 'READY', updated_at = now() WHERE id = :id AND state = 'SENDING'").param("id", id).update();
    }

    public void sent(long id) {
        jdbc.sql("UPDATE reply_draft SET state = 'SENT', sent_at = now(), updated_at = now() WHERE id = :id AND state = 'SENDING'")
                .param("id", id).update();
    }

    /** Không gửi được (hoặc không rõ đã gửi chưa): KHÔNG tự gửi lại, chuyển người thật kèm mã lỗi. */
    public void failed(long id, String code) {
        jdbc.sql("""
                        UPDATE reply_draft SET state = 'HANDOFF', reason = :r, detail = :d, updated_at = now()
                        WHERE id = :id AND state = 'SENDING'""")
                .param("r", Reason.GUI_LOI.name()).param("d", code, java.sql.Types.VARCHAR).param("id", id).update();
    }

    /** Hàng chờ quá hạn (cầu nối chỉ cho trả lời trong 24 giờ): chuyển người thật. */
    public int expireStale() {
        return jdbc.sql("""
                        UPDATE reply_draft SET state = 'HANDOFF', reason = :r, reply_text = NULL, updated_at = now()
                        WHERE state = 'READY' AND inbound_at <= now() - make_interval(hours => :h)""")
                .param("r", Reason.QUA_HAN.name()).param("h", REPLY_WINDOW_HOURS).update();
    }

    // ---------- cho trang quản trị ----------

    private static final String SELECT = """
            SELECT d.id, d.customer_id, c.name, d.state, d.reason, d.detail, d.inbound_text, d.reply_text, d.created_at, d.sent_at, d.context_fields
            FROM reply_draft d JOIN customer c ON c.id = d.customer_id
            """;

    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String r = rs.getString("reason");
        java.sql.Timestamp sent = rs.getTimestamp("sent_at");
        return new Row(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("name"), rs.getString("state"),
                r == null ? null : Reason.valueOf(r), rs.getString("detail"), rs.getString("inbound_text"), rs.getString("reply_text"),
                rs.getTimestamp("created_at").toInstant().atZone(VN).toLocalDateTime(),
                sent == null ? null : sent.toInstant().atZone(VN).toLocalDateTime(), ReplyContext.labelsOf(rs.getString("context_fields")));
    }

    /** Việc chờ người thật (hàng đợi chung). */
    public List<Row> handoffs() {
        return jdbc.sql(SELECT + "WHERE d.state = 'HANDOFF' ORDER BY d.created_at DESC LIMIT 100").query((rs, n) -> row(rs)).list();
    }

    /** Nháp AI soạn (nấc chỉ soạn nháp) chờ người thật xem. */
    public List<Row> drafts() {
        return jdbc.sql(SELECT + "WHERE d.state = 'DRAFT' ORDER BY d.created_at DESC LIMIT 100").query((rs, n) -> row(rs)).list();
    }

    /** Những câu trợ lý đã tự gửi trong {@code days} ngày gần đây. */
    public List<Row> recentSent(int days) {
        return jdbc.sql(SELECT + "WHERE d.state = 'SENT' AND d.sent_at > now() - make_interval(days => :d) ORDER BY d.sent_at DESC LIMIT 100")
                .param("d", days).query((rs, n) -> row(rs)).list();
    }

    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM reply_draft WHERE state = 'HANDOFF'").query(Long.class).single();
    }

    /** Người thật đã xử lý hoặc bỏ qua việc/nháp này: xóa chữ khách và câu AI. */
    public boolean done(long id, String by) {
        return jdbc.sql("""
                        UPDATE reply_draft SET state = 'DONE', inbound_text = NULL, reply_text = NULL, resolved_by = :b, updated_at = now()
                        WHERE id = :id AND state IN ('HANDOFF', 'DRAFT')""")
                .param("b", by).param("id", id).update() == 1;
    }

    /** Xóa chữ khách và câu AI quá {@value #KEEP_DAYS} ngày. */
    public int purgeOld() {
        return jdbc.sql("""
                        UPDATE reply_draft SET inbound_text = NULL, reply_text = NULL
                        WHERE (inbound_text IS NOT NULL OR reply_text IS NOT NULL) AND created_at < now() - make_interval(days => :d)""")
                .param("d", KEEP_DAYS).update();
    }
}
