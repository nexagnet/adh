package vn.shop.crm.contact;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.geo.GeoArea;
import vn.shop.crm.shipping.Carrier;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bộ lọc khách của một chiến dịch (docs/THIET-KE-CAU-HINH-CHIEN-DICH-LINH-DONG.md): MỘT khuôn dùng chung cho form tạo chiến dịch, AI đề xuất
 * và chế độ tự nhận khách mới, lưu vào chiến dịch ({@code invitation_campaign.customer_filter}).
 *
 * <p>Bộ lọc chỉ LÀM HẸP tập khách đang đủ điều kiện mời (không bao giờ mở rộng): khách từ chối, bị chặn, cần kiểm tra, ngoài số thử, đã từng được
 * mời vẫn bị loại vì các điều kiện đó nằm ngay trong truy vấn gốc ({@link ContactService}). Mọi điều kiện kết hợp bằng "và"; trường null hay danh sách
 * rỗng nghĩa là "không lọc". Cố ý chỉ có số, mã và danh sách trắng: không có chữ tự do nào đi vào truy vấn.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CustomerFilter(
        /**
         * Số lần mua thành công từ ... đến ... (null cả hai = đúng 1 lần, hành vi từ trước tới nay). Chỉ đặt lớn hơn 1 khi người (hoặc AI, rồi người duyệt)
         * chủ động chọn cho đúng chiến dịch này (quyết định 25/11/2026): khách mua nhiều lần chỉ được liên hệ khi chiến dịch của họ cho phép
         * ({@code invitation_campaign.max_purchases}); xem {@link ContactService#stillEligible}.
         */
        Integer purchasesMin,
        Integer purchasesMax,
        /** Loại các khu vực này (mã như {@code R:NAM}, {@code S:...}, {@code P:...}, {@code U}). Khách chưa rõ tỉnh không bị coi là thuộc khu vực nào. */
        List<String> excludeAreas,
        /** Chỉ khách có đơn qua các hãng này (tên {@link Carrier}). */
        List<String> carriers,
        /** Có / không có đơn đổi (cần đã cài {@link ExchangeMarkers}). */
        Tri exchange,
        /** Có / không có đơn hủy. */
        Tri cancelled,
        /** Có / không có đơn hoàn hoặc đang hoàn. */
        Tri returned,
        /** true = bỏ khách đang có đơn chưa kết thúc (mới tạo, đang vận chuyển, đang hoàn). */
        boolean noOpenOrders,
        /** Tổng số vận đơn (kể cả hủy) không quá chừng này. */
        Integer maxShipments,
        /** Lần mua gần nhất cách đây ít nhất / nhiều nhất chừng này ngày. */
        Integer minDays,
        Integer maxDays,
        /** Đã mua (giao thành công) nhóm hàng nào, và khớp "bất kỳ" hay "tất cả". */
        List<ProductGroup> bought,
        Mode boughtMode,
        /** CHƯA từng mua các nhóm hàng này (khách có đơn giao thành công chưa rõ tên hàng cũng bị loại cho chắc). */
        List<ProductGroup> notBought,
        /** Tổng tiền COD đã đối soát (chỉ tính khách có đối soát) từ ... đến ... đồng. */
        Long codMin,
        Long codMax,
        /** true = bỏ khách đang có việc cần người thật (bảo hành, khiếu nại, trả lời chờ người, từ chối đang chờ xem). */
        boolean skipOpenCases,
        /** Thứ tự lấy khách, xem {@link ContactService.Pick}. */
        String pickOrder) {

    public enum Tri {
        ANY("Không quan tâm"), YES("Có"), NO("Không có");

        private final String label;

        Tri(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Mode {
        ANY, ALL
    }

    public static final int MAX_DAYS = 1100;
    public static final int MAX_PURCHASES = 20;
    public static final int MAX_SHIPMENTS = 50;
    public static final long MAX_COD = 10_000_000_000L;
    public static final Set<String> PICK_ORDERS = Set.of(ContactService.Pick.OLDEST_APPROVED, ContactService.Pick.NEWEST_PURCHASE,
            ContactService.Pick.OLDEST_PURCHASE);

    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Không lọc gì, thứ tự mặc định (giống hệt hôm nay). */
    public static final CustomerFilter NONE = new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, null, null, null, null,
            null, false, null);

    /** Chuẩn hóa và kiểm tra; giá trị sai ném {@link ImportException} (chữ tiếng Việt, hiện cho người dùng). */
    public CustomerFilter {
        if (purchasesMin != null || purchasesMax != null) {
            int lo = purchasesMin == null ? 1 : purchasesMin;
            int hi = purchasesMax == null ? MAX_PURCHASES : purchasesMax;
            if (lo < 1 || hi > MAX_PURCHASES || hi < lo) {
                throw new ImportException("Số lần mua phải từ 1 đến " + MAX_PURCHASES + " và \"từ\" không lớn hơn \"đến\".");
            }
            purchasesMin = lo == 1 && hi == 1 ? null : lo;
            purchasesMax = lo == 1 && hi == 1 ? null : hi;
        }
        excludeAreas = clean(excludeAreas);
        carriers = clean(carriers);
        exchange = exchange == null ? Tri.ANY : exchange;
        cancelled = cancelled == null ? Tri.ANY : cancelled;
        returned = returned == null ? Tri.ANY : returned;
        bought = bought == null ? List.of() : List.copyOf(new ArrayList<>(new java.util.LinkedHashSet<>(bought)));
        notBought = notBought == null ? List.of() : List.copyOf(new ArrayList<>(new java.util.LinkedHashSet<>(notBought)));
        boughtMode = boughtMode == null ? Mode.ANY : boughtMode;
        pickOrder = pickOrder == null || pickOrder.isBlank() ? ContactService.Pick.OLDEST_APPROVED : pickOrder.strip();
        for (String a : excludeAreas) {
            if (!GeoArea.UNKNOWN.equals(a) && GeoArea.parse(a).isEmpty()) {
                throw new ImportException("Khu vực loại trừ không hợp lệ.");
            }
        }
        for (String c : carriers) {
            try {
                Carrier.valueOf(c);
            } catch (IllegalArgumentException e) {
                throw new ImportException("Hãng vận chuyển không hợp lệ.");
            }
        }
        if (!PICK_ORDERS.contains(pickOrder)) {
            throw new ImportException("Thứ tự lấy khách không hợp lệ.");
        }
        for (ProductGroup g : concat(bought, notBought)) {
            if (!g.filterable()) {
                throw new ImportException("Nhóm hàng này không dùng để lọc khách.");
            }
        }
        if (bought.stream().anyMatch(notBought::contains)) {
            throw new ImportException("Một nhóm hàng không thể vừa \"đã mua\" vừa \"chưa mua\".");
        }
        range("Độ mới của lần mua", minDays == null ? null : minDays.longValue(), maxDays == null ? null : maxDays.longValue(), MAX_DAYS);
        if (maxShipments != null && (maxShipments < 1 || maxShipments > MAX_SHIPMENTS)) {
            throw new ImportException("Tổng số đơn phải từ 1 đến " + MAX_SHIPMENTS + ".");
        }
        range("Giá trị đã thu", codMin, codMax, MAX_COD);
    }

    private static void range(String what, Long min, Long max, long limit) {
        if ((min != null && (min < 0 || min > limit)) || (max != null && (max < 0 || max > limit)) || (min != null && max != null && max < min)) {
            throw new ImportException(what + " không hợp lệ.");
        }
    }

    private static List<String> clean(List<String> in) {
        if (in == null) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(new java.util.LinkedHashSet<>(in.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList())));
    }

    private static List<ProductGroup> concat(List<ProductGroup> a, List<ProductGroup> b) {
        List<ProductGroup> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Không lọc gì khác nhưng chấp nhận mọi số lần mua (nền để đếm "đủ điều kiện, mọi số lần mua" ở phần xem trước). */
    public static CustomerFilter anyPurchases() {
        return new CustomerFilter(1, MAX_PURCHASES, null, null, null, null, null, false, null, null, null, null, null, null, null, null, false, null);
    }

    /** Số lần mua nhỏ nhất / lớn nhất thực sự áp dụng (mặc định đúng 1 lần). */
    public int minPurchases() {
        return purchasesMin == null ? 1 : purchasesMin;
    }

    public int maxPurchases() {
        return purchasesMax == null ? 1 : purchasesMax;
    }

    /** True nếu chiến dịch mời cả khách mua nhiều hơn 1 lần (cần các chốt chặn riêng, xem {@link ContactService}). */
    public boolean multi() {
        return maxPurchases() > 1;
    }

    /** True nếu có bất kỳ điều kiện nào khác mặc định (kể cả thứ tự lấy). */
    public boolean any() {
        return !equals(NONE);
    }

    /** Có điều kiện nào phải tra bảng đối soát hoặc dấu hiệu đơn đổi không (để báo trước cho người dùng). */
    public boolean needsExchangeMarkers() {
        return exchange != Tri.ANY;
    }

    /** Dựng bộ lọc từng trường cho dễ đọc (trường không gọi thì không lọc). */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Integer purchasesMin, purchasesMax, maxShipments, minDays, maxDays;
        private List<String> excludeAreas, carriers;
        private Tri exchange, cancelled, returned;
        private boolean noOpenOrders, skipOpenCases;
        private List<ProductGroup> bought, notBought;
        private Mode boughtMode;
        private Long codMin, codMax;
        private String pickOrder;

        public Builder purchases(Integer min, Integer max) { purchasesMin = min; purchasesMax = max; return this; }
        public Builder exchange(Tri t) { exchange = t; return this; }
        public Builder cancelled(Tri t) { cancelled = t; return this; }
        public Builder returned(Tri t) { returned = t; return this; }
        public Builder noOpenOrders() { noOpenOrders = true; return this; }
        public Builder skipOpenCases() { skipOpenCases = true; return this; }
        public Builder maxShipments(Integer n) { maxShipments = n; return this; }
        public Builder days(Integer min, Integer max) { minDays = min; maxDays = max; return this; }
        public Builder bought(Mode mode, ProductGroup... g) { bought = List.of(g); boughtMode = mode; return this; }
        public Builder notBought(ProductGroup... g) { notBought = List.of(g); return this; }
        public Builder cod(Long min, Long max) { codMin = min; codMax = max; return this; }
        public Builder excludeAreas(List<String> a) { excludeAreas = a; return this; }
        public Builder carriers(List<String> c) { carriers = c; return this; }
        public Builder pickOrder(String o) { pickOrder = o; return this; }

        public CustomerFilter build() {
            return new CustomerFilter(purchasesMin, purchasesMax, excludeAreas, carriers, exchange, cancelled, returned, noOpenOrders, maxShipments,
                    minDays, maxDays, bought, boughtMode, notBought, codMin, codMax, skipOpenCases, pickOrder);
        }
    }

    // ---------- từng điều kiện (cho phần xem trước) ----------

    /** Một điều kiện riêng lẻ của bộ lọc: chữ mô tả và bộ lọc chỉ chứa đúng điều kiện đó. */
    public record Facet(String label, CustomerFilter only) {
    }

    /** Các điều kiện đang bật, mỗi điều kiện một mục, theo thứ tự cố định (không tính thứ tự lấy khách, vì nó không loại ai). */
    public List<Facet> facets() {
        List<Facet> out = new ArrayList<>();
        add(out, new CustomerFilter(purchasesMin, purchasesMax, null, null, null, null, null, false, null, null, null, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, excludeAreas, null, null, null, null, false, null, null, null, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, carriers, null, null, null, false, null, null, null, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, exchange, null, null, false, null, null, null, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, cancelled, null, false, null, null, null, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, null, returned, false, null, null, null, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, null, null, noOpenOrders, null, null, null, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, null, null, false, maxShipments, null, null, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, null, null, false, null, minDays, maxDays, null, null, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, bought, boughtMode, null, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, null, null, notBought, null, null, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, null, null, null, codMin, codMax, false, null));
        add(out, new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, null, null, null, null, null, skipOpenCases, null));
        return out;
    }

    private static void add(List<Facet> out, CustomerFilter only) {
        List<String> lines = only.describe();
        if (!lines.isEmpty()) {
            out.add(new Facet(String.join("; ", lines), only));
        }
    }

    /** Gộp hai bộ lọc (điều kiện của {@code b} được thêm vào {@code a}); dùng để cộng dồn từng điều kiện. Thứ tự lấy lấy của {@code a}. */
    public static CustomerFilter and(CustomerFilter a, CustomerFilter b) {
        return new CustomerFilter(
                b.purchasesMin != null || b.purchasesMax != null ? b.purchasesMin : a.purchasesMin,
                b.purchasesMin != null || b.purchasesMax != null ? b.purchasesMax : a.purchasesMax,
                !b.excludeAreas.isEmpty() ? b.excludeAreas : a.excludeAreas,
                !b.carriers.isEmpty() ? b.carriers : a.carriers,
                b.exchange != Tri.ANY ? b.exchange : a.exchange,
                b.cancelled != Tri.ANY ? b.cancelled : a.cancelled,
                b.returned != Tri.ANY ? b.returned : a.returned,
                a.noOpenOrders || b.noOpenOrders,
                b.maxShipments != null ? b.maxShipments : a.maxShipments,
                b.minDays != null ? b.minDays : a.minDays,
                b.maxDays != null ? b.maxDays : a.maxDays,
                !b.bought.isEmpty() ? b.bought : a.bought,
                !b.bought.isEmpty() ? b.boughtMode : a.boughtMode,
                !b.notBought.isEmpty() ? b.notBought : a.notBought,
                b.codMin != null ? b.codMin : a.codMin,
                b.codMax != null ? b.codMax : a.codMax,
                a.skipOpenCases || b.skipOpenCases,
                a.pickOrder);
    }

    // ---------- JSON ----------

    public String toJson() {
        return JSON.writeValueAsString(this);
    }

    /** Đọc lại bộ lọc đã lưu; chuỗi rỗng hay hỏng thì coi như không lọc (không bao giờ nới: không lọc = tập gốc đã đủ điều kiện). */
    public static CustomerFilter fromJson(String json) {
        if (json == null || json.isBlank()) {
            return NONE;
        }
        try {
            return JSON.readValue(json, CustomerFilter.class);
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    // ---------- chữ cho người đọc ----------

    /** Mô tả bằng tiếng Việt, mỗi điều kiện một dòng; rỗng nếu không lọc gì (chỉ có thứ tự mặc định). */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        if (purchasesMin != null || purchasesMax != null) {
            out.add(maxPurchases() >= MAX_PURCHASES ? "Mua từ " + minPurchases() + " lần trở lên"
                    : "Mua từ " + minPurchases() + " đến " + maxPurchases() + " lần");
        }
        if (!excludeAreas.isEmpty()) {
            out.add("Trừ khu vực: " + String.join(", ", excludeAreas.stream().map(CustomerFilter::areaLabel).toList()));
        }
        if (!carriers.isEmpty()) {
            out.add("Có đơn qua: " + String.join(", ", carriers.stream().map(c -> Carrier.valueOf(c).label()).toList()));
        }
        tri(out, "đơn đổi", exchange);
        tri(out, "đơn hủy", cancelled);
        tri(out, "đơn hoàn hoặc đang hoàn", returned);
        if (noOpenOrders) {
            out.add("Bỏ khách đang có đơn chưa giao xong");
        }
        if (maxShipments != null) {
            out.add("Tổng số đơn không quá " + maxShipments);
        }
        if (minDays != null || maxDays != null) {
            out.add("Lần mua gần nhất cách đây " + (minDays != null && maxDays != null ? "từ " + minDays + " đến " + maxDays + " ngày"
                    : minDays != null ? "ít nhất " + minDays + " ngày" : "không quá " + maxDays + " ngày"));
        }
        if (!bought.isEmpty()) {
            out.add("Đã mua " + (boughtMode == Mode.ALL ? "tất cả: " : "bất kỳ: ") + String.join(", ", bought.stream().map(g -> g.label().toLowerCase(Locale.ROOT)).toList()));
        }
        if (!notBought.isEmpty()) {
            out.add("Chưa mua: " + String.join(", ", notBought.stream().map(g -> g.label().toLowerCase(Locale.ROOT)).toList()));
        }
        if (codMin != null || codMax != null) {
            out.add("Tổng tiền đã thu (đối soát) " + (codMin != null && codMax != null ? "từ " + money(codMin) + " đến " + money(codMax)
                    : codMin != null ? "từ " + money(codMin) : "không quá " + money(codMax)) + " đồng");
        }
        if (skipOpenCases) {
            out.add("Bỏ khách đang có việc cần người thật");
        }
        return out;
    }

    /** Thứ tự lấy khách bằng chữ. */
    public String pickLabel() {
        return switch (pickOrder) {
            case ContactService.Pick.NEWEST_PURCHASE -> "Khách mua gần đây nhất trước";
            case ContactService.Pick.OLDEST_PURCHASE -> "Khách mua đã lâu trước";
            default -> "Khách được duyệt sớm nhất trước";
        };
    }

    private static void tri(List<String> out, String what, Tri t) {
        if (t == Tri.YES) {
            out.add("Có " + what);
        } else if (t == Tri.NO) {
            out.add("Không có " + what);
        }
    }

    private static String areaLabel(String code) {
        return GeoArea.UNKNOWN.equals(code) ? "Chưa rõ khu vực" : GeoArea.parse(code).map(GeoArea::label).orElse(code);
    }

    private static String money(Long v) {
        return String.format(Locale.GERMANY, "%,d", v);
    }

    /** Các nhóm hàng đã chọn có thể dùng để lọc (cho form). */
    public static Set<ProductGroup> filterableGroups() {
        EnumSet<ProductGroup> s = EnumSet.allOf(ProductGroup.class);
        s.removeIf(g -> !g.filterable());
        return s;
    }
}
