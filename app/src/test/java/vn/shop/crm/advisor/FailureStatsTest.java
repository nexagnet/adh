package vn.shop.crm.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.invite.InviteService;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thống kê khách gửi lỗi đưa cho AI đề xuất (25/11/2026): đếm đúng từng loại lỗi theo toàn hệ thống, khu vực và chiến dịch; mã lỗi Zalo (Z223);
 * và QUAN TRỌNG: khách thuộc số thử không bao giờ lọt vào số liệu thật, JSON không có dữ liệu cá nhân.
 */
class FailureStatsTest extends AbstractPostgresTest {

    @Autowired AdvisorSnapshotService service;
    @Autowired FailureStats stats;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;

    private final List<Long> ids = new ArrayList<>();
    private long campaign;

    private long insert(int n, String province) {
        return jdbc.sql("""
                INSERT INTO customer (phone, name, address, segment, successful_purchases, province, last_order_on)
                VALUES (:phone, :name, :address, 'ONE_TIME', 1, :province, now()) RETURNING id""")
                .param("phone", String.format("+84911000%03d", n))
                .param("name", "Tran Thi BiMat" + n)
                .param("address", "So " + n + " Duong BiMat")
                .param("province", province).query(Long.class).single();
    }

    /** 12 khách TP.HCM đã duyệt, một chiến dịch đủ cả 12. */
    @BeforeEach
    void seed() {
        for (int i = 1; i <= 12; i++) {
            ids.add(insert(i, "tphcm"));
            contacts.approve(ids.getLast());
        }
        campaign = invites.createCampaign("Thử", "Chào {ten}", 14, 12);
    }

    private List<Long> invitationIds() {
        return jdbc.sql("SELECT id FROM invitation WHERE campaign_id = :c ORDER BY id").param("c", campaign).query(Long.class).list();
    }

    private void fail(long invitation, String error) {
        jdbc.sql("UPDATE invitation SET status = 'FAILED', last_error = :e WHERE id = :id").param("e", error).param("id", invitation).update();
    }

    private void sent(long invitation) {
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now() WHERE id = :id").param("id", invitation).update();
    }

    private void friendLog(long invitation, String code) {
        jdbc.sql("""
                INSERT INTO send_log (invitation_id, campaign_id, phone_tail, channel, outcome, reason, recorded_by)
                VALUES (:i, :c, '001', 'ZALO', 'SKIPPED', :r, 'test')""")
                .param("i", invitation).param("c", campaign).param("r", "Lời mời kết bạn không gửi được (mã " + code + ")").update();
    }

    /** 10 đã nhắn: 2 không có Zalo, 1 Zalo từ chối, 1 lỗi khác, 1 bị chặn người lạ, 1 lời mời lỗi (Z223), 1 lời mời không rõ, 1 thêm nhóm lỗi, 2 thường. */
    private void scenario() {
        List<Long> inv = invitationIds();
        fail(inv.get(0), "Không tìm thấy trên Zalo (số này chưa dùng Zalo)");
        fail(inv.get(1), "Không tìm thấy trên Zalo");
        fail(inv.get(2), "Zalo từ chối gửi cho người này (mã 114)");
        fail(inv.get(3), "Bộ nối báo lỗi lạ");
        sent(inv.get(4));
        jdbc.sql("UPDATE invitation SET tin1_blocked_at = now() WHERE id = :id").param("id", inv.get(4)).update();
        sent(inv.get(5));
        jdbc.sql("UPDATE invitation SET friend_request_outcome = 'FAILED' WHERE id = :id").param("id", inv.get(5)).update();
        friendLog(inv.get(5), "Z223");
        sent(inv.get(6));
        jdbc.sql("UPDATE invitation SET friend_request_outcome = 'UNKNOWN' WHERE id = :id").param("id", inv.get(6)).update();
        sent(inv.get(7));
        jdbc.sql("UPDATE invitation SET zalo_add_state = 'FAILED', zalo_add_code = 'Z88' WHERE id = :id").param("id", inv.get(7)).update();
        sent(inv.get(8));
        sent(inv.get(9));
        // inv 10, 11: chưa gửi (READY), không tính
    }

    @Test
    void demDungTungLoaiLoiToanHeThong() {
        scenario();
        AdvisorSnapshot.Failures f = stats.overall();

        assertThat(f).isNotNull();
        assertThat(f.tried()).isEqualTo(10);
        assertThat(f.noZalo()).isEqualTo(2);
        assertThat(f.personRejected()).isEqualTo(1);
        assertThat(f.otherUnsendable()).isEqualTo(1);
        assertThat(f.strangerBlocked()).isEqualTo(1);
        assertThat(f.friendRequestFailed()).isEqualTo(1);
        assertThat(f.friendRequestUnknown()).isEqualTo(1);
        assertThat(f.addToGroupFailed()).isEqualTo(1);
        assertThat(f.friendRequestCodes()).containsEntry("Z223", 1L);
        assertThat(f.addToGroupCodes()).containsEntry("Z88", 1L);
    }

    @Test
    void chuaNhanKhachNaoThiKhongCoSoLieuLoi() {
        assertThat(stats.overall()).isNull();
        assertThat(service.build().insights()).isNull();
    }

    @Test
    void maLoiLaThiBiDoiThanhKhacKhongLotChuoiTuDo() {
        List<Long> inv = invitationIds();
        sent(inv.get(0));
        friendLog(inv.get(0), "Z223");
        sent(inv.get(1));
        jdbc.sql("UPDATE invitation SET zalo_add_state = 'FAILED', zalo_add_code = :c WHERE id = :id")
                .param("c", "0900111222 gọi mẹ nhé").param("id", inv.get(1)).update();

        AdvisorSnapshot.Failures f = stats.overall();
        assertThat(f.addToGroupCodes()).containsOnlyKeys("KHAC");
        assertThat(service.build().toJson()).doesNotContain("0900111222", "gọi mẹ");
    }

    @Test
    void khuVucVaChienDichChiHienKhiDuMauVaDeXuatDuocLoiTrongJson() {
        scenario();
        AdvisorSnapshot s = service.build();

        assertThat(s.insights()).isNotNull();
        assertThat(s.insights().failures().tried()).isEqualTo(10); // đủ 10 nên có thống kê khu vực
        assertThat(s.insights().failuresByArea()).hasSize(1);
        assertThat(s.insights().failuresByArea().getFirst().code()).isEqualTo("S:DONG_NAM_BO");
        assertThat(s.insights().failuresByArea().getFirst().failures().noZalo()).isEqualTo(2);
        assertThat(s.insights().friendRequestDaily()).isNotNull();

        var c = s.campaigns().getFirst();
        assertThat(c.failures()).isNotNull();
        assertThat(c.failures().noZalo()).isEqualTo(2);

        String json = s.toJson();
        assertThat(json).contains("\"failures\"", "\"failures_by_area\"", "\"friend_request_daily\"", "\"Z223\"", "\"no_zalo\"", "\"stranger_blocked\"");
        assertThat(json).doesNotContain("BiMat", "Tran Thi", "+84", "911000", "Duong", "http");
        assertThat(json).doesNotContainPattern("\\d{8,}");
    }

    @Test
    void khuVucDuoiNguongVaChienDichChuaNhanDuKhachThiBoQua() {
        List<Long> inv = invitationIds();
        for (int i = 0; i < 4; i++) { // chỉ 4 khách đã nhắn: dưới 5 cho chiến dịch, dưới 10 cho khu vực
            fail(inv.get(i), "Không tìm thấy trên Zalo");
        }
        AdvisorSnapshot s = service.build();

        assertThat(s.insights().failures().tried()).isEqualTo(4);
        assertThat(s.insights().failuresByArea()).isNull();
        assertThat(s.campaigns().getFirst().failures()).isNull();
    }

    @Test
    void khachThuKhongBaoGioLotVaoSoLieuThat() {
        scenario();
        long before = stats.overall().tried();
        // 3 khách thuộc số thử, đều lỗi: không được tính
        for (int i = 0; i < 3; i++) {
            Long c = ids.get(10 + (i % 2)); // hai khách còn READY, và khách thứ 3 dùng lại để thử trùng
            jdbc.sql("INSERT INTO zalo_test_number (phone, created_by) SELECT phone, 'test' FROM customer WHERE id = :id ON CONFLICT DO NOTHING")
                    .param("id", c).update();
        }
        List<Long> inv = invitationIds();
        fail(inv.get(10), "Không tìm thấy trên Zalo");
        fail(inv.get(11), "Không tìm thấy trên Zalo");

        assertThat(stats.overall().tried()).isEqualTo(before);
        assertThat(stats.overall().noZalo()).isEqualTo(2);
        AdvisorSnapshot s = service.build();
        assertThat(s.campaigns().getFirst().failures().tried()).isEqualTo(before);
        assertThat(s.overall().customers()).isEqualTo(10); // 12 khách trừ 2 khách thử
    }

    @Test
    void khiMoiKhachDeuLaSoThuThiKhongConChienDichHayLoiNao() {
        scenario();
        jdbc.sql("INSERT INTO zalo_test_number (phone, created_by) SELECT phone, 'test' FROM customer").update();

        AdvisorSnapshot s = service.build();
        assertThat(s.overall().customers()).isZero();
        assertThat(s.campaigns()).isEmpty();
        assertThat(s.insights()).isNull();
        assertThat(s.overall().eligible()).isZero();
    }

    @Test
    void loiDuocGhiTrongPromptChoAi() {
        assertThat(CampaignAdvisor.SYSTEM).contains("failures", "failures_by_area", "friend_request_daily", "Z223");
    }
}
