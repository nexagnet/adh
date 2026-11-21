package vn.shop.crm.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import vn.shop.crm.bot.HandoffService;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.contact.PrivacyService;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.customers.Segment;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.CanonicalStatus;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.shipping.StatusMappingService;

import java.util.List;

@Controller
public class DashboardController {

    public record ShipmentCount(String carrier, String status, long count) {
    }

    private final JdbcClient jdbc;
    private final CustomerService customers;
    private final ImportService imports;
    private final StatusMappingService statuses;
    private final ContactService contacts;
    private final PrivacyService privacy;
    private final InviteService invites;
    private final HandoffService handoffs;
    private final vn.shop.crm.map.WeatherService weather;
    private final vn.shop.crm.invite.GroupService groups;

    public DashboardController(JdbcClient jdbc, CustomerService customers, ImportService imports,
                               StatusMappingService statuses, ContactService contacts, PrivacyService privacy,
                               InviteService invites, HandoffService handoffs,
                               vn.shop.crm.map.WeatherService weather, vn.shop.crm.invite.GroupService groups) {
        this.groups = groups;
        this.weather = weather;
        this.invites = invites;
        this.handoffs = handoffs;
        this.contacts = contacts;
        this.privacy = privacy;
        this.jdbc = jdbc;
        this.customers = customers;
        this.imports = imports;
        this.statuses = statuses;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String home(Model model) {
        var bySegment = customers.countBySegment();
        List<ShipmentCount> shipments = jdbc.sql("""
                        SELECT carrier, canonical_status, count(*) AS n FROM shipment
                        GROUP BY carrier, canonical_status ORDER BY carrier, canonical_status""")
                .query((rs, i) -> new ShipmentCount(Carrier.valueOf(rs.getString("carrier")).label(),
                        CanonicalStatus.valueOf(rs.getString("canonical_status")).label(), rs.getLong("n")))
                .list();
        long totalShipments = shipments.stream().mapToLong(ShipmentCount::count).sum();
        model.addAttribute("oneTime", bySegment.get(Segment.ONE_TIME));
        model.addAttribute("repeat", bySegment.get(Segment.REPEAT));
        model.addAttribute("none", bySegment.get(Segment.NO_SUCCESSFUL_PURCHASE));
        model.addAttribute("review", customers.countNeedsReview());
        model.addAttribute("contact", contacts.stats());
        model.addAttribute("invites", invites.stats());
        model.addAttribute("handoffOpen", handoffs.openCount());
        model.addAttribute("privacyOpen", privacy.openCount());
        model.addAttribute("privacyOverdue", privacy.overdueCount());
        model.addAttribute("unmapped", statuses.unmapped().size());
        model.addAttribute("shipments", shipments);
        model.addAttribute("totalShipments", totalShipments);
        var openGroups = groups.list().stream()
                .filter(g -> g.status() == vn.shop.crm.invite.GroupStatus.OPEN && g.hasLink()).toList();
        model.addAttribute("freeSpots", openGroups.stream().mapToInt(vn.shop.crm.invite.GroupService.Group::spotsLeft).sum());
        model.addAttribute("groupsNeedRecount", openGroups.stream()
                .filter(vn.shop.crm.invite.GroupService.Group::needsRecount).count());
        model.addAttribute("weatherAlerts", weather.alerts());
        model.addAttribute("batches", imports.list().stream().limit(5).toList());
        return "dashboard";
    }
}
