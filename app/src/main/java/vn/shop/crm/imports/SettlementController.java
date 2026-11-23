package vn.shop.crm.imports;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Nhập file "Lịch sử đối soát" của J&T: tải lên, xem trước, xác nhận; kèm danh sách các kỳ đã nhập. */
@Controller
@RequestMapping("/imports/settlement")
public class SettlementController {

    private final SettlementImportService settlements;

    public SettlementController(SettlementImportService settlements) {
        this.settlements = settlements;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("periods", settlements.periods());
        model.addAttribute("latestEnd", settlements.latestPeriodEnd().orElse(null));
        model.addAttribute("maxFiles", SettlementImportService.MAX_FILES_PER_BATCH);
        return "settlement-list";
    }

    @PostMapping
    public String upload(@RequestParam("file") List<MultipartFile> files) throws IOException {
        List<ImportService.UploadedFile> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                uploaded.add(new ImportService.UploadedFile(file.getOriginalFilename(), file.getBytes()));
            }
        }
        long id = settlements.createBatch(uploaded);
        return "redirect:/imports/settlement/" + id;
    }

    @GetMapping("/{id}")
    public String preview(@PathVariable long id, Model model) {
        SettlementImportService.BatchView batch = settlements.batch(id);
        if (batch.committed()) {
            return "redirect:/imports/settlement";
        }
        model.addAttribute("batch", batch);
        model.addAttribute("report", settlements.preview(id));
        return "settlement-preview";
    }

    @PostMapping("/{id}/commit")
    public String commit(@PathVariable long id, RedirectAttributes redirect) {
        redirect.addFlashAttribute("result", settlements.commit(id));
        return "redirect:/imports/settlement";
    }
}
