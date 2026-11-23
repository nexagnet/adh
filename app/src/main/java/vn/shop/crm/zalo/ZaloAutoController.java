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

    public ZaloAutoController(ZaloAutoService service, ZaloMessageService messages, vn.shop.crm.send.SendService send) {
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

    @GetMapping
    public String page(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
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
            model.addAttribute("waiting", service.waiting());
            model.addAttribute("manual", service.manualQueue());
            model.addAttribute("candidates", service.candidates());
            model.addAttribute("log", cfg.recentLog(40));
        }
        return "zalo";
    }

    @PostMapping("/mode")
    public String mode(@RequestParam ZaloMode mode, @RequestParam(defaultValue = "false") boolean confirmedTested,
                       Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.settings().setMode(mode, confirmedTested, by(user));
        redirect.addFlashAttribute("flash", "Đã chuyển sang nấc: " + mode.label() + ".");
        return "redirect:/zalo";
    }

    @PostMapping("/limits")
    public String limits(@RequestParam int daily, @RequestParam int hourly, @RequestParam int gap, Principal user,
                         RedirectAttributes redirect) {
        requireEnabled();
        service.settings().setLimits(daily, hourly, gap, by(user));
        redirect.addFlashAttribute("flash", "Đã lưu hạn mức.");
        return "redirect:/zalo";
    }

    /** Công tắc tự gửi tin cho khách (D18). Mặc định TẮT; bật/tắt ghi vào nhật ký. */
    @PostMapping("/messages/switch")
    public String messageSwitch(@RequestParam boolean on, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        messages.setEnabled(on, by(user));
        redirect.addFlashAttribute("flash", on
                ? "Đã BẬT tự gửi tin. Chỉ chiến dịch đã được duyệt mới được gửi, theo hạn mức và khoảng nghỉ dưới đây."
                : "Đã TẮT tự gửi tin. Không có tin nào được gửi nữa.");
        return "redirect:/zalo";
    }

    @PostMapping("/messages/limits")
    public String messageLimits(@RequestParam int daily, @RequestParam int gap, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        messages.setLimits(daily, gap, by(user));
        redirect.addFlashAttribute("flash", "Đã lưu hạn mức tin.");
        return "redirect:/zalo";
    }

    @PostMapping("/stop")
    public String stop(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        boolean ok = service.stopNow(by(user));
        redirect.addFlashAttribute("flash", ok
                ? "Đã dừng bộ nối Zalo. Không có gì được thêm cho tới khi bấm Mở lại."
                : "Adh đã chặn mọi việc thêm người, nhưng chưa báo được cho dịch vụ cầu nối (không gọi được). Hãy kiểm tra dịch vụ zalo-bridge.");
        return "redirect:/zalo";
    }

    @PostMapping("/reopen")
    public String reopen(@RequestParam(defaultValue = "false") boolean checked, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        boolean ok = service.reopen(by(user), checked);
        redirect.addFlashAttribute("flash", ok ? "Đã mở lại, bộ nối đang nghe bằng phiên đã lưu."
                : "Chưa mở lại được: cầu nối chưa nghe được (có thể cần quét lại mã QR hoặc không gọi được cầu nối). Vẫn đang dừng.");
        return "redirect:/zalo";
    }

    @PostMapping("/logout")
    public String logout(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        boolean ok = service.logout(by(user));
        redirect.addFlashAttribute("flash", ok
                ? "Đã đăng xuất tài khoản phụ khỏi bộ nối. Muốn dùng lại hãy quét mã QR."
                : "Adh đã dừng bộ nối nhưng chưa xóa được phiên ở dịch vụ cầu nối (không gọi được). Hãy kiểm tra dịch vụ zalo-bridge.");
        return "redirect:/zalo";
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
        return "redirect:/zalo";
    }

    @PostMapping("/invitations/{id}/retry")
    public String retry(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.retry(id, by(user));
        redirect.addFlashAttribute("flash", "Đã đưa về chờ duyệt, hãy duyệt lại nếu muốn thử một lần nữa.");
        return "redirect:/zalo";
    }

    @PostMapping("/invitations/{id}/release")
    public String release(@PathVariable long id, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.release(id, by(user));
        redirect.addFlashAttribute("flash", "Đã bỏ khỏi hàng chờ của bộ nối. Mẹ thêm khách này tay trên Zalo.");
        return "redirect:/zalo";
    }

    @PostMapping("/manual/{eventId}/resolve")
    public String resolve(@PathVariable long eventId, @RequestParam long invitationId, Principal user,
                          RedirectAttributes redirect) {
        requireEnabled();
        service.resolve(eventId, invitationId, by(user));
        redirect.addFlashAttribute("flash", "Đã ghép. Khách này đi tiếp theo nấc hiện tại.");
        return "redirect:/zalo";
    }

    @PostMapping("/manual/{eventId}/dismiss")
    public String dismiss(@PathVariable long eventId, Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.dismiss(eventId, by(user));
        redirect.addFlashAttribute("flash", "Đã bỏ qua dòng này.");
        return "redirect:/zalo";
    }
}
