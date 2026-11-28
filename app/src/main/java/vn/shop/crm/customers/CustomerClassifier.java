package vn.shop.crm.customers;

import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.shipping.StaleOrders;

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
 *   <li>Vận đơn không có mã đơn bán hàng thì mỗi vận đơn là một lần mua riêng (không gộp bằng phỏng đoán). Thiếu mã đơn
 *       KHÔNG làm khách bị gắn cờ cần kiểm tra (chủ shop chốt 28/11/2026).</li>
 *   <li>Một lần mua chỉ thành công khi có ít nhất một vận đơn DELIVERED. Trạng thái chưa xác định không bao giờ
 *       được coi là thành công.</li>
 *   <li>Đơn "đã lấy hàng/đang giao" quá lâu ({@link StaleOrders#AFTER_DAYS} ngày) là đơn kẹt: không thành công,
 *       không hủy, khách bị gắn cờ cần kiểm tra.</li>
 *   <li>"Mua gần nhất" = ngày tạo của vận đơn giao thành công gần nhất; khách chưa có đơn thành công thì để trống.</li>
 * </ul>
 */
public final class CustomerClassifier {

    public enum ReviewReason {
        UNKNOWN_STATUS("Có vận đơn chưa xác định được trạng thái"),
        STALE_OPEN_ORDER("Có đơn \"đã lấy hàng/đang giao\" quá " + StaleOrders.AFTER_DAYS
                + " ngày mà hãng không cập nhật thêm (chưa xác định giao được hay chưa)");

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

    /**
     * @param lastSuccessfulOn ngày tạo của vận đơn giao thành công gần nhất (null nếu chưa có)
     * @param staleShipmentIds mã các vận đơn kẹt (xem {@link StaleOrders})
     */
    public record Classification(Segment segment, int successfulPurchases, List<PurchaseGroup> purchases,
                                 Set<ReviewReason> reasons, LocalDateTime lastSuccessfulOn, Set<Long> staleShipmentIds) {
        public boolean needsReview() {
            return !reasons.isEmpty();
        }
    }

    private CustomerClassifier() {
    }

    /** @param now thời điểm hiện tại, để biết đơn nào đã kẹt quá lâu */
    public static Classification classify(List<ShipmentFact> shipments, LocalDateTime now) {
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
        Set<Long> stale = new java.util.LinkedHashSet<>();
        for (ShipmentFact s : ordered) {
            if (StaleOrders.isStale(s.status(), s.createdOn(), now)) {
                stale.add(s.id());
            }
        }
        if (!stale.isEmpty()) {
            reasons.add(ReviewReason.STALE_OPEN_ORDER);
        }
        LocalDateTime lastSuccessfulOn = shipments.stream()
                .filter(s -> s.status() == CanonicalStatus.DELIVERED && s.createdOn() != null)
                .map(ShipmentFact::createdOn).max(Comparator.naturalOrder()).orElse(null);
        return new Classification(Segment.forCount(successfulCount), successfulCount,
                List.copyOf(purchases), reasons, lastSuccessfulOn, Set.copyOf(stale));
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
