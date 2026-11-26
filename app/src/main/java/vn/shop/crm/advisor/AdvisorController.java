package vn.shop.crm.advisor;

import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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
    private final ShopProfile shop;
    private final AdvisorStore store;
    private final TaskExecutor executor;

    AdvisorController(AdvisorService advisor, ProposalService proposals, AdvisorNotes notes, ShopProfile shop,
                      AdvisorStore store, @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.shop = shop;
        this.advisor = advisor;
        this.proposals = proposals;
        this.notes = notes;
        this.store = store;
        this.executor = executor;
    }

    @GetMapping("/advisor")
    String page(@RequestParam(required = false) Long job, Model model) {
        model.addAttribute("enabled", advisor.enabled());
        var active = store.active();
        model.addAttribute("activeJob", active.orElse(null));
        java.util.Optional.ofNullable(job).or(store::latest)
                .flatMap(proposals::run).ifPresent(detail -> model.addAttribute("job", detail.run()));
        model.addAttribute("open", proposals.open());
        model.addAttribute("decided", proposals.decided());
        model.addAttribute("runs", proposals.runs());
        model.addAttribute("track", proposals.track());
        model.addAttribute("shop", shop.load());
        model.addAttribute("goals", ShopProfile.goalOptions());
        model.addAttribute("shopMax", ShopProfile.MAX_LENGTH);
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
            var job = advisor.reserve(ContactService.actor());
            if (job.started()) {
                try {
                    executor.execute(() -> advisor.process(job.id()));
                } catch (org.springframework.core.task.TaskRejectedException e) {
                    advisor.fail(job.id(), "{}");
                }
            }
            redirect.addAttribute("job", job.id());
        } catch (ImportException e) {
            redirect.addFlashAttribute("flashBad", e.getMessage());
        }
        return "redirect:/advisor";
    }

    @GetMapping("/advisor/status/{id}")
    ResponseEntity<java.util.Map<String, Boolean>> processing(@PathVariable long id) {
        boolean running = store.active().filter(active -> active == id).isPresent();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("running", running));
    }

    /** Chủ shop khai hồ sơ cửa hàng (ô để trống là xóa ô đó). Chỉ lưu chữ; không gọi AI, không gửi gì. */
    @PostMapping("/advisor/shop")
    String saveShop(@RequestParam(defaultValue = "") String products, @RequestParam(defaultValue = "") String season,
                    @RequestParam(defaultValue = "") String offer, @RequestParam(defaultValue = "") String goal,
                    @RequestParam(defaultValue = "") String avoid, RedirectAttributes redirect) {
        try {
            shop.save(products, season, offer, goal, avoid, ContactService.actor());
            redirect.addFlashAttribute("flash", "Đã lưu hồ sơ cửa hàng. Lần nhờ AI đề xuất tới, AI sẽ đọc hồ sơ này.");
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
