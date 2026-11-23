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
import vn.shop.crm.inbox.ReplyService;
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
 *   <li>nhãn khác (muốn mua, khiếu nại, hỏi...): giao cho phần trả lời của trợ lý ({@link ReplyService}, D20; tắt mặc định, mode TẮT thì không làm gì).</li>
 * </ul>
 * Phần này KHÔNG gửi gì cho khách (chỉ ghi nhận việc; tin trả lời do {@code ZaloReplySender} gửi riêng, D20). Chỉ chạy khi bật đủ ba cờ (Adh, cầu nối, AI), nấc không phải OFF, cầu dao đóng và cầu nối đang nghe đúng tài
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
    private final ReplyService replies;

    public ZaloInboxService(ZaloInboxProperties props, ZaloInboxReader reader, ZaloGateway gateway, ZaloAutoSettings cfg,
                            AppSettings settings, RefusalClassifier classifier, RefusalReviewService reviews,
                            ComebackService comebacks, ReplyService replies) {
        this.props = props;
        this.reader = reader;
        this.gateway = gateway;
        this.cfg = cfg;
        this.settings = settings;
        this.classifier = classifier;
        this.reviews = reviews;
        this.comebacks = comebacks;
        this.replies = replies;
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
            // việc trả lời khách do ZaloReplySender dọn; ở đây chỉ nhận tin
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
            if (!comeback(m) && !joined(m)) {
                cfg.log(A_INBOX, null, null, "KHONG_PHAI_KHACH", null, "system");
            }
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
                    answer(new ReplyService.Subject(t.customerId(), t.invitationId(), m.uid()), v, text, m);
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
    private boolean comeback(ZaloInboxReader.Inbound m) {
        if (comebacks.mode() == ComebackService.Mode.OFF) {
            return false;
        }
        Optional<ComebackService.Candidate> c = comebacks.candidateFor(m.uid());
        if (c.isEmpty()) {
            return false;
        }
        String text = RefusalClassifier.prepare(m.text());
        if (text.isBlank()) {
            return true;
        }
        Verdict v = classifier.classify(text);
        ComebackService.Result r = comebacks.handle(c.get(), v, text, cfg.effectiveMode().adds(), props.comebackThreshold());
        cfg.log(A_INBOX, c.get().invitationId(), null, "QUAY_LAI_" + r.name(), null, "system");
        return true;
    }

    /**
     * Khách ĐÃ vào nhóm (lời mời JOINED, không có cờ từ chối) nhắn riêng: chỉ khi trợ lý trả lời không TẮT. AI gắn nhãn, rồi phần trả lời quyết
     * (trả lời trong phạm vi hẹp hoặc chuyển người thật). Khách từng từ chối không bao giờ vào đường này. Mode TẮT thì không gọi AI.
     */
    private boolean joined(ZaloInboxReader.Inbound m) {
        if (replies.mode() == ReplyService.Mode.OFF) {
            return false;
        }
        Optional<ReplyService.Subject> s = replies.joinedSubject(m.uid());
        if (s.isEmpty()) {
            return false;
        }
        String text = RefusalClassifier.prepare(m.text());
        if (!text.isBlank()) {
            answer(s.get(), classifier.classify(text), text, m);
        }
        return true;
    }

    /** Việc trả lời lỗi không được làm kẹt con trỏ tin đến hay phần nhận diện từ chối: ghi log và bỏ qua tin này. */
    private void answer(ReplyService.Subject s, Verdict v, String text, ZaloInboxReader.Inbound m) {
        try {
            ReplyService.Result r = replies.handle(s, v, text, m.at());
            cfg.log(A_INBOX, s.invitationId(), null, "TRA_LOI_" + r.name(), null, "system");
        } catch (RuntimeException e) {
            log.warn("Không xử lý được việc trả lời một tin riêng: {}", e.getClass().getSimpleName());
        }
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
