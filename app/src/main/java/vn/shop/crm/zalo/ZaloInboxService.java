package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.inbox.ComebackService;
import vn.shop.crm.inbox.RefusalClassifier;
import vn.shop.crm.inbox.RefusalClassifier.Label;
import vn.shop.crm.inbox.RefusalClassifier.Verdict;
import vn.shop.crm.inbox.RefusalReviewService;
import vn.shop.crm.inbox.RefusalReviewService.Reason;
import vn.shop.crm.inbox.RefusalReviewService.Target;

import java.util.List;
import java.util.Optional;

/**
 * Nhận diện khách từ chối bằng AI (D19, docs/THIET-KE-AI-DOC-TIN-DEN.md mục 3). Mỗi nhịp: hỏi cầu nối tin riêng mới của người đã nhận Tin 1
 * hoặc Tin 2, che dữ liệu, nhờ AI gắn nhãn, rồi:
 * <ul>
 *   <li>TỪ CHỐI và độ chắc từ ngưỡng: ghi sổ từ chối, hủy Tin 2 và việc thêm nhóm, còn nút Hoàn tác (không giữ nội dung tin);</li>
 *   <li>TỪ CHỐI nhưng chưa đủ chắc, AI lỗi, hết hạn mức hay trả sai khuôn: TẠM DỪNG mọi tin cho khách đó và báo mẹ, không bao giờ tự ghi từ chối;</li>
 *   <li>nhãn khác (muốn mua, khiếu nại, hỏi...): không làm gì, không lưu gì (các việc "AI gợi ý, mẹ quyết" chưa làm).</li>
 * </ul>
 * Hệ thống KHÔNG trả lời khách. Chỉ chạy khi bật đủ ba cờ (Adh, cầu nối, AI), nấc không phải OFF, cầu dao đóng và cầu nối đang nghe đúng tài
 * khoản. Nội dung tin, mã Zalo và số điện thoại không vào log.
 */
@Service
public class ZaloInboxService implements ZaloTickHook {

    private static final Logger log = LoggerFactory.getLogger(ZaloInboxService.class);
    public static final String A_INBOX = "TIN_RIENG";
    static final String CURSOR = "zalo.inbox.cursor";
    /** Số tin tối đa xử lý mỗi nhịp, để một đợt tin dồn lại không chiếm cả nhịp hay làm cạn hạn mức AI trong một lần. */
    static final int PER_TICK = 20;

    private final ZaloInboxProperties props;
    private final ZaloInboxReader reader;
    private final ZaloGateway gateway;
    private final ZaloAutoSettings cfg;
    private final AppSettings settings;
    private final RefusalClassifier classifier;
    private final RefusalReviewService reviews;
    private final ComebackService comebacks;

    public ZaloInboxService(ZaloInboxProperties props, ZaloInboxReader reader, ZaloGateway gateway, ZaloAutoSettings cfg,
                            AppSettings settings, RefusalClassifier classifier, RefusalReviewService reviews,
                            ComebackService comebacks) {
        this.props = props;
        this.reader = reader;
        this.gateway = gateway;
        this.cfg = cfg;
        this.settings = settings;
        this.classifier = classifier;
        this.reviews = reviews;
        this.comebacks = comebacks;
    }

    public boolean running() {
        return props.enabled() && classifier.aiEnabled();
    }

    public double threshold() {
        return props.threshold();
    }

    public double comebackThreshold() {
        return props.comebackThreshold();
    }

    @Override
    public void tick() {
        try {
            reviews.purgeOld();
            comebacks.purgeOld();
        } catch (RuntimeException e) {
            log.warn("Không dọn được trích đoạn cũ: {}", e.getClass().getSimpleName());
        }
        poll();
    }

    /** Trả số tin đã xử lý. */
    public int poll() {
        if (!running() || !cfg.effectiveMode().listens() || cfg.tripped()) {
            return 0;
        }
        ZaloGateway.Status st = gateway.status();
        if (st.state() != ZaloGateway.State.LISTENING || !Boolean.TRUE.equals(st.accountOk())) {
            return 0;
        }
        long cursor = cursor();
        int handled = 0;
        for (ZaloInboxReader.Inbound m : reader.inbound(cursor)) {
            if (m.seq() <= cursor) {
                continue;
            }
            if (handled >= PER_TICK) {
                break;
            }
            try {
                process(m);
            } catch (RuntimeException e) {
                // Chưa ghi nhận được: giữ nguyên con trỏ để nhịp sau thử lại (cầu nối giữ tin tối đa 6 giờ).
                log.warn("Không xử lý được một tin riêng: {}", e.getClass().getSimpleName());
                break;
            }
            cursor = m.seq();
            settings.set(CURSOR, Long.toString(cursor), "system");
            handled++;
        }
        return handled;
    }

    private long cursor() {
        return settings.get(CURSOR).map(v -> {
            try {
                return Long.parseLong(v.strip());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    void process(ZaloInboxReader.Inbound m) {
        Optional<Target> target = reviews.targetFor(m.uid());
        if (target.isEmpty()) {
            comeback(m);
            return;
        }
        Target t = target.get();
        String text = RefusalClassifier.prepare(m.text());
        if (text.isBlank()) {
            return;
        }
        Verdict v = classifier.classify(text);
        switch (v.outcome()) {
            case UNAVAILABLE -> hold(t, Reason.AI_LOI, null, null, text);
            case INVALID -> hold(t, Reason.AI_SAI_KHUON, null, null, text);
            case OK -> {
                if (v.label() != Label.TU_CHOI) {
                    cfg.log(A_INBOX, t.invitationId(), null, "AI_BO_QUA", null, "system");
                } else if (v.confidence() >= props.threshold()) {
                    reviews.autoRecord(t, v.label().name(), v.confidence());
                    cfg.log(A_INBOX, t.invitationId(), null, "AI_GHI_TU_CHOI", null, "system");
                } else {
                    hold(t, Reason.AI_KHONG_CHAC, v.label().name(), v.confidence(), text);
                }
            }
        }
    }

    /**
     * Người không còn lời mời đang chạy: có thể là khách TỪNG TỪ CHỐI tự nhắn lại. Chỉ xử lý khi phần khách quay lại không tắt và cờ từ chối còn
     * hiệu lực; còn lại bỏ qua (không gọi AI). Hệ thống không trả lời ở đây; tin mẫu (nếu có) do {@code ZaloWelcomeSender} gửi sau.
     */
    private void comeback(ZaloInboxReader.Inbound m) {
        if (comebacks.mode() == ComebackService.Mode.OFF) {
            cfg.log(A_INBOX, null, null, "KHONG_PHAI_KHACH", null, "system");
            return;
        }
        Optional<ComebackService.Candidate> c = comebacks.candidateFor(m.uid());
        if (c.isEmpty()) {
            cfg.log(A_INBOX, null, null, "KHONG_PHAI_KHACH", null, "system");
            return;
        }
        String text = RefusalClassifier.prepare(m.text());
        if (text.isBlank()) {
            return;
        }
        Verdict v = classifier.classify(text);
        ComebackService.Result r = comebacks.handle(c.get(), v, text, cfg.effectiveMode().adds(), props.comebackThreshold());
        cfg.log(A_INBOX, c.get().invitationId(), null, "QUAY_LAI_" + r.name(), null, "system");
    }

    private void hold(Target t, Reason reason, String label, Double confidence, String text) {
        reviews.hold(t, reason, label, confidence, text);
        cfg.log(A_INBOX, t.invitationId(), null, "AI_TAM_DUNG", reason.name(), "system");
    }

    /** Dùng cho trang quản trị. */
    public List<Reason> reasons() {
        return List.of(Reason.values());
    }
}
