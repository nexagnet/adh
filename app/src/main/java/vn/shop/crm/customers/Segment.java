package vn.shop.crm.customers;

/** BR-04: hai danh sách khách đã mua, cộng nhóm chưa đủ điều kiện. */
public enum Segment {
    NO_SUCCESSFUL_PURCHASE("Chưa có đơn giao thành công"),
    ONE_TIME("Mua 1 lần"),
    REPEAT("Mua từ 2 lần");

    private final String label;

    Segment(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Segment forCount(int successfulPurchases) {
        if (successfulPurchases <= 0) {
            return NO_SUCCESSFUL_PURCHASE;
        }
        return successfulPurchases == 1 ? ONE_TIME : REPEAT;
    }
}
