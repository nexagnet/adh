package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.inbox.ComebackService;
import vn.shop.crm.inbox.RefusalClassifier;
import vn.shop.crm.inbox.RefusalReviewService;
import vn.shop.crm.inbox.ReplyService;
import vn.shop.crm.inbox.WarrantyService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D23, PR 6: tin riêng của khách và việc trả lời chỉ thuộc về TÀI KHOẢN đã nhắn khách đó. Mã người nhận khác nhau theo tài khoản hỏi, nên mọi
 * tra cứu theo mã Zalo, hàng chờ trả lời và hàng chờ chào khách quay lại đều lọc theo tài khoản; tài khoản khác không thấy và không gửi.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.multi.enabled=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2",
        "app.zalo.accounts[0].live=true"})
class ZaloMultiAccountInboxTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired ReplyService replies;
    @Autowired RefusalReviewService reviews;
    @Autowired ComebackService comebacks;
    @Autowired WarrantyService warranty;
    @Autowired ZaloAccountRegistry registry;

    long invitation;
    long customer;

    @BeforeEach
    void setUp() {
        String csv = HEADER + "T1,DH1,05/01/2026 09:00:00,Khach 1,0900000001,1 Duong A Xa B Hà Nội,Giao thành công\n"
                + "T2,DH2,05/01/2026 09:00:00,Khach 2,0900000002,1 Duong A Xa B Hà Nội,Giao thành công\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        registry.ensureRows();
        invitation = jdbc.sql("SELECT min(id) FROM invitation").query(Long.class).single();
        customer = jdbc.sql("SELECT customer_id FROM invitation WHERE id = :i").param("i", invitation).query(Long.class).single();
        // khách này do tài khoản 2 nhắn, mã Zalo "U2" là mã NHÌN TỪ tài khoản 2
        jdbc.sql("UPDATE invitation SET account_id = 2, status = 'SENT', zalo_uid = 'U2', zalo_target_uid = 'U2' WHERE id = :i")
                .param("i", invitation).update();
    }

    @Test
    void tinRiengChiTraCuuDuocTrongDungTaiKhoan() {
        assertThat(reviews.targetFor("U2")).isEmpty(); // tài khoản 1 không biết người này
        assertThat(ZaloAccountContext.call(2, () -> reviews.targetFor("U2"))).isPresent();
    }

    @Test
    void khachDaVaoNhomChiDuocTraLoiBoiDungTaiKhoan() {
        jdbc.sql("UPDATE invitation SET status = 'JOINED' WHERE id = :i").param("i", invitation).update();
        assertThat(replies.joinedSubject("U2")).isEmpty();
        assertThat(ZaloAccountContext.call(2, () -> replies.joinedSubject("U2"))).isPresent();
    }

    @Test
    void hangChoTraLoiChiGuiBoiDungTaiKhoan() {
        jdbc.sql("""
                INSERT INTO reply_draft (account_id, customer_id, invitation_id, zalo_uid, state, reply_text, inbound_at)
                VALUES (2, :c, :i, 'U2', 'READY', 'Dạ em chào bác', now())""").param("c", customer).param("i", invitation).update();
        assertThat(replies.nextReady()).isEmpty();
        long draft = ZaloAccountContext.call(2, replies::nextReady).orElseThrow().id();
        assertThat(replies.claim(draft)).isFalse();

        jdbc.sql("DELETE FROM invitation WHERE id = :i").param("i", invitation).update();
        assertThat(replies.nextReady()).isEmpty();
        assertThat(ZaloAccountContext.call(2, () -> replies.claim(draft))).isTrue();
    }

    @Test
    void hangChoChaoKhachQuayLaiChiGuiBoiDungTaiKhoan() {
        jdbc.sql("""
                INSERT INTO comeback (customer_id, invitation_id, state, reason, welcome_state, lifted_at)
                VALUES (:c, :i, 'LIFTED', 'AI_CHAC', 'PENDING', now())""").param("c", customer).param("i", invitation).update();
        assertThat(comebacks.nextWelcome()).isEmpty();
        assertThat(ZaloAccountContext.call(2, comebacks::nextWelcome)).isPresent();
    }

    @Test
    void cungUidHaiTaiKhoanCoHaiViecTraLoiDocLap() {
        replies.setMode(ReplyService.Mode.DRAFT, false, "test");
        var verdict = new RefusalClassifier.Verdict(RefusalClassifier.Outcome.INVALID, null, 0);
        Instant at = Instant.now();
        replies.handle(primarySubjectWithSameUid(), verdict, "Tin tài khoản một", at);
        ZaloAccountContext.run(2, () -> replies.handle(new ReplyService.Subject(customer, invitation, "U2"), verdict, "Tin tài khoản hai", at));
        assertThat(jdbc.sql("SELECT inbound_text FROM reply_draft ORDER BY id").query(String.class).list())
                .containsExactly("Tin tài khoản một", "Tin tài khoản hai");
    }

    @Test
    void cungUidHaiTaiKhoanCoHaiHoSoBaoHanhDocLap() {
        warranty.setEnabled(true, "test");
        var verdict = new RefusalClassifier.Verdict(RefusalClassifier.Outcome.OK, RefusalClassifier.Label.KHIEU_NAI, 1);
        Instant at = Instant.now();
        warranty.take(primarySubjectWithSameUid(), verdict, "Lỗi tài khoản một", at);
        ZaloAccountContext.run(2, () -> warranty.take(new ReplyService.Subject(customer, invitation, "U2"), verdict, "Lỗi tài khoản hai", at));
        assertThat(jdbc.sql("SELECT inbound_text FROM warranty_case ORDER BY id").query(String.class).list())
                .containsExactly("Lỗi tài khoản một", "Lỗi tài khoản hai");
    }

    private ReplyService.Subject primarySubjectWithSameUid() {
        return jdbc.sql("""
                        UPDATE invitation SET zalo_uid = 'U2' WHERE account_id = 1 RETURNING id, customer_id""")
                .query((rs, row) -> new ReplyService.Subject(rs.getLong("customer_id"), rs.getLong("id"), "U2")).single();
    }
}
