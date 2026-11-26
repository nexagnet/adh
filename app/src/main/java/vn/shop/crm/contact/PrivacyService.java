package vn.shop.crm.contact;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.CsvOut;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneHash;
import vn.shop.crm.common.PhoneNormalizer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Quyền dữ liệu của MỘT khách: xem, xuất, ngừng liên hệ, xóa; kèm bộ đếm hạn phản hồi.
 *
 * <p>Xóa = xóa khách, mọi vận đơn của khách, mọi dòng lỗi nhập có chứa SĐT đó, các lô nhập chưa xác nhận (còn giữ
 * file gốc); chỉ giữ lại BĂM của SĐT trong danh sách "đã xóa" để không nhập lại và không bao giờ liên hệ lại, cùng
 * nhật ký chỉ có 3 số cuối. Hạn phản hồi theo loại yêu cầu (theo thiết kế đã duyệt): xem, xuất, ngừng liên hệ trong
 * {@code app.privacy.quick-due-working-days} ngày làm việc (mặc định 2, chỉ bỏ qua thứ Bảy và Chủ nhật, chưa tính
 * ngày lễ); xóa trong {@code app.privacy.erase-due-days} ngày (mặc định 20). Các mức này là nguồn phụ, CHƯA có xác nhận
 * pháp lý, cần đối chiếu Công báo.
 */
@Service
public class PrivacyService {

    public enum Kind {
        ACCESS("Xem dữ liệu"),
        EXPORT("Xuất dữ liệu"),
        STOP_CONTACT("Ngừng liên hệ"),
        ERASE("Xóa dữ liệu");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record RequestRow(long id, Kind kind, Long activeCustomerId, String phoneTail, LocalDate receivedOn, LocalDate dueOn,
                             LocalDate doneOn, String note, boolean overdue, long daysLeft) {
        public boolean open() {
            return doneOn == null;
        }
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcClient jdbc;
    private final ContactService contacts;
    private final int quickWorkingDays;
    private final int eraseDays;

    public PrivacyService(JdbcClient jdbc, ContactService contacts,
                          @Value("${app.privacy.quick-due-working-days:2}") int quickWorkingDays,
                          @Value("${app.privacy.erase-due-days:20}") int eraseDays) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.quickWorkingDays = quickWorkingDays;
        this.eraseDays = eraseDays;
    }

    public int quickWorkingDays() {
        return quickWorkingDays;
    }

    public int eraseDays() {
        return eraseDays;
    }

    /** Hạn phản hồi của một yêu cầu, tính từ ngày khách gửi. */
    public LocalDate dueDate(Kind kind, LocalDate received) {
        if (kind == Kind.ERASE) {
            return received.plusDays(eraseDays);
        }
        LocalDate due = received;
        int left = quickWorkingDays;
        while (left > 0) {
            due = due.plusDays(1);
            if (due.getDayOfWeek() != java.time.DayOfWeek.SATURDAY && due.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return due;
    }

    /** Ghi nhận yêu cầu mới theo số điện thoại khách gửi cho cửa hàng (trang Yêu cầu của khách). */
    @Transactional
    public long openRequestByPhone(String phoneText, Kind kind, LocalDate receivedOn, String note) {
        var phone = PhoneNormalizer.firstValid(phoneText == null ? "" : phoneText);
        if (phone.isEmpty()) {
            throw new ImportException("Số điện thoại không hợp lệ.");
        }
        Long id = jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", phone.get())
                .query(Long.class).optional().orElse(null);
        if (id == null) {
            throw new ImportException("Không tìm thấy khách có số điện thoại này. Có thể khách đã bị xóa dữ liệu "
                    + "hoặc chưa từng được nhập.");
        }
        return openRequest(id, kind, receivedOn, note);
    }

    // ---------- yêu cầu & hạn phản hồi ----------

    /** Ghi nhận một yêu cầu MỞ (chưa xử lý) để bộ đếm hạn chạy. */
    @Transactional
    public long openRequest(long customerId, Kind kind, LocalDate receivedOn, String note) {
        String phone = contacts.phoneOf(customerId);
        LocalDate received = receivedOn == null ? LocalDate.now() : receivedOn;
        long id = jdbc.sql("""
                        INSERT INTO data_request (kind, customer_id, phone_tail, received_on, due_on, note, recorded_by)
                        VALUES (:kind, :cid, :tail, :received, :due, :note, :by) RETURNING id""")
                .param("kind", kind.name()).param("cid", customerId).param("tail", PhoneHash.tail(phone))
                .param("received", received).param("due", dueDate(kind, received))
                .param("note", blankToNull(note)).param("by", ContactService.actor())
                .query(Long.class).single();
        contacts.event(customerId, phone, "REQUEST_OPENED", kind.label());
        return id;
    }

    public List<RequestRow> requests(boolean onlyOpen) {
        LocalDate today = LocalDate.now();
        return jdbc.sql("""
                        SELECT r.id, r.kind, c.id AS active_customer_id, r.phone_tail, r.received_on, r.due_on, r.done_on, r.note
                        FROM data_request r LEFT JOIN customer c ON c.id = r.customer_id
                        """ + (onlyOpen ? "WHERE done_on IS NULL " : "")
                        + "ORDER BY (r.done_on IS NULL) DESC, r.due_on, r.id DESC")
                .query((rs, i) -> {
                    LocalDate due = rs.getObject("due_on", LocalDate.class);
                    LocalDate done = rs.getObject("done_on", LocalDate.class);
                    long left = java.time.temporal.ChronoUnit.DAYS.between(today, due);
                    return new RequestRow(rs.getLong("id"), Kind.valueOf(rs.getString("kind")),
                            rs.getObject("active_customer_id", Long.class), rs.getString("phone_tail"),
                            rs.getObject("received_on", LocalDate.class), due, done,
                            rs.getString("note"), done == null && left < 0, left);
                }).list();
    }

    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM data_request WHERE done_on IS NULL").query(Long.class).single();
    }

    public long overdueCount() {
        return jdbc.sql("SELECT count(*) FROM data_request WHERE done_on IS NULL AND due_on < current_date")
                .query(Long.class).single();
    }

    /** Ghi nhận chủ shop đã phản hồi đúng yêu cầu xem, kể cả khi hồ sơ khách đã bị xóa. */
    @Transactional
    public boolean completeAccess(long requestId) {
        int completed = jdbc.sql("""
                        WITH closed AS (
                            UPDATE data_request SET done_on = current_date
                            WHERE id = :id AND kind = 'ACCESS' AND done_on IS NULL
                            RETURNING id, customer_id, phone_tail
                        )
                        INSERT INTO contact_event (customer_id, phone_tail, event, detail, recorded_by)
                        SELECT customer_id, phone_tail, 'ACCESS_COMPLETED', 'Yêu cầu #' || id, :by FROM closed
                        """).param("id", requestId).param("by", ContactService.actor()).update();
        if (completed == 1) {
            return true;
        }
        boolean alreadyDone = jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM data_request WHERE id = :id AND kind = 'ACCESS' AND done_on IS NOT NULL)
                        """).param("id", requestId).query(Boolean.class).single();
        if (!alreadyDone) {
            throw new ImportException("Không tìm thấy yêu cầu xem dữ liệu.");
        }
        return false;
    }

    /** Đóng yêu cầu mở cùng loại của khách; nếu chưa ghi nhận trước thì tạo mới và đóng luôn. */
    private void complete(long customerId, String phone, Kind kind, LocalDate receivedOn, String note) {
        int n = jdbc.sql("""
                        UPDATE data_request SET done_on = current_date
                        WHERE customer_id = :cid AND kind = :kind AND done_on IS NULL""")
                .param("cid", customerId).param("kind", kind.name()).update();
        if (n == 0) {
            LocalDate received = receivedOn == null ? LocalDate.now() : receivedOn;
            jdbc.sql("""
                            INSERT INTO data_request (kind, customer_id, phone_tail, received_on, due_on, done_on, note,
                                                      recorded_by)
                            VALUES (:kind, :cid, :tail, :received, :due, current_date, :note, :by)""")
                    .param("kind", kind.name()).param("cid", customerId).param("tail", PhoneHash.tail(phone))
                    .param("received", received).param("due", dueDate(kind, received))
                    .param("note", blankToNull(note)).param("by", ContactService.actor()).update();
        }
    }

    // ---------- xuất ----------

    /** Toàn bộ dữ liệu về một khách dưới dạng CSV (mỗi vận đơn một dòng, kèm thông tin khách). */
    @Transactional
    public byte[] exportCustomer(long customerId, LocalDate receivedOn) {
        var customer = jdbc.sql("""
                        SELECT phone, name, address, segment, successful_purchases FROM customer WHERE id = :id""")
                .param("id", customerId)
                .query((rs, i) -> new String[]{rs.getString("phone"), rs.getString("name"), rs.getString("address"),
                        rs.getString("segment"), String.valueOf(rs.getInt("successful_purchases"))})
                .optional().orElseThrow(() -> new ImportException("Không tìm thấy khách."));
        String phone = customer[0];
        String basis = contacts.basisOf(customerId).map(b -> b.status().label()).orElse("Chưa duyệt (không được liên hệ)");
        String note = contacts.note(customerId);
        List<List<String>> rows = jdbc.sql("""
                        SELECT carrier, tracking_no, order_ref, raw_status, created_on, receiver_name, receiver_address
                        FROM shipment WHERE receiver_phone = :phone ORDER BY created_on NULLS LAST, id""")
                .param("phone", phone)
                .query((rs, i) -> List.of(
                        PhoneNormalizer.toDisplay(phone), nz(customer[1]), nz(customer[2]), customer[3], customer[4],
                        basis, note, rs.getString("carrier"), rs.getString("tracking_no"), nz(rs.getString("order_ref")),
                        rs.getString("raw_status"),
                        rs.getObject("created_on", java.time.LocalDateTime.class) == null ? ""
                                : rs.getObject("created_on", java.time.LocalDateTime.class).format(DATE),
                        nz(rs.getString("receiver_name")), nz(rs.getString("receiver_address"))))
                .list();
        complete(customerId, phone, Kind.EXPORT, receivedOn, null);
        contacts.event(customerId, phone, "EXPORTED", "Xuất dữ liệu khách");
        return CsvOut.write(List.of("so_dien_thoai", "ten", "dia_chi", "phan_loai", "so_lan_mua_thanh_cong",
                "co_so_lien_he", "ghi_chu", "hang", "ma_van_don", "ma_don_hang", "trang_thai", "ngay_tao",
                "ten_nguoi_nhan_tren_van_don", "dia_chi_tren_van_don"), rows);
    }

    // ---------- ngừng liên hệ / xóa ----------

    @Transactional
    public void stopContact(long customerId, LocalDate receivedOn, String note) {
        String phone = contacts.phoneOf(customerId);
        contacts.suppress(customerId, note == null || note.isBlank() ? "Khách yêu cầu ngừng liên hệ" : note);
        complete(customerId, phone, Kind.STOP_CONTACT, receivedOn, note);
    }

    @Transactional
    public void erase(long customerId, LocalDate receivedOn, String note) {
        String phone = contacts.phoneOf(customerId);
        String digits = phone.replaceAll("\\D", "");
        String last9 = digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;

        contacts.suppressPhone(phone, "ERASED", "Khách yêu cầu xóa dữ liệu");
        // dòng lỗi nhập lưu nguyên dòng gốc (có SĐT): xóa các dòng chứa 9 số cuối của SĐT này
        jdbc.sql("DELETE FROM import_error WHERE raw_row LIKE :pattern").param("pattern", "%" + last9 + "%").update();
        // lô nhập chưa xác nhận còn giữ file gốc có SĐT khách: bỏ hẳn (người dùng tải lại nếu cần)
        jdbc.sql("DELETE FROM import_batch WHERE status <> 'COMMITTED'").update();
        // dòng đối soát tiền của các vận đơn này (chỉ có mã vận đơn và số tiền, nhưng vẫn là vận đơn của khách)
        jdbc.sql("""
                        DELETE FROM settlement_line WHERE tracking_no IN
                            (SELECT tracking_no FROM shipment WHERE receiver_phone = :phone AND carrier = 'JT_EXPRESS')""")
                .param("phone", phone).update();
        jdbc.sql("DELETE FROM shipment WHERE receiver_phone = :phone").param("phone", phone).update();
        jdbc.sql("DELETE FROM customer WHERE id = :id").param("id", customerId).update();

        contacts.event(customerId, phone, "ERASED", "Đã xóa khách và toàn bộ vận đơn của khách");
        complete(customerId, phone, Kind.ERASE, receivedOn, note);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
