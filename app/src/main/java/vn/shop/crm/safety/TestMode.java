package vn.shop.crm.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneHash;
import vn.shop.crm.common.PhoneNormalizer;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CHẾ ĐỘ THỬ: nguồn sự thật duy nhất cho câu hỏi "hệ thống có được chạm tới người này trên Zalo không".
 *
 * <p>Ở chế độ thử (mặc định, xem {@link ZaloSafetyProperties}) chỉ những số điện thoại trong danh sách thử ({@code zalo_test_number}) mới
 * được liên hệ. Bảo vệ ba lớp, lớp sau là lưới an toàn cho lớp trước:
 * <ol>
 *   <li>Nguồn danh sách: {@link #onlyTestSql} chèn vào mọi truy vấn chọn khách (danh sách mời, chiến dịch, tự nhận khách mới, hàng chờ gửi,
 *       hàng chờ thêm nhóm, tạo nhóm) nên khách thật không bao giờ được chọn.</li>
 *   <li>{@code ContactService.mayContact} cho các chỗ sắp gọi Zalo cho một khách.</li>
 *   <li>{@link vn.shop.crm.zalo.SafeZaloLink}: đường duy nhất tới Zalo kiểm lại số/mã Zalo ngay trước khi gọi cầu nối và từ chối nếu
 *       không thuộc số thử; tin riêng của người không phải số thử bị bỏ ngay, không tới AI.</li>
 * </ol>
 * Chế độ chạy thật không hạn chế gì thêm (hành vi như trước). Danh sách thử chỉ chứa số của chủ shop hoặc người đã đồng ý nhận tin thử.
 * Một số đã là khách trong hệ thống hoặc nằm trong danh sách từ chối thì KHÔNG được khai báo là số thử.
 */
@Service("testMode")
public class TestMode {

    private static final Logger log = LoggerFactory.getLogger(TestMode.class);
    static final int MAX_NUMBERS = 30;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Truy vấn con lấy các số thử, dùng chung cho mọi bộ lọc. */
    static final String ALLOWED = "(SELECT n.phone FROM zalo_test_number n)";

    public record Entry(String phone, String note, String createdBy, LocalDateTime createdAt, boolean customer) {
    }

    private final JdbcClient jdbc;
    private final ZaloSafetyProperties props;
    private final AtomicLong blocked = new AtomicLong();

    public TestMode(JdbcClient jdbc, ZaloSafetyProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Chạy thật: không hạn chế. Chỉ bật bằng biến môi trường trên máy chủ. */
    public boolean live() {
        return props.live();
    }

    /** Chế độ thử: chỉ được liên hệ số trong danh sách thử. */
    public boolean testOnly() {
        return !props.live();
    }

    // ---------- bộ lọc ----------

    /** Đoạn SQL " AND cột IN (số thử)" khi ở chế độ thử, rỗng khi chạy thật. {@code phoneColumn} là tên cột SĐT E.164 (ví dụ "c.phone"). */
    public String onlyTestSql(String phoneColumn) {
        return live() ? "" : " AND " + phoneColumn + " IN " + ALLOWED;
    }

    /** Như {@link #onlyTestSql} nhưng cho cột MÃ KHÁCH (ví dụ "k.customer_id"): chỉ giữ các dòng của khách có số thử. */
    public String onlyTestCustomerSql(String customerIdColumn) {
        return live() ? "" : " AND " + customerIdColumn + " IN (SELECT c.id FROM customer c WHERE c.phone IN " + ALLOWED + ")";
    }

    /** Số điện thoại bất kỳ dạng (0912..., +8491..., 8491...) về E.164; rỗng nếu không hợp lệ. */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.replaceAll("[^0-9+]", "");
        if (!s.startsWith("+") && s.startsWith("84")) {
            Optional<String> viaPlus = PhoneNormalizer.toE164("+" + s);
            if (viaPlus.isPresent()) {
                return viaPlus;
            }
        }
        return PhoneNormalizer.toE164(s);
    }

    /** Số này có được liên hệ không (chạy thật: luôn có; chế độ thử: chỉ khi nằm trong danh sách thử). Số không hiểu được thì KHÔNG. */
    public boolean phoneAllowed(String raw) {
        if (live()) {
            return true;
        }
        return normalize(raw).map(this::inAllowList).orElse(false);
    }

    /** Khách này có được liên hệ không. */
    public boolean customerAllowed(long customerId) {
        if (live()) {
            return true;
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM customer c WHERE c.id = :id AND c.phone IN " + ALLOWED + ")")
                .param("id", customerId).query(Boolean.class).single();
    }

    /**
     * Mã Zalo này có thuộc một số thử không. Mã chỉ được biết qua lời mời của một khách (mã tra lúc gửi Tin 1 hoặc mã người kết bạn); mã lạ không thuộc ai thì KHÔNG (thất bại kín).
     */
    public boolean uidAllowed(String uid) {
        if (live()) {
            return true;
        }
        if (uid == null || uid.isBlank()) {
            return false;
        }
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM invitation i JOIN customer c ON c.id = i.customer_id
                                       WHERE (i.zalo_uid = :u OR i.zalo_target_uid = :u) AND c.phone IN %s)"""
                        .formatted(ALLOWED))
                .param("u", uid).query(Boolean.class).single();
    }

    /** Mã nhóm Zalo này là nhóm Adh đã ghi (do chính hệ thống tạo hoặc người gán); ở chế độ chạy thật thì luôn đúng. */
    public boolean ownGroup(String zaloGroupId) {
        if (live()) {
            return true;
        }
        if (zaloGroupId == null || zaloGroupId.isBlank()) {
            return false;
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM zalo_group WHERE zalo_group_id = :g)").param("g", zaloGroupId)
                .query(Boolean.class).single();
    }

    /**
     * Mã Zalo này có chắc chắn KHÔNG phải của một khách THẬT không (dùng để loại tin riêng). Ở chế độ thử, mã không thuộc số thử bị coi là
     * khách thật hoặc người lạ: không đọc, không đưa cho AI.
     */
    public boolean inboundAllowed(String uid) {
        return uidAllowed(uid);
    }

    /** Ghi nhận một lần bị chặn (chỉ đếm và ghi loại việc, không ghi số, mã Zalo hay tên). */
    public void noteBlocked(String what) {
        long n = blocked.incrementAndGet();
        if (n <= 5 || n % 100 == 0) {
            log.warn("Chế độ thử đã chặn một lần {} tới số không có trong danh sách thử (lần thứ {})", what, n);
        }
    }

    public long blockedCount() {
        return blocked.get();
    }

    private boolean inAllowList(String e164) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM zalo_test_number WHERE phone = :p)").param("p", e164).query(Boolean.class).single();
    }

    // ---------- danh sách thử ----------

    public List<Entry> numbers() {
        return jdbc.sql("""
                        SELECT n.phone, n.note, n.created_by, n.created_at,
                               EXISTS (SELECT 1 FROM customer c WHERE c.phone = n.phone) AS is_customer
                        FROM zalo_test_number n ORDER BY n.created_at, n.phone""")
                .query((rs, i) -> new Entry(rs.getString("phone"), rs.getString("note"), rs.getString("created_by"),
                        rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(VN).toLocalDateTime(),
                        rs.getBoolean("is_customer"))).list();
    }

    /** Số khách thật (không thuộc số thử) đang được chế độ thử bảo vệ. 0 khi chạy thật (không bảo vệ). */
    public long protectedCustomers() {
        if (live()) {
            return 0;
        }
        return jdbc.sql("SELECT count(*) FROM customer c WHERE c.phone NOT IN " + ALLOWED).query(Long.class).single();
    }

    /**
     * Khai báo một số thử. Từ chối số không hợp lệ, số đã có, quá nhiều số, số đã là khách trong hệ thống (có thể là khách thật) và số trong
     * danh sách từ chối. Muốn thử "khách giả", hãy khai báo số TRƯỚC rồi mới nhập đơn giả của số đó.
     */
    public void add(String raw, String note, String by) {
        String phone = normalize(raw).orElseThrow(() -> new ImportException("Số điện thoại \"" + (raw == null ? "" : raw.strip()) + "\" không hợp lệ."));
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 100) {
            throw new ImportException("Ghi chú quá dài (tối đa 100 ký tự).");
        }
        if (inAllowList(phone)) {
            throw new ImportException("Số này đã có trong danh sách thử.");
        }
        if (numbers().size() >= MAX_NUMBERS) {
            throw new ImportException("Danh sách thử chỉ nên có tối đa " + MAX_NUMBERS + " số.");
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM customer WHERE phone = :p)").param("p", phone).query(Boolean.class).single()) {
            throw new ImportException("Số này đã là một khách trong hệ thống nên không thể khai báo là số thử (có thể là khách thật). "
                    + "Nếu đây là số của chính bạn từ đơn giả đã nhập, hãy xóa khách đó (Xóa dữ liệu khách) rồi dùng số khác.");
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM suppression WHERE phone_hash = :h)").param("h", PhoneHash.of(phone)).query(Boolean.class).single()) {
            throw new ImportException("Số này nằm trong danh sách từ chối hoặc đã bị xóa theo yêu cầu, nên không dùng làm số thử.");
        }
        jdbc.sql("INSERT INTO zalo_test_number (phone, note, created_by) VALUES (:p, :n, :by)")
                .param("p", phone).param("n", cleanNote, java.sql.Types.VARCHAR).param("by", by).update();
    }

    public void remove(String raw, String by) {
        String phone = normalize(raw).orElseThrow(() -> new ImportException("Số điện thoại không hợp lệ."));
        int n = jdbc.sql("DELETE FROM zalo_test_number WHERE phone = :p").param("p", phone).update();
        if (n == 0) {
            throw new ImportException("Số này không có trong danh sách thử.");
        }
        log.info("Đã xóa một số khỏi danh sách thử (bởi {})", by);
    }
}
