package vn.shop.crm.advisor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

/**
 * Điểm vào để nhờ AI đề xuất chiến dịch (D21): giữ một lượt đang chạy, dựng số liệu ẩn danh, gọi AI, kiểm cứng, ghi nhật ký.
 * MẶC ĐỊNH TẮT ({@code app.ai.advisor-enabled}, cần cả {@code app.ai.enabled}). Không tạo chiến dịch, không gửi gì.
 */
@Service
public class AdvisorService {

    public record RunView(long runId, CampaignAdvisor.Result result) {
    }

    private final AdvisorSnapshotService snapshots;
    private final CampaignAdvisor advisor;
    private final AdvisorStore store;
    private final ContactService contacts;
    private final boolean enabled;

    public AdvisorService(AdvisorSnapshotService snapshots, CampaignAdvisor advisor, AdvisorStore store, ContactService contacts,
                          @Value("${app.ai.advisor-enabled:false}") boolean enabled) {
        this.snapshots = snapshots;
        this.advisor = advisor;
        this.store = store;
        this.contacts = contacts;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled && advisor.aiEnabled();
    }

    AdvisorStore.Submission reserve(String by) {
        if (!enabled) {
            throw new ImportException("Tính năng AI đề xuất chiến dịch đang tắt (APP_AI_ADVISOR_ENABLED).");
        }
        if (!advisor.aiEnabled()) {
            throw new ImportException("AI đang tắt (APP_AI_ENABLED), nên chưa thể đề xuất chiến dịch.");
        }
        return store.reserve(by);
    }

    RunView run(String by) {
        var submission = reserve(by);
        if (!submission.started()) {
            throw new ImportException("Đang xử lý một lượt đề xuất.");
        }
        return process(submission.id());
    }

    RunView process(long id) {
        String snapshotJson = "{}";
        try {
            if (store.active().filter(active -> active == id).isEmpty()) {
                return fail(id, snapshotJson);
            }
            AdvisorSnapshot snapshot = snapshots.build();
            snapshotJson = snapshot.toJson();
            var result = checkMatches(advisor.advise(snapshot));
            store.complete(id, snapshotJson, result);
            return new RunView(id, result);
        } catch (RuntimeException e) {
            LOG.warn("Lượt đề xuất AI {} gặp lỗi {}", id, e.getClass().getSimpleName());
            return fail(id, snapshotJson);
        }
    }

    RunView fail(long id, String snapshotJson) {
        var result = new CampaignAdvisor.Result(CampaignAdvisor.Status.UNAVAILABLE, java.util.List.of(),
                java.util.List.of(), null, "Không thể xử lý lượt đề xuất. Vui lòng thử lại.", null);
        store.complete(id, snapshotJson, result);
        return new RunView(id, result);
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdvisorService.class);

    /**
     * Đối chiếu mọi đề xuất với số khách đủ điều kiện hiện tại. Bỏ đề xuất không còn khách và giảm lô nếu số khách ít hơn đề xuất.
     */
    CampaignAdvisor.Result checkMatches(CampaignAdvisor.Result r) {
        if (r.proposals().isEmpty()) {
            return r;
        }
        java.util.List<AdvisorProposal> kept = new java.util.ArrayList<>();
        java.util.List<String> rejected = new java.util.ArrayList<>(r.rejected());
        int i = 0;
        for (AdvisorProposal p : r.proposals()) {
            i++;
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
