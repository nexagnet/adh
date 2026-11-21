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
 * Phần "động" (chọn nhóm còn chỗ) nằm ở trang {@code /n} (bí danh {@code /moi}) phía sau mã.
 */
@Controller
public class PrintController {

    static final List<String> SIZES = List.of("A5", "A6", "A4X4");
    static final String DEFAULT_SIZE = "A5";

    private final GroupService groups;
    private final PrintTexts texts;
    private final String shopName;

    public PrintController(GroupService groups, PrintTexts texts,
                           @Value("${app.shop-name:Cửa hàng}") String shopName) {
        this.groups = groups;
        this.texts = texts;
        this.shopName = shopName;
    }

    /** Địa chỉ in trên giấy và mã hóa trong QR (đường chung /n, hoặc địa chỉ ngắn mẹ đã sửa). */
    String entryUrl() {
        return texts.entryUrl();
    }

    /** Bản gõ tay được: bỏ "https://" cho ngắn. */
    String shortLink() {
        return texts.shortLink();
    }

    @GetMapping("/print")
    public String page(@RequestParam(defaultValue = DEFAULT_SIZE) String kho, Model model) {
        String size = SIZES.contains(kho.toUpperCase()) ? kho.toUpperCase() : DEFAULT_SIZE;
        List<GroupService.Group> all = groups.list();
        model.addAttribute("kho", size);
        model.addAttribute("t", texts.rendered(shopName));
        model.addAttribute("fields", PrintTexts.FIELDS);
        model.addAttribute("raw", texts.current());
        model.addAttribute("eff", texts.effective());
        model.addAttribute("sizes", SIZES);
        model.addAttribute("shortLink", shortLink());
        model.addAttribute("sheets", IntStream.rangeClosed(1, "A4X4".equals(size) ? 4 : 1).boxed().toList());
        // Cảnh báo trước khi in: địa chỉ chưa phải địa chỉ thật thì tờ in ra sẽ vô dụng.
        String url = entryUrl();
        boolean notReal = !url.startsWith("https://") || url.contains("localhost") || url.contains("example.");
        model.addAttribute("addressNotReal", notReal);
        model.addAttribute("next", groups.pickGroup().orElse(null));
        model.addAttribute("spotsLeft", all.stream()
                .filter(g -> g.status() == GroupStatus.OPEN && g.hasLink()).mapToInt(GroupService.Group::spotsLeft).sum());
        return "print";
    }

    /** Lưu chữ trên giấy. Mỗi ô nhập tên là tên khóa ngắn (shop, title, intro, step1, step2, nocode, link, voluntary, phone, stop). */
    @PostMapping("/print/texts")
    public String saveTexts(@RequestParam Map<String, String> form, @RequestParam(defaultValue = DEFAULT_SIZE) String kho,
                            RedirectAttributes redirect) {
        texts.save(form, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã lưu chữ trên giấy. Xem lại bên dưới rồi in.");
        return "redirect:/print?kho=" + (SIZES.contains(kho.toUpperCase()) ? kho.toUpperCase() : DEFAULT_SIZE);
    }

    @PostMapping("/print/texts/reset")
    public String resetTexts(@RequestParam(defaultValue = DEFAULT_SIZE) String kho, RedirectAttributes redirect) {
        texts.reset(ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã dùng lại chữ mặc định.");
        return "redirect:/print?kho=" + (SIZES.contains(kho.toUpperCase()) ? kho.toUpperCase() : DEFAULT_SIZE);
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
