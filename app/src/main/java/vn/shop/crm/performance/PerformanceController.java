package vn.shop.crm.performance;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.CsvOut;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

/** Trang "Hiệu quả chiến dịch" và báo cáo xuất tổng hợp (không có thông tin khách). */
@Controller
public class PerformanceController {

    private final PerformanceService performance;

    public PerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    @GetMapping("/performance")
    public String page(Model model) {
        var overview = performance.overview();
        model.addAttribute("overview", overview);
        model.addAttribute("total", overview.total());
        model.addAttribute("settings", overview.settings());
        model.addAttribute("minCell", PerformanceService.MIN_CELL);
        model.addAttribute("cohortDays", PerformanceService.COHORT_DAYS);
        return "performance";
    }

    @PostMapping("/performance/settings")
    public String save(@RequestParam String discount, @RequestParam(defaultValue = "false") boolean freeShip,
                       @RequestParam(required = false) String fee, @RequestParam(required = false) String prefix,
                       Principal user, RedirectAttributes redirect) {
        performance.save(discount, freeShip, fee, prefix, user == null ? "admin" : user.getName());
        redirect.addFlashAttribute("flash", "Đã lưu các giá trị tính toán.");
        return "redirect:/performance";
    }

    /** Báo cáo xuất: chỉ số đếm và tiền tổng hợp; chiến dịch có ít hơn {@link PerformanceService#MIN_CELL} người vào nhóm bị ẩn số mua và tiền. */
    @GetMapping("/performance.csv")
    public ResponseEntity<byte[]> export() {
        var overview = performance.anonymized();
        List<List<String>> rows = new ArrayList<>();
        overview.campaigns().forEach(c -> rows.add(row(c)));
        rows.add(row(overview.total()));
        byte[] body = CsvOut.write(List.of("ma_chien_dich", "ten_chien_dich", "khu_vuc", "ngay_tao", "da_moi", "da_ket_ban",
                "da_vao_nhom", "nguoi_mua", "so_lan_mua", "so_lan_mua_da_doi_soat", "doanh_thu_da_doi_soat", "cuoc_van_chuyen",
                "chi_phi_uu_dai_uoc_tinh", "phi_tiktok_hoa_von_phan_tram", "loi_so_voi_tiktok", "mua_lai_khach_vao_nhom_phan_tram",
                "mua_lai_khach_khong_vao_nhom_phan_tram", "ghi_chu"), rows);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("hieu_qua_chien_dich.csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private static List<String> row(CampaignPerformance c) {
        boolean hidden = c.masked();
        Economics e = c.economics();
        return List.of(c.id() == 0 ? "tong" : String.valueOf(c.id()), c.name(), c.scopeLabel(),
                c.createdOn() == null ? "" : c.createdOn().toString(),
                String.valueOf(c.invited()), String.valueOf(c.friended()), String.valueOf(c.joined()),
                hidden ? "" : String.valueOf(c.buyers()), hidden ? "" : String.valueOf(c.orders()),
                hidden ? "" : String.valueOf(c.settledOrders()),
                hidden ? "" : money(c.revenue()), hidden ? "" : money(c.carrierCost()),
                e == null ? "" : money(e.discountCost()), e == null || e.breakEvenFeePercent() == null ? "" : e.breakEvenFeePercent().toPlainString(),
                e == null || e.netGain() == null ? "" : money(e.netGain()),
                hidden ? "" : percent(c.joinedCohort()), hidden ? "" : percent(c.notJoinedCohort()),
                hidden ? "ẩn số mua và tiền vì ít hơn " + PerformanceService.MIN_CELL + " người vào nhóm" : "");
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(CampaignPerformance.Cohort c) {
        // Dưới MIN_CELL người thì tỉ lệ có thể suy ngược ra từng khách: bỏ trống.
        return c.customers() < PerformanceService.MIN_CELL ? "" : String.format(java.util.Locale.ROOT, "%.1f", 100.0 * c.bought() / c.customers());
    }
}
