package vn.shop.crm.advisor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Điểm vào duy nhất để nhờ AI đề xuất chiến dịch (D21): kiểm công tắc và trần số lần mỗi ngày, dựng số liệu ẩn danh, gọi AI, kiểm cứng, ghi nhật ký.
 * MẶC ĐỊNH TẮT ({@code app.ai.advisor-enabled}, cần cả {@code app.ai.enabled}). Không tạo chiến dịch, không gửi gì.
 */
@Service
public class AdvisorService {

    public record RunView(long runId, CampaignAdvisor.Result result) {
    }

    private final AdvisorSnapshotService snapshots;
    private final CampaignAdvisor advisor;
    private final AdvisorStore store;
    private final Clock clock;
    private final boolean enabled;
    private final int maxRunsPerDay;

    public AdvisorService(AdvisorSnapshotService snapshots, CampaignAdvisor advisor, AdvisorStore store, Clock clock,
                          @Value("${app.ai.advisor-enabled:false}") boolean enabled,
                          @Value("${app.ai.advisor-max-runs-per-day:5}") int maxRunsPerDay) {
        this.snapshots = snapshots;
        this.advisor = advisor;
        this.store = store;
        this.clock = clock;
        this.enabled = enabled;
        this.maxRunsPerDay = Math.max(1, Math.min(maxRunsPerDay, 20));
    }

    public boolean enabled() {
        return enabled && advisor.aiEnabled();
    }

    public int maxRunsPerDay() {
        return maxRunsPerDay;
    }

    public int runsToday() {
        LocalDate today = clock.instant().atZone(AdvisorSnapshotService.ZONE).toLocalDate();
        return store.runsSince(today.atStartOfDay(AdvisorSnapshotService.ZONE).toInstant());
    }

    /** Nhờ AI đề xuất một lần. Báo lỗi dễ hiểu nếu tính năng tắt hoặc đã hết số lần hôm nay. */
    public RunView run(String by) {
        if (!enabled) {
            throw new ImportException("Tính năng AI đề xuất chiến dịch đang tắt (APP_AI_ADVISOR_ENABLED).");
        }
        if (!advisor.aiEnabled()) {
            throw new ImportException("AI đang tắt (APP_AI_ENABLED), nên chưa thể đề xuất chiến dịch.");
        }
        if (runsToday() >= maxRunsPerDay) {
            throw new ImportException("Hôm nay đã nhờ AI đề xuất " + maxRunsPerDay + " lần, hãy thử lại vào ngày mai.");
        }
        AdvisorSnapshot snapshot = snapshots.build();
        CampaignAdvisor.Result result = advisor.advise(snapshot);
        long id = store.save(by, snapshot.toJson(), result);
        return new RunView(id, result);
    }
}
