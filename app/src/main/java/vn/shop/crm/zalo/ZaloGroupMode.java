package vn.shop.crm.zalo;

/** Nấc của việc tự tạo nhóm (D19). Mặc định {@link #OFF}; bật lần đầu nên chọn {@link #CONFIRM}. */
public enum ZaloGroupMode {
    OFF("Tắt"),
    CONFIRM("Xác nhận: mẹ bấm duyệt từng nhóm"),
    AUTO("Tự động: tự tạo trong trần mỗi ngày");

    private final String label;

    ZaloGroupMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ZaloGroupMode parse(String raw) {
        if (raw == null) {
            return OFF;
        }
        try {
            return valueOf(raw.strip());
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }
}
