package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloGroupCreationService.Step;
import vn.shop.crm.zalo.ZaloGroupMaker.Created;
import vn.shop.crm.zalo.ZaloGroupMaker.GroupOutcome;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tự tạo nhóm Zalo (D19) với bộ nối GIẢ: đề xuất, duyệt, trần ngày, khoảng nghỉ, cầu dao, không tự thử lại. */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.group.deputy-id=99887766"})
class ZaloGroupCreationTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloGroupCreationService svc;
    @Autowired ZaloGroupSettings cfg;
    @Autowired ZaloAutoSettings auto;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 8; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",090000000").append(i).append(",1 Duong A Xa B Cần Thơ,Giao thành công\n");
        }
        csv.append("T9,DH9,05/01/2026 09:00:00,Khach Bac,0900000009,2 Duong A Xa B Hà Nội,Giao thành công\n");
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
    }

    // ---------- tiện ích ----------

    private long inv(int n) {
        return jdbc.sql("SELECT i.id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p")
                .param("p", "+8490000000" + n).query(Long.class).single();
    }

    /** Khách n đã kết bạn (đã có mã Zalo), đang chờ duyệt thêm vào nhóm; số nhỏ kết bạn sớm hơn. */
    private void friended(int... ns) {
        for (int n : ns) {
            jdbc.sql("""
                            UPDATE invitation SET status = 'FRIENDED', sent_at = now() - interval '2 days',
                                   friended_at = now() - make_interval(hours => :h), zalo_uid = :u, zalo_add_state = 'WAITING_APPROVAL'
                            WHERE id = :id""").param("h", 100 - n).param("u", "7000000" + n).param("id", inv(n)).update();
        }
    }

    private void mode(ZaloGroupMode m) {
        cfg.setMode(m, m == ZaloGroupMode.AUTO, "mẹ");
    }

    private String proposalState() {
        return jdbc.sql("SELECT state FROM zalo_group_proposal ORDER BY id DESC LIMIT 1").query(String.class).single();
    }

    private long proposals() {
        return jdbc.sql("SELECT count(*) FROM zalo_group_proposal").query(Long.class).single();
    }

    private String invStatus(int n) {
        return jdbc.sql("SELECT status FROM invitation WHERE id = :id").param("id", inv(n)).query(String.class).single();
    }

    /** Qua mốc xét đề xuất kế tiếp (mười phút một lần). */
    private void later(int minutes) {
        clock.advance(Duration.ofMinutes(minutes));
    }

    // ---------- mặc định tắt ----------

    @Test
    void macDinhTatKhongDeXuatKhongGoiGi() {
        friended(1, 2, 3);
        assertThat(cfg.chosenMode()).isEqualTo(ZaloGroupMode.OFF);
        assertThat(svc.step()).isEqualTo(Step.NOT_ACTIVE);
        assertThat(proposals()).isZero();
        assertThat(fake.totalCalls()).isZero();
    }

    @Test
    void nacTuDongPhaiTickDaThuTay() {
        assertThatThrownBy(() -> cfg.setMode(ZaloGroupMode.AUTO, false, "mẹ")).isInstanceOf(ImportException.class)
                .hasMessageContaining("thử tay");
        mode(ZaloGroupMode.CONFIRM);
        assertThat(auto.recentLog(5)).anyMatch(l -> "CAU_HINH".equals(l.action()) && "mẹ".equals(l.actor()));
    }

    // ---------- nấc xác nhận ----------

    @Test
    void duKhachThiDeXuatChoMeDuyetKhongTaoNgay() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        assertThat(svc.step()).isEqualTo(Step.PROPOSED);
        var p = svc.recent(5).getFirst();
        assertThat(p.state()).isEqualTo("PROPOSED");
        assertThat(p.groupName()).isEqualTo("Pin ADH - Cần Thơ - 01");
        assertThat(p.reason()).contains("3 khách chờ ở Cần Thơ");
        assertThat(p.members()).isEqualTo(3);
        assertThat(fake.creates).isEmpty();
        // chưa duyệt thì các nhịp sau chỉ chờ, không đề xuất thêm
        later(15);
        assertThat(svc.step()).isEqualTo(Step.WAIT);
        assertThat(proposals()).isEqualTo(1);
    }

    @Test
    void duyetRoiTaoNhomGhiNhomVaDuaKhachVaoDungNhom() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        svc.step();
        long id = svc.recent(1).getFirst().id();
        svc.approve(id, "mẹ");
        assertThat(svc.step()).isEqualTo(Step.CREATED);

        assertThat(fake.creates).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("Pin ADH - Cần Thơ - 01");
            assertThat(c.members()).containsExactly("70000001", "70000002", "70000003");
            assertThat(c.deputy()).isEqualTo("99887766");
        });
        var g = groups.list().getFirst();
        assertThat(g.name()).isEqualTo("Pin ADH - Cần Thơ - 01");
        assertThat(g.scopeCode()).isEqualTo("P:can-tho");
        assertThat(g.zaloGroupId()).isEqualTo("5555");
        assertThat(g.inviteLink()).isEqualTo("https://zalo.me/g/fake5555");
        assertThat(g.status()).isEqualTo(GroupStatus.OPEN);
        assertThat(g.actual()).isEqualTo(3);
        assertThat(invStatus(1)).isEqualTo("JOINED");
        assertThat(svc.recent(1).getFirst()).satisfies(p -> {
            assertThat(p.state()).isEqualTo("DONE");
            assertThat(p.addedCount()).isEqualTo(3);
            assertThat(p.deputyState()).isEqualTo("OK");
            assertThat(p.linkOk()).isTrue();
        });
    }

    @Test
    void nhomDaCoChoThiKhongDeXuat() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        long g = groups.create("Nhóm ĐBSCL", "https://zalo.me/g/dbscl", 50, "S:DB_SONG_CUU_LONG");
        groups.setStatus(g, GroupStatus.OPEN);
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(proposals()).isZero();
    }

    @Test
    void duoiNguongKhongTaoNhomMotHaiNguoi() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2);
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(proposals()).isZero();
        cfg.setLimits(1, 2, "mẹ"); // đổi ngưỡng xuống 2 thì được
        later(15);
        assertThat(svc.step()).isEqualTo(Step.PROPOSED);
    }

    @Test
    void tuChoiThiKhongDeXuatLaiTrong24Gio() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        svc.step();
        svc.reject(svc.recent(1).getFirst().id(), "mẹ");
        later(15);
        // tỉnh Cần Thơ bị từ chối: xét lên cấp vùng thì vẫn là 3 người ở vùng, nên đề xuất vùng; từ chối nốt vùng và miền
        assertThat(svc.step()).isEqualTo(Step.PROPOSED);
        assertThat(svc.recent(1).getFirst().scope()).isEqualTo("S:DB_SONG_CUU_LONG");
        svc.reject(svc.recent(1).getFirst().id(), "mẹ");
        later(15);
        assertThat(svc.step()).isEqualTo(Step.PROPOSED);
        svc.reject(svc.recent(1).getFirst().id(), "mẹ");
        later(15);
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.creates).isEmpty();
        later(24 * 60);
        assertThat(svc.step()).isEqualTo(Step.PROPOSED);
    }

    // ---------- nấc tự động, trần, khoảng nghỉ, khung giờ ----------

    @Test
    void tuDongTaoNgayNhungToiDaMotNhomMoiNgay() {
        mode(ZaloGroupMode.AUTO);
        friended(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates).singleElement().satisfies(c -> assertThat(c.members()).hasSize(5));
        later(120);
        // còn 3 khách chờ nhưng trần mỗi ngày là 1
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.creates).hasSize(1);
        // sang ngày hôm sau thì tạo nhóm thứ hai cùng khu vực, số thứ tự 02
        clock.set(LocalDateTime.of(2026, 11, 23, 9, 0));
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates.get(1).name()).isEqualTo("Pin ADH - Cần Thơ - 02");
    }

    @Test
    void khoangNghiCoDinhGiuaHaiLanTaoTrongCungNgay() {
        mode(ZaloGroupMode.AUTO);
        cfg.setLimits(2, 3, "mẹ");
        friended(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        later(15);
        // nhóm đầu đã nhận 5 khách, còn 3 khách chờ nhưng mới 15 phút: chưa đủ khoảng nghỉ 60 phút
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        later(60);
        // đủ khoảng nghỉ cố định: còn 3 khách chờ mà nhóm đầu đã đầy, nên tạo nhóm thứ hai cùng khu vực
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates.get(1).name()).isEqualTo("Pin ADH - Cần Thơ - 02");
        assertThat(fake.creates.get(1).members()).hasSize(3);
    }

    @Test
    void ngoaiKhungGioKhongDeXuatKhongTao() {
        mode(ZaloGroupMode.AUTO);
        friended(1, 2, 3);
        clock.set(LocalDateTime.of(2026, 11, 22, 21, 0));
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        clock.set(LocalDateTime.of(2026, 11, 22, 7, 30));
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(proposals()).isZero();
        assertThat(fake.creates).isEmpty();
    }

    @Test
    void daDuyetNhungNgoaiGioThiChoDenKhiDuocPhep() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        svc.step();
        svc.approve(svc.recent(1).getFirst().id(), "mẹ");
        clock.set(LocalDateTime.of(2026, 11, 22, 21, 0));
        assertThat(svc.step()).isEqualTo(Step.WAIT);
        assertThat(fake.creates).isEmpty();
        clock.set(LocalDateTime.of(2026, 11, 23, 9, 0));
        assertThat(svc.step()).isEqualTo(Step.CREATED);
    }

    // ---------- lỗi và cầu dao ----------

    private long approvedProposal() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        svc.step();
        long id = svc.recent(1).getFirst().id();
        svc.approve(id, "mẹ");
        return id;
    }

    @Test
    void zaloHanCheThiDungVaKhongTuThuLai() {
        approvedProposal();
        fake.willCreate(Created.of(GroupOutcome.RESTRICTED, "CAPTCHA", false));
        assertThat(svc.step()).isEqualTo(Step.TRIPPED);
        assertThat(proposalState()).isEqualTo("FAILED");
        assertThat(auto.tripped()).isTrue();
        assertThat(fake.stops).isEqualTo(1);
        assertThat(cfg.effectiveMode()).isEqualTo(ZaloGroupMode.OFF);
        later(120);
        assertThat(svc.step()).isEqualTo(Step.NOT_ACTIVE);
        assertThat(fake.creates).hasSize(1);
        assertThat(groups.list()).isEmpty();
    }

    @Test
    void khongRoKetQuaThiDungVaDanhDauThatBai() {
        approvedProposal();
        fake.willCreate(Created.of(GroupOutcome.SYSTEM_ERROR, "TIMEOUT", false));
        assertThat(svc.step()).isEqualTo(Step.TRIPPED);
        assertThat(proposalState()).isEqualTo("FAILED");
        assertThat(auto.trippedReason()).startsWith("SYSTEM_ERROR");
        assertThat(groups.list()).isEmpty();
        assertThat(invStatus(1)).isEqualTo("FRIENDED");
    }

    @Test
    void chuaChamZaloThiTraVeHangDoiVaKhongTinhTran() {
        approvedProposal();
        fake.willCreate(Created.of(GroupOutcome.UNREACHABLE, "BRIDGE_DOWN", true));
        assertThat(svc.step()).isEqualTo(Step.UNREACHABLE);
        assertThat(proposalState()).isEqualTo("APPROVED");
        assertThat(auto.tripped()).isFalse();
        // cầu nối từ chối vì quá sớm: cũng chưa tạo gì
        fake.willCreate(Created.of(GroupOutcome.RATE_LIMITED, null, true));
        assertThat(svc.step()).isEqualTo(Step.UNREACHABLE);
        assertThat(proposalState()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT started_at FROM zalo_group_proposal").query(java.sql.Timestamp.class).optional()).isEmpty();
        // lần sau được thì tạo bình thường
        assertThat(svc.step()).isEqualTo(Step.CREATED);
    }

    @Test
    void dauVaoBiTuChoiThiThatBaiNhungKhongDungCauDao() {
        approvedProposal();
        fake.willCreate(Created.of(GroupOutcome.PERSON_ERROR, "BAD_NAME", true));
        assertThat(svc.step()).isEqualTo(Step.FAILED);
        assertThat(proposalState()).isEqualTo("FAILED");
        assertThat(auto.tripped()).isFalse();
    }

    @Test
    void conLaiMotDongDangTaoThiDungNganKhongGoiThem() {
        approvedProposal();
        jdbc.sql("UPDATE zalo_group_proposal SET state = 'CREATING', started_at = now()").update();
        assertThat(svc.step()).isEqualTo(Step.TRIPPED);
        assertThat(auto.trippedReason()).isEqualTo("TAO_NHOM_INTERRUPTED");
        assertThat(fake.creates).isEmpty();
        // người kiểm tra trên Zalo rồi đóng dòng lại
        long id = svc.recent(1).getFirst().id();
        svc.acknowledge(id, "mẹ");
        assertThat(proposalState()).isEqualTo("FAILED");
    }

    @Test
    void cauDaoDangNhayThiKhongLamGi() {
        mode(ZaloGroupMode.AUTO);
        friended(1, 2, 3);
        auto.trip("DUNG_TAY", "mẹ");
        assertThat(svc.step()).isEqualTo(Step.NOT_ACTIVE);
        assertThat(fake.totalCalls()).isZero();
    }

    // ---------- kết quả không trọn vẹn ----------

    @Test
    void khongCoLinkThiNhomTamDungVaGhiChu() {
        approvedProposal();
        fake.willCreate(new Created(GroupOutcome.OK, null, "6666", null, List.of("70000001", "70000002", "70000003"), List.of(), "FAILED", 0, false));
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        var g = groups.list().getFirst();
        assertThat(g.hasLink()).isFalse();
        assertThat(g.status()).isNotEqualTo(GroupStatus.OPEN);
        assertThat(g.note()).contains("Chưa có link mời");
        assertThat(svc.recent(1).getFirst().deputyState()).isEqualTo("FAILED");
        assertThat(svc.recent(1).getFirst().linkOk()).isFalse();
    }

    @Test
    void ngoaiLinkZaloKhongLuuLink() {
        approvedProposal();
        fake.willCreate(new Created(GroupOutcome.OK, null, "6666", "https://evil.example.com/x", List.of("70000001", "70000002", "70000003"), List.of(), "NONE", 0, false));
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(groups.list().getFirst().hasLink()).isFalse();
    }

    @Test
    void nguoiChuaVaoDuocOLaiHangDoiThemNguoiSanCo() {
        approvedProposal();
        fake.willCreate(new Created(GroupOutcome.OK, null, "6666", "https://zalo.me/g/x", List.of("70000001", "70000002"), List.of("70000003"), "OK", 0, false));
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(invStatus(1)).isEqualTo("JOINED");
        assertThat(invStatus(3)).isEqualTo("FRIENDED");
        assertThat(jdbc.sql("SELECT zalo_add_state FROM invitation WHERE id = :id").param("id", inv(3)).query(String.class).single())
                .isEqualTo("QUEUED");
        assertThat(groups.list().getFirst().actual()).isEqualTo(2);
        assertThat(svc.recent(1).getFirst().addedCount()).isEqualTo(2);
    }

    @Test
    void khachKhongConDuDieuKienLucTaoThiBoRa() {
        approvedProposal();
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE id = :id").param("id", inv(1)).update();
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates.getFirst().members()).containsExactly("70000002", "70000003");
    }

    @Test
    void conQuaItKhachLucTaoThiHetHanKhongGoiZalo() {
        approvedProposal();
        jdbc.sql("UPDATE invitation SET status = 'CANCELLED' WHERE id IN (:a, :b)").param("a", inv(1)).param("b", inv(2)).update();
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(proposalState()).isEqualTo("EXPIRED");
        assertThat(fake.creates).isEmpty();
    }

    @Test
    void deXuatQuaHanThiHetHan() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        svc.step();
        later(49 * 60);
        svc.step();
        assertThat(svc.recent(5)).anyMatch(p -> p.id() == 1 && "EXPIRED".equals(p.state()));
    }

    @Test
    void nhatKyKhongChuaMaZaloHayLinkHayTenKhach() {
        approvedProposal();
        svc.step();
        String all = jdbc.sql("SELECT coalesce(string_agg(action || result || coalesce(code, ''), ' '), '') FROM zalo_auto_log")
                .query(String.class).single();
        assertThat(all).doesNotContain("7000000").doesNotContain("5555").doesNotContain("zalo.me").doesNotContain("Khach");
        assertThat(jdbc.sql("SELECT string_agg(reason || group_name || coalesce(result_code, ''), ' ') FROM zalo_group_proposal")
                .query(String.class).single()).doesNotContain("7000000").doesNotContain("Khach");
    }
}
