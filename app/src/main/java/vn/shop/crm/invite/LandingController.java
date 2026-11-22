package vn.shop.crm.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URI;

/**
 * Trang công khai cho khách bấm link (mục 6 file 01, tiêu chí 7): chữ to, một nút, không OTP, không hỏi SĐT.
 * Chỉ đọc token ngẫu nhiên, không lộ SĐT/tên khách. Trang không báo vì sao link không dùng được (hết hạn, đã hủy,
 * không tồn tại đều giống nhau).
 */
@Controller
public class LandingController {

    private final InviteService invites;
    private final String shopPhone;

    public LandingController(InviteService invites, @Value("${app.shop-phone:}") String shopPhone) {
        this.invites = invites;
        this.shopPhone = shopPhone;
    }

    @GetMapping("/i/{token}")
    public String page(@PathVariable String token, Model model, HttpServletResponse response) {
        model.addAttribute("shopName", invites.shopName());
        model.addAttribute("shopPhone", shopPhone);
        if (!invites.usable(token)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "landing-unavailable";
        }
        model.addAttribute("token", token);
        return "landing";
    }

    /** Nút "Vào nhóm Zalo". Dùng POST để trình xem trước link của Zalo/SMS không vô tình tính thành lượt bấm. */
    @PostMapping("/i/{token}/go")
    public Object go(@PathVariable String token, Model model, HttpServletResponse response) {
        InviteService.GoResult result = invites.go(token);
        model.addAttribute("shopName", invites.shopName());
        model.addAttribute("shopPhone", shopPhone);
        switch (result.outcome()) {
            case REDIRECT:
                return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION,
                        URI.create(result.url()).toString()).build();
            case NO_GROUP:
                model.addAttribute("noGroup", true);
                return "landing-unavailable";
            default:
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return "landing-unavailable";
        }
    }

    @PostMapping("/i/{token}/stop")
    public String stop(@PathVariable String token, Model model) {
        boolean done = invites.optOut(token);
        model.addAttribute("shopName", invites.shopName());
        model.addAttribute("shopPhone", shopPhone);
        model.addAttribute("stopped", done);
        return "landing-stopped";
    }
}
