package vn.shop.crm.invite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.time.Duration;

/**
 * Trang công khai của mã QR/link chung ({@code /moi}). Chữ to, một nút, dùng được trên điện thoại. Dùng POST cho nút
 * "Vào nhóm" để trình xem trước link không vô tình tính thành lượt vào.
 */
@Controller
public class EntryController {

    static final String COOKIE = "adh_moi";

    private final EntryService entries;
    private final PrintTexts texts;
    private final EntryThrottle throttle;
    private final ContactService contacts;
    private final String shopName;
    private final String shopPhone;

    public EntryController(EntryService entries, PrintTexts texts, EntryThrottle throttle, ContactService contacts,
                           @Value("${app.shop-name:Cửa hàng}") String shopName,
                           @Value("${app.shop-phone:}") String shopPhone) {
        this.entries = entries;
        this.texts = texts;
        this.throttle = throttle;
        this.contacts = contacts;
        this.shopName = shopName;
        this.shopPhone = shopPhone;
    }

    /** Danh sách tỉnh/thành xếp theo miền cho câu hỏi "Bạn ở đâu?". */
    private static java.util.List<RegionOption> regionOptions() {
        java.util.List<RegionOption> out = new java.util.ArrayList<>();
        for (vn.shop.crm.geo.Region r : vn.shop.crm.geo.Region.values()) {
            out.add(new RegionOption(r.label(), vn.shop.crm.geo.VnGeo.in(r)));
        }
        return out;
    }

    public record RegionOption(String label, java.util.List<vn.shop.crm.geo.Province> provinces) {
    }

    private void common(Model model) {
        model.addAttribute("shopName", shopName);
        model.addAttribute("shopPhone", shopPhone);
        model.addAttribute("t", texts.rendered(shopName));
    }

    @GetMapping("/moi")
    public String page(Model model) {
        common(model);
        model.addAttribute("regions", regionOptions());
        return "entry";
    }

    /** Khách xin ngừng nhận tin bằng số điện thoại. Chặn gõ lung tung hàng loạt bằng cùng bộ giới hạn theo địa chỉ mạng. */
    @PostMapping("/moi/ngung")
    public String stop(@RequestParam String phone, HttpServletRequest request, HttpServletResponse response, Model model) {
        common(model);
        if (!throttle.tryAcquire("ngung|" + request.getRemoteAddr())) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            model.addAttribute("tooMany", true);
            return "landing-unavailable";
        }
        try {
            contacts.optOutByPhone(phone, "Khách tự xin ngừng nhận tin trên trang giấy giới thiệu");
        } catch (ImportException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("stopError", e.getMessage());
            return "entry";
        }
        model.addAttribute("stopped", true);
        return "landing-stopped";
    }

    @PostMapping("/moi/vao")
    public Object join(@CookieValue(name = COOKIE, required = false) String visitor,
                       @RequestParam(required = false) String province, HttpServletRequest request,
                       HttpServletResponse response, Model model) {
        EntryService.Result result = entries.enter(visitor, request.getRemoteAddr(), province);
        common(model);
        switch (result.outcome()) {
            case REDIRECT:
                ResponseCookie cookie = ResponseCookie.from(COOKIE, result.visitor())
                        .httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/moi")
                        .maxAge(Duration.ofDays(90)).build();
                return ResponseEntity.status(HttpStatus.SEE_OTHER)
                        .header(HttpHeaders.LOCATION, URI.create(result.url()).toString())
                        .header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
            case TOO_MANY:
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                model.addAttribute("tooMany", true);
                return "landing-unavailable";
            default:
                model.addAttribute("noGroup", true);
                if (result.stored()) {
                    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, result.visitor())
                            .httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/moi")
                            .maxAge(Duration.ofDays(90)).build().toString());
                }
                return "landing-unavailable";
        }
    }
}
