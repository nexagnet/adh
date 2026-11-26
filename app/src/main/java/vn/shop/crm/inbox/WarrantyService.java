package vn.shop.crm.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.bot.PiiMasker;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.inbox.RefusalClassifier.Label;
import vn.shop.crm.inbox.RefusalClassifier.Verdict;
import vn.shop.crm.inbox.WarrantyAssistant.Category;
import vn.shop.crm.inbox.WarrantyAssistant.Triage;
import vn.shop.crm.inbox.WarrantyAssistant.Urgency;
import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloAccountContext;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bảo hành và khiếu nại nhanh (docs/THIET-KE-BAO-HANH-KHIEU-NAI.md). Khách đã nhận tin của hệ thống tự nhắn riêng báo lỗi hoặc phàn nàn (AI gắn nhãn
 * KHIEU_NAI ở bước đọc tin riêng): AI phân loại loại việc và mức gấp, tóm tắt, soạn NHÁP; hệ thống tạo MỘT việc cho người thật kèm đơn khách đã mua
 * để mẹ khỏi tra cứu.
 *
 * <p>Giới hạn cứng: hệ thống KHÔNG BAO GIỜ gửi gì cho khách ở phần này (không có đường gửi); AI không cam kết bảo hành, đổi trả hay hoàn tiền; nháp phải
 * qua bộ kiểm cứng ({@link #checkDraft}), không qua thì bỏ nháp (việc vẫn được tạo). Chỉ nạp dữ liệu của ĐÚNG khách đó; AI chỉ nhận văn bản đã che, tên
 * hàng và số ngày kể từ khi giao, không bao giờ số điện thoại, địa chỉ, mã vận đơn hay tên khách. Công tắc riêng, TẮT mặc định. Chữ khách, tóm tắt và nháp
 * tự xóa khi xử lý xong hoặc sau {@value #KEEP_DAYS} ngày; không có nội dung khách, tên hay số điện thoại trong log.
 *
 * <p>Ảnh và video khách gửi: cầu nối chỉ đọc chữ, mô hình hiện dùng chỉ nhận chữ, nên hệ thống không đọc ảnh. AI chỉ gợi ý "nên xin ảnh" và người thật xem ảnh
 * trên Zalo.
 */
@Service
public class WarrantyService {

    private static final Logger log = LoggerFactory.getLogger(WarrantyService.class);

    public static final String ENABLED_KEY = "warranty.enabled";
    static final int KEEP_DAYS = 30;
    static final int TEXT_MAX = 500;
    /** Số đơn gần nhất đưa vào việc. */
    static final int ORDERS = 3;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern NUMBER = Pattern.compile("\\d");
    private static final Pattern LINK = Pattern.compile("(https?:|www\\.|zalo\\.me|\\b[a-z0-9-]+\\.(com|vn|net|me|org|info|app|link|ly|io)\\b|@[a-z0-9_.]{3,})");
    /** Nháp cho khách không được hứa hay cam kết bất cứ điều gì về bảo hành, đổi trả, hoàn tiền (đã bỏ dấu, chữ thường). */
    private static final List<Pattern> PROMISES = java.util.stream.Stream.of(
                    "cam ket", "dam bao", "chac chan", "doi moi", "doi tra", "hoan tien", "hoan tra", "tra hang", "den bu", "mien phi", "1 doi 1", "mot doi mot",
                    "qua tang", "voucher", "ma giam", "dia chi", "so dien thoai", "sdt", "mat khau", "otp", "chuyen khoan", "em la chu shop", "em la me",
                    "em la nguoi", "em la nhan vien")
            .map(w -> Pattern.compile("(?<![a-z0-9])" + Pattern.quote(w) + "(?![a-z0-9])")).toList();

    /** Một đơn khách đã mua, chỉ để người thật xem (có mã vận đơn; KHÔNG đưa cho AI). */
    public record Order(String goods, String carrier, String trackingNo, String orderRef, LocalDateTime createdOn, LocalDateTime deliveredOn, String status) {
    }

    public record Row(long id, long customerId, String customerName, Category category, Urgency urgency, boolean needsPhoto, String summary, String draft,
                      String inbound, int messageCount, LocalDateTime createdAt, LocalDateTime updatedAt, List<Order> orders, long accountId) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final WarrantyAssistant assistant;
    private final Clock clock;

    public WarrantyService(JdbcClient jdbc, AppSettings settings, WarrantyAssistant assistant, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.assistant = assistant;
        this.clock = clock;
    }

    // ---------- công tắc ----------

    public boolean enabled() {
        return settings.flag(ENABLED_KEY);
    }

    public void setEnabled(boolean on, String by) {
        settings.set(ENABLED_KEY, Boolean.toString(on), by);
    }

    // ---------- nhận một tin đến ----------

    /**
     * Xử lý một tin riêng của khách đã được gắn nhãn. Trả true nếu phần bảo hành đã nhận tin này (tạo việc mới hoặc ghi tiếp vào việc đang mở), khi đó
     * bên gọi KHÔNG làm thêm gì với tin (không nhờ AI trả lời chen vào). Trả false (không làm gì) khi công tắc tắt, hoặc tin không phải phàn nàn và khách
     * chưa có việc đang mở, hoặc khách nhắn tin có vẻ từ chối nhận tin (đi đường từ chối riêng).
     *
     * @param v    kết quả gắn nhãn của {@link RefusalClassifier} cho đúng tin này
     * @param text văn bản ĐÃ CHE ({@link RefusalClassifier#prepare})
     */
    public boolean take(ReplyService.Subject s, Verdict v, String text, Instant at) {
        if (!enabled()) {
            return false;
        }
        boolean ok = v.outcome() == RefusalClassifier.Outcome.OK;
        if (ok && v.label() == Label.TU_CHOI) {
            return false;
        }
        Optional<Open> open = open(s.uid());
        if (open.isPresent()) {
            // đã có việc đang mở: ghi thêm chữ khách, không gọi AI nữa
            jdbc.sql("""
                            UPDATE warranty_case SET inbound_text = :t, message_count = message_count + 1, inbound_at = :at, updated_at = now()
                            WHERE id = :id AND state = 'OPEN'""")
                    .param("t", tail(join(open.get().inbound, text))).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).param("id", open.get().id).update();
            return true;
        }
        if (!ok || v.label() != Label.KHIEU_NAI) {
            return false;
        }
        Triage t = assistant.triage(text, orderLinesForAi(s.customerId()));
        String draft = t.outcome() == WarrantyAssistant.Outcome.OK ? usableDraft(t.draft()) : null;
        String summary = t.outcome() == WarrantyAssistant.Outcome.OK && !t.summary().isBlank() ? PiiMasker.mask(t.summary()) : null;
        try {
            jdbc.sql("""
                            INSERT INTO warranty_case (account_id, customer_id, invitation_id, zalo_uid, state, category, urgency,
                                                       needs_photo, summary, draft_text, inbound_text, inbound_at)
                            VALUES (:acct, :c, :i, :u, 'OPEN', :cat, :urg, :ph, :sum, :dr, :in, :at)""")
                    .param("acct", ZaloAccountContext.current())
                    .param("c", s.customerId()).param("i", s.invitationId(), java.sql.Types.BIGINT).param("u", s.uid())
                    .param("cat", t.category().name()).param("urg", t.urgency().name()).param("ph", t.needsPhoto())
                    .param("sum", summary, java.sql.Types.VARCHAR).param("dr", draft, java.sql.Types.VARCHAR).param("in", tail(text))
                    .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).update();
        } catch (DataIntegrityViolationException e) {
            log.warn("Trùng việc bảo hành cho một khách: bỏ qua");
        }
        return true;
    }

    /** Nháp chỉ để người thật duyệt, nhưng vẫn không được chứa lời hứa, số, đường dẫn hay thông tin cá nhân. Rỗng nếu không dùng được. */
    static String usableDraft(String draft) {
        return draft != null && !draft.isBlank() && checkDraft(draft).isEmpty() ? draft : null;
    }

    /** Rỗng nghĩa là nháp qua kiểm tra; có giá trị là mã lý do chặn (không chứa nội dung khách). */
    public static Optional<String> checkDraft(String draft) {
        if (draft == null || draft.isBlank()) {
            return Optional.of("RONG");
        }
        if (draft.length() > WarrantyAssistant.DRAFT_MAX) {
            return Optional.of("QUA_DAI");
        }
        String folded = ReplyValidator.fold(draft);
        if (NUMBER.matcher(folded).find()) {
            return Optional.of("CO_SO"); // không bịa thời hạn, giá, phần trăm
        }
        if (LINK.matcher(folded).find()) {
            return Optional.of("CO_LINK");
        }
        if (PiiMasker.containsPii(draft)) {
            return Optional.of("CO_THONG_TIN_CA_NHAN");
        }
        for (Pattern p : PROMISES) {
            if (p.matcher(folded).find()) {
                return Optional.of("TU_CAM");
            }
        }
        return Optional.empty();
    }

    private record Open(long id, String inbound) {
    }

    private Optional<Open> open(String uid) {
        return jdbc.sql("SELECT id, inbound_text FROM warranty_case WHERE account_id = :acct AND zalo_uid = :u AND state = 'OPEN'")
                .param("acct", ZaloAccountContext.current()).param("u", uid)
                .query((rs, n) -> new Open(rs.getLong("id"), rs.getString("inbound_text"))).optional();
    }

    private static String join(String old, String now) {
        return old == null || old.isBlank() ? now : old + " | " + now;
    }

    /** Giữ phần cuối (tin mới nhất quan trọng hơn) khi quá dài. */
    private static String tail(String s) {
        return s.length() <= TEXT_MAX ? s : s.substring(s.length() - TEXT_MAX);
    }

    // ---------- đơn đã mua ----------

    private static final String ORDER_SQL = """
            SELECT s.goods, s.carrier, s.tracking_no, s.order_ref, s.created_on, s.delivered_on, s.canonical_status
            FROM shipment s JOIN customer c ON c.phone = s.receiver_phone
            WHERE c.id = :c
            ORDER BY COALESCE(s.delivered_on, s.created_on) DESC NULLS LAST, s.id DESC LIMIT\s""" + ORDERS;

    /** Các đơn gần nhất của ĐÚNG khách này, cho người thật xem (có mã vận đơn). */
    public List<Order> ordersOf(long customerId) {
        return jdbc.sql(ORDER_SQL).param("c", customerId).query((rs, n) -> {
            Timestamp created = rs.getTimestamp("created_on");
            Timestamp delivered = rs.getTimestamp("delivered_on");
            return new Order(rs.getString("goods"), carrierLabel(rs.getString("carrier")), rs.getString("tracking_no"), rs.getString("order_ref"),
                    created == null ? null : created.toLocalDateTime(), delivered == null ? null : delivered.toLocalDateTime(),
                    statusLabel(rs.getString("canonical_status")));
        }).list();
    }

    /**
     * Phần "hàng đã mua" đưa cho AI: CHỈ tên hàng (đã che, rút gọn) và số ngày kể từ khi giao. Không mã vận đơn, mã đơn, địa chỉ, tên hay số điện thoại.
     */
    List<String> orderLinesForAi(long customerId) {
        List<String> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(clock.withZone(VN));
        for (Order o : ordersOf(customerId)) {
            String goods = o.goods() == null || o.goods().isBlank() ? "không rõ tên hàng"
                    : RefusalClassifier.prepare(o.goods()).replace("[[[", " ").replace("]]]", " ");
            if (goods.length() > 80) {
                goods = goods.substring(0, 80);
            }
            String when;
            if ("Giao thành công".equals(o.status()) && o.deliveredOn() != null) {
                when = "đã giao cách đây " + Math.max(0, ChronoUnit.DAYS.between(o.deliveredOn(), now)) + " ngày";
            } else {
                when = o.status() == null ? "trạng thái chưa rõ" : o.status().toLowerCase(java.util.Locale.ROOT);
            }
            out.add("- " + goods + "; " + when);
        }
        return out;
    }

    private static String carrierLabel(String c) {
        try {
            return Carrier.valueOf(c).label();
        } catch (RuntimeException e) {
            return c;
        }
    }

    private static String statusLabel(String s) {
        try {
            return CanonicalStatus.valueOf(s).label();
        } catch (RuntimeException e) {
            return s;
        }
    }

    // ---------- cho trang quản trị ----------

    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        long customerId = rs.getLong("customer_id");
        return new Row(rs.getLong("id"), customerId, rs.getString("name"), Category.valueOf(rs.getString("category")), Urgency.valueOf(rs.getString("urgency")),
                rs.getBoolean("needs_photo"), rs.getString("summary"), rs.getString("draft_text"), rs.getString("inbound_text"), rs.getInt("message_count"),
                rs.getTimestamp("created_at").toInstant().atZone(VN).toLocalDateTime(), rs.getTimestamp("updated_at").toInstant().atZone(VN).toLocalDateTime(),
                ordersOf(customerId), rs.getLong("account_id"));
    }

    /** Việc đang mở: gấp trước (CAO, rồi chưa rõ, thường, không gấp), cùng mức thì mới nhất trước. */
    public List<Row> open() {
        return jdbc.sql("""
                        SELECT w.id, w.customer_id, c.name, w.category, w.urgency, w.needs_photo, w.summary, w.draft_text, w.inbound_text, w.message_count,
                               w.created_at, w.updated_at, w.account_id
                        FROM warranty_case w JOIN customer c ON c.id = w.customer_id
                        WHERE w.state = 'OPEN'
                        ORDER BY CASE w.urgency WHEN 'CAO' THEN 0 WHEN 'CHUA_RO' THEN 1 WHEN 'THUONG' THEN 2 ELSE 3 END, w.updated_at DESC
                        LIMIT 100""")
                .query((rs, n) -> row(rs)).list();
    }

    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM warranty_case WHERE state = 'OPEN'").query(Long.class).single();
    }

    /** Người thật đã xử lý: xóa chữ khách, tóm tắt và nháp. */
    public boolean done(long id, String by) {
        return jdbc.sql("""
                        UPDATE warranty_case SET state = 'DONE', inbound_text = NULL, summary = NULL, draft_text = NULL, resolved_by = :b, updated_at = now()
                        WHERE id = :id AND state = 'OPEN'""")
                .param("b", by).param("id", id).update() == 1;
    }

    /** Xóa chữ khách, tóm tắt và nháp quá {@value #KEEP_DAYS} ngày. */
    public int purgeOld() {
        return jdbc.sql("""
                        UPDATE warranty_case SET inbound_text = NULL, summary = NULL, draft_text = NULL
                        WHERE (inbound_text IS NOT NULL OR summary IS NOT NULL OR draft_text IS NOT NULL) AND created_at < now() - make_interval(days => :d)""")
                .param("d", KEEP_DAYS).update();
    }
}
