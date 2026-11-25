package vn.shop.crm.zalo;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.ImportException;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Trang quản trị "Zalo tài khoản phụ" (D16): nấc, hạn mức, dừng/mở lại, quét QR, hàng chờ duyệt, hàng đợi tay, nhật ký.
 * Chỉ admin (cấu hình bảo mật chặn {@code /zalo/**} cho vai trò OWNER). Ảnh QR chỉ nằm trong phản hồi của
 * {@code /zalo/login}, không lưu đĩa, không ghi log, không cache. Mật khẩu, mã OTP Zalo không đi qua trang này.
 */
@Controller
@RequestMapping("/zalo")
public class ZaloAutoController {

    private final ZaloAutoService service;
    private final ZaloMessageService messages;
    private final vn.shop.crm.send.SendService send;
    private final ZaloGroupCreationService groupMaker;
    private final ZaloDeputyService deputy;

    private final ZaloInboxService inbox;
    private final vn.shop.crm.inbox.ComebackService comebacks;

    private final ZaloHealthService health;

    private final ZaloQueueView queueView;

    public ZaloAutoController(ZaloAutoService service, ZaloMessageService messages, vn.shop.crm.send.SendService send,
                              ZaloGroupCreationService groupMaker, ZaloDeputyService deputy, ZaloInboxService inbox,
                              vn.shop.crm.inbox.ComebackService comebacks, ZaloHealthService health, ZaloQueueView queueView) {
        this.queueView = queueView;
        this.health = health;
        this.inbox = inbox;
        this.comebacks = comebacks;
        this.groupMaker = groupMaker;
        this.deputy = deputy;
        this.service = service;
        this.messages = messages;
        this.send = send;
    }

    private void requireEnabled() {
        if (!service.settings().props().enabled()) {
            throw new ImportException("Bộ nối Zalo chưa được bật ở cấu hình triển khai (APP_ZALO_AUTO_ENABLED), nên chưa dùng được.");
        }
    }

    private static String by(Principal p) {
        return p == null ? "system" : p.getName();
    }

    /** Các tab của trang Zalo (chọn bằng {@code ?tab=}, không script); tab lạ thì về "Tình trạng". */
    public record Tab(String key, String label) {
    }

    static final java.util.List<Tab> TABS = java.util.List.of(
            new Tab("tinh-trang", "Tình trạng"), new Tab("gioi-han", "Giới hạn"), new Tab("nhom", "Nhóm"),
            new Tab("thu", "Thử và nhật ký"));

    @GetMapping
    public String page(@RequestParam(defaultValue = "tinh-trang") String tab, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("tabs", TABS);
        model.addAttribute("tab", TABS.stream().anyMatch(t -> t.key().equals(tab)) ? tab : "tinh-trang");
        ZaloAutoSettings cfg = service.settings();
        boolean enabled = cfg.props().enabled();
        model.addAttribute("enabled", enabled);
        model.addAttribute("modes", ZaloMode.values());
        model.addAttribute("chosen", cfg.chosenMode());
        model.addAttribute("effective", cfg.effectiveMode());
        model.addAttribute("tripped", cfg.tripped());
        model.addAttribute("trippedReason", cfg.trippedReason());
        model.addAttribute("trippedAt", cfg.trippedAt() == null ? null
                : LocalDateTime.ofInstant(cfg.trippedAt(), cfg.clock().getZone()));
        model.addAttribute("props", cfg.props());
        // Giới hạn gửi tay, tạm dừng, nhật ký chiến dịch (trước là trang /sending): hiện cả khi bộ nối tắt.
        model.addAttribute("send", send.status());
        if (enabled) {
            ZaloGateway.Status st = service.bridgeStatus();
            model.addAttribute("bridge", st);
            model.addAttribute("quota", cfg.quota());
            model.addAttribute("msg", messages.quota());
            model.addAttribute("queue", queueView.preview());
            model.addAttribute("friendDaily", messages.friendDaily());
            model.addAttribute("friendUsed", messages.friendUsedToday());
            model.addAttribute("friendMax", cfg.props().friendMaxDaily());
            model.addAttribute("waiting", service.waiting());
            model.addAttribute("manual", service.manualQueue());
            model.addAttribute("candidates", service.candidates());
            model.addAttribute("log", cfg.recentLog(40));
            model.addAttribute("healthOpen", health.open());
            model.addAttribute("healthRecent", health.recent(15));
            ZaloGroupSettings gs = groupMaker.settings();
            model.addAttribute("groupModes", ZaloGroupMode.values());
            model.addAttribute("groupChosen", gs.chosenMode());
            model.addAttribute("groupMin", gs.minCustomers());
            model.addAttribute("groupTarget", gs.targetCustomers());
            model.addAttribute("longWaiting", service.longWaitingCount(gs.alertDays()));
            model.addAttribute("longWaitingDays", gs.alertDays());
            model.addAttribute("groupSoftCap", groupMaker.softCap());
            model.addAttribute("groupMergeDays", gs.mergeUpDays());
            model.addAttribute("groupRelaxDays", gs.relaxDays());
            model.addAttribute("groupAlertDays", gs.alertDays());
            model.addAttribute("groupProps", gs.props());
            model.addAttribute("welcomeOn", gs.welcomeEnabled());
            model.addAttribute("welcomeTemplate", gs.welcomeTemplate());
            model.addAttribute("welcomeLink", gs.shopLink());
            model.addAttribute("welcomeMax", ZaloGroupSettings.MAX_WELCOME);
            model.addAttribute("deputyCurrent", deputy.current());
            model.addAttribute("deputyCandidate", deputy.candidate().orElse(null));
            model.addAttribute("inboxRunning", inbox.running());
            model.addAttribute("groupMayNow", groupMaker.mayCreateNow());
            model.addAttribute("proposals", groupMaker.recent(10));
        }
        return "zalo";
    }

    @PostMapping("/mode")
    public String mode(@RequestParam ZaloMode mode, @RequestParam(defaultValue = "false") boolean confirmedTested,
                       Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.settings().setMode(mode, confirmedTested, by(user));
        redirect.addFlashAttribute("flash", "Đã chuyển sang nấc: " + mode.label() + ".");
        return "redirect:/zalo?tab=nhom";
    }

    // ---------- khách từng từ chối nhắn lại muốn mua (D19) ----------

    @PostMapping("/comeback-mode")
    public String comebackMode(@RequestParam vn.shop.crm.inbox.ComebackService.Mode mode,
                               @RequestParam(defaultValue = "false") boolean confirmedTested, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        comebacks.setMode(mode, confirmedTested, by(user));
        redirect.addFlashAttribute("flash", "Đã chuyển việc khách quay lại sang nấc: " + mode.label() + ".");
        return "redirect:/zalo/reply?tab=bat-tat#tin-rieng";
    }

    // ---------- tự tạo nhóm theo khu vực (D19) ----------

    @PostMapping("/group-mode")
    public String groupMode(@RequestParam ZaloGroupMode mode, @RequestParam(defaultValue = "false") boolean confirmedTested,
                            Principal user, RedirectAttributes redirect) {
        requireEnabled();
        groupMaker.settings().setMode(mode, confirmedTested, by(user));
        redirect.addFlashAttribute("flash", "Đã chuyển việc tự tạo nhóm sang nấc: " + mode.label() + ".");
        return "redirect:/zalo?tab=nhom#tao-nhom";
    }

    @PostMapping("/group-limits")
    public String groupLimits(@RequestParam int minCustomers, @RequestParam(required = false) Integer targetCustomers,
                              @RequestParam(required = false) Integer softCap, @RequestParam(required = false) Integer mergeUpDays,
                              @RequestParam(required = false) Integer relaxDays, @RequestParam(required = false) Integer alertDays,
                              Principal user, RedirectAttributes redirect) {
        requireEnabled();
        ZaloGroupSettings gs = groupMaker.settings();
        int target = targetCustomers == null ? gs.targetCustomers() : targetCustomers;
        int cap = softCap == null ? groupMaker.softCap() : softCap;
        if (cap < target) {
            throw new vn.shop.crm.common.ImportException("Trần mềm không được thấp hơn mục tiêu số khách mỗi nhóm.");
        }
        gs.setLimits(minCustomers, target, by(user));
        groupMaker.setSoftCap(cap, by(user));
        gs.setDays(mergeUpDays == null ? gs.mergeUpDays() : mergeUpDays, relaxDays == null ? gs.relaxDays() : relaxDays,
                alertDays == null ? gs.alertDays() : alertDays, by(user));
        redirect.addFlashAttribute("flash", "Đã lưu ngưỡng và sức chứa nhóm.");
        return "redirect:/zalo?tab=nhom#tao-nhom";
    }

    @PostMapping("/group-welcome")
    public String groupWelcome(@RequestParam(defaultValue = "false") boolean enabled, @RequestParam(defaultValue = "") String template,
                               @RequestParam(defaultValue = "") String shopLink, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        groupMaker.settings().setWelcome(enabled, template, shopLink, by(user));
        redirect.addFlashAttribute("flash", "Đã lưu tin chào đầu nhóm.");
        return "redirect:/zalo?tab=nhom#tao-nhom";
    }

    // ---------- mẹ làm phó nhóm: nhập số điện thoại, xem tên và ảnh, xác nhận ----------

    @PostMapping("/deputy/lookup")
    public String deputyLookup(@RequestParam String phone, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        ZaloDeputyService.LookupOutcome r = deputy.lookup(phone, by(user));
        redirect.addFlashAttribute("deputyMsg", r.message());
        redirect.addFlashAttribute("deputyStatus", r.status().name());
        return "redirect:/zalo?tab=nhom#pho-nhom";
    }

    @PostMapping("/deputy/confirm")
    public String deputyConfirm(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        deputy.confirm(by(user));
        redirect.addFlashAttribute("deputyMsg", "Đã lưu mẹ làm phó nhóm cho các nhóm tạo sau này. Nhớ kết bạn tài khoản phụ với mẹ trên Zalo nếu chưa.");
        redirect.addFlashAttribute("deputyStatus", "FOUND");
        return "redirect:/zalo?tab=nhom#pho-nhom";
    }

    @PostMapping("/deputy/cancel")
    public String deputyCancel(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        deputy.cancelCandidate(by(user));
        redirect.addFlashAttribute("deputyMsg", "Đã bỏ kết quả vừa tra. Nhập lại số điện thoại nếu cần.");
        return "redirect:/zalo?tab=nhom#pho-nhom";
    }

    @PostMapping("/deputy/clear")
    public String deputyClear(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        deputy.clear(by(user));
        redirect.addFlashAttribute("deputyMsg", "Đã bỏ phó nhóm đã lưu. Nhóm tạo sau đó sẽ không có phó nhóm (trừ khi còn khai báo ZALO_DEPUTY_ID ở cấu hình triển khai).");
        return "redirect:/zalo?tab=nhom#pho-nhom";
    }

    @PostMapping("/group-proposals/{id}/approve")
    public String approveProposal(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        groupMaker.approve(id, by(user));
        redirect.addFlashAttribute("flash", "Đã duyệt. Nhóm được tạo ở nhịp kế tiếp nếu đang trong giờ và còn trần mỗi ngày.");
        return "redirect:/zalo?tab=nhom#tao-nhom";
    }

    @PostMapping("/group-proposals/{id}/reject")
    public String rejectProposal(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        groupMaker.reject(id, by(user));
        redirect.addFlashAttribute("flash", "Đã từ chối đề xuất. Khu vực này không được đề xuất lại trong 24 giờ.");
        return "redirect:/zalo?tab=nhom#tao-nhom";
    }

    @PostMapping("/group-proposals/{id}/checked")
    public String checkedProposal(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        groupMaker.acknowledge(id, by(user));
        redirect.addFlashAttribute("flash", "Đã ghi nhận bạn kiểm tra trên Zalo. Bộ nối vẫn dừng cho tới khi bấm Mở lại.");
        return "redirect:/zalo?tab=nhom#tao-nhom";
    }

    @PostMapping("/group-proposals/{id}/deputy-retry")
    public String retryDeputy(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        ZaloGroupMaker.DeputyResult r = groupMaker.retryDeputy(id, by(user));
        redirect.addFlashAttribute("flash", r.ok()
                ? "Đã đặt mẹ làm phó nhóm."
                : r.outcome() == ZaloGroupMaker.GroupOutcome.UNREACHABLE || r.outcome() == ZaloGroupMaker.GroupOutcome.RATE_LIMITED
                        || "WINDOW".equals(r.code()) || "NOT_ACTIVE".equals(r.code()) || "BUSY".equals(r.code())
                ? "Chưa gọi được Zalo lúc này (mã: " + (r.code() == null ? "không rõ" : r.code()) + "). Lần thử này không bị tính, hệ thống không dừng. Thử lại sau."
                : "Chưa đặt được mẹ làm phó nhóm (mã lỗi: " + (r.code() == null ? "không rõ" : r.code()) + "). Mã này đã ghi vào nhật ký. Có thể thử lại hoặc thêm tay trong Zalo.");
        return "redirect:/zalo?tab=nhom#tao-nhom";
    }

    @PostMapping("/limits")
    public String limits(@RequestParam int daily, @RequestParam int hourly, @RequestParam int gap, Principal user,
                         RedirectAttributes redirect) {
        requireEnabled();
        service.settings().setLimits(daily, hourly, gap, by(user));
        redirect.addFlashAttribute("flash", "Đã lưu hạn mức.");
        return "redirect:/zalo?tab=gioi-han";
    }

    /** Công tắc tự gửi tin cho khách (D18). Mặc định TẮT; bật/tắt ghi vào nhật ký. */
    @PostMapping("/messages/switch")
    public String messageSwitch(@RequestParam boolean on, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        messages.setEnabled(on, by(user));
        redirect.addFlashAttribute("flash", on
                ? "Đã BẬT tự gửi tin. Chỉ chiến dịch đã được duyệt mới được gửi, theo hạn mức và khoảng nghỉ dưới đây."
                : "Đã TẮT tự gửi tin. Không có tin nào được gửi nữa.");
        return "redirect:/zalo?tab=tinh-trang";
    }

    @PostMapping("/messages/limits")
    public String messageLimits(@RequestParam int daily, @RequestParam int gap, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        messages.setLimits(daily, gap, by(user));
        redirect.addFlashAttribute("flash", "Đã lưu hạn mức tin.");
        return "redirect:/zalo?tab=gioi-han";
    }

    @PostMapping("/messages/friend-limit")
    public String friendLimit(@RequestParam int daily, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        messages.setFriendDaily(daily, by(user));
        redirect.addFlashAttribute("flash", daily == 0 ? "Đã TẮT lời mời kết bạn tự động." : "Đã lưu hạn mức lời mời kết bạn.");
        return "redirect:/zalo?tab=gioi-han";
    }

    @PostMapping("/stop")
    public String stop(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        boolean ok = service.stopNow(by(user));
        redirect.addFlashAttribute("flash", ok
                ? "Đã dừng bộ nối Zalo. Không có gì được thêm cho tới khi bấm Mở lại."
                : "Adh đã chặn mọi việc thêm người, nhưng chưa báo được cho dịch vụ cầu nối (không gọi được). Hãy kiểm tra dịch vụ zalo-bridge.");
        return "redirect:/zalo?tab=tinh-trang";
    }

    @PostMapping("/reopen")
    public String reopen(@RequestParam(defaultValue = "false") boolean checked, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        boolean ok = service.reopen(by(user), checked);
        redirect.addFlashAttribute("flash", ok ? "Đã mở lại, bộ nối đang nghe bằng phiên đã lưu."
                : "Chưa mở lại được: cầu nối chưa nghe được (có thể cần quét lại mã QR hoặc không gọi được cầu nối). Vẫn đang dừng.");
        return "redirect:/zalo?tab=tinh-trang";
    }

    @PostMapping("/logout")
    public String logout(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        boolean ok = service.logout(by(user));
        redirect.addFlashAttribute("flash", ok
                ? "Đã đăng xuất tài khoản phụ khỏi bộ nối. Muốn dùng lại hãy quét mã QR."
                : "Adh đã dừng bộ nối nhưng chưa xóa được phiên ở dịch vụ cầu nối (không gọi được). Hãy kiểm tra dịch vụ zalo-bridge.");
        return "redirect:/zalo?tab=tinh-trang";
    }

    // ---------- quét QR ----------

    @PostMapping("/login/start")
    public String loginStart(Principal user) {
        requireEnabled();
        service.startLogin(by(user));
        return "redirect:/zalo/login";
    }

    @GetMapping("/login")
    public String login(Principal user, Model model, HttpServletResponse response, RedirectAttributes redirect) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        requireEnabled();
        ZaloSession.Login l = service.loginStatus(by(user));
        if (l.state() == ZaloSession.LoginState.DONE) {
            redirect.addFlashAttribute("flash", "Đăng nhập tài khoản phụ xong.");
            return "redirect:/zalo";
        }
        model.addAttribute("state", l.state().name());
        model.addAttribute("scannedId", l.scannedId());
        model.addAttribute("waiting", l.state() == ZaloSession.LoginState.WAITING_SCAN
                || l.state() == ZaloSession.LoginState.SCANNED);
        model.addAttribute("qr", l.qrPng() == null ? null
                : "data:image/png;base64," + Base64.getEncoder().encodeToString(l.qrPng()));
        return "zalo-login";
    }

    // ---------- nhóm Zalo của tài khoản phụ: chọn mã nhóm ----------

    @GetMapping("/groups")
    public String zaloGroups(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        requireEnabled();
        ZaloSession.GroupList list = service.zaloGroups();
        var adh = service.adhGroups();
        java.util.Map<String, String> assigned = new java.util.HashMap<>();
        for (var g : adh) {
            if (g.hasZaloId()) {
                assigned.put(g.zaloGroupId(), g.name());
            }
        }
        model.addAttribute("ok", list.ok());
        model.addAttribute("failCode", list.code());
        model.addAttribute("zaloGroups", list.groups());
        model.addAttribute("adhGroups", adh);
        model.addAttribute("assigned", assigned);
        return "zalo-groups";
    }

    @PostMapping("/groups/assign")
    public String assign(@RequestParam long adhGroupId, @RequestParam String zaloGroupId, RedirectAttributes redirect) {
        requireEnabled();
        service.assignZaloGroup(adhGroupId, zaloGroupId);
        redirect.addFlashAttribute("flash", "Đã gán mã nhóm Zalo cho nhóm của Adh. Bộ nối chỉ thêm khách vào nhóm có mã.");
        return "redirect:/zalo/groups";
    }

    // ---------- duyệt và hàng đợi tay ----------

    @PostMapping("/invitations/{id}/approve")
    public String approve(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.approve(id, by(user));
        redirect.addFlashAttribute("flash", "Đã duyệt. Bộ nối sẽ thêm khách vào nhóm khi tới lượt trong hạn mức.");
        return "redirect:/zalo?tab=nhom";
    }

    @PostMapping("/invitations/{id}/retry")
    public String retry(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.retry(id, by(user));
        redirect.addFlashAttribute("flash", "Đã đưa về chờ duyệt, hãy duyệt lại nếu muốn thử một lần nữa.");
        return "redirect:/zalo?tab=nhom";
    }

    @PostMapping("/invitations/{id}/release")
    public String release(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.release(id, by(user));
        redirect.addFlashAttribute("flash", "Đã bỏ khỏi hàng chờ của bộ nối. Mẹ thêm khách này tay trên Zalo.");
        return "redirect:/zalo?tab=nhom";
    }

    @PostMapping("/manual/{eventId}/resolve")
    public String resolve(@PathVariable long eventId, @RequestParam long invitationId, Principal user,
                          RedirectAttributes redirect) {
        requireEnabled();
        service.resolve(eventId, invitationId, by(user));
        redirect.addFlashAttribute("flash", "Đã ghép. Khách này đi tiếp theo nấc hiện tại.");
        return "redirect:/zalo?tab=nhom";
    }

    @PostMapping("/manual/{eventId}/dismiss")
    public String dismiss(@PathVariable long eventId, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.dismiss(eventId, by(user));
        redirect.addFlashAttribute("flash", "Đã bỏ qua dòng này.");
        return "redirect:/zalo?tab=nhom";
    }
}
