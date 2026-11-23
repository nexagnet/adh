package vn.shop.crm.performance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phễu chiến dịch, doanh thu đối soát, so sánh mua lại và việc ẩn ô nhỏ, trên dữ liệu thật của Postgres nhúng. */
class PerformanceServiceTest extends AbstractPostgresTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    PerformanceService performance;

    private final AtomicInteger seq = new AtomicInteger();
    private long batchId;

    private long batch() {
        if (batchId == 0) {
            batchId = jdbc.sql("""
                    INSERT INTO import_batch (carrier, filename, sha256, file_content) VALUES ('JT_EXPRESS', 'f.csv', 'x', '\\x00')
                    RETURNING id""").query(Long.class).single();
        }
        return batchId;
    }

    private long campaign(String name, String scope) {
        return jdbc.sql("""
                INSERT INTO invitation_campaign (name, message_template, expires_days, created_by, geo_scope)
                VALUES (:n, 't', 14, 'test', :s) RETURNING id""").param("n", name).param("s", scope).query(Long.class).single();
    }

    /** Một khách có số điện thoại riêng; trả về số điện thoại. */
    private String customer() {
        String phone = "09" + String.format("%08d", seq.incrementAndGet());
        jdbc.sql("""
                INSERT INTO customer (phone, name, segment, successful_purchases) VALUES (:p, 'Tên Khách Bí Mật', 'ONE_TIME', 1)""")
                .param("p", phone).update();
        return phone;
    }

    private static OffsetDateTime ago(int days) {
        return OffsetDateTime.now().minusDays(days);
    }

    /** Lời mời của khách; số ngày null = chưa có mốc đó. */
    private void invite(long campaign, String phone, Integer sentAgo, Integer friendedAgo, Integer joinedAgo, Integer leftAgo) {
        jdbc.sql("""
                INSERT INTO invitation (campaign_id, customer_id, token, status, expires_at, sent_at, friended_at, joined_at, left_at)
                VALUES (:c, (SELECT id FROM customer WHERE phone = :p), :t, :st, now() + interval '30 days', :sent, :fr, :jo, :le)""")
                .param("c", campaign).param("p", phone).param("t", "tok" + seq.incrementAndGet())
                .param("st", joinedAgo != null ? "JOINED" : friendedAgo != null ? "FRIENDED" : sentAgo != null ? "SENT" : "READY")
                .param("sent", sentAgo == null ? null : ago(sentAgo)).param("fr", friendedAgo == null ? null : ago(friendedAgo))
                .param("jo", joinedAgo == null ? null : ago(joinedAgo)).param("le", leftAgo == null ? null : ago(leftAgo)).update();
    }

    private void ship(String phone, String orderRef, String tracking, String status, int daysAgo) {
        jdbc.sql("""
                INSERT INTO shipment (carrier, tracking_no, order_ref, receiver_phone_raw, receiver_phone, created_on, raw_status,
                                      status_key, canonical_status, raw_payload, first_batch_id, last_batch_id)
                VALUES ('JT_EXPRESS', :t, :o, :p, :p, :on, 'x', 'x', :st, '{}'::jsonb, :b, :b)""")
                .param("t", tracking).param("o", orderRef).param("p", phone)
                .param("on", LocalDateTime.now(VN).minusDays(daysAgo)).param("st", status).param("b", batch()).update();
    }

    private void settle(String tracking, int cod, int freight, int codFee) {
        Long period = jdbc.sql("SELECT id FROM settlement_period WHERE period_code = 'K1'").query(Long.class).optional().orElse(null);
        if (period == null) {
            period = jdbc.sql("INSERT INTO settlement_period (carrier, period_code, last_batch_id) VALUES ('JT_EXPRESS', 'K1', :b) RETURNING id")
                    .param("b", batch()).query(Long.class).single();
        }
        jdbc.sql("""
                INSERT INTO settlement_line (period_id, tracking_no, cod_collected, freight_fee, cod_fee, return_fee, partial_fee, adjustment, net_payable)
                VALUES (:p, :t, :cod, :fr, :cf, 0, 0, 0, :net)""")
                .param("p", period).param("t", tracking).param("cod", cod).param("fr", freight).param("cf", codFee)
                .param("net", cod - freight - codFee).update();
    }

    private CampaignPerformance only(PerformanceService.Overview o) {
        assertThat(o.campaigns()).hasSize(1);
        return o.campaigns().getFirst();
    }

    @Test
    void phauMoiKetBanVaoNhom() {
        long c = campaign("Rét miền Bắc", "N");
        for (int i = 0; i < 6; i++) {
            invite(c, customer(), 10, i < 4 ? 9 : null, i < 3 ? 8 : null, null);
        }
        invite(c, customer(), null, null, null, null); // chưa gửi: không tính là đã mời
        var p = only(performance.overview());
        assertThat(p.invited()).isEqualTo(6);
        assertThat(p.friended()).isEqualTo(4);
        assertThat(p.joined()).isEqualTo(3);
        assertThat(p.friendedPercent()).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));
        assertThat(p.buyers()).isZero();
        assertThat(p.scopeLabel()).isNotBlank();
    }

    @Test
    void chiTinhLanMuaGiaoThanhCongSauKhiVaoNhom() {
        long c = campaign("Đợt 1", null);
        String a = customer();
        String b = customer();
        String late = customer();
        invite(c, a, 20, 19, 18, null);
        invite(c, b, 20, 19, 18, null);
        invite(c, late, 20, 19, 18, 5); // đã rời nhóm cách đây 5 ngày

        ship(a, "DH-A-CU", "TA0", "DELIVERED", 40);      // mua trước khi vào nhóm: không tính
        ship(a, "DH-A1", "TA1", "DELIVERED", 10);        // mua sau khi vào nhóm: tính, có đối soát
        settle("TA1", 900_000, 40_000, 10_000);
        ship(a, "DH-A2", "TA2", "CANCELLED", 9);         // hủy: không tính
        ship(b, "DH-B1", "TB1", "DELIVERED", 8);         // tách kiện cùng một mã đơn: MỘT lần mua
        ship(b, "DH-B1", "TB2", "DELIVERED", 8);
        settle("TB1", 300_000, 20_000, 5_000);
        settle("TB2", 100_000, 20_000, 5_000);
        ship(b, "DH-B2", "TB3", "DELIVERED", 7);         // chưa có dòng đối soát: đếm lần mua nhưng chưa có tiền
        ship(late, "DH-L1", "TL1", "DELIVERED", 2);      // sau khi đã rời nhóm: không tính
        settle("TL1", 777_000, 1, 1);

        var p = only(performance.overview());
        assertThat(p.buyers()).isEqualTo(2);
        assertThat(p.orders()).isEqualTo(3);
        assertThat(p.settledOrders()).isEqualTo(2);
        assertThat(p.ordersWithoutMoney()).isEqualTo(1);
        assertThat(p.revenue()).isEqualByComparingTo("1300000");
        assertThat(p.carrierCost()).isEqualByComparingTo("100000");
        assertThat(p.economics().discountCost()).isPositive();
    }

    @Test
    void tienToMaDonChiTinhDonQuaNhom() {
        long c = campaign("Đợt 1", null);
        String a = customer();
        invite(c, a, 20, 19, 18, null);
        ship(a, "NHOM-001", "T1", "DELIVERED", 10);
        settle("T1", 500_000, 30_000, 0);
        ship(a, "DH-777", "T2", "DELIVERED", 9);
        settle("T2", 200_000, 30_000, 0);

        assertThat(performance.overview().estimated()).isTrue();
        assertThat(only(performance.overview()).orders()).isEqualTo(2);

        performance.save("10", true, "", " nhom-", "test");
        var o = performance.overview();
        assertThat(o.estimated()).isFalse();
        assertThat(only(o).orders()).isEqualTo(1);
        assertThat(only(o).revenue()).isEqualByComparingTo("500000");
    }

    @Test
    void soSanhMuaLaiGiuaKhachVaoNhomVaKhongVaoNhom() {
        long c = campaign("Đợt 1", null);
        // 4 khách vào nhóm (3 mua lại trong 30 ngày), 3 khách không vào nhóm (1 mua lại), nhận tin 45 ngày trước.
        for (int i = 0; i < 4; i++) {
            String p = customer();
            invite(c, p, 45, 44, 43, null);
            if (i < 3) {
                ship(p, "DH-J" + i, "TJ" + i, "DELIVERED", 30);
            }
        }
        for (int i = 0; i < 3; i++) {
            String p = customer();
            invite(c, p, 45, null, null, null);
            if (i < 1) {
                ship(p, "DH-N" + i, "TN" + i, "DELIVERED", 30);
            }
        }
        // Mua sau 30 ngày kể từ lúc nhận tin thì không tính; người mới nhận tin 5 ngày trước chưa đủ thời gian quan sát.
        String slow = customer();
        invite(c, slow, 45, null, null, null);
        ship(slow, "DH-S", "TS", "DELIVERED", 1);
        String fresh = customer();
        invite(c, fresh, 5, 4, 3, null);

        var p = only(performance.overview());
        assertThat(p.joinedCohort().customers()).isEqualTo(4);
        assertThat(p.joinedCohort().bought()).isEqualTo(3);
        assertThat(p.notJoinedCohort().customers()).isEqualTo(4);
        assertThat(p.notJoinedCohort().bought()).isEqualTo(1);
        assertThat(p.joinedCohort().rate()).isEqualTo(0.75);
    }

    @Test
    void chienDichQuaNhoBiAnTrongBanAnDanh() {
        long small = campaign("Nhỏ", null);
        String s = customer();
        invite(small, s, 20, 19, 18, null);
        ship(s, "NHO-1", "TS1", "DELIVERED", 10);
        settle("TS1", 400_000, 20_000, 0);
        long big = campaign("Lớn", null);
        for (int i = 0; i < PerformanceService.MIN_CELL; i++) {
            String p = customer();
            invite(big, p, 20, 19, 18, null);
            ship(p, "LON-" + i, "TL" + i, "DELIVERED", 10);
            settle("TL" + i, 400_000, 20_000, 0);
        }

        var full = performance.overview();
        assertThat(full.campaigns()).allSatisfy(c -> assertThat(c.masked()).isFalse());
        assertThat(full.campaigns().stream().filter(c -> c.name().equals("Nhỏ")).findFirst().orElseThrow().revenue()).isEqualByComparingTo("400000");

        var anon = performance.anonymized();
        var smallRow = anon.campaigns().stream().filter(c -> c.name().equals("Nhỏ")).findFirst().orElseThrow();
        assertThat(smallRow.masked()).isTrue();
        assertThat(smallRow.revenue()).isEqualByComparingTo("0");
        assertThat(smallRow.economics()).isNull();
        assertThat(smallRow.joined()).isEqualTo(1);
        var bigRow = anon.campaigns().stream().filter(c -> c.name().equals("Lớn")).findFirst().orElseThrow();
        assertThat(bigRow.masked()).isFalse();
        assertThat(bigRow.revenue()).isEqualByComparingTo("2000000");
        assertThat(anon.total().revenue()).isEqualByComparingTo("2400000");
    }

    @Test
    void banAnDanhKhongChuaThongTinKhach() {
        long c = campaign("Đợt 1", null);
        for (int i = 0; i < PerformanceService.MIN_CELL; i++) {
            String p = customer();
            invite(c, p, 20, 19, 18, null);
            ship(p, "DH" + i, "T" + i, "DELIVERED", 10);
            settle("T" + i, 400_000, 20_000, 0);
        }
        String text = performance.anonymized().toString();
        assertThat(text).doesNotContain("Tên Khách Bí Mật").doesNotContainPattern("09\\d{8}").doesNotContainPattern("tok\\d");
    }

    @Test
    void luuGiaTriTinhToanVaTuChoiGiaTriSai() {
        assertThat(performance.settings().discountPercent()).isEqualByComparingTo("10");
        assertThat(performance.settings().freeShip()).isTrue();
        assertThat(performance.settings().tiktokFeePercent()).isNull();

        performance.save("12,5", false, "9", "NHOM", "test");
        var s = performance.settings();
        assertThat(s.discountPercent()).isEqualByComparingTo("12.5");
        assertThat(s.freeShip()).isFalse();
        assertThat(s.tiktokFeePercent()).isEqualByComparingTo("9");
        assertThat(s.groupOrderPrefix()).isEqualTo("NHOM");

        assertThatThrownBy(() -> performance.save("", true, "", "", "t")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> performance.save("95", true, "", "", "t")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> performance.save("abc", true, "", "", "t")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> performance.save("10", true, "70", "", "t")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> performance.save("10", true, "", "NHOM%", "t")).isInstanceOf(ImportException.class);
        // Lưu sai không làm mất giá trị đã lưu.
        assertThat(performance.settings().discountPercent()).isEqualByComparingTo("12.5");
    }
}
