package vn.shop.crm.shipping;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Đơn "kẹt": hãng báo "Đã lấy hàng" / "Đang giao" từ lâu mà không bao giờ cập nhật tiếp (ví dụ đơn J&amp;T từ tháng 6, 7).
 * Đơn như vậy KHÔNG được tính là giao thành công và cũng không được coi là đã hủy hay hoàn: trạng thái thật là
 * "chưa xác định", mẹ cần kiểm tra lại với hãng. Khách có đơn kẹt mang nhãn "Cần kiểm tra".
 *
 * <p>Tuổi của đơn tính từ ngày tạo đơn. Đổi ngưỡng ở {@link #AFTER_DAYS} (chỉ cần sửa một chỗ này).
 */
public final class StaleOrders {

    /** Đơn chưa có kết quả cuối quá chừng này ngày kể từ ngày tạo thì coi là kẹt. */
    public static final int AFTER_DAYS = 30;

    /** Các trạng thái "còn đang chạy" mà quá hạn thì thành kẹt (đơn đang hoàn không nằm trong nhóm này). */
    public static final Set<CanonicalStatus> OPEN = Set.of(CanonicalStatus.CREATED, CanonicalStatus.IN_TRANSIT);

    private StaleOrders() {
    }

    /** Tên các trạng thái {@link #OPEN}, dùng trong câu SQL. */
    public static List<String> openNames() {
        return OPEN.stream().map(Enum::name).sorted().toList();
    }

    /** Đơn tạo trước mốc này (so với {@code now}) mà còn "đang chạy" là kẹt. */
    public static LocalDateTime cutoff(LocalDateTime now) {
        return now.minusDays(AFTER_DAYS);
    }

    public static boolean isStale(CanonicalStatus status, LocalDateTime createdOn, LocalDateTime now) {
        return OPEN.contains(status) && createdOn != null && createdOn.isBefore(cutoff(now));
    }
}
