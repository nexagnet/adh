package vn.shop.crm.zalo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nhịp định kỳ của bộ nối. Chỉ tồn tại khi bật {@code app.zalo.auto.enabled}; nấc OFF thì mỗi nhịp không làm gì. */
@Component
@ConditionalOnProperty(prefix = "app.zalo.auto", name = "enabled", havingValue = "true")
public class ZaloAutoScheduler {

    private final ZaloAutoService service;

    public ZaloAutoScheduler(ZaloAutoService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${app.zalo.auto.initial-delay-millis:30000}",
            fixedDelayString = "${app.zalo.auto.tick-millis:30000}")
    void tick() {
        service.tick();
    }
}
