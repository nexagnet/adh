package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.inbox.ReplyService;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Thả tim vào tin khách mà AI xác định KHÔNG cần đáp (cảm ơn, biểu tượng cảm xúc), thay vì im lặng (24/11/2026, chủ shop duyệt). Không gửi chữ,
 * không tính vào hạn mức khách được nhắn chủ động (Tin 1), không ghi sổ tin gửi.
 *
 * <p>Chốt chặn: chỉ ở nấc trả lời TỰ ĐỘNG và khi công tắc thả tim (mặc định bật) bật; cần công tắc tự gửi bật, nấc tự gửi không tắt, cầu dao đóng, không
 * "Tạm dừng tất cả", trong khung giờ gửi, cầu nối đang nghe đúng tài khoản; trần {@value #DAILY_MAX} lần mỗi ngày ở Adh (cầu nối còn trần và khoảng nghỉ cố định
 * riêng); mỗi tin chỉ một lần (con trỏ tin đến và cầu nối đều khóa); chế độ thử qua {@code SafeZaloLink}. Người từng từ chối không bao giờ tới đây
 * ({@code ReplyService} chuyển người thật trước). Lỗi không làm hỏng việc nhận tin: ghi nhật ký rồi bỏ qua, KHÔNG tự thử lại.
 */
@Service
public class ZaloReactionSender {

    private static final Logger log = LoggerFactory.getLogger(ZaloReactionSender.class);
    public static final String ACTION = "THA_TIM";
    static final int DAILY_MAX = 200;

    private final ReplyService replies;
    private final ZaloMessageService messages;
    private final ZaloAutoSettings cfg;
    private final AppSettings settings;
    private final ZaloLink link;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ZaloReactionSender(ReplyService replies, ZaloMessageService messages, ZaloAutoSettings cfg, AppSettings settings, ZaloLink link,
                              JdbcClient jdbc, Clock clock) {
        this.replies = replies;
        this.messages = messages;
        this.cfg = cfg;
        this.settings = settings;
        this.link = link;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true nếu đã thả tim. */
    public boolean react(Long invitationId, long seq, String uid) {
        try {
            if (!replies.reactEnabled() || replies.mode() != ReplyService.Mode.AUTO || !messages.enabled() || settings.paused()
                    || !cfg.effectiveMode().listens() || cfg.tripped() || !messages.inSendWindow() || usedToday() >= DAILY_MAX) {
                return false;
            }
            ZaloGateway.Status st = link.status();
            if (st.state() != ZaloGateway.State.LISTENING || !Boolean.TRUE.equals(st.accountOk())) {
                return false;
            }
            ZaloReactor.Reacted r = link.heart(seq, uid);
            cfg.log(ACTION, invitationId, null, r.ok() ? "OK" : "LOI", r.code(), "system");
            return r.ok();
        } catch (RuntimeException e) {
            log.warn("Không thả tim được: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private int usedToday() {
        java.time.Instant start = clock.instant().atZone(clock.getZone()).toLocalDate().atStartOfDay(clock.getZone()).toInstant();
        return jdbc.sql("SELECT count(*) FROM zalo_auto_log WHERE " + ZaloAccountContext.sql() + " AND action = :a AND at >= :s").param("a", ACTION)
                .param("s", OffsetDateTime.ofInstant(start, ZoneOffset.UTC)).query(Integer.class).single();
    }
}
