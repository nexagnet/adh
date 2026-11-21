package vn.shop.crm.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.contact.ContactService;
import org.springframework.web.bind.annotation.RequestParam;
import vn.shop.crm.common.QrCodes;

import java.util.Map;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Trang quản trị để in giấy giới thiệu bỏ vào gói hàng: MỘT mã QR tĩnh + link ngắn, giống hệt nhau trên mọi tờ.
 * Phần "động" (chọn nhóm còn chỗ) nằm ở trang {@code /moi} phía sau mã.
 */
@Controller
public class PrintController {

    static final List<String> SIZES = List.of("A6", "A5", "A4X4");

    private final GroupService groups;
    private final PrintTexts texts;
    private final String baseUrl;
    private final String shopName;
    private final String shopPhone;

    public PrintController(GroupService groups, PrintTexts texts,
                           @Value("${app.public-base-url:http://localhost:8080}") String baseUrl,
                           @Value("${app.shop-name:Cửa hàng}") String shopName,
                           @Value("${app.shop-phone:}") String shopPhone) {
        this.groups = groups;
        this.texts = texts;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.shopName = shopName;
        this.shopPhone = shopPhone;
    }

    /** Địa chỉ in trên giấy và mã hóa trong QR. */
    String entryUrl() {
        return baseUrl + "/moi";
    }

    /** Bản gõ tay được: bỏ "https://" cho ngắn. */
    String shortLink() {
        return entryUrl().replaceFirst("^https?://", "");
    }

    @GetMapping("/print")
    public String page(@RequestParam(defaultValue = "A6") String kho, Model model) {
        String size = SIZES.contains(kho.toUpperCase()) ? kho.toUpperCase() : "A6";
        List<GroupService.Group> all = groups.list();
        model.addAttribute("kho", size);
        model.addAttribute("t", texts.rendered(shopName));
        model.addAttribute("fields", PrintTexts.FIELDS);
        model.addAttribute("raw", texts.current());
        model.addAttribute("sizes", SIZES);
        model.addAttribute("shortLink", shortLink());
        model.addAttribute("shopName", shopName);
        model.addAttribute("shopPhone", shopPhone);
        model.addAttribute("sheets", IntStream.rangeClosed(1, "A4X4".equals(size) ? 4 : 1).boxed().toList());
        // Cảnh báo trước khi in: địa chỉ chưa phải địa chỉ thật thì tờ in ra sẽ vô dụng.
        boolean notReal = !baseUrl.startsWith("https://") || baseUrl.contains("localhost") || baseUrl.contains("example.");
        model.addAttribute("addressNotReal", notReal);
        model.addAttribute("next", groups.pickGroup().orElse(null));
        model.addAttribute("spotsLeft", all.stream()
                .filter(g -> g.status() == GroupStatus.OPEN && g.hasLink()).mapToInt(GroupService.Group::spotsLeft).sum());
        return "print";
    }

    /** Lưu chữ trên giấy. Mỗi ô nhập tên là tên khóa ngắn (title, intro, scan, type, note, footer). */
    @PostMapping("/print/texts")
    public String saveTexts(@RequestParam Map<String, String> form, @RequestParam(defaultValue = "A6") String kho,
                            RedirectAttributes redirect) {
        texts.save(form, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã lưu chữ trên giấy. Xem lại bên dưới rồi in.");
        return "redirect:/print?kho=" + (SIZES.contains(kho.toUpperCase()) ? kho.toUpperCase() : "A6");
    }

    @PostMapping("/print/texts/reset")
    public String resetTexts(@RequestParam(defaultValue = "A6") String kho, RedirectAttributes redirect) {
        texts.reset(ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã dùng lại chữ mặc định.");
        return "redirect:/print?kho=" + (SIZES.contains(kho.toUpperCase()) ? kho.toUpperCase() : "A6");
    }

    @GetMapping("/print/qr.svg")
    public ResponseEntity<byte[]> svg() {
        return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("qr-vao-nhom.svg").build().toString())
                .body(QrCodes.svg(entryUrl()));
    }

    @GetMapping("/print/qr.png")
    public ResponseEntity<byte[]> png(@RequestParam(defaultValue = "false") boolean tai) {
        ContentDisposition cd = (tai ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename("qr-vao-nhom.png").build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString()).body(QrCodes.png(entryUrl(), 16));
    }
}
