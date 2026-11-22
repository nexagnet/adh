package vn.shop.crm.customers;

import vn.shop.crm.geo.GeoArea;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bộ lọc, sắp xếp và trang của danh sách khách. Toàn bộ trạng thái nằm trong địa chỉ trang (URL), đọc lỏng tay: giá
 * trị lạ bị bỏ qua chứ không báo lỗi. {@link #params()} cho ra đúng các tham số đã hợp lệ để dựng lại đường dẫn.
 */
public record CustomerQuery(Segment segment, boolean review, String q, String area,
                            String phone, String name, String address,
                            Integer buyFrom, Integer buyTo, LocalDate lastFrom, LocalDate lastTo,
                            Approval approval, Invite invite, NoteFilter note,
                            SortKey sort, boolean desc, int page) {

    /** Trạng thái duyệt mời; mã lưu trong URL, nhãn khớp chữ hiện trong bảng. */
    public enum Approval {
        REVIEW("Cần kiểm tra"), PENDING("Chờ duyệt"), ELIGIBLE("Đã duyệt"), BLOCKED("Đã chặn"), REFUSED("Đã từ chối");

        private final String label;

        Approval(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Tình trạng lời mời của khách. */
    public enum Invite {
        NONE("Chưa mời", null), READY("Chưa gửi", "READY"), SENT("Đã gửi", "SENT"), CLICKED("Đã bấm link", "CLICKED"),
        FRIENDED("Đã kết bạn", "FRIENDED"), JOINED("Đã vào nhóm", "JOINED"), CANCELLED("Đã hủy", "CANCELLED");

        private final String label;
        private final String dbStatus;

        Invite(String label, String dbStatus) {
            this.label = label;
            this.dbStatus = dbStatus;
        }

        public String label() {
            return label;
        }

        /** Trạng thái trong bảng lời mời; null với "Chưa mời". */
        public String dbStatus() {
            return dbStatus;
        }
    }

    public enum NoteFilter {
        HAS("Có ghi chú"), NONE("Chưa có ghi chú");

        private final String label;

        NoteFilter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Cột có thể sắp xếp; {@code param} là giá trị tham số {@code sort} trong URL. */
    public enum SortKey {
        PHONE("phone"), NAME("name"), ADDRESS("address"), AREA("area"), PURCHASES("purchases"), LAST("last"),
        SEGMENT("segment"), APPROVAL("approval"), INVITE("invite"), NOTE("note");

        private final String param;

        SortKey(String param) {
            this.param = param;
        }

        public String param() {
            return param;
        }

        static Optional<SortKey> of(String param) {
            for (SortKey k : values()) {
                if (k.param.equals(param)) {
                    return Optional.of(k);
                }
            }
            return Optional.empty();
        }
    }

    /** Tham số lọc của từng cột (khóa cột = giá trị {@code sort} của cột đó). */
    private static final Map<String, List<String>> COLUMN_PARAMS = Map.of(
            "phone", List.of("phone"), "name", List.of("name"), "address", List.of("address"),
            "area", List.of("area"), "purchases", List.of("buyFrom", "buyTo"), "last", List.of("lastFrom", "lastTo"),
            "approval", List.of("approval"), "invite", List.of("invite"), "note", List.of("note"));

    private static final int MAX_TEXT = 100;

    public static CustomerQuery empty() {
        return parse(Map.of());
    }

    /** Dựng nhanh cho các chỗ gọi cũ (không lọc theo cột, sắp xếp mặc định: mới mua nhất trước). */
    public static CustomerQuery of(Segment segment, boolean review, String area, String q, int page) {
        Map<String, String> p = new LinkedHashMap<>();
        if (segment != null) {
            p.put("segment", segment.name());
        }
        p.put("review", String.valueOf(review));
        if (area != null) {
            p.put("area", area);
        }
        if (q != null) {
            p.put("q", q);
        }
        p.put("page", String.valueOf(page));
        return parse(p);
    }

    public static CustomerQuery parse(Map<String, String> raw) {
        Segment segment = enumOf(Segment.class, raw.get("segment"));
        boolean review = "true".equals(raw.get("review"));
        Integer buyFrom = intOf(raw.get("buyFrom"));
        Integer buyTo = intOf(raw.get("buyTo"));
        if (buyFrom != null && buyTo != null && buyFrom > buyTo) {
            Integer t = buyFrom;
            buyFrom = buyTo;
            buyTo = t;
        }
        LocalDate lastFrom = dateOf(raw.get("lastFrom"));
        LocalDate lastTo = dateOf(raw.get("lastTo"));
        if (lastFrom != null && lastTo != null && lastFrom.isAfter(lastTo)) {
            LocalDate t = lastFrom;
            lastFrom = lastTo;
            lastTo = t;
        }
        SortKey sort = SortKey.of(raw.get("sort")).orElse(null);
        boolean desc = sort != null && "desc".equals(raw.get("dir"));
        int page = 1;
        try {
            page = Math.max(1, Integer.parseInt(raw.getOrDefault("page", "1").strip()));
        } catch (NumberFormatException ignored) {
            // trang sai thì về trang 1
        }
        return new CustomerQuery(segment, review, text(raw.get("q")), validArea(raw.get("area")),
                text(raw.get("phone")), text(raw.get("name")), text(raw.get("address")),
                buyFrom, buyTo, lastFrom, lastTo,
                enumOf(Approval.class, raw.get("approval")), enumOf(Invite.class, raw.get("invite")),
                enumOf(NoteFilter.class, raw.get("note")), sort, desc, page);
    }

    /** Sắp xếp đang dùng; mặc định mới mua nhất trước. */
    public SortKey effectiveSort() {
        return sort == null ? SortKey.LAST : sort;
    }

    public boolean effectiveDesc() {
        return sort == null || desc;
    }

    /** Các tham số hợp lệ, theo thứ tự cố định; chỉ có giá trị đã dùng. */
    public Map<String, String> params() {
        Map<String, String> p = new LinkedHashMap<>();
        put(p, "segment", segment == null ? null : segment.name());
        put(p, "review", review ? "true" : null);
        put(p, "q", q);
        put(p, "area", area);
        put(p, "phone", phone);
        put(p, "name", name);
        put(p, "address", address);
        put(p, "buyFrom", buyFrom == null ? null : buyFrom.toString());
        put(p, "buyTo", buyTo == null ? null : buyTo.toString());
        put(p, "lastFrom", lastFrom == null ? null : lastFrom.toString());
        put(p, "lastTo", lastTo == null ? null : lastTo.toString());
        put(p, "approval", approval == null ? null : approval.name());
        put(p, "invite", invite == null ? null : invite.name());
        put(p, "note", note == null ? null : note.name());
        if (sort != null) {
            p.put("sort", sort.param());
            p.put("dir", desc ? "desc" : "asc");
        }
        if (page > 1) {
            p.put("page", String.valueOf(page));
        }
        return p;
    }

    /** Bản sao với một tham số đặt lại (null/rỗng = bỏ); đổi bộ lọc thì về trang 1 trừ khi chính là tham số {@code page}. */
    public CustomerQuery with(String key, String value) {
        Map<String, String> p = params();
        if (value == null || value.isBlank()) {
            p.remove(key);
        } else {
            p.put(key, value);
        }
        if (!"page".equals(key)) {
            p.remove("page");
        }
        return parse(p);
    }

    public CustomerQuery withPage(int page) {
        return with("page", String.valueOf(page));
    }

    public CustomerQuery withReview(boolean value) {
        return with("review", value ? "true" : null);
    }

    /** Bỏ bộ lọc và sắp xếp của các cột không hiện trong bảng (tránh lọc ngầm mà người dùng không thấy). */
    public CustomerQuery restrictedTo(Set<String> shownColumns) {
        Map<String, String> p = params();
        COLUMN_PARAMS.forEach((column, names) -> {
            if (!shownColumns.contains(column)) {
                names.forEach(p::remove);
            }
        });
        if (sort != null && !shownColumns.contains(sort.param())) {
            p.remove("sort");
            p.remove("dir");
        }
        return parse(p);
    }

    /** Số cột đang có bộ lọc (không tính phân loại, "cần kiểm tra" và ô tìm chung). */
    public int activeColumnFilters(Set<String> shownColumns) {
        int n = 0;
        Map<String, String> p = params();
        for (var e : COLUMN_PARAMS.entrySet()) {
            if (shownColumns.contains(e.getKey()) && e.getValue().stream().anyMatch(p::containsKey)) {
                n++;
            }
        }
        return n;
    }

    /** Bản sao đã bỏ mọi bộ lọc theo cột (giữ phân loại, cần kiểm tra, ô tìm chung và sắp xếp). */
    public CustomerQuery withoutColumnFilters() {
        Map<String, String> p = params();
        COLUMN_PARAMS.values().forEach(names -> names.forEach(p::remove));
        p.remove("page");
        return parse(p);
    }

    private static void put(Map<String, String> p, String key, String value) {
        if (value != null && !value.isBlank()) {
            p.put(key, value);
        }
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    private static Integer intOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int n = Integer.parseInt(value.strip());
            return n < 0 || n > 100_000 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate dateOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Chỉ nhận mã khu vực hợp lệ (hoặc "U" = chưa rõ); mã lạ bị bỏ qua. */
    private static String validArea(String area) {
        if (area == null || area.isBlank()) {
            return null;
        }
        if (GeoArea.UNKNOWN.equals(area)) {
            return area;
        }
        return GeoArea.parse(area).map(GeoArea::code).orElse(null);
    }
}
