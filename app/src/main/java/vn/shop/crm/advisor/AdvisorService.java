package vn.shop.crm.advisor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

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
    private final ContactService contacts;
    private final boolean enabled;
    private final int maxRunsPerDay;

    public AdvisorService(AdvisorSnapshotService snapshots, CampaignAdvisor advisor, AdvisorStore store, Clock clock, ContactService contacts,
                          @Value("${app.ai.advisor-enabled:false}") boolean enabled,
                          @Value("${app.ai.advisor-max-runs-per-day:5}") int maxRunsPerDay) {
        this.snapshots = snapshots;
        this.advisor = advisor;
        this.store = store;
        this.clock = clock;
        this.contacts = contacts;
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
        CampaignAdvisor.Result result = checkMatches(advisor.advise(snapshot));
        long id = store.save(by, snapshot.toJson(), result);
        return new RunView(id, result);
    }

    /**
     * Đối chiếu từng đề xuất có bộ lọc với số khách khớp THẬT (không tin số AI nghĩ): không khớp ai thì bỏ đề xuất và ghi lý do; khớp ít hơn lô thì kẹp lô
     * xuống bằng số khớp (và nói rõ ở phần rủi ro). Chỉ đếm, không ghi gì. Đề xuất không có bộ lọc mới (chỉ các núm cũ) giữ nguyên như trước.
     */
    CampaignAdvisor.Result checkMatches(CampaignAdvisor.Result r) {
        if (r.proposals().stream().noneMatch(p -> p.filter().any())) {
            return r;
        }
        java.util.List<AdvisorProposal> kept = new java.util.ArrayList<>();
        java.util.List<String> rejected = new java.util.ArrayList<>(r.rejected());
        int i = 0;
        for (AdvisorProposal p : r.proposals()) {
            i++;
            if (!p.filter().any()) {
                kept.add(p);
                continue;
            }
            long matched;
            try {
                var full = vn.shop.crm.contact.CustomerFilter.and(new ContactService.Pick(p.pickOrder(), p.minDays(), p.maxDays(),
                        p.productGroup() == null ? null : vn.shop.crm.contact.ProductGroup.valueOf(p.productGroup())).toFilter(), p.filter());
                matched = contacts.countEligibleNotInvited(p.area(), full);
            } catch (ImportException e) {
                rejected.add("Đề xuất " + i + ": bộ lọc không dùng được (" + e.getMessage() + ")");
                continue;
            }
            if (matched < 1) {
                rejected.add("Đề xuất " + i + ": bộ lọc không khớp khách nào ở khu vực này");
                continue;
            }
            if (matched < p.batchSize()) {
                java.util.List<String> risks = new java.util.ArrayList<>(p.risks());
                risks.add("Bộ lọc chỉ khớp " + matched + " khách nên lô giảm còn " + matched + ".");
                kept.add(new AdvisorProposal(p.title(), p.area(), (int) matched, p.startOn(), p.endOn(), p.windowFrom(), p.windowTo(), p.dailyCap(),
                        p.messageAngle(), p.reasons(), java.util.List.copyOf(risks), p.confidence(), p.confidenceCapped(), p.weekdays(), p.pickOrder(),
                        p.minDays(), p.maxDays(), p.productGroup(), p.filter()));
            } else {
                kept.add(p);
            }
        }
        if (kept.isEmpty()) {
            return new CampaignAdvisor.Result(CampaignAdvisor.Status.INVALID, java.util.List.of(), rejected, r.skipReason(),
                    "Mọi đề xuất đều không đạt kiểm tra.", r.rawOutput());
        }
        return new CampaignAdvisor.Result(r.status(), java.util.List.copyOf(kept), rejected, r.skipReason(), r.detail(), r.rawOutput());
    }
}
