package vn.shop.crm.customers;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Một cột của bảng danh sách khách: tiêu đề bấm để sắp xếp, ô lọc nằm ngay dưới tiêu đề. Khóa cột trùng giá trị
 * tham số {@code sort}; {@code param} là tham số lọc (cột khoảng dùng {@code param + "From"/"To"}).
 */
public record CustomerColumn(String key, String label, Kind kind, String param, List<Option> options) {

    public enum Kind { TEXT, AREA, SELECT, NUMBER_RANGE, DATE_RANGE }

    public record Option(String code, String label) {
    }

    private static CustomerColumn text(String key, String label) {
        return new CustomerColumn(key, label, Kind.TEXT, key, List.of());
    }

    private static final CustomerColumn PHONE = text("phone", "Số điện thoại");
    private static final CustomerColumn NAME = text("name", "Khách");
    private static final CustomerColumn ADDRESS = text("address", "Địa chỉ");
    private static final CustomerColumn AREA = new CustomerColumn("area", "Khu vực", Kind.AREA, "area", List.of());
    private static final CustomerColumn PURCHASES = new CustomerColumn("purchases", "Lần mua",
            Kind.NUMBER_RANGE, "buy", List.of());
    private static final CustomerColumn LAST = new CustomerColumn("last", "Mua gần nhất", Kind.DATE_RANGE, "last", List.of());
    private static final CustomerColumn SEGMENT = new CustomerColumn("segment", "Phân loại", Kind.SELECT, "segment",
            Arrays.stream(Segment.values()).map(s -> new Option(s.name(), s.label())).toList());
    private static final CustomerColumn APPROVAL = new CustomerColumn("approval", "Duyệt mời", Kind.SELECT, "approval",
            Arrays.stream(CustomerQuery.Approval.values()).map(a -> new Option(a.name(), a.label())).toList());
    private static final CustomerColumn INVITE = new CustomerColumn("invite", "Lời mời", Kind.SELECT, "invite",
            Arrays.stream(CustomerQuery.Invite.values()).map(a -> new Option(a.name(), a.label())).toList());
    private static final CustomerColumn NOTE = new CustomerColumn("note", "Ghi chú", Kind.SELECT, "note",
            Arrays.stream(CustomerQuery.NoteFilter.values()).map(a -> new Option(a.name(), a.label())).toList());

    /** Cột hiện theo danh sách đang xem: mua 1 lần có duyệt mời và lời mời, mua từ 2 lần có số lần mua và ghi chú. */
    public static List<CustomerColumn> forSegment(Segment segment) {
        if (segment == Segment.ONE_TIME) {
            return List.of(PHONE, NAME, ADDRESS, AREA, LAST, APPROVAL, INVITE);
        }
        if (segment == Segment.REPEAT) {
            return List.of(PHONE, NAME, ADDRESS, AREA, PURCHASES, LAST, NOTE);
        }
        return List.of(PHONE, NAME, ADDRESS, AREA, PURCHASES, LAST, SEGMENT, NOTE);
    }

    public static Set<String> keys(List<CustomerColumn> columns) {
        Set<String> keys = new LinkedHashSet<>();
        columns.forEach(c -> keys.add(c.key()));
        return keys;
    }

    /** Tên các tham số lọc có ô nhập trong hàng lọc của bảng (để biểu mẫu không lặp lại chúng thành trường ẩn). */
    public static List<String> ownParams(List<CustomerColumn> columns) {
        List<String> names = new java.util.ArrayList<>();
        for (CustomerColumn c : columns) {
            switch (c.kind()) {
                case NUMBER_RANGE, DATE_RANGE -> {
                    names.add(c.param() + "From");
                    names.add(c.param() + "To");
                }
                default -> names.add(c.param());
            }
        }
        return names;
    }

    public boolean isRange() {
        return kind == Kind.NUMBER_RANGE || kind == Kind.DATE_RANGE;
    }
}
