package vn.shop.crm.shipping;

/** Trạng thái chuẩn hóa, độc lập với từng hãng. Chỉ DELIVERED mới được tính là giao thành công. */
public enum CanonicalStatus {
    CREATED("Mới tạo / chờ lấy"),
    IN_TRANSIT("Đang vận chuyển / giao"),
    DELIVERED("Giao thành công"),
    CANCELLED("Đã hủy"),
    RETURNING("Đang hoàn"),
    RETURNED("Đã hoàn"),
    UNKNOWN("Chưa xác định");

    private final String label;

    CanonicalStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
