package vn.shop.crm.invite;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Màn hình quản lý nhóm Zalo: link, mục tiêu khoảng 5 khách, số khách thật nhập tay (mốc 1C). */
@Controller
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping("/groups")
    public String list(Model model) {
        model.addAttribute("groups", groups.list());
        model.addAttribute("statuses", GroupStatus.values());
        model.addAttribute("storeAccounts", GroupService.STORE_ACCOUNTS);
        model.addAttribute("next", groups.pickGroup().orElse(null));
        model.addAttribute("spotsLeft", groups.list().stream()
                .filter(g -> g.status() == GroupStatus.OPEN && g.hasLink()).mapToInt(GroupService.Group::spotsLeft).sum());
        return "groups";
    }

    @PostMapping("/groups")
    public String create(@RequestParam String name, @RequestParam(required = false) String link,
                         @RequestParam(required = false) Integer target, RedirectAttributes redirect) {
        groups.create(name, link, target);
        redirect.addFlashAttribute("flash", "Đã thêm nhóm. Nhóm mới ở trạng thái 'Đang chuẩn bị', "
                + "hãy nhập link rồi mở nhóm khi sẵn sàng nhận khách.");
        return "redirect:/groups";
    }

    @PostMapping("/groups/{id}/update")
    public String update(@PathVariable long id, @RequestParam String name, @RequestParam(required = false) String link,
                         @RequestParam(required = false) Integer target, @RequestParam(required = false) String note,
                         RedirectAttributes redirect) {
        groups.update(id, name, link, target, note);
        redirect.addFlashAttribute("flash", "Đã lưu nhóm.");
        return "redirect:/groups";
    }

    @PostMapping("/groups/{id}/count")
    public String recount(@PathVariable long id, @RequestParam Integer actual, RedirectAttributes redirect) {
        groups.recount(id, actual);
        redirect.addFlashAttribute("flash", "Đã cập nhật số khách thật trong nhóm.");
        return "redirect:/groups";
    }

    @PostMapping("/groups/{id}/status")
    public String status(@PathVariable long id, @RequestParam GroupStatus status, RedirectAttributes redirect) {
        groups.setStatus(id, status);
        redirect.addFlashAttribute("flash", "Đã chuyển nhóm sang: " + status.label() + ".");
        return "redirect:/groups";
    }
}
