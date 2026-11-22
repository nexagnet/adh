package vn.shop.crm.web;

import org.springframework.stereotype.Component;
import vn.shop.crm.bot.HandoffService;
import vn.shop.crm.contact.PrivacyService;
import vn.shop.crm.shipping.StatusMappingService;

import java.util.List;

/**
 * Thanh bên trái chia nhóm (thiết kế Penpot đã duyệt). Số "việc cần làm" chỉ được tính khi mẫu trang gọi
 * {@code @navMenu.groups(navKey)}, tức là chỉ trên các trang đã đăng nhập có thanh bên.
 */
@Component("navMenu")
public class NavMenu {

    public record Item(String label, String url, String key, long badge, boolean active) {
        public boolean hasBadge() {
            return badge > 0;
        }
    }

    public record Group(String title, List<Item> items) {
    }

    private final HandoffService handoffs;
    private final PrivacyService privacy;
    private final StatusMappingService statuses;

    public NavMenu(HandoffService handoffs, PrivacyService privacy, StatusMappingService statuses) {
        this.handoffs = handoffs;
        this.privacy = privacy;
        this.statuses = statuses;
    }

    /** Khóa mục đang mở: đường dẫn, riêng danh sách khách thêm loại khách (để tô sáng đúng mục). */
    public static String keyOf(String path, String segment) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if ("/customers".equals(path) || path.matches("/customers/\\d+")) {
            if ("REPEAT".equals(segment)) {
                return "/customers/repeat";
            }
            if ("ONE_TIME".equals(segment) || path.length() > "/customers".length()) {
                return "/customers/one-time";
            }
        }
        return path;
    }

    public List<Group> groups(String currentKey) {
        String key = currentKey == null ? "/" : currentKey;
        long handoffOpen = handoffs.openCount();
        long privacyOpen = privacy.openCount();
        long unmapped = statuses.unmapped().size();
        return List.of(
                new Group("Khách hàng", List.of(
                        item("Tổng quan", "/", "/", 0, key),
                        item("Nhập đơn", "/imports", "/imports", 0, key),
                        item("Khách mua 1 lần", "/customers?segment=ONE_TIME", "/customers/one-time", 0, key),
                        item("Khách mua từ 2 lần", "/customers?segment=REPEAT", "/customers/repeat", 0, key),
                        item("Trạng thái đơn", "/status-mappings", "/status-mappings", unmapped, key))),
                new Group("Mời vào nhóm", List.of(
                        item("Danh sách mời", "/contacts", "/contacts", 0, key),
                        item("Chiến dịch mời", "/campaigns", "/campaigns", 0, key),
                        item("Lô nhắn hôm nay", "/today", "/today", 0, key),
                        item("Chạy thử trên Zalo", "/test-runs", "/test-runs", 0, key),
                        item("Nhóm Zalo", "/groups", "/groups", 0, key),
                        item("Cài đặt gửi tin", "/sending", "/sending", 0, key))),
                new Group("Trợ lý AI", List.of(
                        item("Việc cần mẹ xử lý", "/handoffs", "/handoffs", handoffOpen, key),
                        item("Tài khoản trợ lý AI", "/bot", "/bot", 0, key))),
                new Group("Khác", List.of(
                        item("Yêu cầu của khách", "/privacy", "/privacy", privacyOpen, key),
                        item("In giấy giới thiệu", "/print", "/print", 0, key),
                        item("Bản đồ khách", "/map", "/map", 0, key))));
    }

    private static Item item(String label, String url, String itemKey, long badge, String current) {
        boolean active = itemKey.equals(current) || (!"/".equals(itemKey) && current.startsWith(itemKey + "/"));
        return new Item(label, url, itemKey, badge, active);
    }
}
