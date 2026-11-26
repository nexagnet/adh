package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "app.zalo.reconcile", name = "enabled", havingValue = "true")
class ZaloFriendReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ZaloFriendReconciliationScheduler.class);
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    private final ZaloAccountRegistry accounts;
    private final ZaloFriendReconciliation reconciliation;
    private final TaskExecutor executor;

    ZaloFriendReconciliationScheduler(ZaloAccountRegistry accounts, ZaloFriendReconciliation reconciliation,
                                      @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.accounts = accounts;
        this.reconciliation = reconciliation;
        this.executor = executor;
    }

    @Scheduled(initialDelayString = "${app.zalo.reconcile.initial-delay-ms:60000}",
            fixedDelayString = "${app.zalo.reconcile.interval-ms:1800000}")
    void tick() {
        for (long account : accounts.active()) {
            if (active.add(account)) {
                try {
                    executor.execute(() -> observe(account));
                } catch (org.springframework.core.task.TaskRejectedException e) {
                    active.remove(account);
                    log.warn("Không xếp được lượt đối chiếu kết bạn account={}", account);
                }
            }
        }
    }

    private void observe(long account) {
        try {
            ZaloAccountContext.run(account, reconciliation::observeOne);
        } catch (RuntimeException e) {
            log.warn("Đối chiếu kết bạn thất bại account={} error={}", account, e.getClass().getSimpleName());
        } finally {
            active.remove(account);
        }
    }
}
