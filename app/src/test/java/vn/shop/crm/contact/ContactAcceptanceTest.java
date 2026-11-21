package vn.shop.crm.contact;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneHash;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tiêu chí 6 (chỉ ONE_TIME đủ điều kiện mới vào danh sách mời) và quyền dữ liệu của khách, chạy trên Postgres thật. */
class ContactAcceptanceTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    private static final String ONE = "0912345678";
    private static final String TWO = "0987654321";
    private static final String REPEAT = "0366111222";
    private static final String REVIEW = "0905000111";

    @Autowired
    ImportService imports;
    @Autowired
    CustomerService customers;
    @Autowired
    ContactService contacts;
    @Autowired
    PrivacyService privacy;

    private ImportService.Report importCsv(String... rows) {
        String csv = HEADER + String.join("\n", rows) + "\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "test.csv", csv.getBytes(StandardCharsets.UTF_8));
        ImportService.FileView view = imports.open(id, null);
        return imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    /** 2 khách mua 1 lần, 1 khách mua 2 lần, 1 khách mua 1 lần nhưng có trạng thái chưa rõ nghĩa (cần kiểm tra). */
    private void seed() {
        importCsv(
                "T1,DH1,05/01/2026 09:00:00,Nguyen Van A," + ONE + ",12 Duong A,Giao thành công",
                "T2,DH2,06/01/2026 09:00:00,Tran Thi B," + TWO + ",34 Duong B,Giao thành công",
                "T3,DH3,07/01/2026 09:00:00,Le Van C," + REPEAT + ",56 Duong C,Giao thành công",
                "T4,DH4,08/01/2026 09:00:00,Le Van C," + REPEAT + ",56 Duong C,Giao thành công",
                "T5,DH5,09/01/2026 09:00:00,Pham Thi D," + REVIEW + ",78 Duong D,Giao thành công",
                "T6,DH6,10/01/2026 09:00:00,Pham Thi D," + REVIEW + ",78 Duong D,Chờ xử lý");
    }

    private long idOf(String phone) {
        String e164 = "+84" + phone.substring(1);
        return jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", e164).query(Long.class).single();
    }

    private List<String> invited() {
        return contacts.inviteList(1, 100).items().stream().map(ContactService.InviteRow::phone).toList();
    }

    // ---------- mặc định chặn ----------

    @Test
    void macDinhKhongAiNamTrongDanhSachMoiKeCaKhachMuaMotLan() {
        seed();

        assertThat(invited()).isEmpty();
        assertThat(contacts.stats().candidates()).isEqualTo(2); // ONE và TWO; REVIEW cần kiểm tra, REPEAT không phải ONE_TIME
    }

    @Test
    void duyetHangLoatChiLayKhachMuaMotLanKhongCanKiemTra() {
        seed();

        int approved = contacts.approveAllCandidates();

        assertThat(approved).isEqualTo(2);
        assertThat(invited()).containsExactlyInAnyOrder("+84" + ONE.substring(1), "+84" + TWO.substring(1));
        var basis = contacts.basisOf(idOf(ONE)).orElseThrow();
        assertThat(basis.status()).isEqualTo(ContactStatus.ELIGIBLE);
        assertThat(basis.evidenceRef()).isEqualTo("VIETTEL_POST:T1");
        assertThat(basis.statement()).contains("Chủ cửa hàng chịu trách nhiệm");
        assertThat(contacts.events(idOf(ONE))).extracting(ContactService.Event::event).contains("BASIS_APPROVED");
    }

    @Test
    void duyetHaiLanKhongTaoTrung() {
        seed();
        contacts.approveAllCandidates();

        assertThat(contacts.approveAllCandidates()).isZero();
    }

    @Test
    void khachMuaTuHaiLanKhongBaoGioVaoDanhSachMoiKeCaKhiCoDongEligible() {
        seed();
        long repeatId = idOf(REPEAT);
        // cố tình ép một dòng ELIGIBLE cho khách REPEAT (như dữ liệu sai/thao tác trực tiếp): truy vấn vẫn phải chặn
        jdbc.sql("""
                INSERT INTO contact_basis (customer_id, purpose, basis, status, recorded_by)
                VALUES (:id, 'GROUP_INVITE_POSTSALE', 'TRANSACTION_POSTSALE', 'ELIGIBLE', 'test')""")
                .param("id", repeatId).update();

        assertThat(invited()).isEmpty();
        assertThatThrownBy(() -> contacts.approve(repeatId)).isInstanceOf(ImportException.class);
    }

    @Test
    void khachCanKiemTraKhongDuyetDuoc() {
        seed();

        assertThatThrownBy(() -> contacts.approve(idOf(REVIEW))).isInstanceOf(ImportException.class);
        contacts.approveAllCandidates();
        assertThat(invited()).doesNotContain("+84" + REVIEW.substring(1));
    }

    // ---------- chặn / từ chối ----------

    @Test
    void chanRoiBoChanPhaiDuyetLai() {
        seed();
        contacts.approveAllCandidates();
        long id = idOf(ONE);

        contacts.block(id, "khách phàn nàn");
        assertThat(invited()).containsExactly("+84" + TWO.substring(1));
        assertThat(contacts.basisOf(id).orElseThrow().status()).isEqualTo(ContactStatus.BLOCKED);

        contacts.unblock(id);
        assertThat(contacts.basisOf(id)).isEmpty();
        assertThat(invited()).containsExactly("+84" + TWO.substring(1)); // chưa duyệt lại thì chưa được mời
        contacts.approve(id);
        assertThat(invited()).hasSize(2);
    }

    @Test
    void tuChoiLaVinhVienVaChanCaKhiNhapLaiFile() {
        seed();
        contacts.approveAllCandidates();
        long id = idOf(ONE);

        privacy.stopContact(id, LocalDate.now(), "khách nhắn Zalo");

        assertThat(invited()).containsExactly("+84" + TWO.substring(1));
        assertThat(contacts.basisOf(id).orElseThrow().status()).isEqualTo(ContactStatus.OPTED_OUT);
        assertThat(contacts.isSuppressed("+84" + ONE.substring(1))).isTrue();

        // nhập lại file và duyệt hàng loạt: số đã từ chối không bao giờ quay lại danh sách
        importCsv("T9,DH9,11/01/2026 09:00:00,Nguyen Van A," + ONE + ",12 Duong A,Giao thành công");
        contacts.approveAllCandidates();
        assertThat(invited()).doesNotContain("+84" + ONE.substring(1));
        // chặn thủ công không ghi đè từ chối
        contacts.block(id, "thử chặn");
        assertThat(contacts.basisOf(id).orElseThrow().status()).isEqualTo(ContactStatus.OPTED_OUT);
    }

    // ---------- xóa dữ liệu ----------

    @Test
    void xoaKhachGoSachDuLieuChiConBamVaNhatKyBaSoCuoi() {
        seed();
        contacts.approveAllCandidates();
        long id = idOf(ONE);
        String e164 = "+84" + ONE.substring(1);

        privacy.erase(id, LocalDate.now(), "khách yêu cầu");

        assertThat(jdbc.sql("SELECT count(*) FROM customer WHERE phone = :p").param("p", e164).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM shipment WHERE receiver_phone = :p").param("p", e164)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM contact_basis WHERE customer_id = :id").param("id", id)
                .query(Long.class).single()).isZero();
        assertThat(contacts.isSuppressed(e164)).isTrue();
        // danh sách từ chối chỉ chứa băm, không chứa số
        List<String> hashes = jdbc.sql("SELECT phone_hash FROM suppression").query(String.class).list();
        assertThat(hashes).contains(PhoneHash.of(e164)).noneMatch(h -> h.contains(ONE));
        // nhật ký còn nhưng chỉ có 3 số cuối
        var events = jdbc.sql("SELECT phone_tail, detail FROM contact_event WHERE customer_id = :id").param("id", id)
                .query((rs, i) -> rs.getString(1) + "|" + rs.getString(2)).list();
        assertThat(events).isNotEmpty().allMatch(e -> e.startsWith("***678"));
        assertThat(events).noneMatch(e -> e.contains(ONE));
    }

    @Test
    void khachDaXoaKhongDuocNhapLai() {
        seed();
        privacy.erase(idOf(ONE), LocalDate.now(), null);

        var report = importCsv("T1,DH1,05/01/2026 09:00:00,Nguyen Van A," + ONE + ",12 Duong A,Giao thành công",
                "T7,DH7,12/01/2026 09:00:00,Khach moi,0933000444,Dia chi,Giao thành công");

        assertThat(report.errorRows()).isEqualTo(1);
        assertThat(report.validRows()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM customer WHERE phone = :p").param("p", "+84" + ONE.substring(1))
                .query(Long.class).single()).isZero();
        // dòng gốc của khách đã xóa không được lưu lại trong danh sách lỗi
        assertThat(jdbc.sql("SELECT count(*) FROM import_error WHERE raw_row LIKE :p").param("p", "%" + ONE.substring(1) + "%")
                .query(Long.class).single()).isZero();
    }

    @Test
    void xoaKhachGoCaDongLoiNhapCoChuaSoDo() {
        seed();
        // dòng lỗi (thiếu trạng thái) lưu nguyên dòng gốc có SĐT
        importCsv("T8,DH8,13/01/2026 09:00:00,Nguyen Van A," + ONE + ",12 Duong A,");
        assertThat(jdbc.sql("SELECT count(*) FROM import_error WHERE raw_row LIKE :p")
                .param("p", "%" + ONE.substring(1) + "%").query(Long.class).single()).isEqualTo(1);

        privacy.erase(idOf(ONE), LocalDate.now(), null);

        assertThat(jdbc.sql("SELECT count(*) FROM import_error WHERE raw_row LIKE :p")
                .param("p", "%" + ONE.substring(1) + "%").query(Long.class).single()).isZero();
    }

    @Test
    void khongGiuFileGocSauKhiNhapXong() {
        seed();

        long nonEmpty = jdbc.sql("SELECT count(*) FROM import_batch WHERE length(file_content) > 0")
                .query(Long.class).single();
        assertThat(nonEmpty).isZero();
    }

    // ---------- xuất, hạn phản hồi, ghi chú ----------

    @Test
    void xuatDuLieuMotKhachCoDuMoiVanDon() {
        seed();
        long id = idOf(REPEAT);

        String csv = new String(privacy.exportCustomer(id, LocalDate.now()), StandardCharsets.UTF_8);

        assertThat(csv).contains("T3").contains("T4").contains("0366 111 222").doesNotContain("T1");
        assertThat(csv).contains("Chưa duyệt");
    }

    @Test
    void hanPhanHoiTinhTuNgayKhachGuiVaBaoQuaHan() {
        seed();
        long id = idOf(ONE);

        privacy.openRequest(id, PrivacyService.Kind.ERASE, LocalDate.now().minusDays(20), "nhắn Zalo");
        privacy.openRequest(id, PrivacyService.Kind.EXPORT, LocalDate.now(), null);

        var rows = privacy.requests(true);
        assertThat(rows).hasSize(2);
        var erase = rows.stream().filter(r -> r.kind() == PrivacyService.Kind.ERASE).findFirst().orElseThrow();
        assertThat(erase.dueOn()).isEqualTo(LocalDate.now().minusDays(20).plusDays(privacy.dueDays()));
        assertThat(erase.overdue()).isTrue();
        assertThat(privacy.overdueCount()).isEqualTo(1);
        assertThat(privacy.openCount()).isEqualTo(2);

        // làm xong việc thì yêu cầu được đóng, không tạo yêu cầu thừa
        privacy.exportCustomer(id, LocalDate.now());
        assertThat(privacy.openCount()).isEqualTo(1);
        assertThat(privacy.requests(false)).hasSize(2);
    }

    @Test
    void ghiChuTayKhongMatKhiTinhLaiPhanLoai() {
        seed();
        long id = idOf(REPEAT);

        contacts.saveNote(id, "khách quen, đã gọi điện");
        customers.recalculateAll();

        assertThat(contacts.note(id)).isEqualTo("khách quen, đã gọi điện");
        contacts.saveNote(id, "  ");
        assertThat(contacts.note(id)).isEmpty();
    }
}
