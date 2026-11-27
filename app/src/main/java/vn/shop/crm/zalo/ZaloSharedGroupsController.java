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
import vn.shop.crm.invite.GroupService;

import java.security.Principal;

/** Quản trị quyền thêm khách cho các tài khoản phụ ở cùng một nhóm Adh. */
@Controller
@RequestMapping("/zalo/shared-groups/{groupId}")
public class ZaloSharedGroupsController {

    private final GroupService groups;
    private final ZaloSharedGroupGrants grants;
    private final ZaloAccountRegistry accounts;

    public ZaloSharedGroupsController(GroupService groups, ZaloSharedGroupGrants grants, ZaloAccountRegistry accounts) {
        this.groups = groups;
        this.grants = grants;
        this.accounts = accounts;
    }

    @GetMapping
    public String page(@PathVariable long groupId, Model model, HttpServletResponse response) {
        var group = groups.find(groupId).orElseThrow(() -> new ImportException("Không tìm thấy nhóm."));
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("group", group);
        model.addAttribute("sharing", grants.view(groupId));
        model.addAttribute("systemEnabled", grants.enabled());
        model.addAttribute("otherAccounts", accounts.views().stream().filter(a -> a.id() != group.accountId()).toList());
        model.addAttribute("possibleDuplicates", groups.list().stream().filter(g -> g.id() != groupId)
                .filter(g -> g.name().equalsIgnoreCase(group.name())
                        || (group.inviteLink() != null && group.inviteLink().equals(g.inviteLink())))
                .toList());
        return "zalo-shared-groups";
    }

    @PostMapping("/grants")
    public String approve(@PathVariable long groupId, @RequestParam long grantee,
                          @RequestParam String bridgeGroupId, Principal user, RedirectAttributes redirect) {
        grants.approve(groupId, grantee, bridgeGroupId, user.getName());
        redirect.addFlashAttribute("flash", "Đã kiểm tra đúng nhóm trên hai phiên Zalo và lưu quyền.");
        return "redirect:/zalo/shared-groups/" + groupId;
    }

    @PostMapping("/grants/{grantee}/revoke")
    public String revoke(@PathVariable long groupId, @PathVariable long grantee,
                         @RequestParam long revision, Principal user, RedirectAttributes redirect) {
        grants.revoke(groupId, grantee, revision, user.getName());
        redirect.addFlashAttribute("flash", "Đã thu hồi quyền cấp lượt thêm mới của tài khoản.");
        return "redirect:/zalo/shared-groups/" + groupId;
    }

    @PostMapping("/enabled")
    public String enabled(@PathVariable long groupId, @RequestParam boolean sharing, RedirectAttributes redirect) {
        grants.setGroupEnabled(groupId, sharing);
        redirect.addFlashAttribute("flash", sharing ? "Đã bật dùng chung cho nhóm." : "Đã dừng cấp lượt thêm dùng chung mới.");
        return "redirect:/zalo/shared-groups/" + groupId;
    }
}
