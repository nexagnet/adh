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
        model.addAttribute("scopes", scopeOptions());
        model.addAttribute("waiting", groups.waitingByProvince());
        return "groups";
    }

    public record ScopeOption(String code, String label) {
    }

    /** Khu vực chọn được cho nhóm: từng miền, từng vùng, từng tỉnh. */
    static java.util.List<java.util.List<ScopeOption>> scopeOptions() {
        java.util.List<ScopeOption> regions = new java.util.ArrayList<>();
        java.util.List<ScopeOption> subs = new java.util.ArrayList<>();
        java.util.List<ScopeOption> provinces = new java.util.ArrayList<>();
        for (vn.shop.crm.geo.Region r : vn.shop.crm.geo.Region.values()) {
            regions.add(new ScopeOption(vn.shop.crm.geo.GeoArea.region(r).code(), r.label()));
        }
        for (vn.shop.crm.geo.SubRegion s : vn.shop.crm.geo.SubRegion.values()) {
            subs.add(new ScopeOption(vn.shop.crm.geo.GeoArea.sub(s).code(), "Vùng " + s.label()));
        }
        for (vn.shop.crm.geo.Province p : vn.shop.crm.geo.VnGeo.all()) {
            provinces.add(new ScopeOption(vn.shop.crm.geo.GeoArea.province(p).code(), p.name()));
        }
        return java.util.List.of(regions, subs, provinces);
    }

    @PostMapping("/groups")
    public String create(@RequestParam String name, @RequestParam(required = false) String link,
                         @RequestParam(required = false) Integer target,
                         @RequestParam(required = false) String scope, RedirectAttributes redirect) {
        groups.create(name, link, target, scope);
        redirect.addFlashAttribute("flash", "Đã thêm nhóm. Nhóm mới ở trạng thái 'Đang chuẩn bị', "
                + "hãy nhập link rồi mở nhóm khi sẵn sàng nhận khách.");
        return "redirect:/groups";
    }

    @PostMapping("/groups/{id}/update")
    public String update(@PathVariable long id, @RequestParam String name, @RequestParam(required = false) String link,
                         @RequestParam(required = false) Integer target, @RequestParam(required = false) String note,
                         @RequestParam(required = false) String scope, RedirectAttributes redirect) {
        groups.update(id, name, link, target, note, scope);
        redirect.addFlashAttribute("flash", "Đã lưu nhóm.");
        return "redirect:/groups";
    }

    /** Mã nhóm trong Zalo (chuỗi số) cho bộ nối D16. Để trống để gỡ: bộ nối không thêm ai vào nhóm đó nữa. */
    @PostMapping("/groups/{id}/zalo-id")
    public String zaloId(@PathVariable long id, @RequestParam(required = false) String zaloGroupId, RedirectAttributes redirect) {
        groups.setZaloGroupId(id, zaloGroupId);
        redirect.addFlashAttribute("flash", zaloGroupId == null || zaloGroupId.isBlank()
                ? "Đã gỡ mã nhóm Zalo: bộ nối sẽ không thêm ai vào nhóm này." : "Đã lưu mã nhóm Zalo.");
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
