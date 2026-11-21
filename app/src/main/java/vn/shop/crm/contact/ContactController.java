package vn.shop.crm.contact;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.ImportException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** Màn hình cơ sở liên hệ, danh sách mời và quyền dữ liệu của khách (mốc 1B). */
@Controller
public class ContactController {

    /** Chữ khách phải gõ để xác nhận xóa: chống bấm nhầm. */
    static final String ERASE_WORD = "XOA";

    private final ContactService contacts;
    private final PrivacyService privacy;

    public ContactController(ContactService contacts, PrivacyService privacy) {
        this.contacts = contacts;
        this.privacy = privacy;
    }

    // ---------- danh sách mời ----------

    @GetMapping("/contacts")
    public String invites(@RequestParam(defaultValue = "1") int page, Model model) {
        model.addAttribute("stats", contacts.stats());
        model.addAttribute("page", contacts.inviteList(page, 50));
        model.addAttribute("statement", ContactService.STATEMENT);
        return "contacts";
    }

    @PostMapping("/contacts/approve-all")
    public String approveAll(@RequestParam(defaultValue = "false") boolean confirm, RedirectAttributes redirect) {
        if (!confirm) {
            throw new ImportException("Bạn cần tích ô xác nhận trách nhiệm trước khi duyệt.");
        }
        int n = contacts.approveAllCandidates();
        redirect.addFlashAttribute("flash", "Đã duyệt " + n + " khách vào danh sách đủ điều kiện mời.");
        return "redirect:/contacts";
    }

    // ---------- thao tác trên một khách ----------

    @PostMapping("/customers/{id}/approve")
    public String approve(@PathVariable long id, RedirectAttributes redirect) {
        contacts.approve(id);
        redirect.addFlashAttribute("flash", "Đã duyệt khách vào danh sách đủ điều kiện mời.");
        return "redirect:/customers/" + id;
    }

    @PostMapping("/customers/{id}/block")
    public String block(@PathVariable long id, @RequestParam(required = false) String note,
                        RedirectAttributes redirect) {
        contacts.block(id, note);
        redirect.addFlashAttribute("flash", "Đã chặn: khách này sẽ không nằm trong danh sách mời.");
        return "redirect:/customers/" + id;
    }

    @PostMapping("/customers/{id}/unblock")
    public String unblock(@PathVariable long id, RedirectAttributes redirect) {
        contacts.unblock(id);
        redirect.addFlashAttribute("flash", "Đã bỏ chặn. Khách quay về trạng thái chưa duyệt, cần duyệt lại.");
        return "redirect:/customers/" + id;
    }

    @PostMapping("/customers/{id}/note")
    public String note(@PathVariable long id, @RequestParam(required = false) String note,
                       RedirectAttributes redirect) {
        contacts.saveNote(id, note);
        redirect.addFlashAttribute("flash", "Đã lưu ghi chú.");
        return "redirect:/customers/" + id;
    }

    // ---------- quyền dữ liệu ----------

    @PostMapping("/customers/{id}/privacy/open")
    public String openRequest(@PathVariable long id, @RequestParam PrivacyService.Kind kind,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                              LocalDate receivedOn,
                              @RequestParam(required = false) String note, RedirectAttributes redirect) {
        privacy.openRequest(id, kind, receivedOn, note);
        redirect.addFlashAttribute("flash", "Đã ghi nhận yêu cầu. Hạn phản hồi tính từ ngày khách gửi yêu cầu.");
        return "redirect:/customers/" + id;
    }

    @PostMapping("/customers/{id}/privacy/export.csv")
    public ResponseEntity<byte[]> export(@PathVariable long id,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedOn) {
        byte[] body = privacy.exportCustomer(id, receivedOn);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("du_lieu_khach_" + id + ".csv", StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @PostMapping("/customers/{id}/privacy/stop-contact")
    public String stopContact(@PathVariable long id,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedOn,
                              @RequestParam(required = false) String note, RedirectAttributes redirect) {
        privacy.stopContact(id, receivedOn, note);
        redirect.addFlashAttribute("flash", "Đã ghi nhận: không liên hệ khách này nữa.");
        return "redirect:/customers/" + id;
    }

    @PostMapping("/customers/{id}/privacy/erase")
    public String erase(@PathVariable long id, @RequestParam(required = false) String confirm,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedOn,
                        @RequestParam(required = false) String note, RedirectAttributes redirect) {
        if (confirm == null || !ERASE_WORD.equalsIgnoreCase(confirm.strip())) {
            throw new ImportException("Để xóa, hãy gõ đúng chữ " + ERASE_WORD + " vào ô xác nhận.");
        }
        privacy.erase(id, receivedOn, note);
        redirect.addFlashAttribute("flash", "Đã xóa khách và toàn bộ đơn của khách. Số này sẽ không được nhập lại "
                + "hay liên hệ.");
        return "redirect:/customers";
    }

    @GetMapping("/privacy")
    public String requests(@RequestParam(defaultValue = "open") String tab, Model model) {
        var all = privacy.requests(false);
        boolean done = "done".equals(tab);
        model.addAttribute("tab", done ? "done" : "open");
        model.addAttribute("requests", all.stream().filter(r -> r.open() != done).toList());
        model.addAttribute("openCount", all.stream().filter(PrivacyService.RequestRow::open).count());
        model.addAttribute("doneCount", all.stream().filter(r -> !r.open()).count());
        model.addAttribute("quickDays", privacy.quickWorkingDays());
        model.addAttribute("eraseDays", privacy.eraseDays());
        model.addAttribute("kinds", PrivacyService.Kind.values());
        model.addAttribute("today", LocalDate.now());
        return "privacy";
    }

    @PostMapping("/privacy/new")
    public String newRequest(@RequestParam String phone, @RequestParam PrivacyService.Kind kind,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedOn,
                             @RequestParam(required = false) String note, RedirectAttributes redirect) {
        privacy.openRequestByPhone(phone, kind, receivedOn, note);
        redirect.addFlashAttribute("flash", "Đã ghi nhận yêu cầu \"" + kind.label() + "\".");
        return "redirect:/privacy";
    }
}
