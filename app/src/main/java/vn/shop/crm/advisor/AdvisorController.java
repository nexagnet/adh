package vn.shop.crm.advisor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

/**
 * Trang "Đề xuất của AI" (D21): bấm nhờ AI đề xuất, xem lý do và số liệu đã gửi, duyệt (tạo chiến dịch CHƯA duyệt tự gửi) hoặc bỏ qua.
 * Controller này không gọi Zalo và không gửi tin nào.
 */
@Controller
class AdvisorController {

    private final AdvisorService advisor;
    private final ProposalService proposals;
    private final AdvisorNotes notes;

    AdvisorController(AdvisorService advisor, ProposalService proposals, AdvisorNotes notes) {
        this.advisor = advisor;
        this.proposals = proposals;
        this.notes = notes;
    }

    @GetMapping("/advisor")
    String page(Model model) {
        model.addAttribute("enabled", advisor.enabled());
        model.addAttribute("runsToday", advisor.runsToday());
        model.addAttribute("maxRuns", advisor.maxRunsPerDay());
        model.addAttribute("open", proposals.open());
        model.addAttribute("decided", proposals.decided());
        model.addAttribute("runs", proposals.runs());
        model.addAttribute("track", proposals.track());
        model.addAttribute("notes", notes.active());
        model.addAttribute("noteMax", AdvisorNotes.MAX_LENGTH);
        model.addAttribute("noteMaxActive", AdvisorNotes.MAX_ACTIVE);
        model.addAttribute("noteDefaultExpiry", java.time.LocalDate.now(AdvisorSnapshotService.ZONE).plusDays(14));
        model.addAttribute("noteMaxExpiry", java.time.LocalDate.now(AdvisorSnapshotService.ZONE).plusDays(AdvisorNotes.MAX_DAYS));
        return "advisor";
    }

    @GetMapping("/advisor/runs/{id}")
    String run(@PathVariable long id, Model model) {
        model.addAttribute("detail", proposals.run(id).orElseThrow(() -> new ImportException("Không tìm thấy lượt nhờ AI.")));
        return "advisor-run";
    }

    /** Chỉ người bấm mới gọi AI; tắt, hết lượt hay lỗi thì báo lại ngay trên trang. */
    @PostMapping("/advisor/run")
    String ask(RedirectAttributes redirect) {
        try {
            var view = advisor.run(ContactService.actor());
            var r = view.result();
            switch (r.status()) {
                case OK -> redirect.addFlashAttribute("flash", "AI đã đề xuất " + r.proposals().size()
                        + " chiến dịch. Chưa có chiến dịch nào được tạo và chưa gửi gì: bạn đọc lý do rồi mới quyết định."
                        + (r.rejected().isEmpty() ? "" : " Có " + r.rejected().size() + " đề xuất bị mã bỏ vì không đạt kiểm tra (xem ở lượt nhờ AI)."));
                case SKIPPED -> redirect.addFlashAttribute("flash", "AI không đề xuất gì lần này"
                        + (r.skipReason() == null ? "." : ": " + r.skipReason()));
                default -> redirect.addFlashAttribute("flashBad", "Chưa có đề xuất dùng được"
                        + (r.detail() == null ? "." : ": " + r.detail()));
            }
        } catch (ImportException e) {
            redirect.addFlashAttribute("flashBad", e.getMessage());
        }
        return "redirect:/advisor";
    }

    /** Chủ shop thêm một ghi chú cho AI (có ngày hết hạn). Chỉ lưu chữ; không gọi AI, không gửi gì. */
    @PostMapping("/advisor/notes")
    String addNote(@RequestParam String body, @RequestParam String expiresOn, RedirectAttributes redirect) {
        try {
            java.time.LocalDate exp;
            try {
                exp = java.time.LocalDate.parse(expiresOn.strip());
            } catch (java.time.DateTimeException e) {
                throw new ImportException("Ngày hết hạn không hợp lệ.");
            }
            notes.add(body, exp, ContactService.actor());
            redirect.addFlashAttribute("flash", "Đã lưu ghi chú. Lần nhờ AI đề xuất tới, AI sẽ đọc ghi chú này cho đến hết ngày "
                    + exp.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".");
        } catch (ImportException e) {
            redirect.addFlashAttribute("flashBad", e.getMessage());
        }
        return "redirect:/advisor";
    }

    @PostMapping("/advisor/notes/{id}/remove")
    String removeNote(@PathVariable long id, RedirectAttributes redirect) {
        notes.remove(id, ContactService.actor());
        redirect.addFlashAttribute("flash", "Đã gỡ ghi chú: AI sẽ không đọc ghi chú này nữa.");
        return "redirect:/advisor";
    }

    @PostMapping("/advisor/proposals/{id}/approve")
    String approve(@PathVariable long id, RedirectAttributes redirect) {
        try {
            long campaignId = proposals.approve(id, ContactService.actor());
            redirect.addFlashAttribute("flash", "Đã triển khai: chiến dịch được tạo theo đề xuất của AI. Chưa có tin nào được gửi: "
                    + "bạn kiểm tra danh sách khách và lịch rồi mới duyệt cho hệ thống tự gửi. Ngày bắt đầu, khung giờ và hạn mức ngày AI gợi ý đã được đặt vào chiến dịch "
                    + "(luôn trong giới hạn chung của hệ thống); bạn chỉnh được ở mục Lịch và nhịp riêng.");
            return "redirect:/campaigns/" + campaignId;
        } catch (ImportException e) {
            redirect.addFlashAttribute("flashBad", e.getMessage());
            return "redirect:/advisor";
        }
    }

    @PostMapping("/advisor/proposals/{id}/reject")
    String reject(@PathVariable long id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            proposals.reject(id, ContactService.actor(), note);
            redirect.addFlashAttribute("flash", "Đã bỏ qua đề xuất.");
        } catch (ImportException e) {
            redirect.addFlashAttribute("flashBad", e.getMessage());
        }
        return "redirect:/advisor";
    }
}
