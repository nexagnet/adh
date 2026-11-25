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
    private final ZaloAccountRegistry accounts;

    public ZaloAutoScheduler(ZaloAutoService service, ZaloInviteSender sender, ObjectProvider<ZaloTickHook> hooks,
                             ZaloAccountRegistry accounts) {
        this.service = service;
        this.sender = sender;
        this.hooks = hooks;
        this.accounts = accounts;
    }

    @Scheduled(initialDelayString = "${app.zalo.auto.initial-delay-millis:30000}",
            fixedDelayString = "${app.zalo.auto.tick-millis:30000}")
    void tick() {
        // D23: mỗi nhịp chạy MỘT lần cho từng tài khoản đang chạy, lần lượt (không song song trong cùng nhịp), mỗi lần trong bối cảnh riêng của
        // tài khoản đó; lỗi ở một tài khoản không làm tài khoản khác bỏ nhịp. Chưa bật nhiều tài khoản thì chỉ có tài khoản 1, y như trước.
        for (long id : accounts.active()) {
            try {
                ZaloAccountContext.run(id, this::tickOne);
            } catch (RuntimeException e) {
                log.warn("Bộ nối Zalo tài khoản {} gặp lỗi ở nhịp này: {}", id, e.getClass().getSimpleName());
            }
        }
    }

    private void tickOne() {
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
