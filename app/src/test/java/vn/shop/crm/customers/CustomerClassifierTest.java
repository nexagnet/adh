package vn.shop.crm.customers;

import org.junit.jupiter.api.Test;
import vn.shop.crm.customers.CustomerClassifier.Classification;
import vn.shop.crm.customers.CustomerClassifier.ReviewReason;
import vn.shop.crm.customers.CustomerClassifier.ShipmentFact;
import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.shipping.StaleOrders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerClassifierTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 5, 9, 0);

    /** Mặc định "bây giờ" là 10 ngày sau đơn đầu tiên, nên chưa đơn nào bị coi là kẹt. */
    private static final LocalDateTime NOW = T.plusDays(10);

    private static Classification classify(List<ShipmentFact> facts) {
        return CustomerClassifier.classify(facts, NOW);
    }

    private static ShipmentFact fact(long id, Carrier carrier, String tracking, String orderRef,
                                     CanonicalStatus status) {
        return new ShipmentFact(id, carrier, tracking, orderRef, status, T.plusDays(id));
    }

    @Test
    void motDonGiaoThanhCongLaOneTime() {
        Classification c = classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.DELIVERED)));
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(c.successfulPurchases()).isEqualTo(1);
        assertThat(c.needsReview()).isFalse();
    }

    /** Tiêu chí 2: 1 đơn thành công ở Viettel Post + 1 đơn thành công ở J&T => REPEAT. */
    @Test
    void haiHangMoiHangMotDonThanhCongLaRepeat() {
        Classification c = classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.DELIVERED),
                fact(2, Carrier.JT_EXPRESS, "JT1", "DH2", CanonicalStatus.DELIVERED)));
        assertThat(c.segment()).isEqualTo(Segment.REPEAT);
        assertThat(c.successfulPurchases()).isEqualTo(2);
        assertThat(c.needsReview()).isFalse();
    }

    /** Tiêu chí 3: 1 đơn giao thành công + 2 đơn hủy/hoàn => ONE_TIME. */
    @Test
    void donHuyVaHoanKhongDuocTinh() {
        Classification c = classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.DELIVERED),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "DH2", CanonicalStatus.CANCELLED),
                fact(3, Carrier.JT_EXPRESS, "JT3", "DH3", CanonicalStatus.RETURNED)));
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(c.successfulPurchases()).isEqualTo(1);
    }

    /** Tiêu chí 4: trạng thái chưa xác định không tự coi là thành công. */
    @Test
    void trangThaiChuaXacDinhKhongPhaiThanhCong() {
        Classification c = classify(List.of(
                fact(1, Carrier.JT_EXPRESS, "JT1", "DH1", CanonicalStatus.UNKNOWN)));
        assertThat(c.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        assertThat(c.reasons()).containsExactly(ReviewReason.UNKNOWN_STATUS);
    }

    @Test
    void donDangGiaoChuaTinh() {
        Classification c = classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.IN_TRANSIT),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "DH2", CanonicalStatus.CREATED)));
        assertThat(c.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
    }

    /** BR-03: một đơn tách nhiều kiện không được tự động tính nhiều lần mua. */
    @Test
    void cungMaDonBanHangLaMotLanMua() {
        Classification c = classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH7", CanonicalStatus.DELIVERED),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "dh7", CanonicalStatus.DELIVERED)));
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(c.purchases()).hasSize(1);
        assertThat(c.purchases().getFirst().shipments()).hasSize(2);
    }

    /** Không có mã đơn gốc thì không gộp bằng phỏng đoán; chỉ thiếu mã đơn thì không gắn cờ cần kiểm tra (chủ shop duyệt 28/11/2026). */
    @Test
    void khongCoMaDonKhongGopVaKhongGanCo() {
        Classification c = classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", null, CanonicalStatus.DELIVERED),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "  ", CanonicalStatus.DELIVERED)));
        assertThat(c.segment()).isEqualTo(Segment.REPEAT);
        assertThat(c.successfulPurchases()).isEqualTo(2);
        assertThat(c.reasons()).isEmpty();
        assertThat(c.needsReview()).isFalse();
    }

    /** Thiếu mã đơn nhưng có lý do khác (trạng thái lạ) thì chỉ giữ lý do khác. */
    @Test
    void thieuMaDonVaTrangThaiLaChiGiuLyDoTrangThai() {
        Classification c = classify(List.of(
                fact(1, Carrier.JT_EXPRESS, "JT1", null, CanonicalStatus.DELIVERED),
                fact(2, Carrier.JT_EXPRESS, "JT2", null, CanonicalStatus.DELIVERED),
                fact(3, Carrier.JT_EXPRESS, "JT3", "DH3", CanonicalStatus.UNKNOWN)));
        assertThat(c.reasons()).containsExactly(ReviewReason.UNKNOWN_STATUS);
    }

    @Test
    void motKienDaGiaoMotKienHoanVanLaMotLanMuaThanhCong() {
        Classification c = classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH9", CanonicalStatus.DELIVERED),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "DH9", CanonicalStatus.RETURNED)));
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
    }

    @Test
    void khongCoVanDon() {
        Classification c = classify(List.of());
        assertThat(c.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        assertThat(c.purchases()).isEmpty();
    }

    // ---------- "Mua gần nhất" = ngày đơn giao thành công gần nhất ----------

    @Test
    void muaGanNhatLaNgayDonGiaoThanhCongKhongPhaiDonHuyMoiNhat() {
        Classification c = classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.DELIVERED),
                fact(2, Carrier.JT_EXPRESS, "JT2", "DH2", CanonicalStatus.DELIVERED),
                fact(5, Carrier.JT_EXPRESS, "JT5", "DH5", CanonicalStatus.CANCELLED),
                fact(6, Carrier.JT_EXPRESS, "JT6", "DH6", CanonicalStatus.RETURNED)));
        assertThat(c.lastSuccessfulOn()).isEqualTo(T.plusDays(2));
    }

    @Test
    void khachChuaCoDonThanhCongThiMuaGanNhatTrong() {
        Classification c = classify(List.of(
                fact(1, Carrier.JT_EXPRESS, "JT1", "DH1", CanonicalStatus.CANCELLED),
                fact(2, Carrier.JT_EXPRESS, "JT2", "DH2", CanonicalStatus.RETURNED)));
        assertThat(c.lastSuccessfulOn()).isNull();
        assertThat(classify(List.of()).lastSuccessfulOn()).isNull();
    }

    @Test
    void donThanhCongKhongCoNgayTaoThiBoQua() {
        Classification c = classify(List.of(
                new ShipmentFact(1, Carrier.JT_EXPRESS, "JT1", "DH1", CanonicalStatus.DELIVERED, null),
                fact(2, Carrier.JT_EXPRESS, "JT2", "DH2", CanonicalStatus.DELIVERED)));
        assertThat(c.lastSuccessfulOn()).isEqualTo(T.plusDays(2));
    }

    // ---------- đơn "đã lấy hàng" kẹt quá lâu ----------

    private static final LocalDateTime LATE = T.plusDays(StaleOrders.AFTER_DAYS + 5);

    @Test
    void donDaLayHangQuaCuLaChuaXacDinhKhongThanhCongKhongHuy() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.JT_EXPRESS, "JT1", "DH1", CanonicalStatus.IN_TRANSIT)), LATE);
        assertThat(c.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        assertThat(c.successfulPurchases()).isZero();
        assertThat(c.lastSuccessfulOn()).isNull();
        assertThat(c.staleShipmentIds()).containsExactly(1L);
        assertThat(c.reasons()).containsExactly(ReviewReason.STALE_OPEN_ORDER);
        assertThat(c.needsReview()).isTrue();
    }

    @Test
    void donCoMoiChuaKetVaDonDangHoanKhongBiCoiLaKet() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.JT_EXPRESS, "JT1", "DH1", CanonicalStatus.IN_TRANSIT),
                fact(2, Carrier.JT_EXPRESS, "JT2", "DH2", CanonicalStatus.RETURNING)),
                T.plusDays(1 + StaleOrders.AFTER_DAYS));
        // đơn 1 tạo T+1, đúng 30 ngày trước "bây giờ": chưa quá ngưỡng. Đơn đang hoàn không bao giờ là kẹt.
        assertThat(c.staleShipmentIds()).isEmpty();
        assertThat(c.needsReview()).isFalse();
    }

    @Test
    void khachCoDonThanhCongVaDonKetVanThanhCongNhungCanKiemTra() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.DELIVERED),
                fact(2, Carrier.JT_EXPRESS, "JT2", "DH2", CanonicalStatus.IN_TRANSIT)), LATE);
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(c.lastSuccessfulOn()).isEqualTo(T.plusDays(1));
        assertThat(c.staleShipmentIds()).containsExactly(2L);
        assertThat(c.reasons()).containsExactly(ReviewReason.STALE_OPEN_ORDER);
    }

    @Test
    void donKetDuocGiaoSauKhiCapNhatThiHetKet() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.JT_EXPRESS, "JT1", "DH1", CanonicalStatus.DELIVERED)), LATE);
        assertThat(c.staleShipmentIds()).isEmpty();
        assertThat(c.needsReview()).isFalse();
    }
}
