package vn.shop.crm.invite;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * "Chờ vào nhóm" (D15, R6, R7, R10): mọi khách đã kết bạn mà chưa vào nhóm, qua mọi chiến dịch, và khách đã nhắn lâu chưa
 * kết bạn. Chỉ hiển thị và nhắc trên web; hệ thống không nhắn thêm cho khách nào.
 */
@Controller
public class FriendsController {

    private final InviteService invites;

    public FriendsController(InviteService invites) {
        this.invites = invites;
    }

    @GetMapping("/friends")
    public String friends(Model model) {
        model.addAttribute("friended", invites.friendedQueue());
        model.addAttribute("noReply", invites.noReplyQueue());
        model.addAttribute("stats", invites.friendStats());
        model.addAttribute("overdueDays", invites.overdueDays());
        model.addAttribute("noReplyDays", invites.noReplyDays());
        model.addAttribute("reserveHours", invites.reserveHours());
        return "friends";
    }
}
