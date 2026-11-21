package vn.shop.crm.imports;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import vn.shop.crm.common.CsvOut;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.shipping.Carrier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/imports")
public class ImportController {

    private static final String COLUMN_PREFIX = "col_";

    private final ImportService imports;
    private final ExportWindowService exportWindow;

    public ImportController(ImportService imports, ExportWindowService exportWindow) {
        this.imports = imports;
        this.exportWindow = exportWindow;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("batches", imports.list());
        return "imports-list";
    }

    @GetMapping("/new")
    public String form(Model model) {
        model.addAttribute("carriers", Carrier.values());
        model.addAttribute("advice", exportWindow.all());
        return "imports-new";
    }

    @PostMapping
    public String upload(@RequestParam Carrier carrier, @RequestParam("file") List<MultipartFile> files)
            throws IOException {
        List<ImportService.UploadedFile> uploaded = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                uploaded.add(new ImportService.UploadedFile(file.getOriginalFilename(), file.getBytes()));
            }
        }
        long id = imports.createBatch(carrier, uploaded);
        return "redirect:/imports/" + id + "/mapping";
    }

    @GetMapping("/{id}/mapping")
    public String mapping(@PathVariable long id, @RequestParam(required = false) Integer headerRow, Model model) {
        ImportService.FileView view = imports.open(id, headerRow);
        model.addAttribute("view", view);
        model.addAttribute("fields", FieldKey.values());
        model.addAttribute("selected", view.suggested());
        model.addAttribute("headerRow", view.headerRow());
        return "imports-mapping";
    }

    @PostMapping("/{id}/preview")
    public String preview(@PathVariable long id, @RequestParam Map<String, String> form, Model model) {
        ImportService.Mapping mapping = toMapping(form);
        ImportService.Report report = imports.preview(id, mapping);
        model.addAttribute("batch", imports.summary(id));
        model.addAttribute("report", report);
        model.addAttribute("fields", FieldKey.values());
        model.addAttribute("selected", mapping.columns());
        model.addAttribute("headerRow", mapping.headerRow());
        return "imports-preview";
    }

    @PostMapping("/{id}/commit")
    public String commit(@PathVariable long id, @RequestParam Map<String, String> form) {
        imports.commit(id, toMapping(form));
        return "redirect:/imports/" + id;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        model.addAttribute("batch", imports.summary(id));
        model.addAttribute("errors", imports.errors(id, 500));
        return "imports-detail";
    }

    @GetMapping("/{id}/errors.csv")
    public ResponseEntity<byte[]> errorsCsv(@PathVariable long id) {
        List<List<String>> rows = imports.errors(id, Integer.MAX_VALUE / 2).stream()
                .map(e -> List.of(String.valueOf(e.rowNumber()), e.code(), e.message(),
                        e.rawRow() == null ? "" : e.rawRow()))
                .toList();
        byte[] body = CsvOut.write(List.of("dong", "ma_loi", "mo_ta", "du_lieu_goc"), rows);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("loi_nhap_" + id + ".csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private static ImportService.Mapping toMapping(Map<String, String> form) {
        int headerRow;
        try {
            headerRow = Integer.parseInt(form.getOrDefault("headerRow", "1").strip());
        } catch (NumberFormatException e) {
            throw new ImportException("Dòng tiêu đề phải là một số.");
        }
        Map<FieldKey, String> columns = new EnumMap<>(FieldKey.class);
        for (FieldKey field : FieldKey.values()) {
            String value = form.get(COLUMN_PREFIX + field.name());
            if (value != null && !value.isBlank()) {
                columns.put(field, value.strip());
            }
        }
        return new ImportService.Mapping(headerRow, columns);
    }
}
