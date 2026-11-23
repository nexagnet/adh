package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.inbox.ReplyService;
import vn.shop.crm.zalo.ZaloMessageService.Delivery;
import vn.shop.crm.zalo.ZaloMessenger.Kind;

import java.util.Optional;

/**
 * Gửi câu trả lời do trợ lý AI soạn (loại tin thứ tư, {@link Kind#REPLY}, D20) cho khách vừa TỰ nhắn tới. Chỉ ở nấc TỰ ĐỘNG của phần trả lời.
 * Câu đã qua kiểm tra cứng ({@code ReplyValidator}) trước khi vào hàng chờ này.
 *
 * <p>Chốt chặn: đi qua đúng {@link ZaloMessageService} như mọi tin (công tắc, nấc, khung giờ, khoảng nghỉ dùng chung, cầu dao); cầu nối chỉ cho trả lời
 * người vừa nhắn trong 24 giờ và MỖI tin đến chỉ được trả lời một lần; chỉ theo mã Zalo, không bao giờ nhắn người chưa nhắn trước; mỗi nhịp tối đa một tin;
 * khóa trước khi gọi nên không gửi trùng; lỗi không rõ đã gửi chưa thì chuyển người thật và KHÔNG tự gửi lại. Tin trả lời không tính vào hạn mức khách mỗi
 * ngày (hạn mức đó chỉ đếm khách được nhắn chủ động) nhưng cầu nối có phanh khẩn cấp đếm mọi tin.
 */
@Service
public class ZaloReplySender implements ZaloTickHook {

    private static final Logger log = LoggerFactory.getLogger(ZaloReplySender.class);

    private final ReplyService replies;
    private final ZaloMessageService messages;
    private final AppSettings settings;

    public ZaloReplySender(ReplyService replies, ZaloMessageService messages, AppSettings settings) {
        this.replies = replies;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void tick() {
        try {
            replies.expireStale();
            replies.purgeOld();
        } catch (RuntimeException e) {
            log.warn("Không dọn được việc trả lời: {}", e.getClass().getSimpleName());
        }
        step();
    }

    /** Gửi tối đa MỘT tin. Trả true nếu đã gọi tới bộ nối. */
    boolean step() {
        if (replies.mode() != ReplyService.Mode.AUTO || !messages.enabled() || settings.paused()) {
            return false;
        }
        if (messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.REPLY, null, null).isPresent()) {
            return false;
        }
        Optional<ReplyService.Ready> next = replies.nextReady();
        if (next.isEmpty() || !replies.claim(next.get().id())) {
            return false;
        }
        ReplyService.Ready r = next.get();
        Delivery d;
        try {
            d = messages.sendByUid(ZaloMessageService.SCOPE_REAL, Kind.REPLY, r.id(), r.uid(), r.text(), null, null);
        } catch (RuntimeException e) {
            log.warn("Gửi tin trả lời báo lỗi: {}", e.getClass().getSimpleName());
            replies.failed(r.id(), "LOI_HE_THONG"); // không biết đã gửi chưa: không tự gửi lại
            return true;
        }
        switch (d.status()) {
            case SENT -> replies.sent(r.id());
            case BLOCKED, RETRY -> {
                if (d.certainlyNotSent() && !"NOT_REPLYABLE".equals(d.code())) {
                    replies.restore(r.id()); // chưa chạm Zalo: thử lại nhịp sau
                    return false;
                }
                replies.failed(r.id(), d.code());
            }
            default -> replies.failed(r.id(), d.code() == null ? d.status().name() : d.code());
        }
        return true;
    }
}
