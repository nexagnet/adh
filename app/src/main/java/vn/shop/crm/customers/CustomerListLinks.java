package vn.shop.crm.customers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dựng các đường dẫn của trang danh sách khách (đổi sắp xếp, đổi bộ lọc, sang trang, xuất file) từ trạng thái hiện tại,
 * để mọi liên kết giữ nguyên các bộ lọc còn lại. Trang đã đặt về 1 mỗi khi đổi bộ lọc hoặc sắp xếp.
 */
public final class CustomerListLinks {

    public record Hidden(String name, String value) {
    }

    private static final String LIST = "/customers";
    private static final String EXPORT = "/customers/export.csv";

    private final CustomerQuery query;
    private final Set<String> shownColumns;

    public CustomerListLinks(CustomerQuery query, Set<String> shownColumns) {
        this.query = query;
        this.shownColumns = shownColumns;
    }

    /** Bấm tiêu đề cột: cột đang sắp tăng thì chuyển giảm và ngược lại; cột khác thì sắp tăng. */
    public String sort(String key) {
        boolean sameColumn = query.effectiveSort().param().equals(key);
        String dir = sameColumn && !query.effectiveDesc() ? "desc" : "asc";
        Map<String, String> p = query.params();
        p.put("sort", key);
        p.put("dir", dir);
        p.remove("page");
        return url(LIST, p);
    }

    /** "ascending", "descending" hoặc "none" cho thuộc tính aria-sort của tiêu đề cột. */
    public String ariaSort(String key) {
        if (!query.effectiveSort().param().equals(key)) {
            return "none";
        }
        return query.effectiveDesc() ? "descending" : "ascending";
    }

    public String with(String key, String value) {
        return url(LIST, query.with(key, value).params());
    }

    public String page(int page) {
        return url(LIST, query.withPage(page).params());
    }

    public String clearFilters() {
        return url(LIST, query.withoutColumnFilters().params());
    }

    /** Xuất file đúng bộ lọc và thứ tự đang xem, không giới hạn theo trang. */
    public String export() {
        Map<String, String> p = query.params();
        p.remove("page");
        return url(EXPORT, p);
    }

    public int activeFilters() {
        return query.activeColumnFilters(shownColumns);
    }

    /** Các trường ẩn của một biểu mẫu để giữ nguyên trạng thái trang, trừ những tham số mà biểu mẫu tự có ô nhập. */
    public List<Hidden> hiddenExcept(List<String> ownNames) {
        Map<String, String> p = query.params();
        p.remove("page");
        return p.entrySet().stream().filter(e -> !ownNames.contains(e.getKey()))
                .map(e -> new Hidden(e.getKey(), e.getValue())).toList();
    }

    private static String url(String path, Map<String, String> params) {
        if (params.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
