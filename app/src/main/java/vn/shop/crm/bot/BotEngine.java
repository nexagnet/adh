package vn.shop.crm.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.AppSettings;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lõi bot trong nhóm, độc lập với kênh (mục 8 file 02, BR-11/12, tiêu chí 9–10 file 01).
 *
 * <p>Nguyên tắc: bot CÔNG KHAI là trợ lý, chỉ trả lời bằng FAQ cửa hàng đã duyệt; thấy ý định mua, khiếu nại hay muốn
 * gặp người thật thì báo mẹ (tạo "việc cần xử lý") và KHÔNG bao giờ tự nhận đã chốt đơn; không hỏi/ghi địa chỉ, SĐT;
 * im lặng khi mẹ đang trả lời; không trả lời tin của chính nó; mỗi tin xử lý đúng một lần. Mặc định bot TẮT và chỉ
 * chạy thử (ghi "sẽ trả lời gì"). Bot hỏng không ảnh hưởng việc mẹ tự trả lời trên Zalo thật.
 */
@Service
public class BotEngine {

    private static final Logger LOG = LoggerFactory.getLogger(BotEngine.class);

    static final String PREFIX = "Trợ lý cửa hàng: ";
    static final String ACK_TEXT = PREFIX + "Mình đã báo cho chủ cửa hàng, sẽ có người trả lời bạn sớm nhé.";
    static final String UNSURE_TEXT = PREFIX + "Mình chưa chắc về câu này nên đã nhờ chủ cửa hàng trả lời giúp bạn nhé.";
    static final String PII_REMINDER = " Bạn vui lòng không gửi số điện thoại hay địa chỉ trong nhóm, "
            + "cửa hàng sẽ nhắn riêng nếu cần.";

    public enum SenderKind { CUSTOMER, OWNER, BOT }

    public record IncomingMessage(long groupId, String messageId, SenderKind sender, String text) {
    }

    public enum Type { SELF, HUMAN_ACTIVE, BOT_OFF, RATE_LIMITED, IGNORED, ANSWERED, HANDOFF, DUPLICATE, UNKNOWN_GROUP }

    /** {@code reply} là tin bot MUỐN gửi (có thể null); {@code sent} cho biết đã thật sự gửi chưa. */
    public record Decision(Type type, Intent intent, String reply, boolean sent, boolean dryRun) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final FaqService faqs;
    private final HandoffService handoffs;
    private final AiProvider ai;
    private final BotChannel channel;
    private final int quietMinutes;
    private final int maxRepliesPer5Min;

    public BotEngine(JdbcClient jdbc, AppSettings settings, FaqService faqs, HandoffService handoffs, AiProvider ai,
                     BotChannel channel, @Value("${app.bot.human-quiet-minutes:30}") int quietMinutes,
                     @Value("${app.bot.max-replies-per-5-min:3}") int maxRepliesPer5Min) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.faqs = faqs;
        this.handoffs = handoffs;
        this.ai = ai;
        this.channel = channel;
        this.quietMinutes = quietMinutes;
        this.maxRepliesPer5Min = maxRepliesPer5Min;
    }

    /** Xử lý một tin nhắn nhóm do kênh chuyển tới. Gọi lặp lại cùng mã tin không làm gì thêm. */
    @Transactional
    public Decision handle(IncomingMessage msg) {
        Optional<GroupState> group = jdbc.sql("SELECT bot_enabled, human_active_until FROM zalo_group WHERE id = :id")
                .param("id", msg.groupId())
                .query((rs, i) -> new GroupState(rs.getBoolean("bot_enabled"),
                        rs.getObject("human_active_until", OffsetDateTime.class)))
                .optional();
        if (group.isEmpty()) {
            return new Decision(Type.UNKNOWN_GROUP, null, null, false, true);
        }
        boolean dry = settings.botDryRun();
        int inserted = jdbc.sql("""
                        INSERT INTO bot_event (group_id, message_id, sender_kind, dry_run)
                        VALUES (:g, :m, :s, :dry) ON CONFLICT (group_id, message_id) DO NOTHING""")
                .param("g", msg.groupId()).param("m", msg.messageId()).param("s", msg.sender().name())
                .param("dry", dry).update();
        if (inserted == 0) {
            return new Decision(Type.DUPLICATE, null, null, false, dry);
        }
        if (msg.sender() == SenderKind.BOT) {
            return finish(msg, Type.SELF, null, null, dry);
        }
        if (msg.sender() == SenderKind.OWNER) {
            jdbc.sql("UPDATE zalo_group SET human_active_until = now() + make_interval(mins => :m) WHERE id = :id")
                    .param("m", quietMinutes).param("id", msg.groupId()).update();
            return finish(msg, Type.HUMAN_ACTIVE, null, null, dry);
        }
        if (!settings.botEnabled() || !group.get().botEnabled()) {
            return finish(msg, Type.BOT_OFF, null, null, dry);
        }
        OffsetDateTime until = group.get().humanActiveUntil();
        if (until != null && until.isAfter(OffsetDateTime.now())) {
            return finish(msg, Type.HUMAN_ACTIVE, null, null, dry);
        }

        Intent intent = IntentClassifier.classify(msg.text());
        if (intent == Intent.OTHER) {
            return finish(msg, Type.IGNORED, intent, null, dry);
        }
        if (recentReplies(msg.groupId()) >= maxRepliesPer5Min) {
            return finish(msg, Type.RATE_LIMITED, intent, null, dry);
        }
        Plan plan = plan(msg.text(), intent);
        String reply = plan.reply();
        if (plan.handoffReason() != null) {
            boolean created = handoffs.open(msg.groupId(), plan.handoffReason(), PiiMasker.excerpt(msg.text(), 200));
            // đã có việc đang mở cho nhóm này thì không báo lại cho khách lần nữa
            reply = created ? withPiiReminder(reply, msg.text()) : null;
        }
        return finish(msg, plan.handoffReason() != null ? Type.HANDOFF : Type.ANSWERED, intent, reply, dry);
    }

    /** Xem bot SẼ làm gì với một câu, không ghi gì vào cơ sở dữ liệu (dùng cho ô "thử bot" trên giao diện). */
    public Decision preview(String text) {
        Intent intent = IntentClassifier.classify(text);
        if (intent == Intent.OTHER) {
            return new Decision(Type.IGNORED, intent, null, false, true);
        }
        Plan plan = plan(text, intent);
        String reply = plan.handoffReason() != null ? withPiiReminder(plan.reply(), text) : plan.reply();
        return new Decision(plan.handoffReason() != null ? Type.HANDOFF : Type.ANSWERED, intent, reply, false, true);
    }

    private record GroupState(boolean botEnabled, OffsetDateTime humanActiveUntil) {
    }

    private record Plan(String reply, String handoffReason) {
    }

    /** Quyết định thuần túy: trả lời FAQ hay chuyển người thật (không đụng cơ sở dữ liệu ngoài việc đọc FAQ). */
    private Plan plan(String text, Intent intent) {
        if (intent == Intent.WANTS_HUMAN) {
            return new Plan(ACK_TEXT, "HUMAN");
        }
        if (intent == Intent.COMPLAINT) {
            return new Plan(ACK_TEXT, "COMPLAINT");
        }
        if (IntentClassifier.strongOrder(text)) {
            return new Plan(ACK_TEXT, "ORDER");
        }
        Optional<FaqService.Faq> faq = faqs.match(text);
        if (faq.isPresent()) {
            return new Plan(PREFIX + faq.get().answer(), null);
        }
        if (IntentClassifier.weakOrder(text)) {
            return new Plan(ACK_TEXT, "ORDER");
        }
        // AI chỉ nhận văn bản đã che và câu FAQ đã duyệt; mặc định không có câu trả lời
        List<String> approved = faqs.list().stream().filter(FaqService.Faq::enabled).map(FaqService.Faq::answer).toList();
        Optional<String> suggestion = ai.suggest(PiiMasker.mask(text), approved);
        if (suggestion.isPresent() && !suggestion.get().isBlank()) {
            return new Plan(PREFIX + suggestion.get().strip(), null);
        }
        return new Plan(UNSURE_TEXT, "UNSURE");
    }

    private static String withPiiReminder(String reply, String text) {
        return PiiMasker.containsPii(text) ? reply + PII_REMINDER : reply;
    }

    private long recentReplies(long groupId) {
        return jdbc.sql("""
                        SELECT count(*) FROM bot_event WHERE group_id = :g AND reply_text IS NOT NULL
                        AND created_at > now() - interval '5 minutes'""")
                .param("g", groupId).query(Long.class).single();
    }

    /** Ghi kết quả cuối; nếu không phải chạy thử và có tin muốn gửi thì gửi qua kênh. */
    private Decision finish(IncomingMessage msg, Type type, Intent intent, String reply, boolean dry) {
        boolean sent = false;
        if (reply != null && !dry) {
            try {
                sent = channel.send(msg.groupId(), reply);
            } catch (RuntimeException e) {
                // bot hỏng không được làm hỏng việc khác: chỉ ghi nhận không gửi được
                LOG.warn("Kênh bot báo lỗi: {}", e.getClass().getSimpleName());
            }
        }
        jdbc.sql("""
                        UPDATE bot_event SET event_type = :t, intent = :i, reply_text = :r, sent = :s
                        WHERE group_id = :g AND message_id = :m""")
                .param("t", dbType(type)).param("i", intent == null ? null : intent.name()).param("r", reply)
                .param("s", sent).param("g", msg.groupId()).param("m", msg.messageId()).update();
        return new Decision(type, intent, reply, sent, dry);
    }

    private static String dbType(Type t) {
        return t.name();
    }
}
