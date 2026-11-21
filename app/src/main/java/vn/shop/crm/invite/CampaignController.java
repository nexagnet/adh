package vn.shop.crm.invite;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

/** Chiến dịch mời: tạo lô nhỏ, xem lời mời và lượt bấm, tạm dừng tất cả. Mốc 1C chưa gửi tin cho ai. */
@Controller
public class CampaignController {

    private final InviteService invites;
    private final ContactService contacts;
    private final AppSettings settings;

    public CampaignController(InviteService invites, ContactService contacts, AppSettings settings) {
        this.invites = invites;
        this.contacts = contacts;
        this.settings = settings;
    }

    @GetMapping("/campaigns")
    public String list(Model model) {
        model.addAttribute("campaigns", invites.campaigns());
        model.addAttribute("stats", invites.stats());
        model.addAttribute("contact", contacts.stats());
        model.addAttribute("paused", settings.paused());
        model.addAttribute("pausedReason", settings.pausedReason());
        model.addAttribute("maxBatch", invites.maxBatch());
        model.addAttribute("defaultTemplate", InviteService.DEFAULT_TEMPLATE);
        return "campaigns";
    }

    @PostMapping("/campaigns")
    public String create(@RequestParam String name, @RequestParam(required = false) String template,
                         @RequestParam(defaultValue = "14") int expiresDays, @RequestParam int count,
                         RedirectAttributes redirect) {
        long id = invites.createCampaign(name, template, expiresDays, count);
        redirect.addFlashAttribute("flash", "Đã tạo chiến dịch ở chế độ thử. Chưa có tin nào được gửi.");
        return "redirect:/campaigns/" + id;
    }

    @GetMapping("/campaigns/{id}")
    public String detail(@PathVariable long id, Model model) {
        var campaign = invites.campaign(id).orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
        model.addAttribute("campaign", campaign);
        model.addAttribute("rows", invites.invitations(id, campaign.template()));
        model.addAttribute("paused", settings.paused());
        return "campaign-detail";
    }

    @PostMapping("/campaigns/{id}/cancel-unsent")
    public String cancelUnsent(@PathVariable long id, RedirectAttributes redirect) {
        int n = invites.cancelUnsent(id);
        redirect.addFlashAttribute("flash", "Đã hủy " + n + " lời mời chưa gửi. Các khách này quay về danh sách "
                + "chưa được mời.");
        return "redirect:/campaigns/" + id;
    }

    @PostMapping("/campaigns/pause-all")
    public String pauseAll(@RequestParam(required = false) String reason, RedirectAttributes redirect) {
        settings.pauseAll(reason == null || reason.isBlank() ? "Tạm dừng thủ công" : reason.strip(),
                ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã tạm dừng TẤT CẢ chiến dịch.");
        return "redirect:/campaigns";
    }

    @PostMapping("/campaigns/resume-all")
    public String resumeAll(RedirectAttributes redirect) {
        settings.resumeAll(ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã bỏ tạm dừng.");
        return "redirect:/campaigns";
    }
}
