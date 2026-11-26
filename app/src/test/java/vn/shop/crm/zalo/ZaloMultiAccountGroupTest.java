package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloGroupCreationService.Step;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D23, PR 5: nhóm Zalo thuộc về tài khoản đã tạo ra nó (mã nhóm Zalo chỉ có nghĩa trong tài khoản đó); đề xuất tạo nhóm, khoảng nghỉ và
 * khách chờ tính riêng từng tài khoản; mẹ làm phó nhóm ở nhóm của MỌI tài khoản (cấu hình phó nhóm riêng từng tài khoản).
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.group.deputy-id=99887766",
        "app.zalo.multi.enabled=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].live=true",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2"})
class ZaloMultiAccountGroupTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloGroupCreationService svc;
    @Autowired ZaloGroupSettings cfg;
    @Autowired ZaloAutoSettings auto;
    @Autowired ZaloAccountRegistry registry;
    @Autowired ZaloDeputyService deputies;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 6; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",090000000").append(i).append(",1 Duong A Xa B Cần Thơ,Giao thành công\n");
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
        registry.ensureRows();
    }

    private long inv(int n) {
        return jdbc.sql("SELECT i.id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p")
                .param("p", "+8490000000" + n).query(Long.class).single();
    }

    /** Khách n đã kết bạn với tài khoản {@code account}, chờ thêm vào nhóm. */
    private void friended(long account, int... ns) {
        for (int n : ns) {
            jdbc.sql("""
                            UPDATE invitation SET status = 'FRIENDED', sent_at = now() - interval '2 days', account_id = :a,
                                   friended_at = now() - make_interval(hours => :h), zalo_uid = :u, zalo_add_state = 'WAITING_APPROVAL'
                            WHERE id = :id""").param("a", account).param("h", 100 - n).param("u", "7000000" + n).param("id", inv(n)).update();
        }
    }

    private Step stepAs(long account) {
        return ZaloAccountContext.call(account, svc::step);
    }

    @Test
    void khachChoCuaTaiKhoanNaoThiTaiKhoanDoDeXuatNhom() {
        ZaloAccountContext.run(2, () -> cfg.setMode(ZaloGroupMode.CONFIRM, false, "mẹ"));
        cfg.setMode(ZaloGroupMode.CONFIRM, false, "mẹ");
        friended(2, 1, 2, 3);
        assertThat(stepAs(1)).isNotEqualTo(Step.PROPOSED); // tài khoản 1 không có khách chờ
        assertThat(stepAs(2)).isEqualTo(Step.PROPOSED);
        assertThat(jdbc.sql("SELECT account_id FROM zalo_group_proposal").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void nhomTaoRaThuocTaiKhoanTaoVaChiKhachCuaTaiKhoanDoVaoNhom() {
        ZaloAccountContext.run(2, () -> cfg.setMode(ZaloGroupMode.CONFIRM, false, "mẹ"));
        friended(2, 1, 2, 3);
        friended(1, 4, 5, 6);
        assertThat(stepAs(2)).isEqualTo(Step.PROPOSED);
        long pid = jdbc.sql("SELECT id FROM zalo_group_proposal").query(Long.class).single();
        ZaloAccountContext.run(2, () -> svc.approve(pid, "mẹ"));
        assertThat(stepAs(2)).isEqualTo(Step.CREATED);
        assertThat(fake.creates).singleElement().satisfies(c -> assertThat(c.members()).containsExactly("70000001", "70000002", "70000003"));
        assertThat(jdbc.sql("SELECT account_id FROM zalo_group").query(Long.class).single()).isEqualTo(2L);
        assertThat(groups.listOfAccount(2)).hasSize(1);
        assertThat(groups.listOfAccount(1)).isEmpty();
    }

    @Test
    void khoangNghiTaoNhomTinhRiengTungTaiKhoan() {
        ZaloAccountContext.run(2, () -> cfg.setMode(ZaloGroupMode.AUTO, true, "mẹ"));
        friended(2, 1, 2, 3);
        assertThat(stepAs(2)).isEqualTo(Step.CREATED);
        assertThat(ZaloAccountContext.call(2, svc::mayCreateNow)).isFalse(); // vừa tạo: đang nghỉ
        assertThat(ZaloAccountContext.call(1, svc::mayCreateNow)).isTrue();   // tài khoản 1 chưa tạo gì
    }

    @Test
    void nhomTaoThanhCongVanDuocGhiKhiCauNoiSauDoBiHanChe() {
        ZaloAccountContext.run(2, () -> cfg.setMode(ZaloGroupMode.AUTO, true, "mẹ"));
        friended(2, 1, 2, 3);
        fake.statusAfterCreate = new ZaloGateway.Status(ZaloGateway.State.TRIPPED, "RESTRICTED:Z999", true, null, null, null);

        assertThat(stepAs(2)).isEqualTo(Step.TRIPPED);
        assertThat(groups.listOfAccount(2)).hasSize(1);
        assertThat(jdbc.sql("SELECT state FROM zalo_group_proposal").query(String.class).single()).isEqualTo("DONE");
        assertThat(auto.tripped()).isTrue();
        assertThat(auto.sharedHaltSource()).isEqualTo(2L);
    }

    @Test
    void maPhoNhomTuMoiTruongChiLaCuaTaiKhoanMot() {
        // mã mẹ nhìn từ tài khoản 1; tài khoản khác phải tự tra và xác nhận mẹ, không được dùng nhầm mã này
        assertThat(deputies.effectiveUid()).isEqualTo("99887766");
        assertThat(ZaloAccountContext.call(2, deputies::effectiveUid)).isEmpty();
        fake.willFind(new ZaloPersonFinder.Found(ZaloPersonFinder.FindResult.OK, null, "5151", "Mẹ Lan", null, 0));
        ZaloAccountContext.run(2, () -> {
            deputies.lookup("0912345678", "admin");
            deputies.confirm("admin");
        });
        assertThat(ZaloAccountContext.call(2, deputies::effectiveUid)).isEqualTo("5151");
        assertThat(deputies.effectiveUid()).isEqualTo("99887766");
    }
}
