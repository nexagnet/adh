package vn.shop.crm.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.invite.CampaignSteps.Step;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D21 PR 5a: cấu hình gửi mời nhiều lần. Mặc định 1 lần; tối đa 3 lần mỗi khách (cả ở mã lẫn cơ sở dữ liệu); khoảng cách tối thiểu 3 ngày;
 * tin nhắc không ép, không link, không số điện thoại, không trùng; Tin 1 của chiến dịch nhiều lần không còn "sẽ không nhắn lại";
 * Tin 1 chỉ sửa được khi chưa gửi; chưa có gì được gửi.
 */
class CampaignStepsTest extends AbstractPostgresTest {

    static final String ONE = "Chào anh/chị {ten}, em bên {cua_hang} mời mình vào nhóm ạ.";
    static final String NO_MORE = ONE + " Không cần thì bỏ qua, em sẽ không nhắn lại ạ.";
    static final String FOLLOW = "Em nhắn lại phòng khi mình chưa kịp xem tin trước ạ.";
    static final String FOLLOW2 = "Mình cần hướng dẫn dùng sản phẩm thì cứ kết bạn với em nhé.";

    @Autowired CampaignSteps steps;
    long id;

    @BeforeEach
    void setUp() {
        id = campaign(ONE);
    }

    private long campaign(String template) {
        return jdbc.sql("INSERT INTO invitation_campaign (name, message_template, expires_days, created_by) VALUES ('Đợt', :t, 14, 'test') RETURNING id")
                .param("t", template).query(Long.class).single();
    }

    private Step s(int no, String text, int gap) {
        return new Step(no, text, gap);
    }

    @Test
    void macDinhMotLanVaChuaCoTinNhac() {
        var plan = steps.plan(id);
        assertThat(plan.maxTouches()).isEqualTo(1);
        assertThat(plan.multiple()).isFalse();
        assertThat(plan.followups()).isEmpty();
    }

    @Test
    void luuHaiLanVaBaLanRoiVeMotLan() {
        steps.saveFollowups(id, List.of(s(2, FOLLOW, 3)), "mẹ");
        assertThat(steps.plan(id).maxTouches()).isEqualTo(2);
        steps.saveFollowups(id, List.of(s(2, FOLLOW, 4), s(3, FOLLOW2, 7)), "mẹ");
        var plan = steps.plan(id);
        assertThat(plan.maxTouches()).isEqualTo(3);
        assertThat(plan.step(2).gapDays()).isEqualTo(4);
        assertThat(plan.step(3).template()).isEqualTo(FOLLOW2);
        steps.saveFollowups(id, List.of(), "mẹ");
        assertThat(steps.plan(id).maxTouches()).isEqualTo(1);
        assertThat(steps.plan(id).followups()).isEmpty();
    }

    @Test
    void khongQuaBaLanMoiKhach() {
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, FOLLOW, 3), s(3, FOLLOW2, 3), s(4, "Tin thứ tư khác nữa ạ", 3)), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("tối đa 3 lần");
        // cơ sở dữ liệu cũng chặn, kể cả khi mã có lỗi
        assertThatThrownBy(() -> jdbc.sql("UPDATE invitation_campaign SET max_touches = 4 WHERE id = :id").param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO campaign_step (campaign_id, step_no, template, gap_days) VALUES (:id, 4, 'x', 3)")
                .param("id", id).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(steps.plan(id).maxTouches()).isEqualTo(1);
    }

    @Test
    void khoangCachToiThieuBaNgay() {
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, FOLLOW, 2)), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("từ 3 đến 30 ngày");
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, FOLLOW, 31)), "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO campaign_step (campaign_id, step_no, template, gap_days) VALUES (:id, 2, 'x', 2)")
                .param("id", id).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(steps.plan(id).followups()).isEmpty();
    }

    @Test
    void soDemCungKhongChoMotKhachNhanQuaBaLanHoacTrungBuoc() {
        long customer = jdbc.sql("INSERT INTO customer (phone, name, segment, successful_purchases) VALUES ('+84900009999', 'K', 'ONE_TIME', 1) RETURNING id")
                .query(Long.class).single();
        long inv = jdbc.sql("INSERT INTO invitation (campaign_id, customer_id, token, expires_at) VALUES (:c, :k, 'tok-a', now() + interval '5 days') RETURNING id")
                .param("c", id).param("k", customer).query(Long.class).single();
        for (int no = 1; no <= 3; no++) {
            jdbc.sql("INSERT INTO invitation_touch (invitation_id, step_no, outcome) VALUES (:i, :n, 'SENT')").param("i", inv).param("n", no).update();
        }
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO invitation_touch (invitation_id, step_no, outcome) VALUES (:i, 4, 'SENT')").param("i", inv).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO invitation_touch (invitation_id, step_no, outcome) VALUES (:i, 2, 'SENT')").param("i", inv).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tinNhacKhongDuocEpLinkSoDienThoaiHayChoTrongLa() {
        for (String bad : new String[]{
                "Đây là lần cuối cùng em nhắn ạ.", "Chỉ còn hôm nay thôi ạ.", "Anh chị cần gấp thì nhắn em nhé.", "Đừng bỏ lỡ ưu đãi ạ.",
                "Em cam kết hỗ trợ ạ.", "Vào zalo.me/g/abc nhé.", "Xem www.shop.vn nhé.", "Gọi 0901234567 nhé.", "Gửi mail a@b.com nhé.",
                "Chào {ten_khach} ạ.", "Chào {link} ạ."}) {
            assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, bad, 3)), "mẹ"))
                    .as(bad).isInstanceOf(ImportException.class);
        }
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, "   ", 3)), "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, "x".repeat(501), 3)), "mẹ")).isInstanceOf(ImportException.class);
        // "gặp" (gặp mặt) khác "gấp" (vội): không bị chặn nhầm
        steps.saveFollowups(id, List.of(s(2, "Em rất vui được gặp anh chị trong nhóm ạ, {ten} cần gì cứ nhắn em nhé.", 3)), "mẹ");
        assertThat(steps.plan(id).maxTouches()).isEqualTo(2);
    }

    @Test
    void tinNhacPhaiKhacTinLienTruoc() {
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, ONE, 3)), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("khác nội dung");
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, FOLLOW, 3), s(3, "  " + FOLLOW.toUpperCase() + " ", 3)), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("khác nội dung");
    }

    @Test
    void tinNhacKhongDuocNoiSeKhongNhanLai() {
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, "Em sẽ không nhắn lại nữa ạ.", 3)), "mẹ"))
                .isInstanceOf(ImportException.class);
    }

    @Test
    void tin1CuaChienDichNhieuLanKhongConCauKhongNhanLai() {
        long old = campaign(NO_MORE); // đúng kiểu mẫu D18 hiện nay
        assertThatThrownBy(() -> steps.saveFollowups(old, List.of(s(2, FOLLOW, 3)), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("Tin 1");
        assertThat(steps.plan(old).maxTouches()).isEqualTo(1);
        // chiến dịch 1 lần vẫn dùng được mẫu cũ
        assertThat(InviteService.DEFAULT_TEMPLATE).contains("em sẽ không nhắn lại");
        // đổi Tin 1 sang mẫu gợi ý thì lưu được
        steps.saveFirstMessage(old, InviteService.MULTI_TEMPLATE, "mẹ");
        assertThat(InviteService.MULTI_TEMPLATE).doesNotContain("không nhắn lại");
        steps.saveFollowups(old, List.of(s(2, FOLLOW, 3)), "mẹ");
        assertThat(steps.plan(old).maxTouches()).isEqualTo(2);
        // và khi đã nhiều lần thì không đưa lại câu cũ vào Tin 1 được
        assertThatThrownBy(() -> steps.saveFirstMessage(old, NO_MORE, "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> steps.saveFirstMessage(old, FOLLOW, "mẹ")).isInstanceOf(ImportException.class).hasMessageContaining("trùng");
    }

    @Test
    void tin1ChiSuaDuocKhiChuaGuiChoKhachNao() {
        long customer = jdbc.sql("INSERT INTO customer (phone, name, segment, successful_purchases) VALUES ('+84900008888', 'K', 'ONE_TIME', 1) RETURNING id")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO invitation (campaign_id, customer_id, token, expires_at) VALUES (:c, :k, 'tok-b', now() + interval '5 days')")
                .param("c", id).param("k", customer).update();
        steps.saveFirstMessage(id, "Tin 1 mới {ten}", "mẹ");
        assertThat(jdbc.sql("SELECT message_template FROM invitation_campaign WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("Tin 1 mới {ten}");
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now()").update();
        assertThatThrownBy(() -> steps.saveFirstMessage(id, "Lại đổi", "mẹ")).isInstanceOf(ImportException.class).hasMessageContaining("đã gửi");
        assertThatThrownBy(() -> steps.saveFirstMessage(id, "  ", "mẹ")).isInstanceOf(ImportException.class);
    }

    @Test
    void chienDichKhongCoThiBaoLoiVaLoiThiKhongLuuGi() {
        assertThatThrownBy(() -> steps.saveFollowups(999999, List.of(), "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> steps.saveFirstMessage(999999, "x", "mẹ")).isInstanceOf(ImportException.class);
        steps.saveFollowups(id, List.of(s(2, FOLLOW, 3)), "mẹ");
        // lần lưu sau có tin thứ hai sai: không đổi gì của lần trước
        assertThatThrownBy(() -> steps.saveFollowups(id, List.of(s(2, FOLLOW2, 5), s(3, "Lần cuối cùng ạ", 5)), "mẹ"))
                .isInstanceOf(ImportException.class);
        var plan = steps.plan(id);
        assertThat(plan.maxTouches()).isEqualTo(2);
        assertThat(plan.step(2).template()).isEqualTo(FOLLOW);
    }

    @Test
    void chuaGuiGiOBuocNay() {
        steps.saveFollowups(id, List.of(s(2, FOLLOW, 3)), "mẹ");
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_msg_log").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation_touch").query(Long.class).single()).isZero();
    }
}
