package vn.shop.crm.invite;

import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.CustomerFilter;
import vn.shop.crm.contact.ExchangeMarkers;
import vn.shop.crm.contact.ProductGroup;
import vn.shop.crm.shipping.Carrier;

import java.util.Arrays;
import java.util.List;

/**
 * Đọc bộ lọc khách từ form tạo chiến dịch và đưa các lựa chọn của form ra mẫu trang. Chỉ đọc giá trị trong danh sách trắng; số sai thì báo lỗi
 * tiếng Việt, không đoán. Form để trống mọi ô thì ra {@link CustomerFilter#NONE} (giống hệt cách tạo chiến dịch trước đây).
 */
@Component
public class CampaignFilterForm {

    private final ExchangeMarkers markers;

    public CampaignFilterForm(ExchangeMarkers markers) {
        this.markers = markers;
    }

    public CustomerFilter parse(MultiValueMap<String, String> p) {
        return new CustomerFilter(
                integer("Số lần mua (từ)", first(p, "purchasesMin")),
                integer("Số lần mua (đến)", first(p, "purchasesMax")),
                p.get("excludeAreas"),
                p.get("carriers"),
                tri(first(p, "exchange")),
                tri(first(p, "cancelled")),
                tri(first(p, "returned")),
                "true".equals(first(p, "noOpenOrders")),
                integer("Tổng số đơn", first(p, "maxShipments")),
                integer("Lần mua gần nhất (ít nhất)", first(p, "minDays")),
                integer("Lần mua gần nhất (nhiều nhất)", first(p, "maxDays")),
                groups(p.get("bought")),
                "ALL".equals(first(p, "boughtMode")) ? CustomerFilter.Mode.ALL : CustomerFilter.Mode.ANY,
                groups(p.get("notBought")),
                money("Giá trị đã thu (từ)", first(p, "codMin")),
                money("Giá trị đã thu (đến)", first(p, "codMax")),
                "true".equals(first(p, "skipOpenCases")),
                first(p, "pickOrder"));
    }

    private static String first(MultiValueMap<String, String> p, String key) {
        String v = p.getFirst(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static CustomerFilter.Tri tri(String v) {
        if (v == null) {
            return CustomerFilter.Tri.ANY;
        }
        try {
            return CustomerFilter.Tri.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new ImportException("Lựa chọn \"có / không có\" không hợp lệ.");
        }
    }

    private static Integer integer(String what, String v) {
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v.replace(".", "").replace(",", ""));
        } catch (NumberFormatException e) {
            throw new ImportException(what + " phải là số nguyên.");
        }
    }

    private static Long money(String what, String v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v.replace(".", "").replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            throw new ImportException(what + " phải là số nguyên (đồng).");
        }
    }

    private static List<ProductGroup> groups(List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream().filter(s -> s != null && !s.isBlank()).map(s -> {
            try {
                return ProductGroup.valueOf(s.strip());
            } catch (IllegalArgumentException e) {
                throw new ImportException("Nhóm hàng không hợp lệ.");
            }
        }).toList();
    }

    /** Các lựa chọn cho form tạo chiến dịch. */
    public void addOptions(Model model) {
        model.addAttribute("tris", CustomerFilter.Tri.values());
        model.addAttribute("carrierOptions", Arrays.asList(Carrier.values()));
        model.addAttribute("groupOptions", List.copyOf(CustomerFilter.filterableGroups()));
        model.addAttribute("exchangeMarkers", markers.get());
        model.addAttribute("exchangeReady", !markers.get().isEmpty());
    }

    /** Lưu cách nhận biết đơn đổi (ba ô chữ, mỗi từ khóa cách nhau bằng dấu phẩy). */
    public void saveMarkers(String orderRef, String goods, String status, String by) {
        markers.save(orderRef, goods, status, by);
    }
}
