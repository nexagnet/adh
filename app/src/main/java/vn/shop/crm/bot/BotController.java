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
import java.util.Map;
import java.util.stream.Collectors;

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
    private final vn.shop.crm.inbox.RefusalReviewService refusals;
    private final vn.shop.crm.inbox.ComebackService comebacks;
    private final vn.shop.crm.inbox.ReplyService replies;
    private final vn.shop.crm.inbox.WarrantyService warranty;

    public BotController(JdbcClient jdbc, AppSettings settings, FaqService faqs, BotEngine engine,
                         HandoffService handoffs, vn.shop.crm.inbox.RefusalReviewService refusals,
                         vn.shop.crm.inbox.ComebackService comebacks, vn.shop.crm.inbox.ReplyService replies,
                         vn.shop.crm.inbox.WarrantyService warranty) {
        this.warranty = warranty;
        this.refusals = refusals;
        this.replies = replies;
        this.comebacks = comebacks;
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

    private static final String ALL_WORK = "all";
    private static final String GROUP_WORK = "group";
    private static final List<WorkType> WORK_TYPES = List.of(
            new WorkType(ALL_WORK, "Tất cả"),
            new WorkType("refusals", "Nghi từ chối"),
            new WorkType("warranty", "Bảo hành, khiếu nại"),
            new WorkType("replies", "Cần trả lời"),
            new WorkType("comebacks", "Khách quay lại"),
            new WorkType(GROUP_WORK, "Việc trong nhóm"),
            new WorkType("history", "Có thể hoàn tác"));

    public record WorkType(String key, String label) {
    }

    @GetMapping("/handoffs")
    public String handoffs(@RequestParam(defaultValue = "false") boolean all,
                           @RequestParam(defaultValue = "false") boolean done,
                           @RequestParam(required = false) String type, Model model) {
        String selected = done ? GROUP_WORK : normalizedWorkType(type);
        populateWork(model, selected, all, done);
        model.addAttribute("workTypes", WORK_TYPES);
        model.addAttribute("workType", selected);
        model.addAttribute("done", done);
        model.addAttribute("doneCount", handoffs.resolvedCount());
        model.addAttribute("groupOpenCount", handoffs.unresolvedCount());
        model.addAttribute("botEnabled", settings.botEnabled());
        model.addAttribute("accountLabels", accountLabels());
        model.addAttribute("teamEnabled", handoffs.teamEnabled());
        model.addAttribute("assignees", handoffs.teamEnabled() ? jdbc.sql("""
                SELECT sg.group_id, s.username, s.display_name FROM staff_group sg
                JOIN staff_member s ON s.username = sg.username WHERE s.active ORDER BY s.display_name
                """).query().listOfRows().stream()
                .collect(Collectors.groupingBy(row -> ((Number) row.get("group_id")).longValue())) : Map.of());
        return "handoffs";
    }

    private void populateWork(Model model, String selected, boolean all, boolean done) {
        model.addAttribute("rows", includes(selected, GROUP_WORK) ? groupWork(all, done) : List.of());
        model.addAttribute("heldRefusals", includes(selected, "refusals") ? refusals.held() : List.of());
        model.addAttribute("autoRefusals", includes(selected, "history") ? refusals.recentAuto(14) : List.of());
        model.addAttribute("comebackTasks", includes(selected, "comebacks") ? comebacks.tasks() : List.of());
        model.addAttribute("comebackLifted", includes(selected, "history") ? comebacks.recentLifted(14) : List.of());
        model.addAttribute("replyHandoffs", includes(selected, "replies") ? replies.handoffs() : List.of());
        model.addAttribute("warrantyCases", includes(selected, "warranty") ? warranty.open() : List.of());
    }

    private List<HandoffService.Row> groupWork(boolean all, boolean done) {
        return done ? handoffs.resolved() : handoffs.list(!all);
    }

    private Map<Long, String> accountLabels() {
        return jdbc.sql("SELECT id, label FROM zalo_account")
                .query((rs, n) -> Map.entry(rs.getLong("id"), rs.getString("label"))).list()
                .stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static boolean includes(String selection, String type) {
        return ALL_WORK.equals(selection) || type.equals(selection);
    }

    private static String normalizedWorkType(String type) {
        return WORK_TYPES.stream().map(WorkType::key).filter(k -> k.equals(type)).findFirst().orElse(ALL_WORK);
    }

    private static String returnToWork(String type, RedirectAttributes redirect) {
        String selected = normalizedWorkType(type);
        if (!ALL_WORK.equals(selected)) {
            redirect.addAttribute("type", selected);
        }
        return "redirect:/handoffs";
    }

    @PostMapping("/handoffs/{id}/ack")
    public String ack(@PathVariable long id, @RequestParam(required = false) String type, RedirectAttributes redirect) {
        handoffs.acknowledge(id);
        redirect.addFlashAttribute("flash", "Đã nhận việc. Mẹ vào Zalo trả lời khách nhé.");
        return returnToWork(type, redirect);
    }

    @PostMapping("/handoffs/{id}/resolve")
    public String resolve(@PathVariable long id, @RequestParam(required = false) String type, RedirectAttributes redirect) {
        handoffs.resolve(id);
        redirect.addFlashAttribute("flash", "Đã xử lý xong.");
        return returnToWork(type, redirect);
    }

    // ---------- tin riêng của khách: trợ lý AI nghi từ chối (D19) ----------

    @PostMapping("/handoffs/refusals/{id}/confirm")
    public String confirmRefusal(@PathVariable long id, @RequestParam(required = false) String type, RedirectAttributes redirect) {
        refusals.confirm(id);
        redirect.addFlashAttribute("flash", "Đã ghi sổ: khách từ chối, sẽ không nhắn lại.");
        return returnToWork(type, redirect);
    }

    @PostMapping("/handoffs/refusals/{id}/release")
    public String releaseRefusal(@PathVariable long id, @RequestParam(required = false) String type, RedirectAttributes redirect) {
        boolean restored = refusals.release(id);
        redirect.addFlashAttribute("flash", restored ? "Đã cho tiếp tục: khách này lại nằm trong luồng mời như trước."
                : "Đã đóng việc này, nhưng lời mời của khách đã bị đổi ở chỗ khác nên không khôi phục.");
        return returnToWork(type, redirect);
    }

    @PostMapping("/handoffs/refusals/{id}/undo")
    public String undoRefusal(@PathVariable long id, @RequestParam(required = false) String type, RedirectAttributes redirect) {
        boolean restored = refusals.undo(id);
        redirect.addFlashAttribute("flash", restored ? "Đã hoàn tác: khách không còn bị ghi từ chối, lời mời trở lại như trước."
                : "Đã gỡ ghi từ chối, nhưng lời mời của khách đã bị đổi ở chỗ khác nên không khôi phục.");
        return returnToWork(type, redirect);
    }

    // ---------- trợ lý AI trả lời khách: việc chuyển người thật và nháp (D20) ----------

    @PostMapping("/handoffs/replies/{id}/done")
    public String doneReply(@PathVariable long id, java.security.Principal user,
                            @RequestParam(required = false) String type, RedirectAttributes redirect) {
        replies.done(id, user == null ? "người thật" : user.getName());
        redirect.addFlashAttribute("flash", "Đã xử lý xong việc trả lời khách.");
        return returnToWork(type, redirect);
    }

    // ---------- bảo hành và khiếu nại: AI phân loại, người thật xử lý ----------

    @PostMapping("/handoffs/warranty/{id}/done")
    public String doneWarranty(@PathVariable long id, java.security.Principal user,
                               @RequestParam(required = false) String type, RedirectAttributes redirect) {
        warranty.done(id, user == null ? "người thật" : user.getName());
        redirect.addFlashAttribute("flash", "Đã xử lý xong việc bảo hành hoặc khiếu nại.");
        return returnToWork(type, redirect);
    }

    // ---------- khách từng từ chối tự nhắn lại muốn mua (D19, docs/THIET-KE-KHACH-QUAY-LAI.md) ----------

    @PostMapping("/handoffs/comebacks/{id}/lift")
    public String liftComeback(@PathVariable long id, @RequestParam(required = false) String type, RedirectAttributes redirect) {
        comebacks.liftByHand(id);
        redirect.addFlashAttribute("flash", "Đã gỡ \"không nhắn nữa\": khách vào hàng chờ vào nhóm theo khu vực. Mẹ nhắn khách trên Zalo nhé. Số điện thoại vẫn được giữ như cũ.");
        return returnToWork(type, redirect);
    }

    @PostMapping("/handoffs/comebacks/{id}/keep")
    public String keepComeback(@PathVariable long id, @RequestParam(required = false) String type, RedirectAttributes redirect) {
        comebacks.dismiss(id);
        redirect.addFlashAttribute("flash", "Đã giữ nguyên: khách vẫn không được nhắn.");
        return returnToWork(type, redirect);
    }

    @PostMapping("/handoffs/comebacks/{id}/undo")
    public String undoComeback(@PathVariable long id, @RequestParam(required = false) String type, RedirectAttributes redirect) {
        boolean restored = comebacks.undo(id);
        redirect.addFlashAttribute("flash", restored ? "Đã hoàn tác: khách lại bị đánh dấu không nhắn nữa."
                : "Đã đánh dấu khách lại không nhắn nữa. Khách đã được thêm vào nhóm (hoặc đang được thêm) nên hệ thống không đưa ra khỏi nhóm được; mẹ tự mời ra trên Zalo nếu cần.");
        return returnToWork(type, redirect);
    }
}
