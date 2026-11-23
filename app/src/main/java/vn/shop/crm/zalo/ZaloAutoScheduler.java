package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nhịp định kỳ của bộ nối. Chỉ tồn tại khi bật {@code app.zalo.auto.enabled}; nấc OFF thì mỗi nhịp không làm gì. */
@Component
@ConditionalOnProperty(prefix = "app.zalo.auto", name = "enabled", havingValue = "true")
public class ZaloAutoScheduler {

    private static final Logger log = LoggerFactory.getLogger(ZaloAutoScheduler.class);

    private final ZaloAutoService service;
    private final ZaloInviteSender sender;
    private final ObjectProvider<ZaloTickHook> hooks;

    public ZaloAutoScheduler(ZaloAutoService service, ZaloInviteSender sender, ObjectProvider<ZaloTickHook> hooks) {
        this.service = service;
        this.sender = sender;
        this.hooks = hooks;
    }

    @Scheduled(initialDelayString = "${app.zalo.auto.initial-delay-millis:30000}",
            fixedDelayString = "${app.zalo.auto.tick-millis:30000}")
    void tick() {
        service.tick(); // nghe kết bạn, thêm tối đa một người vào nhóm
        try {
            sender.step(); // tự gửi tối đa một tin của chiến dịch thật (tắt thì không làm gì)
        } catch (RuntimeException e) {
            log.warn("Bộ nối Zalo gặp lỗi khi tự gửi tin: {}", e.getClass().getSimpleName());
        }
        hooks.orderedStream().forEach(h -> {
            try {
                h.tick();
            } catch (RuntimeException e) {
                log.warn("Bước chạy thử gặp lỗi: {}", e.getClass().getSimpleName());
            }
        });
    }
}
