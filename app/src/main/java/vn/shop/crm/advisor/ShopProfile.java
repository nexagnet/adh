package vn.shop.crm.advisor;

import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;

import java.util.List;
import java.util.Map;

/**
 * Hồ sơ cửa hàng do chủ shop khai (25/11/2026): những điều AI nên luôn biết khi đề xuất chiến dịch (sản phẩm chính, mùa bán chạy, ưu đãi hiện có,
 * mục tiêu, điều cần tránh). Lưu ở {@code app_setting} (khóa {@code advisor.shop.*}), không cần bảng mới. Chỉ là chữ chủ shop gõ ({@link OwnerText}):
 * không lấy gì từ khách. Với AI đây vẫn chỉ là DỮ LIỆU, không nới được giới hạn nào. Lớp này không gọi AI, Zalo hay gửi gì.
 */
@Service
public class ShopProfile {

    public static final int MAX_LENGTH = 200;
    static final int MIN_LENGTH = 3;

    /** Mục tiêu chính (rỗng = không nói). */
    public static final Map<String, String> GOALS = Map.of(
            "VAO_NHOM", "Đưa thêm khách vào nhóm Zalo",
            "MUA_LAI", "Tăng khách mua lại",
            "CA_HAI", "Cả hai");

    static final String K_PRODUCTS = "advisor.shop.products";
    static final String K_SEASON = "advisor.shop.season";
    static final String K_OFFER = "advisor.shop.offer";
    static final String K_GOAL = "advisor.shop.goal";
    static final String K_AVOID = "advisor.shop.avoid";

    /** Hồ sơ; trường chưa khai là chuỗi rỗng. */
    public record Profile(String products, String season, String offer, String goal, String avoid) {
        public boolean empty() {
            return products.isEmpty() && season.isEmpty() && offer.isEmpty() && goal.isEmpty() && avoid.isEmpty();
        }
    }

    private final AppSettings settings;

    public ShopProfile(AppSettings settings) {
        this.settings = settings;
    }

    public Profile load() {
        return new Profile(get(K_PRODUCTS), get(K_SEASON), get(K_OFFER), GOALS.containsKey(get(K_GOAL)) ? get(K_GOAL) : "", get(K_AVOID));
    }

    private String get(String key) {
        return settings.get(key).orElse("");
    }

    /** Lưu cả hồ sơ một lượt (ô để trống là xóa ô đó). Kiểm tất cả trước khi ghi nên lỗi một ô thì không lưu gì. */
    public void save(String products, String season, String offer, String goal, String avoid, String by) {
        String p = optional(products, "Sản phẩm chính");
        String s = optional(season, "Mùa bán chạy");
        String o = optional(offer, "Ưu đãi hiện có");
        String a = optional(avoid, "Điều cần tránh");
        String g = goal == null ? "" : goal.strip();
        if (!g.isEmpty() && !GOALS.containsKey(g)) {
            throw new ImportException("Mục tiêu không hợp lệ.");
        }
        settings.set(K_PRODUCTS, p, by);
        settings.set(K_SEASON, s, by);
        settings.set(K_OFFER, o, by);
        settings.set(K_GOAL, g, by);
        settings.set(K_AVOID, a, by);
    }

    private static String optional(String raw, String label) {
        return raw == null || raw.isBlank() ? "" : OwnerText.clean(raw, MIN_LENGTH, MAX_LENGTH, label);
    }

    /** Phần đưa cho AI: chỉ các ô đã khai; null nếu chưa khai gì. */
    public AdvisorSnapshot.Shop forAi() {
        Profile p = load();
        if (p.empty()) {
            return null;
        }
        return new AdvisorSnapshot.Shop(blankToNull(p.products()), blankToNull(p.season()), blankToNull(p.offer()),
                blankToNull(p.goal()), blankToNull(p.avoid()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Danh sách mục tiêu cho ô chọn ở giao diện, theo thứ tự cố định. */
    public static List<Map.Entry<String, String>> goalOptions() {
        return List.of(Map.entry("VAO_NHOM", GOALS.get("VAO_NHOM")), Map.entry("MUA_LAI", GOALS.get("MUA_LAI")), Map.entry("CA_HAI", GOALS.get("CA_HAI")));
    }
}
