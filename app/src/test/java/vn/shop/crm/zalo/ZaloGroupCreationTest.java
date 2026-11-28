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
    @Autowired ZaloDeputyService deputies;
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

    @Autowired vn.shop.crm.common.AppSettings appSettings;

    private void settingsSet(String key, String value) {
        appSettings.set(key, value, "test");
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
        assertThat(p.groupName()).isEqualTo("Áo điều hòa ADH - Cần Thơ - 01");
        assertThat(p.reason()).contains("3 khách chờ ở Cần Thơ");
        assertThat(p.members()).isEqualTo(3);
        assertThat(fake.creates).isEmpty();
        // chưa duyệt thì các nhịp sau chỉ chờ, không đề xuất thêm
        later(15);
        assertThat(svc.step()).isEqualTo(Step.WAIT);
        assertThat(proposals()).isEqualTo(1);
    }

    @Test
    void phoNhomLaNguoiDaXacNhanBangSoDienThoaiUuTienHonBienMoiTruong() {
        fake.willFind(new ZaloPersonFinder.Found(ZaloPersonFinder.FindResult.OK, null, "4242", "Mẹ Lan", null, 0));
        deputies.lookup("0912345678", "admin");
        deputies.confirm(deputies.candidate().orElseThrow().revision(), "admin");
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        svc.step();
        svc.approve(svc.recent(1).getFirst().id(), "mẹ");
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates).singleElement().satisfies(c -> assertThat(c.deputy()).isEqualTo("4242"));
    }

    @Test
    void taoNhomGuiMotTinChaoCoLinkGianHangMacDinh() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        svc.step();
        svc.approve(svc.recent(1).getFirst().id(), "mẹ");
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.welcomes).singleElement().satisfies(w -> assertThat(w)
                .contains("https://vt.tiktok.com/ZSsYdPXfU/?page=TikTokShop", "giảm 10% so với TikTok", "miễn ship")
                .doesNotContain("{", "}"));
        assertThat(auto.recentLog(10)).anyMatch(l -> "TIN_CHAO_OK".equals(l.result()));
    }

    @Test
    void tinChaoTatHoacSuaDuocVaMauLaBiTuChoi() {
        cfg.setWelcome(false, "", "", "mẹ");
        assertThat(cfg.welcomeText()).isNull();
        cfg.setWelcome(true, "Chào cả nhà, gian hàng: {link}", "https://example.com/shop", "mẹ");
        assertThat(cfg.welcomeText()).isEqualTo("Chào cả nhà, gian hàng: https://example.com/shop");
        cfg.setWelcome(true, "", "", "mẹ");
        assertThat(cfg.welcomeText()).isEqualTo(ZaloGroupSettings.DEFAULT_WELCOME.replace("{link}", ZaloGroupSettings.DEFAULT_SHOP_LINK));
        assertThatThrownBy(() -> cfg.setWelcome(true, "Chào {ten}", "", "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> cfg.setWelcome(true, "", "http://khong-an-toan", "mẹ")).isInstanceOf(ImportException.class);
    }

    @Test
    void tinChaoTatThiKhongYeuCauGuiGiVaoNhom() {
        cfg.setWelcome(false, "", "", "mẹ");
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        svc.step();
        svc.approve(svc.recent(1).getFirst().id(), "mẹ");
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.welcomes).containsExactly((String) null);
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
            assertThat(c.name()).isEqualTo("Áo điều hòa ADH - Cần Thơ - 01");
            assertThat(c.members()).containsExactly("70000001", "70000002", "70000003");
            assertThat(c.deputy()).isEqualTo("99887766");
        });
        var g = groups.list().getFirst();
        assertThat(g.name()).isEqualTo("Áo điều hòa ADH - Cần Thơ - 01");
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
        groups.setZaloGroupId(g, "888"); // bộ nối chỉ thêm được vào nhóm có mã nhóm Zalo
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
        cfg.setMinCustomers(2, "mẹ"); // đổi ngưỡng xuống 2 thì được
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
    void tuDongKhongCoTranNhomMoiNgayChiCoKhungGioVaKhoangNghi() {
        fake.uniqueGroupIds = true;
        mode(ZaloGroupMode.AUTO);
        friended(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates).singleElement().satisfies(c -> assertThat(c.members()).hasSize(5));
        later(120);
        // còn 3 khách chờ, KHÔNG có trần số nhóm mỗi ngày (24/11): hết khoảng nghỉ thì tạo nhóm thứ hai ngay trong ngày
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates).hasSize(2);
        assertThat(fake.creates.get(1).name()).isEqualTo("Áo điều hòa ADH - Cần Thơ - 02");
    }

    @Test
    void khoangNghiCoDinhGiuaHaiLanTaoTrongCungNgay() {
        fake.uniqueGroupIds = true;
        mode(ZaloGroupMode.AUTO);
        friended(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        later(15);
        // nhóm đầu đã nhận 5 khách, còn 3 khách chờ nhưng mới 15 phút: chưa đủ khoảng nghỉ 60 phút
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        later(60);
        // đủ khoảng nghỉ cố định: còn 3 khách chờ mà nhóm đầu đã đầy, nên tạo nhóm thứ hai cùng khu vực
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates.get(1).name()).isEqualTo("Áo điều hòa ADH - Cần Thơ - 02");
        assertThat(fake.creates.get(1).members()).hasSize(3);
    }

    @Test
    void claimRechecksAnotherProposalStartedDuringBridgeStatusRead() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        assertThat(svc.step()).isEqualTo(Step.PROPOSED);
        long pending = svc.recent(1).getFirst().id();
        svc.approve(pending, "owner");
        fake.beforeStatusCall = () -> {
            fake.beforeStatusCall = () -> {};
            jdbc.sql("""
                    INSERT INTO zalo_group_proposal (scope, group_name, reason, waiting_count, state, started_at)
                    VALUES ('P:can-tho', 'Đề xuất đang chạy ở tiến trình khác', 'test', 3, 'CREATING', :at)
                    """).param("at", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)).update();
        };

        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.creates).isEmpty();
        assertThat(jdbc.sql("SELECT state FROM zalo_group_proposal WHERE id = :id").param("id", pending).query(String.class).single())
                .isEqualTo("APPROVED");
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

    // ---------- phó nhóm (24/11/2026) ----------

    /** Tạo nhóm với đặt phó nhóm lỗi kèm mã Zalo thật: ghi mã vào đề xuất và nhật ký. */
    private void createdWithDeputyFailed(String code) {
        approvedProposal();
        fake.willCreate(new Created(GroupOutcome.OK, null, "6666", "https://zalo.me/g/x", List.of("70000001", "70000002", "70000003"), List.of(),
                "FAILED", code, 0, false));
        assertThat(svc.step()).isEqualTo(Step.CREATED);
    }

    @Test
    void phoNhomLoiGhiMaLoiThatVaoDeXuatVaNhatKy() {
        createdWithDeputyFailed("Z118");
        var p = svc.recent(1).getFirst();
        assertThat(p.deputyState()).isEqualTo("FAILED");
        assertThat(p.deputyCode()).isEqualTo("Z118");
        assertThat(p.canRetryDeputy()).isTrue();
        assertThat(auto.recentLog(10)).anySatisfy(l -> assertThat(l.toString()).contains("PHO_NHOM_LOI").contains("Z118"));
    }

    @Test
    void thuLaiPhoNhomThanhCongThiDoiSangOkVaKhongHienNutNua() {
        createdWithDeputyFailed("Z118");
        long id = svc.recent(1).getFirst().id();
        var r = svc.retryDeputy(id, "mẹ");
        assertThat(r.ok()).isTrue();
        assertThat(fake.deputyCalls).singleElement().satisfies(c -> {
            assertThat(c.groupId()).isEqualTo("6666");
            assertThat(c.uid()).isEqualTo("99887766");
        });
        var p = svc.recent(1).getFirst();
        assertThat(p.deputyState()).isEqualTo("OK");
        assertThat(p.deputyCode()).isNull();
        assertThat(p.canRetryDeputy()).isFalse();
        assertThatThrownBy(() -> svc.retryDeputy(id, "mẹ")).isInstanceOf(ImportException.class);
        assertThat(fake.deputyCalls).hasSize(1);
    }

    @Test
    void thuLaiPhoNhomLoiThiGhiMaMoiVaToiDaNamLan() {
        createdWithDeputyFailed("Z118");
        long id = svc.recent(1).getFirst().id();
        for (int i = 0; i < ZaloGroupCreationService.MAX_DEPUTY_TRIES; i++) {
            fake.deputyResults.add(ZaloGroupMaker.DeputyResult.of(GroupOutcome.OK, "Z" + (200 + i)));
            assertThat(svc.retryDeputy(id, "mẹ").ok()).isFalse();
        }
        var p = svc.recent(1).getFirst();
        assertThat(p.deputyState()).isEqualTo("FAILED");
        assertThat(p.deputyCode()).isEqualTo("Z204");
        assertThat(p.deputyTries()).isEqualTo(ZaloGroupCreationService.MAX_DEPUTY_TRIES);
        assertThat(p.canRetryDeputy()).isFalse();
        assertThatThrownBy(() -> svc.retryDeputy(id, "mẹ")).isInstanceOf(ImportException.class).hasMessageContaining("bằng tay");
        assertThat(fake.deputyCalls).hasSize(ZaloGroupCreationService.MAX_DEPUTY_TRIES);
        assertThat(auto.tripped()).isFalse();
    }

    @Test
    void thuLaiPhoNhomKhiZaloHanCheThiDungCauDao() {
        createdWithDeputyFailed("Z118");
        long id = svc.recent(1).getFirst().id();
        fake.deputyResults.add(ZaloGroupMaker.DeputyResult.of(GroupOutcome.RESTRICTED, "Z999"));
        assertThat(svc.retryDeputy(id, "mẹ").ok()).isFalse();
        assertThat(auto.tripped()).isTrue();
    }

    @Test
    void thuLaiNgoaiGioCuaCauNoiKhongDungCauDaoVaKhongTinhLan() {
        createdWithDeputyFailed("DEPUTY-Z114");
        long id = svc.recent(1).getFirst().id();
        fake.deputyResults.add(ZaloGroupMaker.DeputyResult.of(GroupOutcome.RESTRICTED, "WINDOW"));
        assertThat(svc.retryDeputy(id, "mẹ").ok()).isFalse();
        assertThat(auto.tripped()).isFalse();
        var p = svc.recent(1).getFirst();
        assertThat(p.deputyTries()).isZero();
        assertThat(p.deputyCode()).isEqualTo("DEPUTY-Z114"); // giữ mã Zalo thật, không bị mã WINDOW đè
        assertThat(p.canRetryDeputy()).isTrue();
    }

    @Test
    void thuLaiNgoaiGioCuaAdhBiTuChoiTruocKhiGoiZalo() {
        createdWithDeputyFailed("Z118");
        long id = svc.recent(1).getFirst().id();
        clock.set(LocalDateTime.of(2026, 11, 22, 23, 30));
        assertThatThrownBy(() -> svc.retryDeputy(id, "mẹ")).isInstanceOf(ImportException.class).hasMessageContaining("ngoài giờ");
        assertThat(fake.deputyCalls).isEmpty();
        assertThat(svc.recent(1).getFirst().deputyTries()).isZero();
    }

    @Test
    void nhomTaoLucChuaChonMeVanThuLaiDuocSauKhiChon() {
        approvedProposal();
        fake.willCreate(new Created(GroupOutcome.OK, null, "6666", "https://zalo.me/g/x", List.of("70000001", "70000002", "70000003"), List.of(),
                "NONE", null, 0, false));
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        var p = svc.recent(1).getFirst();
        assertThat(p.deputyState()).isEqualTo("NONE");
        assertThat(p.canRetryDeputy()).isTrue();
        assertThat(svc.retryDeputy(p.id(), "mẹ").ok()).isTrue();
        assertThat(svc.recent(1).getFirst().deputyState()).isEqualTo("OK");
    }

    @Test
    void maMeTuTaiKhoanCuThiKhongDungVaXacNhanLaiThiDatLaiSoLan() {
        createdWithDeputyFailed("Z118");
        long id = svc.recent(1).getFirst().id();
        jdbc.sql("UPDATE zalo_group_proposal SET deputy_tries = 5 WHERE id = :id").param("id", id).update();
        settingsSet("zalo.deputy.uid", "4242");
        settingsSet("zalo.deputy.acct", "aaaaaaaaaaaa");
        fake.status = new vn.shop.crm.zalo.ZaloGateway.Status(vn.shop.crm.zalo.ZaloGateway.State.LISTENING, null, true, null,
                java.time.Instant.parse("2026-11-22T00:00:00Z"), null, "bbbbbbbbbbbb");
        assertThat(deputies.stale()).isTrue();
        assertThat(deputies.effectiveUid()).isEmpty();
        assertThat(deputies.current().stale()).isTrue();
        // khớp tài khoản thì dùng được
        settingsSet("zalo.deputy.acct", "bbbbbbbbbbbb");
        assertThat(deputies.stale()).isFalse();
        assertThat(deputies.effectiveUid()).isEqualTo("4242");
        // xác nhận mã mới: đặt lại số lần của các nhóm chưa có phó nhóm
        settingsSet("zalo.deputy.cand_uid", "5555");
        settingsSet("zalo.deputy.cand_name", "Mẹ");
        settingsSet("zalo.deputy.cand_at", clock.instant().toString());
        settingsSet("zalo.deputy.cand_acct", "bbbbbbbbbbbb");
        settingsSet("zalo.deputy.cand_revision", "lookup-revision");
        deputies.confirm(deputies.candidate().orElseThrow().revision(), "admin");
        assertThat(deputies.effectiveUid()).isEqualTo("5555");
        assertThat(svc.recent(1).getFirst().deputyTries()).isZero();
    }

    @Test
    void tuThuLaiPhoNhomKhiRanhCoKhoangNghiCoDinhVaTranBaLan() {
        createdWithDeputyFailed("Z118");
        mode(ZaloGroupMode.CONFIRM);
        fake.deputyResults.add(ZaloGroupMaker.DeputyResult.of(GroupOutcome.OK, "DEPUTY-Z114"));
        svc.tick();
        assertThat(fake.deputyCalls).hasSize(1);
        svc.tick(); // chưa đủ 60 phút
        assertThat(fake.deputyCalls).hasSize(1);
        later(61);
        fake.deputyResults.add(ZaloGroupMaker.DeputyResult.of(GroupOutcome.OK, "DEPUTY-Z114"));
        svc.tick();
        later(61);
        fake.deputyResults.add(ZaloGroupMaker.DeputyResult.of(GroupOutcome.OK, "DEPUTY-Z114"));
        svc.tick();
        assertThat(fake.deputyCalls).hasSize(3);
        later(61);
        svc.tick(); // đủ ba lần tự thử: dừng, nút tay còn hai lần
        assertThat(fake.deputyCalls).hasSize(3);
        assertThat(svc.recent(1).getFirst().canRetryDeputy()).isTrue();
    }

    @Test
    void tuThuLaiThanhCongThiDoiSangOk() {
        createdWithDeputyFailed("Z118");
        mode(ZaloGroupMode.CONFIRM);
        svc.tick();
        assertThat(svc.recent(1).getFirst().deputyState()).isEqualTo("OK");
    }

    @Test
    void thuLaiPhoNhomKhongDuocChoNhomKhongLoiHayDeXuatKhacDone() {
        approvedProposal();
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        long id = svc.recent(1).getFirst().id();
        assertThat(svc.recent(1).getFirst().deputyState()).isEqualTo("OK");
        assertThatThrownBy(() -> svc.retryDeputy(id, "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> svc.retryDeputy(987654, "mẹ")).isInstanceOf(ImportException.class);
        assertThat(fake.deputyCalls).isEmpty();
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

    // ---------- 24/11/2026: sức chứa nhóm, nhóm chưa có mã Zalo, nhóm chờ link, tên nhóm ----------

    @Test
    void mucTieuMacDinhNamKhachVaKhachDuLeVaoNhomConDuoiTranMemKhongTaoNhomThem() {
        mode(ZaloGroupMode.AUTO);
        friended(1, 2, 3, 4, 5, 6, 7);
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates).singleElement().satisfies(c -> assertThat(c.members()).hasSize(5));
        assertThat(groups.list().getFirst().target()).isEqualTo(5);
        // 2 khách dư (dưới ngưỡng 3) còn chỗ ở nhóm vừa tạo vì trần mềm là 5 + 3 = 8 khách: không cần lập nhóm mới
        assertThat(svc.waitingCustomers()).isEmpty();
        later(120);
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.creates).hasSize(1);
    }

    @Test
    void nhomDaChamTranMemThiKhachDuKhongLotVaoNhom() {
        mode(ZaloGroupMode.AUTO);
        friended(1, 2, 3, 4, 5, 6, 7);
        svc.step();
        groups.recount(groups.list().getFirst().id(), 8); // đã 8 khách: chạm trần mềm
        assertThat(svc.waitingCustomers()).hasSize(2); // 2 khách vẫn chờ (dưới ngưỡng lập nhóm mới nên chưa tạo, chờ thêm người hoặc hạ ngưỡng theo tuổi chờ)
        later(120);
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
    }

    @Test
    void sucChuaPhaiTuNamDenTran() {
        assertThatThrownBy(() -> cfg.setLimits(3, 4, "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> cfg.setLimits(3, 101, "mẹ")).isInstanceOf(ImportException.class);
        cfg.setLimits(3, 50, "mẹ");
        assertThat(cfg.targetCustomers()).isEqualTo(50);
    }

    @Test
    void nhomMeTaoTayCoLinkNhungChuaCoMaZaloKhongDuocTinhLaConCho() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3);
        long g = groups.create("Nhóm tay", "https://zalo.me/g/tay", 50, "P:can-tho");
        groups.setStatus(g, GroupStatus.OPEN); // có link, đang mở, KHÔNG có mã nhóm Zalo: bộ nối không thêm được ai
        assertThat(svc.step()).isEqualTo(Step.PROPOSED);
        assertThat(svc.recent(1).getFirst().waitingCount()).isEqualTo(3);
    }

    @Test
    void nhomTuTaoChuaCoLinkThiChoKhongTaoNhomTrung() {
        mode(ZaloGroupMode.AUTO);
        friended(1, 2, 3, 4, 5, 6, 7, 8);
        // nhóm tạo xong nhưng bật link lỗi: đang chờ mẹ dán link
        fake.willCreate(new Created(GroupOutcome.OK, null, "6666", null, List.of("70000001", "70000002", "70000003", "70000004", "70000005"), List.of(), "OK", 0, false));
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(groups.list().getFirst().status()).isNotEqualTo(GroupStatus.OPEN);
        later(120);
        assertThat(svc.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.creates).hasSize(1);
    }

    @Test
    void khachVuaLoiThemNhomKhongLotVaoNhomMoi() {
        mode(ZaloGroupMode.CONFIRM);
        friended(1, 2, 3, 4);
        svc.step();
        svc.approve(svc.recent(1).getFirst().id(), "mẹ");
        jdbc.sql("UPDATE invitation SET zalo_add_state = 'FAILED', zalo_add_code = 'Z1' WHERE id = :id").param("id", inv(4)).update();
        assertThat(svc.step()).isEqualTo(Step.CREATED);
        assertThat(fake.creates).singleElement().satisfies(c -> assertThat(c.members()).containsExactly("70000001", "70000002", "70000003"));
    }

    @Test
    void tenNhomKhongLapTenDaCo() {
        mode(ZaloGroupMode.CONFIRM);
        groups.create("Áo điều hòa ADH - Cần Thơ - 01", "https://zalo.me/g/cu", 5, null); // nhóm chung trùng tên, khác khu vực
        friended(1, 2, 3);
        svc.step();
        assertThat(svc.recent(1).getFirst().groupName()).isEqualTo("Áo điều hòa ADH - Cần Thơ - 02");
    }
}
