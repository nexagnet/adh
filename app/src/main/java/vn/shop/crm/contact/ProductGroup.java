package vn.shop.crm.contact;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Nhóm hàng của một dòng "tên hàng" (chữ tự do từ file đơn), chỉ để ĐẾM và LỌC khách khi đề xuất chiến dịch. Phân loại GẦN ĐÚNG theo ít từ khóa:
 * cửa hàng bán chủ yếu áo điều hòa và phụ kiện, trong đó có pin. Một dòng có thể thuộc nhiều nhóm (ví dụ "áo điều hòa kèm pin").
 * Không có tên hàng thì là {@link #CHUA_RO}. Không bao giờ đưa tên hàng ra ngoài: chỉ số lượng theo nhóm.
 */
public enum ProductGroup {
    AO_DIEU_HOA("Áo điều hòa"),
    PIN("Pin"),
    PHU_KIEN_KHAC("Phụ kiện khác"),
    CHUA_RO("Chưa rõ tên hàng");

    private final String label;

    ProductGroup(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Nhóm có thể dùng để lọc khách khi tạo chiến dịch (khách chưa rõ tên hàng thì không lọc được). */
    public boolean filterable() {
        return this != CHUA_RO;
    }

    private static final Pattern SHIRT = Pattern.compile("(?<![a-z0-9])(ao|dieu hoa|ao dieu hoa|quan ao)(?![a-z0-9])");
    private static final Pattern BATTERY = Pattern.compile("(?<![a-z0-9])(pin|battery|batteries)(?![a-z0-9])");

    /** Các nhóm của một dòng tên hàng (không bao giờ rỗng). */
    public static Set<ProductGroup> of(String goods) {
        if (goods == null || goods.isBlank()) {
            return EnumSet.of(CHUA_RO);
        }
        String f = fold(goods);
        EnumSet<ProductGroup> out = EnumSet.noneOf(ProductGroup.class);
        if (SHIRT.matcher(f).find()) {
            out.add(AO_DIEU_HOA);
        }
        if (BATTERY.matcher(f).find()) {
            out.add(PIN);
        }
        if (out.isEmpty()) {
            out.add(PHU_KIEN_KHAC);
        }
        return out;
    }

    static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace('đ', 'd').replaceAll("\\s+", " ");
    }
}
