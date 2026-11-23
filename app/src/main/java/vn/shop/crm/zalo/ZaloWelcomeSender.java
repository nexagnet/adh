package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.inbox.ComebackService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.zalo.ZaloMessageService.Delivery;
import vn.shop.crm.zalo.ZaloMessenger.Kind;

import java.util.Optional;

/**
 * Gửi MỘT tin mẫu CỐ ĐỊNH (loại tin thứ ba, {@link Kind#WELCOME}) cho khách từng từ chối mà vừa TỰ nhắn tới muốn mua và đã được gỡ cờ (D19,
 * docs/THIET-KE-KHACH-QUAY-LAI.md). Không do AI viết; AI chỉ gắn nhãn. Chỉ ở nấc TỰ ĐỘNG của phần khách quay lại.
 *
 * <p>Chốt chặn: đi qua đúng {@link ZaloMessageService} như mọi tin (công tắc, nấc, khung giờ, hạn mức ngày và khoảng nghỉ dùng chung, cầu dao);
 * cầu nối chỉ cho trả lời người vừa nhắn trong 24 giờ và MỖI tin đến chỉ được trả lời một lần; mỗi nhịp tối đa một tin; khóa trước khi gọi nên
 * không gửi trùng; lỗi không rõ đã gửi chưa thì đánh dấu thất bại và KHÔNG tự gửi lại.
 */
@Service
public class ZaloWelcomeSender implements ZaloTickHook {

    private static final Logger log = LoggerFactory.getLogger(ZaloWelcomeSender.class);
    static final String CHANNEL_LOG = "WELCOME";

    /** Tin mẫu cố định. Nói rõ là trợ lý tự động, không giả làm chủ shop, và cho khách đường từ chối. */
    static final String TEMPLATE = "Chào anh/chị, em là trợ lý tự động của {cua_hang} ạ. Em thấy anh/chị nhắn muốn mua pin nên em đã báo chủ shop, "
            + "và sẽ thêm anh/chị vào nhóm Zalo khách hàng để mua giá ưu đãi. Chủ shop sẽ nhắn anh/chị sớm ạ. "
            + "Nếu anh/chị không muốn, cứ nhắn \"đừng nhắn nữa\", em sẽ không nhắn lại ạ.";

    private final ComebackService comebacks;
    private final ZaloMessageService messages;
    private final InviteService invites;
    private final AppSettings settings;

    public ZaloWelcomeSender(ComebackService comebacks, ZaloMessageService messages, InviteService invites, AppSettings settings) {
        this.comebacks = comebacks;
        this.messages = messages;
        this.invites = invites;
        this.settings = settings;
    }

    public String text() {
        return TEMPLATE.replace("{cua_hang}", invites.shopName());
    }

    @Override
    public void tick() {
        step();
    }

    /** Gửi tối đa MỘT tin. Trả true nếu đã gọi tới bộ nối. */
    boolean step() {
        if (comebacks.mode() != ComebackService.Mode.AUTO || !messages.enabled() || settings.paused()) {
            return false;
        }
        if (messages.blocker(ZaloMessageService.SCOPE_REAL, null, null).isPresent()) {
            return false;
        }
        Optional<ComebackService.Welcome> next = comebacks.nextWelcome();
        if (next.isEmpty() || !comebacks.claimWelcome(next.get().id())) {
            return false;
        }
        ComebackService.Welcome w = next.get();
        Delivery d;
        try {
            d = messages.sendByUid(ZaloMessageService.SCOPE_REAL, Kind.WELCOME, w.id(), w.uid(), text(), null, null);
        } catch (RuntimeException e) {
            log.warn("Gửi tin mẫu báo lỗi: {}", e.getClass().getSimpleName());
            comebacks.finishWelcome(w.id(), "FAILED", "LOI_HE_THONG"); // không biết đã gửi chưa: không tự gửi lại
            return true;
        }
        switch (d.status()) {
            case SENT -> comebacks.finishWelcome(w.id(), "SENT", null);
            case BLOCKED, RETRY -> {
                if (d.certainlyNotSent() && !"NOT_REPLYABLE".equals(d.code())) {
                    comebacks.restoreWelcome(w.id()); // chưa chạm Zalo: thử lại nhịp sau
                    return false;
                }
                comebacks.finishWelcome(w.id(), "FAILED", d.code());
            }
            default -> comebacks.finishWelcome(w.id(), "FAILED", d.code() == null ? d.status().name() : d.code());
        }
        return true;
    }
}
