package vn.shop.crm.web;

import org.springframework.stereotype.Component;
import vn.shop.crm.bot.HandoffService;
import vn.shop.crm.contact.PrivacyService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.StatusMappingService;
import vn.shop.crm.zalo.ZaloAutoService;

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
    private final ZaloAutoService zalo;
    private final InviteService invites;
    private final vn.shop.crm.inbox.RefusalReviewService refusals;
    private final vn.shop.crm.inbox.ReplyService replies;
    private final vn.shop.crm.zalo.ZaloHealthService zaloHealth;

    public NavMenu(HandoffService handoffs, PrivacyService privacy, StatusMappingService statuses, ZaloAutoService zalo,
                   InviteService invites, vn.shop.crm.inbox.RefusalReviewService refusals, vn.shop.crm.inbox.ReplyService replies,
                   vn.shop.crm.zalo.ZaloHealthService zaloHealth) {
        this.zaloHealth = zaloHealth;
        this.refusals = refusals;
        this.replies = replies;
        this.invites = invites;
        this.handoffs = handoffs;
        this.privacy = privacy;
        this.statuses = statuses;
        this.zalo = zalo;
    }

    /** Khóa mục đang mở: đường dẫn; trang một khách (/customers/{id}) tô sáng mục Khách hàng. */
    public static String keyOf(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.matches("/customers/\\d+")) {
            return "/customers";
        }
        return path;
    }

    public List<Group> groups(String currentKey) {
        String key = currentKey == null ? "/" : currentKey;
        long handoffOpen = handoffs.openCount() + refusals.heldCount() + replies.openCount();
        long privacyOpen = privacy.openCount();
        long unmapped = statuses.unmapped().size();
        long friendWaiting = invites.friendStats().readyToAdd();
        List<Item> invite = new java.util.ArrayList<>(List.of(
                item("Danh sách mời", "/contacts", "/contacts", 0, key),
                item("Chiến dịch mời", "/campaigns", "/campaigns", 0, key),
                item("Chờ vào nhóm", "/friends", "/friends", friendWaiting, key),
                item("Lô nhắn hôm nay", "/today", "/today", 0, key),
                item("Chạy thử trên Zalo", "/test-runs", "/test-runs", 0, key),
                item("Nhóm Zalo", "/groups", "/groups", 0, key),
                // Luôn hiện (D18): đây là trung tâm điều khiển tự gửi; khi bộ nối tắt, trang chỉ báo "đang tắt".
                item("Zalo tài khoản phụ", "/zalo", "/zalo", zalo.attentionCount() + zaloHealth.openCriticalCount(), key)));
        return List.of(
                new Group("Khách hàng", List.of(
                        item("Tổng quan", "/", "/", 0, key),
                        item("Nhập đơn", "/imports", "/imports", 0, key),
                        item("Khách hàng", "/customers", "/customers", 0, key),
                        item("Trạng thái đơn", "/status-mappings", "/status-mappings", unmapped, key))),
                new Group("Mời vào nhóm", List.copyOf(invite)),
                new Group("Trợ lý AI", List.of(
                        item("Việc cần người thật xử lý", "/handoffs", "/handoffs", handoffOpen, key),
                        item("Trợ lý trong nhóm", "/bot", "/bot", 0, key))),
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
