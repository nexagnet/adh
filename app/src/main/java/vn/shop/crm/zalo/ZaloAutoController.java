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

    public ZaloAutoController(ZaloAutoService service) {
        this.service = service;
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
        if (enabled) {
            ZaloGateway.Status st = service.bridgeStatus();
            model.addAttribute("bridge", st);
            model.addAttribute("quota", cfg.quota());
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

    @PostMapping("/stop")
    public String stop(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.stopNow(by(user));
        redirect.addFlashAttribute("flash", "Đã dừng bộ nối Zalo. Không có gì được thêm cho tới khi bấm Mở lại.");
        return "redirect:/zalo";
    }

    @PostMapping("/reopen")
    public String reopen(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        boolean ok = service.reopen(by(user));
        redirect.addFlashAttribute("flash", ok ? "Đã mở lại, bộ nối đang nghe bằng phiên đã lưu."
                : "Đã bỏ trạng thái dừng nhưng chưa nghe được: có thể cần quét lại mã QR.");
        return "redirect:/zalo";
    }

    @PostMapping("/logout")
    public String logout(Principal user, RedirectAttributes redirect) {
        requireEnabled();
        service.logout(by(user));
        redirect.addFlashAttribute("flash", "Đã đăng xuất tài khoản phụ khỏi bộ nối. Muốn dùng lại hãy quét mã QR.");
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
        model.addAttribute("waiting", l.state() == ZaloSession.LoginState.WAITING_SCAN
                || l.state() == ZaloSession.LoginState.SCANNED);
        model.addAttribute("qr", l.qrPng() == null ? null
                : "data:image/png;base64," + Base64.getEncoder().encodeToString(l.qrPng()));
        return "zalo-login";
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
