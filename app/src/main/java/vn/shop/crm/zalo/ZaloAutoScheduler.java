package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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
        List<Long> active = accounts.active();
        if (active.size() <= 1) {
            active.forEach(id -> runTick(id, null));
            return;
        }
        // Kiểm tra tất cả cầu nối trước khi tài khoản đầu tiên có thể ghi lên Zalo.
        List<AccountStatus> observed = active.stream()
                .map(id -> new AccountStatus(id, probe(id))).toList();
        if (observed.stream().noneMatch(status -> status.bridge() == null)) {
            observed.forEach(status -> runTick(status.account(), status.bridge()));
        }
    }

    private record AccountStatus(long account, ZaloGateway.Status bridge) {
    }

    private ZaloGateway.Status probe(long id) {
        try {
            return ZaloAccountContext.call(id, service::probeBeforeTick);
        } catch (RuntimeException e) {
            log.warn("Không kiểm tra được trạng thái Zalo của tài khoản {}: {}", id, e.getClass().getSimpleName());
            return null;
        }
    }

    private void runTick(long id, ZaloGateway.Status status) {
        try {
            ZaloAccountContext.run(id, () -> tickOne(status));
        } catch (RuntimeException e) {
            log.warn("Bộ nối Zalo tài khoản {} gặp lỗi ở nhịp này: {}", id, e.getClass().getSimpleName());
        }
    }

    private void tickOne(ZaloGateway.Status status) {
        service.tick(status); // nghe kết bạn, thêm tối đa một người vào nhóm
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
