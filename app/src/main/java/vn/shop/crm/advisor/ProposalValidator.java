package vn.shop.crm.advisor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.bot.PiiMasker;

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

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> TOP_KEYS = Set.of("proposals", "skip_reason");
    private static final Set<String> KEYS = Set.of("title", "area", "batch_size", "start_date", "end_date", "window_from",
            "window_to", "daily_cap", "message_angle", "reasons", "risks", "confidence");
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
        if (!n.isObject() || !keysOf(n).equals(KEYS)) {
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
        int maxBatch = (int) Math.min(a.get().eligible(), snap.limits().maxCampaignSize());
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

        double confidence = n.get("confidence").asDouble();
        double ceiling = 1.0;
        if (snap.overall().funnel().sent() < MIN_HISTORY_SENT) {
            ceiling = Math.min(ceiling, CAP_NO_HISTORY);
        }
        if (a.get().weather() == null) {
            ceiling = Math.min(ceiling, CAP_NO_WEATHER);
        }
        boolean capped = confidence > ceiling;
        return new AdvisorProposal(title, area, batch, start, end, from, to, cap, angle, reasons, risks,
                Math.min(confidence, ceiling), capped);
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
