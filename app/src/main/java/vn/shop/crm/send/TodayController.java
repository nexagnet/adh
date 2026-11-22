package vn.shop.crm.send;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.invite.InviteService;

/**
 * "Lô nhắn hôm nay": hệ thống chỉ gợi ý ai nên được nhắn ngay lúc này (theo hạn mức ngày, hạn mức giờ, giờ nhắn);
 * người thật nhắn tay bằng tài khoản Zalo phụ rồi bấm "Đã nhắn". Hệ thống không gửi và không đăng nhập Zalo.
 */
@Controller
public class TodayController {

    private final SendService send;
    private final InviteService invites;

    public TodayController(SendService send, InviteService invites) {
        this.send = send;
        this.invites = invites;
    }

    @GetMapping("/today")
    public String today(@RequestParam(required = false) Long campaign, Model model) {
        SendService.Today today = send.today(campaign);
        model.addAttribute("today", today);
        model.addAttribute("rows", invites.invitationsByIds(today.invitationIds()));
        model.addAttribute("campaignId", campaign);
        model.addAttribute("campaign", campaign == null ? null : invites.campaign(campaign).orElse(null));
        return "today";
    }

    @PostMapping("/today/{invitationId}/messaged")
    public String messaged(@PathVariable long invitationId, @RequestParam(required = false) Long campaign,
                           RedirectAttributes redirect) {
        send.markMessaged(invitationId);
        redirect.addFlashAttribute("flash", "Đã ghi: đã nhắn khách này. Khi khách đồng ý kết bạn, mở chiến dịch để bấm "
                + "\"Đã kết bạn\" và \"Đã vào nhóm\".");
        return campaign == null ? "redirect:/today" : "redirect:/today?campaign=" + campaign;
    }
}
