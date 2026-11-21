package vn.shop.crm.invite;

/** Trạng thái nhóm Zalo. Chỉ nhóm {@code OPEN} có link mới được cấp cho khách bấm. */
public enum GroupStatus {
    PREPARING("Đang chuẩn bị"),
    OPEN("Đang nhận khách"),
    PAUSED("Tạm dừng nhận khách"),
    CLOSED("Đã đóng");

    private final String label;

    GroupStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
