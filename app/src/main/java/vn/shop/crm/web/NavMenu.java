package vn.shop.crm.web;

import org.springframework.stereotype.Component;
import vn.shop.crm.bot.HandoffService;
import vn.shop.crm.contact.PrivacyService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.StatusMappingService;
import vn.shop.crm.zalo.ZaloAccountContext;
import vn.shop.crm.zalo.ZaloAccountRegistry;
import vn.shop.crm.zalo.ZaloAutoService;

import java.util.List;
import java.util.stream.Stream;

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

    /** {@code collapsible}: nhóm hiếm dùng, gập trong menu (tự mở khi mục đang xem nằm trong đó); {@code badge} là tổng việc của cả nhóm. */
    public record Group(String title, List<Item> items, boolean collapsible) {
        public Group(String title, List<Item> items) {
            this(title, items, false);
        }

        public boolean hasActive() {
            return items.stream().anyMatch(Item::active);
        }

        public long badge() {
            return items.stream().mapToLong(Item::badge).sum();
        }
    }

    private final HandoffService handoffs;
    private final PrivacyService privacy;
    private final StatusMappingService statuses;
    private final ZaloAutoService zalo;
    private final InviteService invites;
    private final vn.shop.crm.inbox.RefusalReviewService refusals;
    private final vn.shop.crm.inbox.ReplyService replies;
    private final vn.shop.crm.inbox.WarrantyService warranty;
    private final vn.shop.crm.zalo.ZaloHealthService zaloHealth;
    private final vn.shop.crm.advisor.ProposalService proposals;
    private final ZaloAccountRegistry zaloAccounts;

    public NavMenu(HandoffService handoffs, PrivacyService privacy, StatusMappingService statuses, ZaloAutoService zalo,
                   InviteService invites, vn.shop.crm.inbox.RefusalReviewService refusals, vn.shop.crm.inbox.ReplyService replies,
                   vn.shop.crm.inbox.WarrantyService warranty, vn.shop.crm.zalo.ZaloHealthService zaloHealth,
                   vn.shop.crm.advisor.ProposalService proposals, ZaloAccountRegistry zaloAccounts) {
        this.proposals = proposals;
        this.warranty = warranty;
        this.zaloHealth = zaloHealth;
        this.refusals = refusals;
        this.replies = replies;
        this.invites = invites;
        this.handoffs = handoffs;
        this.privacy = privacy;
        this.statuses = statuses;
        this.zalo = zalo;
        this.zaloAccounts = zaloAccounts;
    }

    /** Khóa mục đang mở: đường dẫn; trang một khách (/customers/{id}) tô sáng mục Khách hàng. */
    public static String keyOf(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.matches("/customers/\\d+")) {
            return "/customers";
        }
        if (path.startsWith("/advisor/")) {
            return "/advisor";
        }
        return path;
    }

    /** Số đề xuất AI đang chờ, hiện trên tab "Đề xuất của AI" của trang chiến dịch. */
    public long proposalBadge() {
        return proposals.open().size();
    }

    /** Số khách đã kết bạn đang chờ thêm vào nhóm, hiện trên tab "Chờ vào nhóm" của trang nhóm. */
    public long friendBadge() {
        return invites.friendStats().readyToAdd();
    }

    /** Trang con nằm dưới một mục menu cha (tab của trang cha) thì tô sáng mục cha. */
    static String highlightKey(String key) {
        if (key.equals("/advisor") || key.equals("/today")) {
            return "/campaigns";
        }
        if (key.equals("/friends") || key.equals("/zalo/groups")) {
            return "/groups";
        }
        return key;
    }

    public List<Group> groups(String currentKey) {
        String key = highlightKey(currentKey == null ? "/" : currentKey);
        long handoffOpen = handoffs.unresolvedCount() + refusals.heldCount() + replies.openCount() + warranty.openCount();
        long privacyOpen = privacy.openCount();
        long unmapped = statuses.unmapped().size();
        // Ba nhóm theo mức dùng (kế hoạch giao diện 24/11/2026): việc hằng ngày, chiến dịch, cài đặt; nhóm "Khác" gập.
        // "Đề xuất của AI", "Chờ vào nhóm" và "Lô nhắn hôm nay" là tab của trang chiến dịch hoặc trang nhóm (địa chỉ cũ vẫn dùng được).
        return List.of(
                new Group("Hằng ngày", List.of(
                        item("Tổng quan", "/", "/", 0, key),
                        item("Việc cần người thật xử lý", "/handoffs", "/handoffs", handoffOpen, key),
                        item("Khách hàng", "/customers", "/customers", 0, key),
                        item("Nhập đơn", "/imports", "/imports", 0, key))),
                new Group("Chiến dịch", List.of(
                        item("Danh sách mời", "/contacts", "/contacts", 0, key),
                        item("Chiến dịch mời", "/campaigns", "/campaigns", proposals.open().size(), key),
                        item("Nhóm Zalo", "/groups", "/groups", invites.friendStats().readyToAdd(), key),
                        item("Hiệu quả chiến dịch", "/performance", "/performance", 0, key))),
                new Group("Cài đặt", settingsItems(key)),
                new Group("Khác", List.of(
                        item("Yêu cầu của khách", "/privacy", "/privacy", privacyOpen, key),
                        item("Trạng thái đơn", "/status-mappings", "/status-mappings", unmapped, key),
                        item("In giấy giới thiệu", "/print", "/print", 0, key),
                        item("Bản đồ khách", "/map", "/map", 0, key),
                        item("Trợ lý trong nhóm", "/bot", "/bot", 0, key)), true));
    }

    private List<Item> settingsItems(String key) {
        Stream<Item> bridges = zaloAccounts.multipleConfigured()
                ? zaloAccounts.views().stream().map(account -> {
                    long id = account.id();
                    return new Item(account.label(), "/zalo?account=" + id, "/zalo", zaloBadge(id),
                            id == ZaloAccountContext.current() && matches("/zalo", key));
                })
                : Stream.of(item("Zalo tài khoản phụ", "/zalo", "/zalo",
                        zaloBadge(ZaloAccountContext.DEFAULT_ACCOUNT), key));
        return Stream.concat(bridges, Stream.of(item("Trợ lý AI trả lời khách", "/zalo/reply", "/zalo/reply", 0, key)))
                .toList();
    }

    private long zaloBadge(long accountId) {
        return ZaloAccountContext.call(accountId, () -> zalo.attentionCount() + zaloHealth.openCriticalCount());
    }

    private static Item item(String label, String url, String itemKey, long badge, String current) {
        return new Item(label, url, itemKey, badge, matches(itemKey, current));
    }

    private static boolean matches(String itemKey, String current) {
        // /zalo/reply có mục riêng trên menu nên không tô sáng thêm mục /zalo.
        boolean underItem = !"/".equals(itemKey) && current.startsWith(itemKey + "/")
                && !("/zalo".equals(itemKey) && current.startsWith("/zalo/reply"));
        return itemKey.equals(current) || underItem;
    }
}
