package vn.shop.crm.contact;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneHash;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Sổ cơ sở liên hệ và danh sách từ chối (mục 3.2 file 03).
 *
 * <p>Quy tắc nền: không có dòng cơ sở liên hệ thì KHÔNG được liên hệ. Một khách chỉ nằm trong danh sách mời khi
 * đồng thời: phân loại {@code ONE_TIME}, không bị gắn "cần kiểm tra", có cơ sở liên hệ {@code ELIGIBLE}, và SĐT
 * không nằm trong danh sách từ chối. Khách {@code REPEAT} không bao giờ vào danh sách này, kể cả khi có dòng
 * {@code ELIGIBLE} (điều kiện nằm ngay trong truy vấn, không dựa vào việc "đừng duyệt").
 */
@Service
public class ContactService {

    public static final String PURPOSE = "GROUP_INVITE_POSTSALE";
    /** D3: chủ cửa hàng tự chấp nhận trách nhiệm; ghi rõ vào từng bản ghi để có thể xem lại. */
    public static final String STATEMENT = "Chủ cửa hàng chịu trách nhiệm: khách đã mua và nhận hàng; liên hệ chăm sóc "
            + "sau bán hàng, không phải quảng cáo sản phẩm. Chưa có xác nhận pháp lý riêng.";

    private static final String SUPPRESSED_HASH = "encode(sha256(convert_to(c.phone, 'UTF8')), 'hex')";
    /** Điều kiện của "ứng viên": một lần mua, không cần kiểm tra, chưa từ chối, chưa có dòng cơ sở liên hệ nào. */
    private static final String CANDIDATE_WHERE = """
            c.segment = 'ONE_TIME' AND c.needs_review = FALSE
            AND NOT EXISTS (SELECT 1 FROM suppression s WHERE s.phone_hash = %s)
            AND NOT EXISTS (SELECT 1 FROM contact_basis b WHERE b.customer_id = c.id AND b.purpose = '%s')
            """.formatted(SUPPRESSED_HASH, PURPOSE);

    public record Stats(long oneTime, long eligible, long candidates, long blocked, long optedOut, long erased,
                        long oneTimeNeedsReview) {
    }

    public record InviteRow(long id, String phone, String name, String address, LocalDateTime lastOrderOn,
                            String evidenceRef, LocalDateTime approvedAt, String province) {
        public String provinceLabel() {
            return vn.shop.crm.geo.VnGeo.byCode(province).map(vn.shop.crm.geo.Province::name).orElse("Chưa rõ");
        }
    }

    public record Basis(String basis, ContactStatus status, String evidenceRef, String statement, String note,
                        String recordedBy, LocalDateTime recordedAt) {
    }

    public record Event(String event, String detail, String recordedBy, LocalDateTime recordedAt) {
    }

    public record InvitePage(List<InviteRow> items, long total, int page, int size) {
        public int totalPages() {
            return (int) Math.max(1, Math.ceil(total / (double) size));
        }

        public boolean hasPrevious() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < totalPages();
        }
    }

    private final JdbcClient jdbc;

    public ContactService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tên người đang đăng nhập, để ghi "ai làm". */
    public static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    // ---------- thống kê & danh sách ----------

    public Stats stats() {
        long oneTime = count("SELECT count(*) FROM customer c WHERE c.segment = 'ONE_TIME'");
        long eligible = count(inviteCountSql());
        long candidates = count("SELECT count(*) FROM customer c WHERE " + CANDIDATE_WHERE);
        long blocked = count("SELECT count(*) FROM contact_basis WHERE purpose = '" + PURPOSE
                + "' AND status = 'BLOCKED'");
        long optedOut = count("SELECT count(*) FROM suppression WHERE reason = 'OPT_OUT'");
        long erased = count("SELECT count(*) FROM suppression WHERE reason = 'ERASED'");
        long review = count("SELECT count(*) FROM customer c WHERE c.segment = 'ONE_TIME' AND c.needs_review");
        return new Stats(oneTime, eligible, candidates, blocked, optedOut, erased, review);
    }

    /** Trạng thái duyệt mời và lời mời của một khách, dạng chữ tiếng Việt để hiện trong danh sách khách. */
    public record InviteState(String approval, String invitation) {
    }

    /**
     * Gộp trạng thái duyệt mời (cơ sở liên hệ, sổ từ chối) và lời mời của nhiều khách bằng vài truy vấn, không truy vấn
     * từng dòng. Khách không có trong kết quả thì chưa duyệt và chưa được mời.
     */
    public java.util.Map<Long, InviteState> inviteStates(java.util.Collection<Long> ids) {
        java.util.Map<Long, InviteState> result = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        java.util.Map<Long, String> basis = new java.util.HashMap<>();
        jdbc.sql("SELECT customer_id, status FROM contact_basis WHERE purpose = :p AND customer_id IN (:ids)")
                .param("p", PURPOSE).param("ids", ids)
                .query((rs, i) -> java.util.Map.entry(rs.getLong("customer_id"), rs.getString("status")))
                .list().forEach(e -> basis.put(e.getKey(), e.getValue()));
        java.util.Set<Long> suppressed = new java.util.HashSet<>(jdbc.sql(
                        "SELECT c.id FROM customer c WHERE c.id IN (:ids) AND EXISTS (SELECT 1 FROM suppression s "
                                + "WHERE s.phone_hash = " + SUPPRESSED_HASH + ")")
                .param("ids", ids).query(Long.class).list());
        java.util.Map<Long, String> invites = new java.util.HashMap<>();
        jdbc.sql("SELECT customer_id, status FROM invitation WHERE customer_id IN (:ids)").param("ids", ids)
                .query((rs, i) -> java.util.Map.entry(rs.getLong("customer_id"), rs.getString("status")))
                .list().forEach(e -> invites.put(e.getKey(), e.getValue()));
        for (Long id : ids) {
            String approval;
            if (suppressed.contains(id)) {
                approval = "Không được liên hệ";
            } else if (!basis.containsKey(id)) {
                approval = "Chưa duyệt";
            } else {
                approval = switch (basis.get(id)) {
                    case "ELIGIBLE" -> "Đã duyệt";
                    case "BLOCKED" -> "Bị chặn";
                    default -> "Không được liên hệ";
                };
            }
            String invitation = switch (invites.getOrDefault(id, "")) {
                case "READY" -> "Chưa gửi";
                case "SENT" -> "Đã gửi";
                case "CLICKED" -> "Đã bấm link";
                case "FRIENDED" -> "Đã kết bạn";
                case "JOINED" -> "Đã vào nhóm";
                case "CANCELLED" -> "Đã hủy";
                default -> "Chưa mời";
            };
            result.put(id, new InviteState(approval, invitation));
        }
        return result;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String inviteFrom() {
        return """
                FROM customer c
                JOIN contact_basis b ON b.customer_id = c.id AND b.purpose = '%s' AND b.status = 'ELIGIBLE'
                WHERE c.segment = 'ONE_TIME' AND c.needs_review = FALSE
                  AND NOT EXISTS (SELECT 1 FROM suppression s WHERE s.phone_hash = %s)
                """.formatted(PURPOSE, SUPPRESSED_HASH);
    }

    private static String inviteCountSql() {
        return "SELECT count(*) " + inviteFrom();
    }

    /** Danh sách "đủ điều kiện mời". Đây là nguồn DUY NHẤT cho mọi chiến dịch mời (mốc 1C trở đi). */
    public InvitePage inviteList(int page, int size) {
        int p = Math.max(1, page);
        long total = count(inviteCountSql());
        List<InviteRow> items = jdbc.sql("""
                        SELECT c.id, c.phone, c.name, c.address, c.last_order_on, b.evidence_ref, b.recorded_at, c.province
                        """ + inviteFrom() + " ORDER BY b.recorded_at, c.id LIMIT :limit OFFSET :offset")
                .param("limit", size).param("offset", (long) (p - 1) * size)
                .query((rs, i) -> new InviteRow(rs.getLong("id"), rs.getString("phone"), rs.getString("name"),
                        rs.getString("address"), rs.getObject("last_order_on", LocalDateTime.class),
                        rs.getString("evidence_ref"), local(rs.getObject("recorded_at", OffsetDateTime.class)),
                        rs.getString("province")))
                .list();
        return new InvitePage(items, total, p, size);
    }

    public record Invitee(long id, String phone, String name) {
    }

    /**
     * Khách đủ điều kiện mời mà CHƯA từng được mời (mỗi khách một lần). Đây là cửa duy nhất để tạo lời mời: dùng
     * đúng điều kiện của danh sách mời, nên khách REPEAT, cần kiểm tra, bị chặn hay từ chối không bao giờ lọt vào.
     */
    public List<Invitee> eligibleNotInvited(int limit) {
        return eligibleNotInvited(limit, null);
    }

    /**
     * Như {@link #eligibleNotInvited(int)} nhưng chỉ lấy khách ở một khu vực (mã {@code R:NAM}, {@code S:...},
     * {@code P:...}, hoặc {@code U} = chưa rõ khu vực). Mã rỗng = mọi nơi. Khách chưa rõ tỉnh không lọt vào khi đã chọn
     * một khu vực cụ thể.
     */
    public List<Invitee> eligibleNotInvited(int limit, String area) {
        StringBuilder sql = new StringBuilder("SELECT c.id, c.phone, c.name " + inviteFrom()
                + " AND NOT EXISTS (SELECT 1 FROM invitation i WHERE i.customer_id = c.id)");
        var areaCodes = appendArea(sql, area);
        sql.append(" ORDER BY b.recorded_at, c.id LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (areaCodes != null) {
            spec = spec.param("areaCodes", areaCodes);
        }
        return spec.query((rs, i) -> new Invitee(rs.getLong("id"), rs.getString("phone"), rs.getString("name")))
                .list();
    }

    /** Thêm điều kiện khu vực vào câu truy vấn; trả danh sách mã tỉnh cần gán vào {@code :areaCodes} (hoặc null). */
    private static List<String> appendArea(StringBuilder sql, String area) {
        if (area == null || area.isBlank()) {
            return null;
        }
        if (vn.shop.crm.geo.GeoArea.UNKNOWN.equals(area)) {
            sql.append(" AND c.province IS NULL");
            return null;
        }
        var parsed = vn.shop.crm.geo.GeoArea.parse(area)
                .orElseThrow(() -> new vn.shop.crm.common.ImportException("Khu vực không hợp lệ."));
        sql.append(" AND c.province IN (:areaCodes)");
        return vn.shop.crm.geo.VnGeo.in(parsed).stream().map(vn.shop.crm.geo.Province::code).toList();
    }

    /** Số khách đủ điều kiện mời mà chưa từng được mời, ở một khu vực (rỗng = mọi nơi). */
    public long countEligibleNotInvited(String area) {
        StringBuilder sql = new StringBuilder("SELECT count(*) " + inviteFrom()
                + " AND NOT EXISTS (SELECT 1 FROM invitation i WHERE i.customer_id = c.id)");
        var areaCodes = appendArea(sql, area);
        var spec = jdbc.sql(sql.toString());
        if (areaCodes != null) {
            spec = spec.param("areaCodes", areaCodes);
        }
        return spec.query(Long.class).single();
    }

    /** Khách này CÒN đủ điều kiện mời ngay lúc này không (kiểm lại ở thời điểm khách bấm link và trước khi gửi). */
    public boolean stillEligible(long customerId) {
        return count("SELECT count(*) " + inviteFrom() + " AND c.id = " + customerId) > 0;
    }

    // ---------- duyệt / chặn / từ chối ----------

    /** Duyệt MỘT lần cho mọi khách "ứng viên". Trả số khách vừa được duyệt. */
    @Transactional
    public int approveAllCandidates() {
        return jdbc.sql(approveSql("")).param("statement", STATEMENT).param("by", actor()).update();
    }

    /** Duyệt một khách; báo lỗi rõ ràng nếu khách không đủ điều kiện. */
    @Transactional
    public void approve(long customerId) {
        int n = jdbc.sql(approveSql(" AND c.id = :id")).param("id", customerId)
                .param("statement", STATEMENT).param("by", actor()).update();
        if (n == 0) {
            throw new ImportException("Khách này chưa đủ điều kiện để duyệt: chỉ khách mua 1 lần, không cần kiểm tra, "
                    + "chưa từ chối và chưa được duyệt/chặn trước đó.");
        }
    }

    private static String approveSql(String extraWhere) {
        return """
                WITH ins AS (
                    INSERT INTO contact_basis (customer_id, purpose, basis, status, evidence_ref, statement, recorded_by)
                    SELECT c.id, '%s', 'TRANSACTION_POSTSALE', 'ELIGIBLE',
                           (SELECT sh.carrier || ':' || sh.tracking_no FROM shipment sh
                             WHERE sh.receiver_phone = c.phone AND sh.canonical_status = 'DELIVERED'
                             ORDER BY sh.created_on DESC NULLS LAST, sh.id DESC LIMIT 1),
                           :statement, :by
                    FROM customer c WHERE %s %s
                    ON CONFLICT (customer_id, purpose) DO NOTHING
                    RETURNING customer_id)
                INSERT INTO contact_event (customer_id, phone_tail, event, detail, recorded_by)
                SELECT ins.customer_id, '***' || right(c.phone, 3), 'BASIS_APPROVED', 'Duyệt cơ sở liên hệ sau bán hàng', :by
                FROM ins JOIN customer c ON c.id = ins.customer_id
                """.formatted(PURPOSE, CANDIDATE_WHERE, extraWhere);
    }

    /** Chặn thủ công một khách (ví dụ khách phàn nàn). Khách đã từ chối thì giữ nguyên. */
    @Transactional
    public void block(long customerId, String note) {
        String phone = phoneOf(customerId);
        jdbc.sql("""
                        INSERT INTO contact_basis (customer_id, purpose, basis, status, statement, note, recorded_by)
                        VALUES (:id, :purpose, 'NONE', 'BLOCKED', :statement, :note, :by)
                        ON CONFLICT (customer_id, purpose) DO UPDATE
                           SET status = 'BLOCKED', note = EXCLUDED.note, recorded_by = EXCLUDED.recorded_by,
                               recorded_at = now()
                           WHERE contact_basis.status <> 'OPTED_OUT'""")
                .param("id", customerId).param("purpose", PURPOSE).param("statement", "Chặn thủ công")
                .param("note", blankToNull(note)).param("by", actor()).update();
        cancelInvitations(customerId, "Khách bị chặn thủ công");
        event(customerId, phone, "BLOCKED", blankToNull(note));
    }

    /** Bỏ chặn: xóa dòng chặn, khách quay về trạng thái "chưa duyệt" (phải duyệt lại). Không đụng tới từ chối. */
    @Transactional
    public void unblock(long customerId) {
        String phone = phoneOf(customerId);
        int n = jdbc.sql("DELETE FROM contact_basis WHERE customer_id = :id AND purpose = :p AND status = 'BLOCKED'")
                .param("id", customerId).param("p", PURPOSE).update();
        if (n > 0) {
            event(customerId, phone, "UNBLOCKED", "Về trạng thái chưa duyệt");
        }
    }

    /**
     * Khách yêu cầu ngừng liên hệ: ghi vào danh sách từ chối (vĩnh viễn, chặn cả sau khi nhập lại file) và đặt
     * cơ sở liên hệ thành {@code OPTED_OUT}. Không có đường nào trong ứng dụng để gỡ từ chối.
     */
    @Transactional
    public void suppress(long customerId, String source) {
        suppressAs(customerId, source, actor());
    }

    /** Như {@link #suppress} nhưng ghi rõ "ai làm" (ví dụ khách tự bấm "ngừng nhận tin" trên trang mời). */
    @Transactional
    public void suppressAs(long customerId, String source, String by) {
        String phone = phoneOf(customerId);
        suppressPhone(phone, "OPT_OUT", source, by);
        jdbc.sql("""
                        INSERT INTO contact_basis (customer_id, purpose, basis, status, statement, note, recorded_by)
                        VALUES (:id, :purpose, 'NONE', 'OPTED_OUT', :statement, :note, :by)
                        ON CONFLICT (customer_id, purpose) DO UPDATE
                           SET basis = 'NONE', status = 'OPTED_OUT', note = EXCLUDED.note,
                               recorded_by = EXCLUDED.recorded_by, recorded_at = now()""")
                .param("id", customerId).param("purpose", PURPOSE).param("statement", "Khách yêu cầu ngừng liên hệ")
                .param("note", blankToNull(source)).param("by", by).update();
        cancelInvitations(customerId, "Khách yêu cầu ngừng liên hệ");
        eventAs(customerId, phone, "OPTED_OUT", blankToNull(source), by);
    }

    /** Tên người ghi trong sổ khi trợ lý AI tự ghi từ chối (để hoàn tác chỉ gỡ đúng phần AI làm). */
    public static final String AI_ACTOR = "tro-ly-ai";

    /**
     * Những gì cần để HOÀN TÁC một lần trợ lý AI tự ghi từ chối: có dòng cơ sở liên hệ từ trước không (và nội dung nó), số điện thoại đã nằm
     * trong danh sách từ chối từ trước chưa (nếu rồi thì hoàn tác không được gỡ).
     */
    public record AiOptOutUndo(boolean suppressionCreated, boolean hadBasis, String basis, String status, String note,
                               String recordedBy, String recordedAt) {
    }

    /** Như {@link #suppressAs} với người ghi là trợ lý AI, và trả thông tin để {@link #undoAiOptOut} đưa mọi thứ về như trước. */
    @Transactional
    public AiOptOutUndo suppressByAi(long customerId, String source) {
        String phone = phoneOf(customerId);
        boolean existed = isSuppressed(phone);
        AiOptOutUndo undo = jdbc.sql("""
                        SELECT basis, status, note, recorded_by, recorded_at FROM contact_basis
                        WHERE customer_id = :id AND purpose = :p""")
                .param("id", customerId).param("p", PURPOSE)
                .query((rs, i) -> new AiOptOutUndo(!existed, true, rs.getString("basis"), rs.getString("status"), rs.getString("note"),
                        rs.getString("recorded_by"), rs.getObject("recorded_at", OffsetDateTime.class).toInstant().toString()))
                .optional().orElse(new AiOptOutUndo(!existed, false, null, null, null, null, null));
        suppressAs(customerId, source, AI_ACTOR);
        return undo;
    }

    /**
     * Gỡ phần trợ lý AI đã ghi: xóa dòng từ chối do AI tạo (không đụng dòng có từ trước hoặc do người ghi), đưa cơ sở liên hệ về như cũ.
     * Lời mời đã bị hủy theo thì do bên gọi khôi phục (chỉ nó biết trạng thái cũ).
     */
    @Transactional
    public void undoAiOptOut(long customerId, AiOptOutUndo u) {
        String phone = phoneOf(customerId);
        if (u.suppressionCreated()) {
            jdbc.sql("DELETE FROM suppression WHERE phone_hash = :h AND reason = 'OPT_OUT' AND recorded_by = :by")
                    .param("h", PhoneHash.of(phone)).param("by", AI_ACTOR).update();
        }
        if (u.hadBasis()) {
            jdbc.sql("""
                            UPDATE contact_basis SET basis = :basis, status = :status, note = :note, recorded_by = :by,
                                   recorded_at = :at
                            WHERE customer_id = :id AND purpose = :p AND recorded_by = :ai""")
                    .param("basis", u.basis()).param("status", u.status()).param("note", u.note()).param("by", u.recordedBy())
                    .param("at", OffsetDateTime.ofInstant(java.time.Instant.parse(u.recordedAt()), java.time.ZoneOffset.UTC))
                    .param("id", customerId).param("p", PURPOSE).param("ai", AI_ACTOR).update();
        } else {
            jdbc.sql("DELETE FROM contact_basis WHERE customer_id = :id AND purpose = :p AND recorded_by = :ai AND status = 'OPTED_OUT'")
                    .param("id", customerId).param("p", PURPOSE).param("ai", AI_ACTOR).update();
        }
        event(customerId, phone, "OPT_OUT_UNDONE", "Hoàn tác: trợ lý AI nhận nhầm");
    }

    /**
     * Hủy lời mời của khách: lời mời chưa gửi thì xóa hẳn, lời mời đã gửi thì giữ lại và đánh dấu CANCELLED để còn
     * dấu vết. Landing page cũng kiểm lại điều kiện khi khách bấm nên link đã gửi không còn dùng được.
     */
    void cancelInvitations(long customerId, String reason) {
        jdbc.sql("DELETE FROM invitation WHERE customer_id = :id AND status = 'READY'")
                .param("id", customerId).update();
        jdbc.sql("""
                        UPDATE invitation SET status = 'CANCELLED', cancelled_reason = :reason
                        WHERE customer_id = :id AND status IN ('SENT', 'CLICKED', 'FRIENDED')""")
                .param("id", customerId).param("reason", reason).update();
    }

    /**
     * Khách tự xin ngừng nhận tin bằng số điện thoại (trang giấy giới thiệu dùng mã QR chung, không có link riêng).
     * Số hợp lệ nào cũng được ghi vào danh sách từ chối (chỉ lưu băm); nếu số đó đã là khách thì xử lý như
     * "ngừng liên hệ" đầy đủ. Trả lỗi tiếng Việt nếu số không hợp lệ.
     */
    @Transactional
    public void optOutByPhone(String rawPhone, String source) {
        String phone = vn.shop.crm.common.PhoneNormalizer.toE164(rawPhone)
                .orElseThrow(() -> new ImportException("Số điện thoại chưa đúng. Hãy nhập số 10 chữ số, ví dụ 0912345678."));
        Optional<Long> customer = jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", phone)
                .query(Long.class).optional();
        if (customer.isPresent()) {
            suppressAs(customer.get(), source, "khach-tu-bam");
        } else {
            suppressPhone(phone, "OPT_OUT", source, "khach-tu-bam");
        }
    }

    /** Ghi SĐT vào danh sách từ chối (chỉ lưu băm). Gọi lặp lại không sao. */
    void suppressPhone(String phone, String reason, String source) {
        suppressPhone(phone, reason, source, actor());
    }

    void suppressPhone(String phone, String reason, String source, String by) {
        jdbc.sql("""
                        INSERT INTO suppression (phone_hash, reason, source, recorded_by)
                        VALUES (:hash, :reason, :source, :by)
                        ON CONFLICT (phone_hash) DO NOTHING""")
                .param("hash", PhoneHash.of(phone)).param("reason", reason)
                .param("source", blankToNull(source)).param("by", by).update();
    }

    public boolean isSuppressed(String phone) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM suppression WHERE phone_hash = :h)")
                .param("h", PhoneHash.of(phone)).query(Boolean.class).single();
    }

    // ---------- xem ----------

    public Optional<Basis> basisOf(long customerId) {
        return jdbc.sql("""
                        SELECT basis, status, evidence_ref, statement, note, recorded_by, recorded_at
                        FROM contact_basis WHERE customer_id = :id AND purpose = :p""")
                .param("id", customerId).param("p", PURPOSE)
                .query((rs, i) -> new Basis(rs.getString("basis"), ContactStatus.valueOf(rs.getString("status")),
                        rs.getString("evidence_ref"), rs.getString("statement"), rs.getString("note"),
                        rs.getString("recorded_by"), local(rs.getObject("recorded_at", OffsetDateTime.class))))
                .optional();
    }

    public List<Event> events(long customerId) {
        return jdbc.sql("""
                        SELECT event, detail, recorded_by, recorded_at FROM contact_event
                        WHERE customer_id = :id ORDER BY recorded_at DESC, id DESC""")
                .param("id", customerId)
                .query((rs, i) -> new Event(rs.getString("event"), rs.getString("detail"), rs.getString("recorded_by"),
                        local(rs.getObject("recorded_at", OffsetDateTime.class))))
                .list();
    }

    public String note(long customerId) {
        return jdbc.sql("SELECT note FROM customer_note WHERE customer_id = :id").param("id", customerId)
                .query(String.class).optional().orElse("");
    }

    /** Ghi chú của nhiều khách bằng một truy vấn (khách không có ghi chú thì không có trong kết quả). */
    public java.util.Map<Long, String> notes(java.util.Collection<Long> ids) {
        java.util.Map<Long, String> result = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT customer_id, note FROM customer_note WHERE customer_id IN (:ids)").param("ids", ids)
                .query((rs, i) -> java.util.Map.entry(rs.getLong("customer_id"), rs.getString("note")))
                .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    @Transactional
    public void saveNote(long customerId, String note) {
        phoneOf(customerId); // báo lỗi nếu khách không tồn tại
        String text = note == null ? "" : note.strip();
        if (text.length() > 2000) {
            throw new ImportException("Ghi chú quá dài (tối đa 2000 ký tự).");
        }
        if (text.isEmpty()) {
            jdbc.sql("DELETE FROM customer_note WHERE customer_id = :id").param("id", customerId).update();
        } else {
            jdbc.sql("""
                            INSERT INTO customer_note (customer_id, note) VALUES (:id, :note)
                            ON CONFLICT (customer_id) DO UPDATE SET note = EXCLUDED.note, updated_at = now()""")
                    .param("id", customerId).param("note", text).update();
        }
    }

    // ---------- nội bộ ----------

    public String phoneOf(long customerId) {
        return jdbc.sql("SELECT phone FROM customer WHERE id = :id").param("id", customerId)
                .query(String.class).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy khách."));
    }

    public void event(Long customerId, String phone, String event, String detail) {
        eventAs(customerId, phone, event, detail, actor());
    }

    void eventAs(Long customerId, String phone, String event, String detail, String by) {
        jdbc.sql("""
                        INSERT INTO contact_event (customer_id, phone_tail, event, detail, recorded_by)
                        VALUES (:id, :tail, :event, :detail, :by)""")
                .param("id", customerId).param("tail", PhoneHash.tail(phone)).param("event", event)
                .param("detail", detail).param("by", by).update();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDateTime();
    }
}
