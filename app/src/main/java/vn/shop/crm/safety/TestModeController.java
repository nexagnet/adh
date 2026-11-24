package vn.shop.crm.safety;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/**
 * Khai báo danh sách số thử (chế độ thử). Chỉ admin: cấu hình bảo mật chặn {@code /zalo/**} cho vai trò OWNER. Chế độ chạy thật cố ý
 * KHÔNG có nút ở đây: chỉ bật bằng biến môi trường trên máy chủ.
 */
@Controller
@RequestMapping("/zalo/test-numbers")
public class TestModeController {

    private final TestMode mode;
    private final TestDataSeeder seeder;

    public TestModeController(TestMode mode, TestDataSeeder seeder) {
        this.mode = mode;
        this.seeder = seeder;
    }

    private static String by(Principal p) {
        return p == null ? "system" : p.getName();
    }

    @PostMapping
    public String add(@RequestParam String phone, @RequestParam(required = false) String note, Principal user, RedirectAttributes redirect) {
        mode.add(phone, note, by(user));
        redirect.addFlashAttribute("flash", "Đã thêm số thử.");
        return "redirect:/zalo#che-do-thu";
    }

    @PostMapping("/remove")
    public String remove(@RequestParam String phone, Principal user, RedirectAttributes redirect) {
        mode.remove(phone, by(user));
        redirect.addFlashAttribute("flash", "Đã xóa số khỏi danh sách thử.");
        return "redirect:/zalo#che-do-thu";
    }

    /** Dựng khách và đơn giả từ các số thử (chỉ ở chế độ thử). */
    @PostMapping("/seed")
    public String seed(RedirectAttributes redirect) {
        redirect.addFlashAttribute("flash", seeder.seed().message());
        return "redirect:/zalo#che-do-thu";
    }
}
