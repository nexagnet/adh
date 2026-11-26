package vn.shop.crm.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/** Dọn nội dung hết hạn trong DB, kể cả khi Zalo hoặc AI đang tắt. */
@Component
public class InboxRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(InboxRetentionScheduler.class);

    private final RefusalReviewService reviews;
    private final ComebackService comebacks;
    private final WarrantyService warranty;
    private final ReplyService replies;

    public InboxRetentionScheduler(RefusalReviewService reviews, ComebackService comebacks,
                                   WarrantyService warranty, ReplyService replies) {
        this.reviews = reviews;
        this.comebacks = comebacks;
        this.warranty = warranty;
        this.replies = replies;
    }

    @Scheduled(initialDelayString = "${app.privacy.retention.initial-delay-millis:60000}",
            fixedDelayString = "${app.privacy.retention.delay-millis:60000}")
    public void purge() {
        purge("yêu cầu từ chối", reviews::purgeOld);
        purge("khách quay lại", comebacks::purgeOld);
        purge("việc bảo hành", warranty::purgeOld);
        purge("nháp trả lời", replies::purgeOld);
    }

    private void purge(String label, IntSupplier operation) {
        try {
            operation.getAsInt();
        } catch (RuntimeException e) {
            log.warn("Không dọn được nội dung cũ của {}: {}", label, e.getClass().getSimpleName());
        }
    }
}
