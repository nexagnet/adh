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
import java.util.Map;
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
    public String list(@RequestParam Map<String, String> params, Model model) {
        var columns = CustomerColumn.forSegment(CustomerQuery.parse(params).segment());
        var shown = CustomerColumn.keys(columns);
        // Bỏ bộ lọc của cột không hiện trong bảng này để không lọc ngầm mà người dùng không thấy.
        CustomerQuery query = CustomerQuery.parse(params).restrictedTo(shown);
        var pageResult = customers.search(query, 50);
        model.addAttribute("page", pageResult);
        var ids = pageResult.items().stream().map(c -> c.id()).toList();
        model.addAttribute("states", contacts.inviteStates(ids));
        model.addAttribute("notes", contacts.notes(ids));
        // Số trên hai nút "Tất cả" và "Cần kiểm tra" theo đúng bộ lọc đang chọn.
        model.addAttribute("allCount", query.review() ? customers.search(query.withReview(false), 1).total() : pageResult.total());
        model.addAttribute("reviewCount", query.review() ? pageResult.total() : customers.search(query.withReview(true), 1).total());
        model.addAttribute("shown", pageResult.items().size());
        var byRegion = customers.countByRegion();
        model.addAttribute("regions", java.util.Arrays.stream(vn.shop.crm.geo.Region.values())
                .map(r -> new RegionCount(vn.shop.crm.geo.GeoArea.region(r).code(), r.label(), byRegion.get(r))).toList());
        model.addAttribute("unknownCount", customers.countUnknownRegion());
        model.addAttribute("areaGroups", areaGroups());
        model.addAttribute("segment", query.segment());
        model.addAttribute("review", query.review());
        model.addAttribute("q", query.q() == null ? "" : query.q());
        model.addAttribute("area", query.area() == null ? "" : query.area());
        model.addAttribute("segments", Segment.values());
        // Cột của bảng, giá trị đang lọc và các đường dẫn (sắp xếp, trang, xuất file) giữ nguyên trạng thái trên URL.
        var links = new CustomerListLinks(query, shown);
        model.addAttribute("columns", columns);
        model.addAttribute("values", query.params());
        model.addAttribute("links", links);
        model.addAttribute("ownParams", CustomerColumn.ownParams(columns));
        model.addAttribute("hiddenFields", links.hiddenExcept(CustomerColumn.ownParams(columns)));
        model.addAttribute("searchHidden", links.hiddenExcept(List.of("q")));
        model.addAttribute("moreHidden", links.hiddenExcept(List.of("segment", "area", "q", "review")));
        model.addAttribute("activeFilters", links.activeFilters());
        return "customers-list";
    }

    public record RegionCount(String code, String label, long count) {
    }

    public record AreaOption(String code, String label) {
    }

    public record AreaGroup(String label, List<AreaOption> options) {
    }

    /** Bộ lọc khu vực: miền, vùng, rồi từng tỉnh. */
    public static List<AreaGroup> areaGroups() {
        List<AreaGroup> groups = new java.util.ArrayList<>();
        groups.add(new AreaGroup("Miền", java.util.Arrays.stream(vn.shop.crm.geo.Region.values())
                .map(r -> new AreaOption(vn.shop.crm.geo.GeoArea.region(r).code(), r.label())).toList()));
        groups.add(new AreaGroup("Vùng", java.util.Arrays.stream(vn.shop.crm.geo.SubRegion.values())
                .map(s -> new AreaOption(vn.shop.crm.geo.GeoArea.sub(s).code(), s.label())).toList()));
        groups.add(new AreaGroup("Tỉnh/thành", vn.shop.crm.geo.VnGeo.all().stream()
                .map(p -> new AreaOption(vn.shop.crm.geo.GeoArea.province(p).code(), p.name())).toList()));
        return groups;
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
        model.addAttribute("inviteState", contacts.inviteStates(java.util.List.of(id)).get(id));
        model.addAttribute("note", contacts.note(id));
        model.addAttribute("today", java.time.LocalDate.now());
        return "customers-detail";
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> params) {
        var first = CustomerQuery.parse(params);
        CustomerQuery query = first.restrictedTo(CustomerColumn.keys(CustomerColumn.forSegment(first.segment())));
        Segment segment = query.segment();
        boolean review = query.review();
        List<List<String>> rows = customers.listAll(query).stream().map(c -> List.of(
                PhoneNormalizer.toDisplay(c.phone()), c.name() == null ? "" : c.name(),
                c.address() == null ? "" : c.address(), c.provinceLabel(), c.regionLabel(), String.valueOf(c.successfulPurchases()),
                c.segment().label(), c.lastOrderOn() == null ? "" : c.lastOrderOn().format(DATE),
                c.needsReview() ? c.reviewReasonLabels().stream().collect(Collectors.joining("; ")) : "")).toList();
        byte[] body = CsvOut.write(List.of("so_dien_thoai", "ten", "dia_chi",
                "tinh_thanh", "mien", "so_lan_mua_thanh_cong", "phan_loai", "lan_mua_gan_nhat", "can_kiem_tra"), rows);
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
        customers.refreshProvinces(true);
        return "redirect:/customers";
    }
}
