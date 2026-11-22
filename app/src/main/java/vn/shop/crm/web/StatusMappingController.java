package vn.shop.crm.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.shipping.StatusMappingService;

@Controller
@RequestMapping("/status-mappings")
public class StatusMappingController {

    private final StatusMappingService statuses;

    public StatusMappingController(StatusMappingService statuses) {
        this.statuses = statuses;
    }

    @GetMapping
    public String view(Model model) {
        model.addAttribute("unmapped", statuses.unmapped());
        var stuck = statuses.stuck();
        model.addAttribute("stuck", stuck);
        model.addAttribute("stuckTotal", stuck.stream().mapToLong(StatusMappingService.StuckRow::shipments).sum());
        model.addAttribute("staleDays", vn.shop.crm.shipping.StaleOrders.AFTER_DAYS);
        model.addAttribute("mappings", statuses.all());
        model.addAttribute("options", CanonicalStatus.values());
        return "status-mappings";
    }

    @PostMapping
    public String assign(@RequestParam Carrier carrier, @RequestParam String sample,
                         @RequestParam CanonicalStatus status) {
        statuses.assign(carrier, sample, status);
        return "redirect:/status-mappings";
    }
}
