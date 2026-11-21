package vn.shop.crm.customers;

import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Quy tắc đếm lần mua thành công (BR-02..BR-04). Hàm thuần, không đụng cơ sở dữ liệu.
 *
 * <ul>
 *   <li>Mọi vận đơn có cùng mã đơn bán hàng (và cùng SĐT) là MỘT lần mua.</li>
 *   <li>Vận đơn không có mã đơn bán hàng thì mỗi vận đơn là một lần mua riêng (không gộp bằng phỏng đoán)
 *       và khách bị gắn cờ cần kiểm tra nếu kết quả có thể bị đếm dư.</li>
 *   <li>Một lần mua chỉ thành công khi có ít nhất một vận đơn DELIVERED. Trạng thái chưa xác định không bao giờ
 *       được coi là thành công.</li>
 * </ul>
 */
public final class CustomerClassifier {

    public enum ReviewReason {
        UNKNOWN_STATUS("Có vận đơn chưa xác định được trạng thái"),
        REPEAT_WITHOUT_ORDER_REF("Từ 2 lần mua nhưng có đơn không có mã đơn bán hàng (có thể là một đơn tách kiện)");

        private final String label;

        ReviewReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record ShipmentFact(long id, Carrier carrier, String trackingNo, String orderRef,
                               CanonicalStatus status, LocalDateTime createdOn) {
    }

    public record PurchaseGroup(String key, boolean hasOrderRef, boolean successful, List<ShipmentFact> shipments) {
    }

    public record Classification(Segment segment, int successfulPurchases, List<PurchaseGroup> purchases,
                                 Set<ReviewReason> reasons) {
        public boolean needsReview() {
            return !reasons.isEmpty();
        }
    }

    private CustomerClassifier() {
    }

    public static Classification classify(List<ShipmentFact> shipments) {
        Map<String, List<ShipmentFact>> byKey = new LinkedHashMap<>();
        List<ShipmentFact> ordered = new ArrayList<>(shipments);
        ordered.sort(Comparator
                .comparing(ShipmentFact::createdOn, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(ShipmentFact::id));
        for (ShipmentFact s : ordered) {
            byKey.computeIfAbsent(purchaseKey(s), k -> new ArrayList<>()).add(s);
        }

        List<PurchaseGroup> purchases = new ArrayList<>();
        byKey.forEach((key, list) -> {
            boolean successful = list.stream().anyMatch(s -> s.status() == CanonicalStatus.DELIVERED);
            purchases.add(new PurchaseGroup(key, hasOrderRef(list.getFirst()), successful, List.copyOf(list)));
        });

        int successfulCount = (int) purchases.stream().filter(PurchaseGroup::successful).count();
        Set<ReviewReason> reasons = EnumSet.noneOf(ReviewReason.class);
        if (shipments.stream().anyMatch(s -> s.status() == CanonicalStatus.UNKNOWN)) {
            reasons.add(ReviewReason.UNKNOWN_STATUS);
        }
        if (successfulCount >= 2
                && purchases.stream().anyMatch(p -> p.successful() && !p.hasOrderRef())) {
            reasons.add(ReviewReason.REPEAT_WITHOUT_ORDER_REF);
        }
        return new Classification(Segment.forCount(successfulCount), successfulCount,
                List.copyOf(purchases), reasons);
    }

    private static boolean hasOrderRef(ShipmentFact s) {
        return s.orderRef() != null && !s.orderRef().isBlank();
    }

    private static String purchaseKey(ShipmentFact s) {
        if (hasOrderRef(s)) {
            return "ORDER:" + s.orderRef().strip().toUpperCase(Locale.ROOT);
        }
        return "TRACKING:" + s.carrier().name() + ":" + s.trackingNo();
    }
}
