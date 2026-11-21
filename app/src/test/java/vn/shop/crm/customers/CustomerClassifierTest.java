package vn.shop.crm.customers;

import org.junit.jupiter.api.Test;
import vn.shop.crm.customers.CustomerClassifier.Classification;
import vn.shop.crm.customers.CustomerClassifier.ReviewReason;
import vn.shop.crm.customers.CustomerClassifier.ShipmentFact;
import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerClassifierTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 5, 9, 0);

    private static ShipmentFact fact(long id, Carrier carrier, String tracking, String orderRef,
                                     CanonicalStatus status) {
        return new ShipmentFact(id, carrier, tracking, orderRef, status, T.plusDays(id));
    }

    @Test
    void motDonGiaoThanhCongLaOneTime() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.DELIVERED)));
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(c.successfulPurchases()).isEqualTo(1);
        assertThat(c.needsReview()).isFalse();
    }

    /** Tiêu chí 2: 1 đơn thành công ở Viettel Post + 1 đơn thành công ở J&T => REPEAT. */
    @Test
    void haiHangMoiHangMotDonThanhCongLaRepeat() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.DELIVERED),
                fact(2, Carrier.JT_EXPRESS, "JT1", "DH2", CanonicalStatus.DELIVERED)));
        assertThat(c.segment()).isEqualTo(Segment.REPEAT);
        assertThat(c.successfulPurchases()).isEqualTo(2);
        assertThat(c.needsReview()).isFalse();
    }

    /** Tiêu chí 3: 1 đơn giao thành công + 2 đơn hủy/hoàn => ONE_TIME. */
    @Test
    void donHuyVaHoanKhongDuocTinh() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.DELIVERED),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "DH2", CanonicalStatus.CANCELLED),
                fact(3, Carrier.JT_EXPRESS, "JT3", "DH3", CanonicalStatus.RETURNED)));
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(c.successfulPurchases()).isEqualTo(1);
    }

    /** Tiêu chí 4: trạng thái chưa xác định không tự coi là thành công. */
    @Test
    void trangThaiChuaXacDinhKhongPhaiThanhCong() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.JT_EXPRESS, "JT1", "DH1", CanonicalStatus.UNKNOWN)));
        assertThat(c.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        assertThat(c.reasons()).containsExactly(ReviewReason.UNKNOWN_STATUS);
    }

    @Test
    void donDangGiaoChuaTinh() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH1", CanonicalStatus.IN_TRANSIT),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "DH2", CanonicalStatus.CREATED)));
        assertThat(c.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
    }

    /** BR-03: một đơn tách nhiều kiện không được tự động tính nhiều lần mua. */
    @Test
    void cungMaDonBanHangLaMotLanMua() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH7", CanonicalStatus.DELIVERED),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "dh7", CanonicalStatus.DELIVERED)));
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
        assertThat(c.purchases()).hasSize(1);
        assertThat(c.purchases().getFirst().shipments()).hasSize(2);
    }

    /** Không có mã đơn gốc thì không gộp bằng phỏng đoán, nhưng gắn cờ cần kiểm tra. */
    @Test
    void khongCoMaDonKhongGopNhungGanCo() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", null, CanonicalStatus.DELIVERED),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "  ", CanonicalStatus.DELIVERED)));
        assertThat(c.segment()).isEqualTo(Segment.REPEAT);
        assertThat(c.reasons()).containsExactly(ReviewReason.REPEAT_WITHOUT_ORDER_REF);
    }

    @Test
    void motKienDaGiaoMotKienHoanVanLaMotLanMuaThanhCong() {
        Classification c = CustomerClassifier.classify(List.of(
                fact(1, Carrier.VIETTEL_POST, "VTP1", "DH9", CanonicalStatus.DELIVERED),
                fact(2, Carrier.VIETTEL_POST, "VTP2", "DH9", CanonicalStatus.RETURNED)));
        assertThat(c.segment()).isEqualTo(Segment.ONE_TIME);
    }

    @Test
    void khongCoVanDon() {
        Classification c = CustomerClassifier.classify(List.of());
        assertThat(c.segment()).isEqualTo(Segment.NO_SUCCESSFUL_PURCHASE);
        assertThat(c.purchases()).isEmpty();
    }
}
