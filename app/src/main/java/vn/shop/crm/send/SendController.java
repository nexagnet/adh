package vn.shop.crm.send;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.invite.InviteService;

import java.nio.charset.StandardCharsets;

/** Chạy thử và xuất danh sách cho mẹ gửi tay (mốc 1D). */
@Controller
public class SendController {

    private final SendService send;
    private final InviteService invites;
    private final MockSender mock;
    private final ManualExportSender manual;

    public SendController(SendService send, InviteService invites, MockSender mock, ManualExportSender manual) {
        this.send = send;
        this.invites = invites;
        this.mock = mock;
        this.manual = manual;
    }

    /** Trang cũ "Cài đặt gửi tin" đã gộp vào trang Zalo tài khoản phụ (D18); giữ đường dẫn cũ để dấu trang không chết. */
    @GetMapping("/sending")
    public String settings() {
        return "redirect:/zalo#gui-tay";
    }

    @PostMapping("/campaigns/{id}/dry-run")
    public String dryRun(@PathVariable long id, RedirectAttributes redirect) {
        SendService.BatchResult result = send.runBatch(id, mock, invites.maxBatch());
        redirect.addFlashAttribute("flash", "Chạy thử xong, KHÔNG gửi gì. " + result.summary()
                + " Xem chi tiết ở mục Gửi tin.");
        return "redirect:/campaigns/" + id;
    }

    /**
     * Xuất danh sách để mẹ tự gửi. Các lời mời trong file được đánh dấu "đã gửi" (kênh MANUAL_EXPORT): hệ thống
     * không biết tin có tới khách hay không. Vẫn chịu hạn mức ngày, tạm dừng và kiểm lại điều kiện liên hệ.
     */
    @PostMapping("/campaigns/{id}/export.csv")
    public ResponseEntity<byte[]> export(@PathVariable long id) {
        SendService.BatchResult result = send.runBatch(id, manual, invites.maxBatch());
        if (result.delivered().isEmpty()) {
            throw new ImportException("Không có tin nào để xuất. " + result.summary());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("loi_moi_chien_dich_" + id + ".csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(SendService.exportCsv(result.delivered()));
    }
}
