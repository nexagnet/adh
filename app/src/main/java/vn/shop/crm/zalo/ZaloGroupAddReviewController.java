package vn.shop.crm.zalo;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
public class ZaloGroupAddReviewController {
    private final ZaloGroupAddAttempts attempts;
    private final ZaloAutoService auto;

    private final ZaloRestoreFence restoreFence;

    public ZaloGroupAddReviewController(ZaloGroupAddAttempts attempts, ZaloAutoService auto, ZaloRestoreFence restoreFence) {
        this.restoreFence = restoreFence;
        this.attempts = attempts;
        this.auto = auto;
    }

    @GetMapping("/zalo/group-add-review")
    public String review(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("reviews", attempts.reviews());
        model.addAttribute("restoreFence", restoreFence.locked());
        return "zalo-group-add-review";
    }

    @PostMapping("/zalo/group-add-review/{id}")
    public String resolve(@PathVariable long id, @RequestParam boolean joined, @RequestParam String note,
                          Principal principal, RedirectAttributes flash) {
        boolean settled = attempts.resolve(id, joined, note, principal.getName());
        flash.addFlashAttribute("flash", settled ? "Đã lưu xác nhận." : "Kết quả đã lưu. Hai nhóm đang khác nhau, cần tiếp tục đối chiếu.");
        return "redirect:/zalo/group-add-review";
    }

    @PostMapping("/zalo/group-add-review/restore")
    public String restore(@RequestParam long invitation, @RequestParam long group, @RequestParam long account,
                          @RequestParam String note, Principal principal, RedirectAttributes flash) {
        attempts.recordRestoredMembership(invitation, group, account, note, principal.getName());
        flash.addFlashAttribute("flash", "Đã ghi lại tư cách thành viên đã xác minh.");
        return "redirect:/zalo/group-add-review";
    }

    @GetMapping("/zalo/group-add-preview")
    public String preview(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("rows", auto.preview());
        return "zalo-group-add-preview";
    }
}
