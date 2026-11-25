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
    public static final int MAX_SHIPMENTS = 50;
    public static final long MAX_COD = 10_000_000_000L;
    public static final Set<String> PICK_ORDERS = Set.of(ContactService.Pick.OLDEST_APPROVED, ContactService.Pick.NEWEST_PURCHASE,
            ContactService.Pick.OLDEST_PURCHASE);

    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Không lọc gì, thứ tự mặc định (giống hệt hôm nay). */
    public static final CustomerFilter NONE = new CustomerFilter(null, null, null, null, null, false, null, null, null, null, null, null, null,
            null, false, null);

    /** Chuẩn hóa và kiểm tra; giá trị sai ném {@link ImportException} (chữ tiếng Việt, hiện cho người dùng). */
    public CustomerFilter {
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

    /** True nếu có bất kỳ điều kiện nào khác mặc định (kể cả thứ tự lấy). */
    public boolean any() {
        return !equals(NONE);
    }

    /** Có điều kiện nào phải tra bảng đối soát hoặc dấu hiệu đơn đổi không (để báo trước cho người dùng). */
    public boolean needsExchangeMarkers() {
        return exchange != Tri.ANY;
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
