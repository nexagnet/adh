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
 * Bản tổng hợp ẨN DANH đưa cho AI đề xuất chiến dịch (D21): đếm đúng, ẩn vùng ít khách, thời tiết trung bình theo số khách, và QUAN TRỌNG NHẤT
 * không có dữ liệu cá nhân nào lọt vào chuỗi JSON gửi đi.
 */
class AdvisorSnapshotTest extends AbstractPostgresTest {

    @Autowired AdvisorSnapshotService service;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;

    private long insert(int n, String province, String lastOrder) {
        return jdbc.sql("""
                INSERT INTO customer (phone, name, address, segment, successful_purchases, province, last_order_on)
                VALUES (:phone, :name, :address, 'ONE_TIME', 1, :province, CAST(:last AS timestamp)) RETURNING id""")
                .param("phone", String.format("+84900000%03d", n))
                .param("name", "Nguyen Van BiMat" + n)
                .param("address", "So " + n + " Duong BiMat Xa Z")
                .param("province", province, java.sql.Types.VARCHAR)
                .param("last", lastOrder, java.sql.Types.VARCHAR).query(Long.class).single();
    }

    private String ago(int days) {
        return jdbc.sql("SELECT to_char(now() AT TIME ZONE 'Asia/Ho_Chi_Minh' - make_interval(days => :d), 'YYYY-MM-DD HH24:MI:SS')")
                .param("d", days).query(String.class).single();
    }

    private final List<Long> hcm = new ArrayList<>();

    @BeforeEach
    void seed() {
        int n = 0;
        // TP.HCM: 12 khách; mua cách đây 10, 60, 120, 300 ngày và một khách chưa có ngày mua
        int[] ages = {10, 10, 10, 60, 60, 60, 120, 120, 120, 300, 300, -1};
        for (int age : ages) {
            hcm.add(insert(++n, "tphcm", age < 0 ? null : ago(age)));
        }
        for (int i = 0; i < 5; i++) {
            insert(++n, "binh-duong", ago(5));
        }
        for (int i = 0; i < 4; i++) {
            insert(++n, "ha-giang", ago(5));
        }
        for (int i = 0; i < 2; i++) {
            insert(++n, null, ago(5));
        }
        // duyệt 6 khách TP.HCM (đủ điều kiện mời)
        for (int i = 0; i < 6; i++) {
            contacts.approve(hcm.get(i));
        }
    }

    private void forecast(String province, double min, double max, double rain) {
        jdbc.sql("""
                INSERT INTO weather_forecast (province, min_temp, max_temp, symbol, hourly, expires_at)
                VALUES (:p, :min, :max, 'cloudy', CAST(:h AS jsonb), now() + interval '3 hours')""")
                .param("p", province).param("min", min).param("max", max)
                .param("h", "[[1700000000," + min + "," + rain + ",50,\"cloudy\"]]").update();
    }

    @Test
    void demDungKhachDoMoiVaKhoKhachMoi() {
        AdvisorSnapshot s = service.build();

        assertThat(s.overall().customers()).isEqualTo(23);
        assertThat(s.overall().oneTime()).isEqualTo(23);
        assertThat(s.overall().unknownRegion()).isEqualTo(2);
        assertThat(s.overall().hiddenSmallAreas()).isEqualTo(1); // Đông Bắc chỉ 4 khách
        assertThat(s.overall().eligible()).isEqualTo(6);
        assertThat(s.overall().awaitingApproval()).isEqualTo(17); // 23 trừ 6 đã duyệt

        assertThat(s.areas()).hasSize(1);
        AdvisorSnapshot.Area a = s.areas().getFirst();
        assertThat(a.code()).isEqualTo("S:DONG_NAM_BO");
        assertThat(a.label()).isEqualTo("Đông Nam Bộ");
        assertThat(a.region()).isEqualTo("Miền Nam");
        assertThat(a.customers()).isEqualTo(17);
        assertThat(a.eligible()).isEqualTo(6);
        assertThat(a.awaitingApproval()).isEqualTo(11);
        assertThat(a.newLast30Days()).isEqualTo(17);
        assertThat(a.recency()).isEqualTo(new AdvisorSnapshot.Recency(8, 3, 3, 2, 1));
        // chỉ tỉnh đủ 10 khách mới được liệt kê riêng
        assertThat(a.topProvinces()).extracting(AdvisorSnapshot.ProvinceInfo::code).containsExactly("tphcm");
        assertThat(a.weather()).isNull(); // chưa có dự báo thì không bịa
    }

    @Test
    void phayLoiMoiVaKhungGioGui() {
        long campaign = invites.createCampaign("Thử", "Chào {ten}", 14, 3);
        List<Long> ids = jdbc.sql("SELECT id FROM invitation WHERE campaign_id = :c ORDER BY id").param("c", campaign)
                .query(Long.class).list();
        assertThat(ids).hasSize(3);
        String[] hours = {"09:30", "15:10", "22:00"}; // giờ Việt Nam
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                    UPDATE invitation SET status = CASE WHEN :i = 0 THEN 'JOINED' WHEN :i = 1 THEN 'FRIENDED' ELSE 'SENT' END,
                        sent_at = (date '2026-11-20' + CAST(:t AS time)) AT TIME ZONE 'Asia/Ho_Chi_Minh',
                        friended_at = CASE WHEN :i < 2 THEN now() END, joined_at = CASE WHEN :i = 0 THEN now() END
                    WHERE id = :id""").param("t", hours[i]).param("i", i).param("id", ids.get(i)).update();
        }
        long invitedCustomer = jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", ids.get(2))
                .query(Long.class).single();
        contacts.suppressAs(invitedCustomer, "thử", "test");

        AdvisorSnapshot s = service.build();
        assertThat(s.overall().funnel()).isEqualTo(new AdvisorSnapshot.Funnel(3, 2, 1, 1));
        assertThat(s.overall().eligible()).isEqualTo(3); // 6 đã duyệt trừ 3 đã mời
        AdvisorSnapshot.Area a = s.areas().getFirst();
        assertThat(a.funnel()).isEqualTo(new AdvisorSnapshot.Funnel(3, 2, 1, 1));
        assertThat(s.sendSlots()).extracting(AdvisorSnapshot.Slot::label, AdvisorSnapshot.Slot::sent, AdvisorSnapshot.Slot::friended)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("08-11", 1L, 1L),
                        org.assertj.core.groups.Tuple.tuple("11-14", 0L, 0L),
                        org.assertj.core.groups.Tuple.tuple("14-17", 1L, 1L),
                        org.assertj.core.groups.Tuple.tuple("17-20", 0L, 0L),
                        org.assertj.core.groups.Tuple.tuple("khac", 1L, 0L));
    }

    @Test
    void thoiTietTrungBinhTheoSoKhachCuaTungTinh() {
        forecast("tphcm", 24.0, 34.0, 6.0);      // 12 khách, mưa 6 mm
        forecast("binh-duong", 26.0, 36.0, 0.0); // 5 khách, không mưa
        AdvisorSnapshot.Weather w = service.build().areas().getFirst().weather();
        assertThat(w).isNotNull();
        assertThat(w.avgMinTemp()).isEqualTo(24.6);
        assertThat(w.avgMaxTemp()).isEqualTo(34.6);
        assertThat(w.rainyPercent()).isEqualTo(71); // 12/17 khách ở tỉnh có mưa đáng kể
        assertThat(w.coveragePercent()).isEqualTo(100);
        assertThat(service.build().areas().getFirst().topProvinces().getFirst().minTemp()).isEqualTo(24);
    }

    @Test
    void jsonGuiChoAiKhongCoDuLieuCaNhan() {
        forecast("tphcm", 24.0, 34.0, 6.0);
        invites.createCampaign("Thử", "Chào {ten}", 14, 2);
        String json = service.build().toJson();

        assertThat(json).contains("\"S:DONG_NAM_BO\"");
        assertThat(json).contains("\"awaiting_approval\"", "\"send_slots\"", "\"max_campaign_size\"");
        // không tên, địa chỉ, số điện thoại (đủ dạng), mã khách, email, đường dẫn
        assertThat(json).doesNotContain("BiMat", "Nguyen", "Duong", "+84", "0900000", "@", "http", "Chào {ten}", "Thử");
        assertThat(json).doesNotContainPattern("\\d{8,}");
        assertThat(json).doesNotContainPattern("\\d{3}[ .-]?\\d{3}[ .-]?\\d{3,4}");
    }

    @Test
    void baoChoAiCacChienDichDangChoMaKhongCoTenHayKhach() {
        long id = invites.createCampaign("Chiến dịch BiMat ten rieng", null, 14, 3, "P:tphcm");
        jdbc.sql("UPDATE invitation_campaign SET start_on = CURRENT_DATE, end_on = CURRENT_DATE + 5, daily_cap = 7 WHERE id = :id").param("id", id).update();

        AdvisorSnapshot s = service.build();
        assertThat(s.campaigns()).hasSize(1);
        var c = s.campaigns().getFirst();
        assertThat(c.scope()).isEqualTo("P:tphcm");
        assertThat(c.state()).isEqualTo("CHUA_DUYET");
        assertThat(c.open()).isTrue();
        assertThat(c.total()).isEqualTo(3);
        assertThat(c.ready()).isEqualTo(3);
        assertThat(c.sent()).isZero();
        assertThat(c.dailyCap()).isEqualTo(7);
        assertThat(s.toJson()).doesNotContain("BiMat").doesNotContain("Chiến dịch");

        jdbc.sql("UPDATE invitation_campaign SET auto_send_approved_at = now() WHERE id = :id").param("id", id).update();
        assertThat(service.build().campaigns().getFirst().state()).isEqualTo("DANG_GUI");
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now() WHERE campaign_id = :id").param("id", id).update();
        var done = service.build().campaigns().getFirst();
        assertThat(done.state()).isEqualTo("XONG");
        assertThat(done.sent()).isEqualTo(3);
    }
}
