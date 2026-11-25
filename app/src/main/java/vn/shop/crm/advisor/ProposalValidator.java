package vn.shop.crm.advisor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.bot.PiiMasker;
import vn.shop.crm.contact.CustomerFilter;
import vn.shop.crm.contact.ProductGroup;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kiểm cứng đầu ra của AI đề xuất chiến dịch (D21). Không dùng AI để kiểm AI. Khuôn sai thì bỏ cả lượt; một đề xuất sai thì bỏ riêng đề xuất đó
 * (không sửa hộ) và ghi lý do để người dùng thấy. Mọi giới hạn lấy từ bản số liệu mà AI đã nhận, nên đề xuất không bao giờ vượt giới hạn hệ thống.
 */
final class ProposalValidator {

    private ProposalValidator() {
    }

    static final int MAX_PROPOSALS = 3;
    static final int MAX_DAYS = 14;
    static final int MAX_START_AHEAD_DAYS = 30;
    static final int MIN_WINDOW_MINUTES = 60;
    static final int MAX_TITLE = 80;
    static final int MAX_ANGLE = 200;
    static final int MAX_LINE = 200;
    /** Chưa đủ chừng này tin đã nhắn thì AI chưa có lịch sử để dựa vào: độ tin cậy bị chặn trần. */
    static final int MIN_HISTORY_SENT = 30;
    static final double CAP_NO_HISTORY = 0.6;
    static final double CAP_NO_WEATHER = 0.7;
    /** Ngày bắt đầu nằm ngoài những ngày đã có dự báo thời tiết thì độ tin cậy bị chặn trần (lý do dựa vào thời tiết không kiểm chứng được). */
    static final double CAP_BEYOND_FORECAST = 0.65;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> TOP_KEYS = Set.of("proposals", "skip_reason");
    private static final Set<String> KEYS = Set.of("title", "area", "batch_size", "start_date", "end_date", "window_from",
            "window_to", "daily_cap", "message_angle", "reasons", "risks", "confidence");
    /** Khóa tùy chọn: thiếu thì lấy mặc định của hệ thống (mọi ngày, duyệt sớm nhất, không lọc độ mới lần mua). */
    private static final Set<String> OPTIONAL_KEYS = Set.of("weekdays", "pick_order", "min_days_since_purchase", "max_days_since_purchase", "product_group", "filter");
    /** Khóa của đối tượng {@code filter} (cùng khuôn với bộ lọc khách của người dùng, {@link CustomerFilter}). */
    private static final Set<String> FILTER_KEYS = Set.of("purchases_min", "purchases_max", "exchange", "cancelled", "returned", "no_open_orders",
            "max_shipments", "bought", "bought_mode", "not_bought", "cod_min", "cod_max", "skip_open_cases");
    private static final Set<String> PICK_ORDERS = Set.of("OLDEST_APPROVED", "NEWEST_PURCHASE", "OLDEST_PURCHASE");
    static final int MAX_DAYS_SINCE_PURCHASE = 1100;
    private static final Set<String> PRODUCT_GROUPS = Set.of("AO_DIEU_HOA", "PIN", "PHU_KIEN_KHAC");
    private static final Pattern TIME = Pattern.compile("\\d{2}:\\d{2}");
    private static final Pattern LINK = Pattern.compile(
            "(https?:|www\\.|zalo\\.me|\\b[a-z0-9-]+\\.(com|vn|net|me|org|info|app|link|ly|io)\\b|@[a-z0-9_.]{3,})");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");
    /** Chữ cấm kể cả khi chỉ để người dùng đọc: người dùng có thể chép sang tin nhắn (đã bỏ dấu, chữ thường). */
    private static final List<String> FORBIDDEN = List.of("cam ket", "dam bao", "chac chan", "qua tang", "tang qua", "tang kem", "voucher", "ma giam",
            "doi tra", "hoan tien", "shopee", "lazada", "tiki", "facebook");

    /** Kết quả: đề xuất đạt, các đề xuất bị bỏ kèm lý do, lý do AI không đề xuất; hoặc {@code fatal} nếu khuôn sai. */
    record Outcome(List<AdvisorProposal> accepted, List<String> rejected, String skipReason, String fatal) {
        boolean invalidFormat() {
            return fatal != null;
        }
    }

    private static Outcome fatal(String why) {
        return new Outcome(List.of(), List.of(), null, why);
    }

    static Outcome validate(String raw, AdvisorSnapshot snap, LocalDate today) {
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (RuntimeException e) {
            return fatal("AI không trả JSON.");
        }
        if (root == null || !root.isObject() || !keysOf(root).equals(TOP_KEYS)) {
            return fatal("Khuôn JSON sai (cần đúng hai khóa proposals và skip_reason).");
        }
        JsonNode props = root.get("proposals");
        JsonNode skip = root.get("skip_reason");
        if (!props.isArray() || props.size() > MAX_PROPOSALS) {
            return fatal("proposals phải là danh sách tối đa " + MAX_PROPOSALS + " mục.");
        }
        if (!(skip.isNull() || skip.isString()) || (skip.isString() && skip.asString().length() > 300)) {
            return fatal("skip_reason phải là null hoặc chữ ngắn.");
        }
        String skipReason = skip.isString() && !skip.asString().isBlank() ? clean(skip.asString()) : null;
        if (skipReason != null && unsafe(skipReason).isPresent()) {
            skipReason = "(lý do không đạt kiểm tra, đã ẩn)";
        }

        List<AdvisorProposal> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Set<String> usedAreas = new HashSet<>();
        for (int i = 0; i < props.size(); i++) {
            String tag = "Đề xuất " + (i + 1) + ": ";
            Object r = one(props.get(i), snap, today, usedAreas);
            if (r instanceof String why) {
                rejected.add(tag + why);
            } else {
                AdvisorProposal p = (AdvisorProposal) r;
                usedAreas.add(p.area());
                accepted.add(p);
            }
        }
        return new Outcome(accepted, rejected, skipReason, null);
    }

    private static Set<String> keysOf(JsonNode n) {
        Set<String> out = new HashSet<>();
        n.propertyNames().forEach(out::add);
        return out;
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    /** Trả {@link AdvisorProposal} nếu đạt, ngược lại trả chuỗi lý do bị bỏ. */
    private static Object one(JsonNode n, AdvisorSnapshot snap, LocalDate today, Set<String> usedAreas) {
        Set<String> keys = n.isObject() ? keysOf(n) : Set.of();
        if (!n.isObject() || !keys.containsAll(KEYS) || !allowed(keys)) {
            return "sai khuôn (thiếu hoặc thừa khóa)";
        }
        for (String k : List.of("title", "area", "start_date", "end_date", "window_from", "window_to", "message_angle")) {
            if (!n.get(k).isString()) {
                return k + " phải là chữ";
            }
        }
        if (!n.get("batch_size").isIntegralNumber() || !n.get("daily_cap").isIntegralNumber()) {
            return "batch_size và daily_cap phải là số nguyên";
        }
        if (!n.get("confidence").isNumber() || n.get("confidence").asDouble() < 0 || n.get("confidence").asDouble() > 1
                || Double.isNaN(n.get("confidence").asDouble())) {
            return "confidence phải là số từ 0 đến 1";
        }

        String area = n.get("area").asString();
        Optional<AdvisorSnapshot.Area> a = snap.areas().stream().filter(x -> x.code().equals(area)).findFirst();
        if (a.isEmpty()) {
            return "khu vực không có trong số liệu";
        }
        if (usedAreas.contains(area)) {
            return "trùng khu vực với một đề xuất trước";
        }

        int batch = n.get("batch_size").asInt();
        // Chiến dịch mời cả khách mua nhiều lần: số khách đủ điều kiện của khu vực (chỉ khách mua 1 lần) không còn là trần; số khớp thật được kẹp lúc lưu.
        boolean multi = n.has("filter") && n.get("filter").isObject() && n.get("filter").has("purchases_max")
                && n.get("filter").get("purchases_max").isIntegralNumber() && n.get("filter").get("purchases_max").asInt() > 1;
        int maxBatch = (int) Math.min(multi ? Long.MAX_VALUE : a.get().eligible(), snap.limits().maxCampaignSize());
        if (batch < 1 || batch > maxBatch) {
            return "batch_size phải từ 1 đến " + maxBatch + " (số khách đủ điều kiện mời của khu vực, không quá trần chiến dịch)";
        }
        int cap = n.get("daily_cap").asInt();
        if (cap < 1 || cap > snap.limits().dailyCustomers()) {
            return "daily_cap phải từ 1 đến " + snap.limits().dailyCustomers() + " (hạn mức khách mỗi ngày hiện hành)";
        }

        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(n.get("start_date").asString());
            end = LocalDate.parse(n.get("end_date").asString());
        } catch (DateTimeException e) {
            return "ngày phải đúng dạng YYYY-MM-DD";
        }
        if (start.isBefore(today) || start.isAfter(today.plusDays(MAX_START_AHEAD_DAYS))) {
            return "ngày bắt đầu phải từ hôm nay tới " + MAX_START_AHEAD_DAYS + " ngày tới";
        }
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            return "chiến dịch kéo dài tối đa " + MAX_DAYS + " ngày và ngày kết thúc không trước ngày bắt đầu";
        }

        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if ((long) batch > (long) cap * days) {
            return "batch_size " + batch + " vượt số khách gửi được trong chiến dịch (" + cap + " khách mỗi ngày x " + days
                    + " ngày = " + (long) cap * days + "): khách dư sẽ hết hạn mà chưa được nhắn";
        }
        for (AdvisorSnapshot.CampaignInfo c : snap.campaigns()) {
            if (c.open() && overlapsScope(c.scope(), area) && overlapsDates(c, start, end)) {
                return "trùng khu vực và thời gian với một chiến dịch đang chạy hoặc còn khách chờ (" + c.ready() + " khách chờ gửi)";
            }
        }

        String f = n.get("window_from").asString();
        String t = n.get("window_to").asString();
        if (!TIME.matcher(f).matches() || !TIME.matcher(t).matches()) {
            return "khung giờ phải đúng dạng HH:mm";
        }
        LocalTime from;
        LocalTime to;
        LocalTime lo;
        LocalTime hi;
        try {
            from = LocalTime.parse(f);
            to = LocalTime.parse(t);
            lo = LocalTime.parse(snap.limits().windowFrom());
            hi = LocalTime.parse(snap.limits().windowTo());
        } catch (DateTimeException e) {
            return "khung giờ không hợp lệ";
        }
        if (from.isBefore(lo) || to.isAfter(hi) || ChronoUnit.MINUTES.between(from, to) < MIN_WINDOW_MINUTES) {
            return "khung giờ phải nằm trong " + lo + " đến " + hi + " và rộng ít nhất " + MIN_WINDOW_MINUTES + " phút";
        }

        String title = clean(n.get("title").asString());
        String angle = clean(n.get("message_angle").asString());
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            return "title phải từ 1 đến " + MAX_TITLE + " ký tự";
        }
        if (angle.length() > MAX_ANGLE) {
            return "message_angle tối đa " + MAX_ANGLE + " ký tự";
        }
        List<String> reasons = lines(n.get("reasons"), 1, 4);
        List<String> risks = lines(n.get("risks"), 0, 3);
        if (reasons == null || risks == null) {
            return "reasons cần 1 đến 4 dòng và risks tối đa 3 dòng, mỗi dòng tối đa " + MAX_LINE + " ký tự";
        }
        List<String> all = new ArrayList<>(List.of(title, angle));
        all.addAll(reasons);
        all.addAll(risks);
        for (String s : all) {
            Optional<String> bad = unsafe(s);
            if (bad.isPresent()) {
                return "chữ không đạt kiểm tra (" + bad.get() + ")";
            }
        }

        Integer weekdays = null;
        if (n.has("weekdays") && !n.get("weekdays").isNull()) {
            JsonNode w = n.get("weekdays");
            if (!w.isArray() || w.isEmpty() || w.size() > 7) {
                return "weekdays phải là danh sách 1 đến 7 ngày (1 = thứ Hai ... 7 = Chủ nhật) hoặc null";
            }
            int mask = 0;
            for (JsonNode d : w) {
                if (!d.isIntegralNumber() || d.asInt() < 1 || d.asInt() > 7) {
                    return "weekdays chỉ nhận số nguyên từ 1 đến 7";
                }
                mask |= 1 << (d.asInt() - 1);
            }
            weekdays = mask == 127 ? null : mask;
            if (weekdays != null && !hasAllowedDay(weekdays, start, end)) {
                return "weekdays không có ngày nào nằm trong khoảng từ ngày bắt đầu tới ngày kết thúc";
            }
            long sendDays = allowedDays(mask, start, end);
            if ((long) batch > (long) cap * sendDays) {
                return "batch_size " + batch + " vượt số khách gửi được trong các ngày được chọn (" + cap + " khách mỗi ngày x " + sendDays
                        + " ngày gửi = " + (long) cap * sendDays + ")";
            }
        }
        String pickOrder = "OLDEST_APPROVED";
        if (n.has("pick_order") && !n.get("pick_order").isNull()) {
            if (!n.get("pick_order").isString() || !PICK_ORDERS.contains(n.get("pick_order").asString())) {
                return "pick_order chỉ nhận OLDEST_APPROVED, NEWEST_PURCHASE hoặc OLDEST_PURCHASE";
            }
            pickOrder = n.get("pick_order").asString();
        }
        Integer minDays = optionalDays(n, "min_days_since_purchase");
        Integer maxDays = optionalDays(n, "max_days_since_purchase");
        if (minDays != null && minDays == -1 || maxDays != null && maxDays == -1) {
            return "min_days_since_purchase và max_days_since_purchase phải là số nguyên từ 0 đến " + MAX_DAYS_SINCE_PURCHASE + " hoặc null";
        }
        if (minDays != null && maxDays != null && maxDays < minDays) {
            return "max_days_since_purchase không được nhỏ hơn min_days_since_purchase";
        }

        String productGroup = null;
        if (n.has("product_group") && !n.get("product_group").isNull()) {
            if (!n.get("product_group").isString() || !PRODUCT_GROUPS.contains(n.get("product_group").asString())) {
                return "product_group chỉ nhận AO_DIEU_HOA, PIN, PHU_KIEN_KHAC hoặc null";
            }
            productGroup = n.get("product_group").asString();
        }

        CustomerFilter filter = CustomerFilter.NONE;
        if (n.has("filter") && !n.get("filter").isNull()) {
            Object parsed = filter(n.get("filter"), snap);
            if (parsed instanceof String why) {
                return why;
            }
            filter = (CustomerFilter) parsed;
            if (productGroup != null && !filter.bought().isEmpty()) {
                return "product_group và filter.bought không dùng cùng lúc (chỉ dùng filter.bought)";
            }
        }

        double confidence = n.get("confidence").asDouble();
        double ceiling = 1.0;
        if (snap.overall().funnel().sent() < MIN_HISTORY_SENT) {
            ceiling = Math.min(ceiling, CAP_NO_HISTORY);
        }
        if (a.get().weather() == null) {
            ceiling = Math.min(ceiling, CAP_NO_WEATHER);
        } else if (ChronoUnit.DAYS.between(today, start) >= a.get().weather().coverageDays()) {
            ceiling = Math.min(ceiling, CAP_BEYOND_FORECAST);
        }
        boolean capped = confidence > ceiling;
        return new AdvisorProposal(title, area, batch, start, end, from, to, cap, angle, reasons, risks,
                Math.min(confidence, ceiling), capped, weekdays, pickOrder, minDays, maxDays, productGroup, filter);
    }

    /**
     * Đọc đối tượng {@code filter} của AI thành {@link CustomerFilter}; trả chuỗi lý do nếu sai. Chỉ nhận đúng các khóa của khuôn, đúng kiểu, nằm trong miền
     * cho phép; lọc theo đơn đổi chỉ khi chủ shop đã cài cách nhận biết, lọc theo tiền chỉ khi đã có đối soát. Không bao giờ sửa hộ.
     */
    static Object filter(JsonNode f, AdvisorSnapshot snap) {
        if (!f.isObject() || !FILTER_KEYS.containsAll(keysOf(f))) {
            return "filter phải là đối tượng chỉ gồm các khóa: " + String.join(", ", new java.util.TreeSet<>(FILTER_KEYS));
        }
        var audience = snap.insights() == null ? null : snap.insights().audience();
        CustomerFilter.Builder b = CustomerFilter.builder();
        Integer pMin = intField(f, "purchases_min");
        Integer pMax = intField(f, "purchases_max");
        if (pMin != null && pMin == Integer.MIN_VALUE || pMax != null && pMax == Integer.MIN_VALUE) {
            return "filter.purchases_min và purchases_max phải là số nguyên";
        }
        b.purchases(pMin, pMax);
        for (String k : List.of("exchange", "cancelled", "returned")) {
            if (!f.has(k) || f.get(k).isNull()) {
                continue;
            }
            String v = f.get(k).isString() ? f.get(k).asString() : "";
            if (!List.of("YES", "NO", "ANY").contains(v)) {
                return "filter." + k + " chỉ nhận YES, NO hoặc ANY";
            }
            CustomerFilter.Tri t = CustomerFilter.Tri.valueOf(v);
            switch (k) {
                case "exchange" -> {
                    if (t != CustomerFilter.Tri.ANY && (audience == null || !audience.exchangeReady())) {
                        return "filter.exchange chưa dùng được: chủ cửa hàng chưa cài cách nhận biết đơn đổi";
                    }
                    b.exchange(t);
                }
                case "cancelled" -> b.cancelled(t);
                default -> b.returned(t);
            }
        }
        for (String k : List.of("no_open_orders", "skip_open_cases")) {
            if (f.has(k) && !f.get(k).isNull()) {
                if (!f.get(k).isBoolean()) {
                    return "filter." + k + " phải là true hoặc false";
                }
                if (f.get(k).asBoolean()) {
                    if (k.equals("no_open_orders")) {
                        b.noOpenOrders();
                    } else {
                        b.skipOpenCases();
                    }
                }
            }
        }
        Integer maxShip = intField(f, "max_shipments");
        if (maxShip != null && maxShip == Integer.MIN_VALUE) {
            return "filter.max_shipments phải là số nguyên";
        }
        b.maxShipments(maxShip);
        List<ProductGroup> bought = groups(f, "bought");
        List<ProductGroup> notBought = groups(f, "not_bought");
        if (bought == null || notBought == null) {
            return "filter.bought và not_bought chỉ nhận danh sách AO_DIEU_HOA, PIN, PHU_KIEN_KHAC";
        }
        CustomerFilter.Mode mode = CustomerFilter.Mode.ANY;
        if (f.has("bought_mode") && !f.get("bought_mode").isNull()) {
            String m = f.get("bought_mode").isString() ? f.get("bought_mode").asString() : "";
            if (!List.of("ANY", "ALL").contains(m)) {
                return "filter.bought_mode chỉ nhận ANY hoặc ALL";
            }
            mode = CustomerFilter.Mode.valueOf(m);
        }
        b.bought(mode, bought.toArray(new ProductGroup[0])).notBought(notBought.toArray(new ProductGroup[0]));
        Long codMin = longField(f, "cod_min");
        Long codMax = longField(f, "cod_max");
        if (codMin != null && codMin == Long.MIN_VALUE || codMax != null && codMax == Long.MIN_VALUE) {
            return "filter.cod_min và cod_max phải là số nguyên (đồng)";
        }
        if ((codMin != null || codMax != null) && (audience == null || !audience.settlementAvailable())) {
            return "filter.cod chưa dùng được: chưa có file đối soát";
        }
        b.cod(codMin, codMax);
        try {
            return b.build();
        } catch (vn.shop.crm.common.ImportException e) {
            return "filter không hợp lệ: " + e.getMessage();
        }
    }

    /** Số nguyên tùy chọn: null nếu thiếu; {@link Integer#MIN_VALUE} nếu sai kiểu. */
    private static Integer intField(JsonNode n, String key) {
        if (!n.has(key) || n.get(key).isNull()) {
            return null;
        }
        return n.get(key).isIntegralNumber() && n.get(key).canConvertToInt() ? n.get(key).asInt() : Integer.MIN_VALUE;
    }

    private static Long longField(JsonNode n, String key) {
        if (!n.has(key) || n.get(key).isNull()) {
            return null;
        }
        return n.get(key).isIntegralNumber() && n.get(key).canConvertToLong() ? n.get(key).asLong() : Long.MIN_VALUE;
    }

    /** Danh sách nhóm hàng tùy chọn; null nếu sai. Thiếu hoặc null là danh sách rỗng. */
    private static List<ProductGroup> groups(JsonNode n, String key) {
        if (!n.has(key) || n.get(key).isNull()) {
            return List.of();
        }
        if (!n.get(key).isArray() || n.get(key).size() > PRODUCT_GROUPS.size()) {
            return null;
        }
        List<ProductGroup> out = new ArrayList<>();
        for (JsonNode x : n.get(key)) {
            if (!x.isString() || !PRODUCT_GROUPS.contains(x.asString())) {
                return null;
            }
            out.add(ProductGroup.valueOf(x.asString()));
        }
        return out;
    }

    /** Hai khu vực có chung tỉnh nào không. "ALL" là mọi nơi, "U" là chưa rõ tỉnh. */
    static boolean overlapsScope(String campaignScope, String area) {
        if (campaignScope == null || campaignScope.isBlank() || "ALL".equals(campaignScope)) {
            return true;
        }
        if (campaignScope.equals(area)) {
            return true;
        }
        var a = vn.shop.crm.geo.GeoArea.parse(campaignScope);
        var b = vn.shop.crm.geo.GeoArea.parse(area);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Set<String> pa = new HashSet<>();
        vn.shop.crm.geo.VnGeo.in(a.get()).forEach(p -> pa.add(p.code()));
        return vn.shop.crm.geo.VnGeo.in(b.get()).stream().anyMatch(p -> pa.contains(p.code()));
    }

    /** Khoảng ngày của chiến dịch (null = không giới hạn đầu hoặc cuối) có giao với [start, end] không. */
    static boolean overlapsDates(AdvisorSnapshot.CampaignInfo c, LocalDate start, LocalDate end) {
        try {
            LocalDate cs = c.startOn() == null ? null : LocalDate.parse(c.startOn());
            LocalDate ce = c.endOn() == null ? null : LocalDate.parse(c.endOn());
            return (cs == null || !cs.isAfter(end)) && (ce == null || !ce.isBefore(start));
        } catch (DateTimeException e) {
            return true;
        }
    }

    private static boolean allowed(Set<String> keys) {
        for (String k : keys) {
            if (!KEYS.contains(k) && !OPTIONAL_KEYS.contains(k)) {
                return false;
            }
        }
        return true;
    }

    static long allowedDays(int mask, LocalDate start, LocalDate end) {
        long n = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if ((mask & (1 << (d.getDayOfWeek().getValue() - 1))) != 0) {
                n++;
            }
        }
        return n;
    }

    /** Ít nhất một ngày trong khoảng [start, end] có bit tương ứng trong {@code mask}. */
    static boolean hasAllowedDay(int mask, LocalDate start, LocalDate end) {
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if ((mask & (1 << (d.getDayOfWeek().getValue() - 1))) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Số ngày tùy chọn: null nếu thiếu hoặc null; -1 nếu sai (không phải số nguyên 0 đến trần). */
    private static Integer optionalDays(JsonNode n, String key) {
        if (!n.has(key) || n.get(key).isNull()) {
            return null;
        }
        JsonNode v = n.get(key);
        if (!v.isIntegralNumber() || v.asInt() < 0 || v.asInt() > MAX_DAYS_SINCE_PURCHASE) {
            return -1;
        }
        return v.asInt();
    }

    private static List<String> lines(JsonNode n, int min, int max) {
        if (!n.isArray() || n.size() < min || n.size() > max) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode x : n) {
            if (!x.isString()) {
                return null;
            }
            String s = clean(x.asString());
            if (s.isEmpty() || s.length() > MAX_LINE) {
                return null;
            }
            out.add(s);
        }
        return out;
    }

    /** Lý do không an toàn của một đoạn chữ, hoặc rỗng nếu ổn. */
    static Optional<String> unsafe(String s) {
        if (CONTROL.matcher(s).find()) {
            return Optional.of("ký tự điều khiển");
        }
        String folded = fold(s);
        if (LINK.matcher(folded).find()) {
            return Optional.of("đường dẫn");
        }
        if (PiiMasker.containsPii(s)) {
            return Optional.of("số điện thoại hoặc email");
        }
        for (String w : FORBIDDEN) {
            if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(w) + "(?![a-z0-9])").matcher(folded).find()) {
                return Optional.of("chữ không được dùng: " + w);
            }
        }
        return Optional.empty();
    }

    static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(java.util.Locale.ROOT).replace('đ', 'd').replaceAll("\\s+", " ");
    }
}
