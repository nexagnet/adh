package vn.shop.crm.customers;

import vn.shop.crm.geo.GeoArea;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Các thẻ "đang lọc" hiện trên bảng khách: mỗi thẻ nói rõ một điều kiện và có đường dẫn để bỏ riêng điều kiện đó. */
public final class CustomerFilterChips {

    public record Chip(String label, String removeUrl) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private CustomerFilterChips() {
    }

    public static List<Chip> of(CustomerQuery q, CustomerListLinks links) {
        List<Chip> chips = new ArrayList<>();
        if (q.segment() != null) {
            chips.add(new Chip("Loại: " + q.segment().label(), links.without("segment")));
        }
        if (q.review()) {
            chips.add(new Chip("Chỉ khách cần kiểm tra", links.without("review")));
        }
        if (q.area() != null) {
            String label = GeoArea.UNKNOWN.equals(q.area()) ? "Chưa rõ khu vực"
                    : GeoArea.parse(q.area()).map(GeoArea::label).orElse(q.area());
            chips.add(new Chip("Khu vực: " + label, links.without("area")));
        }
        if (q.q() != null) {
            chips.add(new Chip("Tìm: " + q.q(), links.without("q")));
        }
        if (q.phone() != null) {
            chips.add(new Chip("Số điện thoại: " + q.phone(), links.without("phone")));
        }
        if (q.name() != null) {
            chips.add(new Chip("Khách: " + q.name(), links.without("name")));
        }
        if (q.address() != null) {
            chips.add(new Chip("Địa chỉ: " + q.address(), links.without("address")));
        }
        if (q.buyFrom() != null || q.buyTo() != null) {
            chips.add(new Chip("Lần mua: " + range(q.buyFrom(), q.buyTo()), links.without("buyFrom", "buyTo")));
        }
        if (q.lastFrom() != null || q.lastTo() != null) {
            chips.add(new Chip("Mua gần nhất: " + range(q.lastFrom() == null ? null : q.lastFrom().format(DAY),
                    q.lastTo() == null ? null : q.lastTo().format(DAY)), links.without("lastFrom", "lastTo")));
        }
        if (q.approval() != null) {
            chips.add(new Chip("Duyệt mời: " + q.approval().label(), links.without("approval")));
        }
        if (q.invite() != null) {
            chips.add(new Chip("Lời mời: " + q.invite().label(), links.without("invite")));
        }
        if (q.note() != null) {
            chips.add(new Chip("Ghi chú: " + q.note().label(), links.without("note")));
        }
        return chips;
    }

    private static String range(Object from, Object to) {
        if (from != null && to != null) {
            return from.equals(to) ? from.toString() : "từ " + from + " đến " + to;
        }
        return from != null ? "từ " + from : "đến " + to;
    }
}
