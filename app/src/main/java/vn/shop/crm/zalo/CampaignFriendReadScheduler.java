package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "app.zalo.reconcile", name = "enabled", havingValue = "true")
class CampaignFriendReadScheduler {
    private static final Logger log = LoggerFactory.getLogger(CampaignFriendReadScheduler.class);
    private final Set<Long> active = ConcurrentHashMap.newKeySet();

    private final CampaignFriendReconciliation jobs;
    private final TaskExecutor executor;

    CampaignFriendReadScheduler(CampaignFriendReconciliation jobs,
                                @Qualifier("applicationTaskExecutor") TaskExecutor executor) {

        this.jobs = jobs;
        this.executor = executor;
    }

    @Scheduled(initialDelayString = "${app.zalo.reconcile.manual-initial-delay-ms:10000}", fixedDelay = 4000)
    void tick() {
        for (long account : jobs.dueAccounts()) {
            if (!active.add(account)) {
                continue;
            }
            try {
                executor.execute(() -> work(account));
            } catch (TaskRejectedException e) {
                active.remove(account);
                log.warn("Không xếp được lượt đối chiếu chiến dịch account={}", account);
            }
        }
    }

    private void work(long account) {
        try {
            jobs.work(account);
        } catch (RuntimeException e) {
            log.warn("Đối chiếu chiến dịch thất bại account={} error={}", account, e.getClass().getSimpleName());
        } finally {
            active.remove(account);
        }
    }
}
