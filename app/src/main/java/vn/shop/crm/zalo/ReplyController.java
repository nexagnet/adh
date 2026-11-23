package vn.shop.crm.zalo;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.inbox.ReplyContext;
import vn.shop.crm.inbox.ReplyService;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trang "Trợ lý AI trả lời khách" (D20): nấc (tắt / chỉ soạn nháp / tự động), bảng thông tin cửa hàng (nguồn duy nhất AI được dùng), thử một câu,
 * nháp và các câu đã tự gửi. Chỉ chủ (cấu hình bảo mật chặn {@code /zalo/**} cho vai trò OWNER). Việc chuyển người thật nằm ở trang "Việc cần xử lý".
 */
@Controller
@RequestMapping("/zalo/reply")
public class ReplyController {

    private final ReplyService replies;
    private final ZaloMessageService messages;
    private final ZaloInboxService inbox;

    public ReplyController(ReplyService replies, ZaloMessageService messages, ZaloInboxService inbox) {
        this.replies = replies;
        this.messages = messages;
        this.inbox = inbox;
    }

    private static String by(Principal p) {
        return p == null ? "admin" : p.getName();
    }

    private void fill(Model model) {
        model.addAttribute("modes", ReplyService.Mode.values());
        model.addAttribute("chosen", replies.mode());
        model.addAttribute("tried", replies.draftTried());
        model.addAttribute("infoRows", replies.info().rows());
        model.addAttribute("drafts", replies.drafts());
        model.addAttribute("sent", replies.recentSent(14));
        model.addAttribute("handoffCount", replies.openCount());
        model.addAttribute("inboxRunning", inbox.running());
        model.addAttribute("sendEnabled", messages.enabled());
        model.addAttribute("scopeMin", ReplyService.SCOPE_MIN);
        model.addAttribute("signature", replies.signature());
        model.addAttribute("ctxFields", ReplyContext.Field.values());
        model.addAttribute("ctxOn", replies.context().enabled());
        model.addAttribute("ctxSample", replies.context().sample());
    }

    @GetMapping
    public String page(Model model) {
        fill(model);
        return "reply";
    }

    @PostMapping("/mode")
    public String mode(@RequestParam ReplyService.Mode mode, @RequestParam(defaultValue = "false") boolean confirmedTested,
                       Principal user, RedirectAttributes redirect) {
        replies.setMode(mode, confirmedTested, by(user));
        redirect.addFlashAttribute("flash", "Đã chuyển trợ lý trả lời sang nấc: " + mode.label() + ".");
        return "redirect:/zalo/reply";
    }

    @PostMapping("/info")
    public String info(@RequestParam Map<String, String> form, Principal user, RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        for (var f : replies.info().fields()) {
            values.put(f.key(), form.get(f.key()));
        }
        replies.info().save(values, by(user));
        redirect.addFlashAttribute("flash", "Đã lưu bảng thông tin cửa hàng.");
        return "redirect:/zalo/reply";
    }

    @PostMapping("/context")
    public String context(@RequestParam(name = "field", required = false) java.util.List<String> fields, Principal user, RedirectAttributes redirect) {
        replies.context().saveEnabled(fields == null ? java.util.Set.of() : new java.util.HashSet<>(fields), by(user));
        redirect.addFlashAttribute("flash", "Đã lưu các trường hồ sơ khách được gửi cho AI.");
        return "redirect:/zalo/reply";
    }

    @PostMapping("/preview")
    public String preview(@RequestParam String text, Model model) {
        fill(model);
        model.addAttribute("sample", text);
        model.addAttribute("preview", replies.preview(text));
        return "reply";
    }
}
