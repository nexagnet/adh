package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import vn.shop.crm.AbstractPostgresTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D23, PR nền: có đúng MỘT tài khoản Zalo (mã 1), mọi dòng mới mặc định thuộc nó, và các khóa duy nhất của mã Zalo và số thứ tự sự kiện
 * chỉ có nghĩa BÊN TRONG một tài khoản (mã Zalo của cùng một người khác nhau theo từng tài khoản hỏi).
 */
class ZaloAccountFoundationTest extends AbstractPostgresTest {

    private void secondAccount() {
        jdbc.sql("INSERT INTO zalo_account (id, label) VALUES (2, 'Tài khoản phụ 2')").update();
    }

    private void event(long account, long seq) {
        jdbc.sql("""
                INSERT INTO zalo_friend_event (account_id, bridge_seq, uid, event_at, outcome)
                VALUES (:a, :s, '1', now(), 'NO_PHONE')""").param("a", account).param("s", seq).update();
    }

    @Test
    void coMotTaiKhoanMacDinhVaDongMoiThuocNo() {
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_account").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT label FROM zalo_account WHERE id = 1").query(String.class).single()).isEqualTo("Tài khoản phụ 1");
        jdbc.sql("INSERT INTO zalo_friend_event (bridge_seq, uid, event_at, outcome) VALUES (7, '1', now(), 'NO_PHONE')").update();
        jdbc.sql("INSERT INTO zalo_health_alert (kind, severity, opened_at, last_seen_at) VALUES ('X', 'WARN', now(), now())").update();
        assertThat(jdbc.sql("SELECT account_id FROM zalo_friend_event").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT account_id FROM zalo_health_alert").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void soThuTuSuKienChiDuyNhatTrongMotTaiKhoan() {
        secondAccount();
        event(1, 5);
        event(2, 5); // cầu nối thứ hai tự sinh cùng số: không đụng nhau
        assertThatThrownBy(() -> event(1, 5)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void canhBaoSucKhoeMoiTaiKhoanMotDongDangMoMoiLoai() {
        secondAccount();
        String sql = "INSERT INTO zalo_health_alert (account_id, kind, severity, opened_at, last_seen_at) VALUES (:a, 'LOI', 'WARN', now(), now())";
        jdbc.sql(sql).param("a", 1L).update();
        jdbc.sql(sql).param("a", 2L).update();
        assertThatThrownBy(() -> jdbc.sql(sql).param("a", 1L).update()).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void maZaloKhachChiDuyNhatTrongMotTaiKhoan() {
        String idx = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_invitation_zalo_uid'").query(String.class).single();
        assertThat(idx).contains("UNIQUE").contains("(account_id, zalo_uid)");
    }
}
