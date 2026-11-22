package vn.shop.crm.zalo;

/**
 * Nấc của bộ nối (ADR-0002 mục 3.3). Thứ tự là thứ tự bật từng nấc: mỗi nấc chạy thử trước nấc sau.
 * {@link #OFF}: không nghe, không gọi gì. {@link #LISTEN_ONLY}: chỉ nhận sự kiện kết bạn và gợi ý, người thêm tay.
 * {@link #CONFIRM}: hiện nút duyệt, người bấm thì bộ nối thêm. {@link #AUTO}: tự thêm khi đủ điều kiện, trong hạn mức.
 */
public enum ZaloMode {
    OFF("Tắt"),
    LISTEN_ONLY("Chỉ nghe, người thêm tay"),
    CONFIRM("Mẹ duyệt từng người"),
    AUTO("Tự thêm trong hạn mức");

    private final String label;

    ZaloMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean listens() {
        return this != OFF;
    }

    public boolean adds() {
        return this == CONFIRM || this == AUTO;
    }

    public static ZaloMode parse(String raw) {
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
