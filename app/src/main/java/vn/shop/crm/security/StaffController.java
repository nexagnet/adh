package vn.shop.crm.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "APP_ADH_STAGE2_ENABLED", havingValue = "true")
class StaffController {
    private final StaffMembers members;
    private final StaffWork work;
    private final JdbcClient jdbc;

    StaffController(StaffMembers members, StaffWork work, JdbcClient jdbc) {
        this.members = members;
        this.work = work;
        this.jdbc = jdbc;
    }

    @GetMapping("/staff/members")
    String members(Model model) {
        model.addAttribute("members", members.list());
        model.addAttribute("groups", jdbc.sql("SELECT id, name FROM zalo_group ORDER BY name").query().listOfRows());
        model.addAttribute("grants", jdbc.sql("""
                SELECT sg.username, g.name FROM staff_group sg JOIN zalo_group g ON g.id = sg.group_id
                ORDER BY sg.username, g.name""").query().listOfRows());
        model.addAttribute("workSummary", jdbc.sql("""
                SELECT s.username, count(h.id) FILTER (WHERE h.status = 'ACKNOWLEDGED') AS open,
                    count(h.id) FILTER (WHERE h.status = 'RESOLVED') AS resolved,
                    round(avg(extract(epoch FROM (h.resolved_at - h.created_at)) / 60)) AS minutes
                FROM staff_member s LEFT JOIN handoff h ON h.assigned_to = s.username
                GROUP BY s.username ORDER BY s.username""").query().listOfRows());
        return "staff-members";
    }

    @PostMapping("/staff/members")
    String add(@RequestParam String username, @RequestParam String name, @RequestParam String password) {
        members.add(username, name, password);
        return "redirect:/staff/members";
    }

    @PostMapping("/staff/members/{username}/active")
    String active(@PathVariable String username, @RequestParam boolean active) {
        members.setActive(username, active);
        return "redirect:/staff/members";
    }

    @PostMapping("/staff/members/{username}/groups")
    String grant(@PathVariable String username, @RequestParam long groupId, @RequestParam boolean allowed) {
        members.grant(username, groupId, allowed);
        return "redirect:/staff/members";
    }

    @PostMapping("/staff/assign/{id}")
    String assign(@PathVariable long id, @RequestParam String assignee, Principal owner) {
        work.assign(id, owner.getName(), assignee);
        return "redirect:/handoffs?type=group";
    }

    @GetMapping("/staff/work")
    String work(Model model, org.springframework.security.core.Authentication user) {
        if (user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"))) {
            return "redirect:/handoffs?type=group";
        }
        model.addAttribute("jobs", work.current(user.getName()));
        return "staff-work";
    }

    @PostMapping("/staff/work/{id}/claim")
    String claim(@PathVariable long id, Principal employee) {
        work.claim(id, employee.getName());
        return "redirect:/staff/work";
    }

    @PostMapping("/staff/work/{id}/resolve")
    String resolve(@PathVariable long id, Principal employee) {
        work.resolve(id, employee.getName());
        return "redirect:/staff/work";
    }
}
