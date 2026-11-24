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
import vn.shop.crm.inbox.WarrantyService;

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
    private final WarrantyService warranty;
    private final vn.shop.crm.inbox.ComebackService comebacks;

    public ReplyController(ReplyService replies, ZaloMessageService messages, ZaloInboxService inbox, WarrantyService warranty,
                           vn.shop.crm.inbox.ComebackService comebacks) {
        this.comebacks = comebacks;
        this.replies = replies;
        this.messages = messages;
        this.inbox = inbox;
        this.warranty = warranty;
    }

    private static String by(Principal p) {
        return p == null ? "admin" : p.getName();
    }

    public record Tab(String key, String label) {
    }

    static final java.util.List<Tab> TABS = java.util.List.of(new Tab("hom-nay", "Hôm nay"), new Tab("bat-tat", "Bật tắt"),
            new Tab("thong-tin", "Thông tin cửa hàng"), new Tab("nang-cao", "Nâng cao"));

    private static String tabOf(String tab) {
        return TABS.stream().anyMatch(t -> t.key().equals(tab)) ? tab : "hom-nay";
    }

    private void fill(Model model, String tab) {
        model.addAttribute("tabs", TABS);
        model.addAttribute("tab", tabOf(tab));
        model.addAttribute("inboxThreshold", inbox.threshold());
        model.addAttribute("comebackModes", vn.shop.crm.inbox.ComebackService.Mode.values());
        model.addAttribute("comebackChosen", comebacks.mode());
        model.addAttribute("comebackTried", comebacks.tasksTried());
        model.addAttribute("comebackThreshold", inbox.comebackThreshold());
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
        model.addAttribute("reactOn", replies.reactEnabled());
        model.addAttribute("warrantyOn", warranty.enabled());
        model.addAttribute("warrantyOpen", warranty.openCount());
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "hom-nay") String tab, Model model) {
        fill(model, tab);
        return "reply";
    }

    @PostMapping("/mode")
    public String mode(@RequestParam ReplyService.Mode mode, @RequestParam(defaultValue = "false") boolean confirmedTested,
                       Principal user, RedirectAttributes redirect) {
        replies.setMode(mode, confirmedTested, by(user));
        redirect.addFlashAttribute("flash", "Đã chuyển trợ lý trả lời sang nấc: " + mode.label() + ".");
        return "redirect:/zalo/reply?tab=bat-tat";
    }

    /** Công tắc phân loại bảo hành và khiếu nại (tắt mặc định). Phần này không bao giờ gửi gì cho khách. */
    @PostMapping("/warranty")
    public String warranty(@RequestParam(defaultValue = "false") boolean enabled, Principal user, RedirectAttributes redirect) {
        warranty.setEnabled(enabled, by(user));
        redirect.addFlashAttribute("flash", enabled ? "Đã bật phân loại bảo hành và khiếu nại." : "Đã tắt phân loại bảo hành và khiếu nại.");
        return "redirect:/zalo/reply?tab=bat-tat#bao-hanh";
    }

    /** Công tắc thả tim tin khách mà AI xác định không cần đáp (mặc định bật; chỉ có tác dụng ở nấc Tự động). */
    @PostMapping("/react")
    public String react(@RequestParam(defaultValue = "false") boolean enabled, Principal user, RedirectAttributes redirect) {
        replies.setReactEnabled(enabled, by(user));
        redirect.addFlashAttribute("flash", enabled ? "Đã bật thả tim tin khách không cần đáp." : "Đã tắt thả tim; tin cảm ơn sẽ được trả lời ngắn như trước.");
        return "redirect:/zalo/reply?tab=bat-tat#tha-tim";
    }

    @PostMapping("/info")
    public String info(@RequestParam Map<String, String> form, Principal user, RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        for (var f : replies.info().fields()) {
            values.put(f.key(), form.get(f.key()));
        }
        replies.info().save(values, by(user));
        redirect.addFlashAttribute("flash", "Đã lưu bảng thông tin cửa hàng.");
        return "redirect:/zalo/reply?tab=thong-tin";
    }

    @PostMapping("/context")
    public String context(@RequestParam(name = "field", required = false) java.util.List<String> fields, Principal user, RedirectAttributes redirect) {
        replies.context().saveEnabled(fields == null ? java.util.Set.of() : new java.util.HashSet<>(fields), by(user));
        redirect.addFlashAttribute("flash", "Đã lưu các trường hồ sơ khách được gửi cho AI.");
        return "redirect:/zalo/reply?tab=nang-cao";
    }

    @PostMapping("/preview")
    public String preview(@RequestParam String text, Model model) {
        fill(model, "thong-tin");
        model.addAttribute("sample", text);
        model.addAttribute("preview", replies.preview(text));
        return "reply";
    }
}
