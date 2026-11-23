package vn.shop.crm.advisor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Ghi nhật ký mỗi lần nhờ AI đề xuất và các đề xuất đạt kiểm cứng (V34). Không có dữ liệu cá nhân. */
@Component
class AdvisorStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Chữ AI trả về lưu tối đa chừng này ký tự (khớp CHECK của bảng). */
    private static final int MAX_OUTPUT = 20000;

    private final JdbcClient jdbc;

    AdvisorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    long save(String by, String snapshotJson, CampaignAdvisor.Result r) {
        String out = r.rawOutput() == null ? null : r.rawOutput().length() <= MAX_OUTPUT ? r.rawOutput() : r.rawOutput().substring(0, MAX_OUTPUT);
        long runId = jdbc.sql("""
                        INSERT INTO advisor_run (created_by, status, snapshot_json, ai_output, skip_reason, detail, rejected)
                        VALUES (:by, :status, :snap, :out, :skip, :detail, CAST(:rej AS jsonb)) RETURNING id""")
                .param("by", by).param("status", r.status().name()).param("snap", snapshotJson)
                .param("out", out, java.sql.Types.VARCHAR).param("skip", r.skipReason(), java.sql.Types.VARCHAR)
                .param("detail", r.detail(), java.sql.Types.VARCHAR).param("rej", JSON.writeValueAsString(r.rejected()))
                .query(Long.class).single();
        for (AdvisorProposal p : r.proposals()) {
            jdbc.sql("""
                            INSERT INTO campaign_proposal (run_id, title, area, batch_size, start_on, end_on, window_from, window_to,
                                daily_cap, message_angle, reasons, risks, confidence, confidence_capped)
                            VALUES (:run, :title, :area, :batch, :start, :end, :wf, :wt, :cap, :angle,
                                CAST(:reasons AS jsonb), CAST(:risks AS jsonb), :conf, :capped)""")
                    .param("run", runId).param("title", p.title()).param("area", p.area()).param("batch", p.batchSize())
                    .param("start", p.startOn()).param("end", p.endOn()).param("wf", p.windowFrom()).param("wt", p.windowTo())
                    .param("cap", p.dailyCap()).param("angle", p.messageAngle())
                    .param("reasons", JSON.writeValueAsString(p.reasons())).param("risks", JSON.writeValueAsString(p.risks()))
                    .param("conf", p.confidence()).param("capped", p.confidenceCapped()).update();
        }
        return runId;
    }

    int runsSince(Instant since) {
        return jdbc.sql("SELECT count(*) FROM advisor_run WHERE created_at >= :s")
                .param("s", OffsetDateTime.ofInstant(since, ZoneOffset.UTC)).query(Integer.class).single();
    }
}
