package vn.shop.crm.bot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** Màn hình bot: công tắc tắt/bật, chạy thử, bật theo nhóm, FAQ, thử bot và việc cần mẹ xử lý (mốc 1E). */
@Controller
public class BotController {

    public record GroupRow(long id, String name, boolean botEnabled) {
    }

    public record EventRow(LocalDateTime at, String group, String senderKind, String type, String intent,
                           String reply, boolean dryRun, boolean sent) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final FaqService faqs;
    private final BotEngine engine;
    private final HandoffService handoffs;

    public BotController(JdbcClient jdbc, AppSettings settings, FaqService faqs, BotEngine engine,
                         HandoffService handoffs) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.faqs = faqs;
        this.engine = engine;
        this.handoffs = handoffs;
    }

    @GetMapping("/bot")
    public String bot(Model model) {
        fill(model);
        return "bot";
    }

    private void fill(Model model) {
        model.addAttribute("botEnabled", settings.botEnabled());
        model.addAttribute("dryRun", settings.botDryRun());
        model.addAttribute("groups", jdbc.sql("SELECT id, name, bot_enabled FROM zalo_group ORDER BY id")
                .query((rs, i) -> new GroupRow(rs.getLong("id"), rs.getString("name"), rs.getBoolean("bot_enabled")))
                .list());
        model.addAttribute("faqs", faqs.list());
        model.addAttribute("events", recentEvents());
    }

    private List<EventRow> recentEvents() {
        return jdbc.sql("""
                        SELECT e.created_at, g.name, e.sender_kind, e.event_type, e.intent, e.reply_text, e.dry_run, e.sent
                        FROM bot_event e JOIN zalo_group g ON g.id = e.group_id ORDER BY e.id DESC LIMIT 30""")
                .query((rs, i) -> new EventRow(
                        rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh"))
                                .toLocalDateTime(), rs.getString("name"), rs.getString("sender_kind"),
                        rs.getString("event_type"), rs.getString("intent"), rs.getString("reply_text"),
                        rs.getBoolean("dry_run"), rs.getBoolean("sent")))
                .list();
    }

    @PostMapping("/bot/enable")
    public String enable(@RequestParam boolean on, RedirectAttributes redirect) {
        settings.set(AppSettings.BOT_ENABLED, String.valueOf(on), ContactService.actor());
        redirect.addFlashAttribute("flash", on ? "Đã BẬT trợ lý AI (vẫn chỉ chạy thử nếu chế độ thử đang bật)."
                : "Đã TẮT trợ lý AI. Mẹ vẫn trả lời khách trên Zalo như bình thường.");
        return "redirect:/bot";
    }

    @PostMapping("/bot/dry-run")
    public String dryRun(@RequestParam boolean on, RedirectAttributes redirect) {
        settings.set(AppSettings.BOT_DRY_RUN, String.valueOf(on), ContactService.actor());
        redirect.addFlashAttribute("flash", on ? "Trợ lý AI đang ở chế độ thử: chỉ ghi sẽ trả lời gì, không gửi."
                : "Đã tắt chế độ thử. Trợ lý AI SẼ gửi tin qua kênh khi có kênh được cài (hiện chưa có kênh nào).");
        return "redirect:/bot";
    }

    @PostMapping("/bot/groups/{id}/toggle")
    public String toggleGroup(@PathVariable long id, @RequestParam boolean on, RedirectAttributes redirect) {
        int n = jdbc.sql("UPDATE zalo_group SET bot_enabled = :on WHERE id = :id").param("on", on).param("id", id)
                .update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy nhóm.");
        }
        redirect.addFlashAttribute("flash", on ? "Đã bật trợ lý AI cho nhóm." : "Đã tắt trợ lý AI cho nhóm.");
        return "redirect:/bot";
    }

    @PostMapping("/bot/faq")
    public String createFaq(@RequestParam String title, @RequestParam String keywords, @RequestParam String answer,
                            RedirectAttributes redirect) {
        faqs.create(title, keywords, answer);
        redirect.addFlashAttribute("flash", "Đã thêm câu hỏi thường gặp.");
        return "redirect:/bot";
    }

    @PostMapping("/bot/faq/{id}/update")
    public String updateFaq(@PathVariable long id, @RequestParam String title, @RequestParam String keywords,
                            @RequestParam String answer, @RequestParam(defaultValue = "false") boolean enabled,
                            RedirectAttributes redirect) {
        faqs.update(id, title, keywords, answer, enabled);
        redirect.addFlashAttribute("flash", "Đã lưu câu hỏi.");
        return "redirect:/bot";
    }

    @PostMapping("/bot/faq/{id}/delete")
    public String deleteFaq(@PathVariable long id, RedirectAttributes redirect) {
        faqs.delete(id);
        redirect.addFlashAttribute("flash", "Đã xóa câu hỏi.");
        return "redirect:/bot";
    }

    /** Thử bot với một câu: không ghi gì, không tạo việc, không gửi gì. */
    @PostMapping("/bot/simulate")
    public String simulate(@RequestParam String text, Model model) {
        fill(model);
        model.addAttribute("sample", text);
        model.addAttribute("decision", engine.preview(text));
        return "bot";
    }

    // ---------- việc cần mẹ xử lý ----------

    @GetMapping("/handoffs")
    public String handoffs(@RequestParam(defaultValue = "false") boolean all,
                           @RequestParam(defaultValue = "false") boolean done, Model model) {
        var everything = handoffs.list(false);
        long doneCount = everything.stream().filter(r -> "RESOLVED".equals(r.status())).count();
        model.addAttribute("rows", done ? everything.stream().filter(r -> "RESOLVED".equals(r.status())).toList()
                : (all ? everything : everything.stream().filter(r -> !"RESOLVED".equals(r.status())).toList()));
        model.addAttribute("all", all);
        model.addAttribute("done", done);
        model.addAttribute("doneCount", doneCount);
        model.addAttribute("openCount", everything.size() - doneCount);
        model.addAttribute("botEnabled", settings.botEnabled());
        return "handoffs";
    }

    @PostMapping("/handoffs/{id}/ack")
    public String ack(@PathVariable long id, RedirectAttributes redirect) {
        handoffs.acknowledge(id);
        redirect.addFlashAttribute("flash", "Đã nhận việc. Mẹ vào Zalo trả lời khách nhé.");
        return "redirect:/handoffs";
    }

    @PostMapping("/handoffs/{id}/resolve")
    public String resolve(@PathVariable long id, RedirectAttributes redirect) {
        handoffs.resolve(id);
        redirect.addFlashAttribute("flash", "Đã xử lý xong.");
        return "redirect:/handoffs";
    }
}
