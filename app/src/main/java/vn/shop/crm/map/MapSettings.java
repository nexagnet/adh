package vn.shop.crm.map;

/** Giá trị mặc định cho nền bản đồ (OpenStreetMap). Đổi bằng cấu hình {@code app.map.*}. */
public final class MapSettings {

    /** Ô bản đồ công cộng của OpenStreetMap: miễn phí, không cần khóa, có chính sách sử dụng hợp lý (xem docs). */
    public static final String DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

    public static final String DEFAULT_TILE_ATTRIBUTION =
            "© <a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\" rel=\"noopener noreferrer\">"
                    + "Những người đóng góp OpenStreetMap</a>";

    private MapSettings() {
    }
}
