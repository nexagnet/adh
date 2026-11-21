package vn.shop.crm.customers;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import vn.shop.crm.common.CsvOut;
import vn.shop.crm.common.PhoneNormalizer;
import vn.shop.crm.contact.ContactService;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/customers")
public class CustomerController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CustomerService customers;
    private final ContactService contacts;

    public CustomerController(CustomerService customers, ContactService contacts) {
        this.customers = customers;
        this.contacts = contacts;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Segment segment,
                       @RequestParam(defaultValue = "false") boolean review,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        model.addAttribute("page", customers.search(segment, review, q, page, 50));
        model.addAttribute("segment", segment);
        model.addAttribute("review", review);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("segments", Segment.values());
        return "customers-list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        var detail = customers.detail(id);
        var basis = contacts.basisOf(id).orElse(null);
        boolean suppressed = contacts.isSuppressed(detail.customer().phone());
        model.addAttribute("detail", detail);
        model.addAttribute("basis", basis);
        model.addAttribute("suppressed", suppressed);
        model.addAttribute("canApprove", basis == null && !suppressed
                && detail.customer().segment() == Segment.ONE_TIME && !detail.customer().needsReview());
        model.addAttribute("events", contacts.events(id));
        model.addAttribute("note", contacts.note(id));
        model.addAttribute("today", java.time.LocalDate.now());
        return "customers-detail";
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Segment segment,
                                         @RequestParam(defaultValue = "false") boolean review) {
        List<List<String>> rows = customers.listAll(segment, review).stream().map(c -> List.of(
                PhoneNormalizer.toDisplay(c.phone()), c.name() == null ? "" : c.name(),
                c.address() == null ? "" : c.address(), String.valueOf(c.successfulPurchases()),
                c.segment().label(), c.lastOrderOn() == null ? "" : c.lastOrderOn().format(DATE),
                c.needsReview() ? c.reviewReasonLabels().stream().collect(Collectors.joining("; ")) : "")).toList();
        byte[] body = CsvOut.write(List.of("so_dien_thoai", "ten", "dia_chi",
                "so_lan_mua_thanh_cong", "phan_loai", "lan_mua_gan_nhat", "can_kiem_tra"), rows);
        String name = "khach_" + (segment == null ? "tat_ca" : segment.name()) + (review ? "_can_kiem_tra" : "") + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @PostMapping("/recalculate")
    public String recalculate() {
        customers.recalculateAll();
        return "redirect:/customers";
    }
}
