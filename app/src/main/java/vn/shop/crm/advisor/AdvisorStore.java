package vn.shop.crm.advisor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/** Ghi nhật ký mỗi lần nhờ AI đề xuất và các đề xuất đạt kiểm cứng (V36). Không có dữ liệu cá nhân. */
@Component
class AdvisorStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Chữ AI trả về lưu tối đa chừng này ký tự (khớp CHECK của bảng). */
    private static final int MAX_OUTPUT = 20000;

    private final JdbcClient jdbc;

    AdvisorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Submission(long id, boolean started) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Submission reserve(String by) {
        jdbc.sql("LOCK TABLE advisor_run IN SHARE ROW EXCLUSIVE MODE").update();
        Optional<Long> active = active();
        if (active.isPresent()) {
            return new Submission(active.get(), false);
        }
        long id = jdbc.sql("""
                        INSERT INTO advisor_run (created_by, status, snapshot_json)
                        VALUES (:by, 'RUNNING', '{}') RETURNING id""")
                .param("by", by).query(Long.class).single();
        return new Submission(id, true);
    }

    Optional<Long> active() {
        expire();
        return jdbc.sql("SELECT id FROM advisor_run WHERE status = 'RUNNING'").query(Long.class).optional();
    }

    Optional<Long> latest() {
        return jdbc.sql("SELECT id FROM advisor_run ORDER BY id DESC LIMIT 1").query(Long.class).optional();
    }

    void expire() {
        jdbc.sql("""
                UPDATE advisor_run SET status = 'UNAVAILABLE', detail = 'Lượt đề xuất quá thời gian xử lý. Vui lòng thử lại.'
                WHERE status = 'RUNNING' AND created_at <= now() - interval '5 minutes'""").update();
    }

    @Transactional
    void complete(long runId, String snapshot, CampaignAdvisor.Result r) {
        String out = r.rawOutput() == null ? null : r.rawOutput().length() <= MAX_OUTPUT ? r.rawOutput() : r.rawOutput().substring(0, MAX_OUTPUT);
        int updated = jdbc.sql("""
                        UPDATE advisor_run SET status = :status, ai_output = :out, skip_reason = :skip,
                            detail = :detail, rejected = CAST(:rej AS jsonb), snapshot_json = :snapshot
                        WHERE id = :id AND status = 'RUNNING'""")
                .param("id", runId).param("status", r.status().name()).param("snapshot", snapshot)
                .param("out", out, java.sql.Types.VARCHAR).param("skip", r.skipReason(), java.sql.Types.VARCHAR)
                .param("detail", r.detail(), java.sql.Types.VARCHAR).param("rej", JSON.writeValueAsString(r.rejected()))
                .update();
        if (updated == 0) {
            return;
        }
        for (AdvisorProposal p : r.proposals()) {
            jdbc.sql("""
                            INSERT INTO campaign_proposal (run_id, title, area, batch_size, start_on, end_on, window_from, window_to,
                                daily_cap, message_angle, reasons, risks, confidence, confidence_capped,
                                weekdays, pick_order, min_days, max_days, product_group, filter_json)
                            VALUES (:run, :title, :area, :batch, :start, :end, :wf, :wt, :cap, :angle,
                                CAST(:reasons AS jsonb), CAST(:risks AS jsonb), :conf, :capped,
                                  :weekdays, :pick, :minDays, :maxDays, :product, CAST(:filter AS jsonb))""")
                    .param("run", runId).param("title", p.title()).param("area", p.area()).param("batch", p.batchSize())
                    .param("start", p.startOn()).param("end", p.endOn()).param("wf", p.windowFrom()).param("wt", p.windowTo())
                    .param("cap", p.dailyCap()).param("angle", p.messageAngle())
                    .param("reasons", JSON.writeValueAsString(p.reasons())).param("risks", JSON.writeValueAsString(p.risks()))
                    .param("conf", p.confidence()).param("capped", p.confidenceCapped())
                    .param("weekdays", p.weekdays(), java.sql.Types.SMALLINT).param("pick", p.pickOrder())
                    .param("minDays", p.minDays(), java.sql.Types.INTEGER).param("maxDays", p.maxDays(), java.sql.Types.INTEGER).param("product", p.productGroup(), java.sql.Types.VARCHAR)
                    .param("filter", p.filter().any() ? p.filter().toJson() : null, java.sql.Types.VARCHAR).update();
        }
    }

}
