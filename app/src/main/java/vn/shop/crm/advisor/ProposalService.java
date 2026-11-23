package vn.shop.crm.advisor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.GeoArea;
import vn.shop.crm.invite.InviteService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Xem, duyệt và bỏ qua đề xuất của AI (D21, PR 3). Duyệt chỉ tạo chiến dịch bằng đường HIỆN CÓ ({@link InviteService#createCampaign}):
 * chiến dịch CHƯA duyệt tự gửi, nên chưa có tin nào đi cho ai; người dùng vẫn phải vào chiến dịch bấm duyệt tự gửi như mọi chiến dịch khác.
 * Nội dung tin gửi khách luôn là mẫu tin mặc định của cửa hàng: chữ của AI ({@code message_angle}) chỉ để người dùng đọc, không bao giờ vào tin.
 */
@Service
public class ProposalService {

    static final int EXPIRES_DAYS = 14;
    static final int MAX_NOTE = 300;
    private static final int MAX_NAME = 100;
    private static final int RECENT_DECIDED = 20;
    private static final int RECENT_RUNS = 10;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    public record Proposal(long id, long runId, String title, String area, String areaLabel, int batchSize, LocalDate startOn,
                    LocalDate endOn, LocalTime windowFrom, LocalTime windowTo, int dailyCap, String messageAngle,
                    List<String> reasons, List<String> risks, double confidence, boolean confidenceCapped, String state,
                    String decidedBy, LocalDateTime decidedAt, String decisionNote, Long campaignId, LocalDateTime createdAt) {
        public boolean open() {
            return "PROPOSED".equals(state);
        }

        public int confidencePercent() {
            return (int) Math.round(confidence * 100);
        }

        public String stateLabel() {
            return switch (state) {
                case "PROPOSED" -> "Chờ bạn quyết định";
                case "APPROVED" -> "Đã duyệt, đã tạo chiến dịch";
                case "REJECTED" -> "Đã bỏ qua";
                default -> "Quá hạn (ngày bắt đầu đã qua)";
            };
        }
    }

    public record Run(long id, LocalDateTime createdAt, String createdBy, String status, String skipReason, String detail,
               List<String> rejected, int proposals) {
        public String statusLabel() {
            return switch (status) {
                case "OK" -> "Có đề xuất";
                case "SKIPPED" -> "AI không đề xuất";
                case "INVALID" -> "Đề xuất không đạt kiểm tra";
                default -> "Không gọi được AI";
            };
        }
    }

    public record RunDetail(Run run, String snapshotJson, String aiOutput) {
    }

    private final JdbcClient jdbc;
    private final ContactService contacts;
    private final InviteService invites;
    private final Clock clock;

    ProposalService(JdbcClient jdbc, ContactService contacts, InviteService invites, Clock clock) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.invites = invites;
        this.clock = clock;
    }

    private LocalDate today() {
        return clock.instant().atZone(AdvisorSnapshotService.ZONE).toLocalDate();
    }

    /** Đề xuất mà ngày bắt đầu đã qua thì thành EXPIRED (không còn hợp thời điểm). */
    @Transactional
    public int expireOld() {
        return jdbc.sql("UPDATE campaign_proposal SET state = 'EXPIRED' WHERE state = 'PROPOSED' AND start_on < :today")
                .param("today", today()).update();
    }

    @Transactional
    public List<Proposal> open() {
        expireOld();
        return jdbc.sql(SELECT + " WHERE p.state = 'PROPOSED' ORDER BY p.run_id DESC, p.id").query(this::map).list();
    }

    public List<Proposal> decided() {
        return jdbc.sql(SELECT + " WHERE p.state <> 'PROPOSED' ORDER BY coalesce(p.decided_at, r.created_at) DESC, p.id LIMIT " + RECENT_DECIDED)
                .query(this::map).list();
    }

    public List<Run> runs() {
        return jdbc.sql("""
                        SELECT r.id, r.created_at, r.created_by, r.status, r.skip_reason, r.detail, r.rejected::text AS rejected,
                               (SELECT count(*) FROM campaign_proposal p WHERE p.run_id = r.id) AS proposals
                        FROM advisor_run r ORDER BY r.id DESC LIMIT""" + " " + RECENT_RUNS)
                .query(this::mapRun).list();
    }

    public Optional<RunDetail> run(long id) {
        return jdbc.sql("""
                        SELECT r.id, r.created_at, r.created_by, r.status, r.skip_reason, r.detail, r.rejected::text AS rejected,
                               (SELECT count(*) FROM campaign_proposal p WHERE p.run_id = r.id) AS proposals,
                               r.snapshot_json, r.ai_output
                        FROM advisor_run r WHERE r.id = :id""")
                .param("id", id)
                .query((rs, n) -> new RunDetail(mapRun(rs, n), pretty(rs.getString("snapshot_json")), rs.getString("ai_output")))
                .optional();
    }

    /**
     * Duyệt: tạo chiến dịch (chưa duyệt tự gửi) cho khu vực của đề xuất. Số khách lấy theo đề xuất nhưng không vượt số khách còn đủ điều kiện
     * lúc này và trần chiến dịch. Khóa dòng đề xuất nên bấm hai lần không tạo hai chiến dịch. Trả mã chiến dịch.
     */
    @Transactional
    public long approve(long id, String by) {
        expireOld();
        Proposal p = lock(id);
        requireOpen(p);
        long available = contacts.countEligibleNotInvited(p.area());
        int count = (int) Math.min(p.batchSize(), Math.min(available, invites.maxCampaign()));
        if (count < 1) {
            throw new ImportException("Hiện không còn khách nào ở khu vực này đủ điều kiện mời và chưa từng được mời, nên chưa tạo được chiến dịch. "
                    + "Hãy duyệt thêm khách ở mục Danh sách mời, hoặc bỏ qua đề xuất này.");
        }
        String name = p.title().length() <= MAX_NAME ? p.title() : p.title().substring(0, MAX_NAME);
        long campaignId = invites.createCampaign(name, null, EXPIRES_DAYS, count, p.area());
        jdbc.sql("""
                        UPDATE campaign_proposal SET state = 'APPROVED', decided_by = :by, decided_at = now(), campaign_id = :cid
                        WHERE id = :id""")
                .param("by", by).param("cid", campaignId).param("id", id).update();
        return campaignId;
    }

    /** Bỏ qua, kèm lý do (không bắt buộc) để sau này đo AI đoán trúng hay trượt. */
    @Transactional
    public void reject(long id, String by, String note) {
        expireOld();
        Proposal p = lock(id);
        requireOpen(p);
        String n = note == null ? null : note.strip();
        if (n != null && n.length() > MAX_NOTE) {
            throw new ImportException("Lý do bỏ qua tối đa " + MAX_NOTE + " ký tự.");
        }
        jdbc.sql("""
                        UPDATE campaign_proposal SET state = 'REJECTED', decided_by = :by, decided_at = now(), decision_note = :note
                        WHERE id = :id""")
                .param("by", by).param("note", n == null || n.isEmpty() ? null : n, java.sql.Types.VARCHAR).param("id", id).update();
    }

    private Proposal lock(long id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id FOR UPDATE OF p").param("id", id).query(this::map).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy đề xuất."));
    }

    private static void requireOpen(Proposal p) {
        if (!p.open()) {
            throw new ImportException("Đề xuất này đã được xử lý: " + p.stateLabel().toLowerCase(java.util.Locale.ROOT) + ".");
        }
    }

    private static final String SELECT = """
            SELECT p.id, p.run_id, p.title, p.area, p.batch_size, p.start_on, p.end_on, p.window_from, p.window_to, p.daily_cap,
                   p.message_angle, p.reasons::text AS reasons, p.risks::text AS risks, p.confidence, p.confidence_capped, p.state,
                   p.decided_by, p.decided_at, p.decision_note, p.campaign_id, r.created_at
            FROM campaign_proposal p JOIN advisor_run r ON r.id = p.run_id""";

    private Proposal map(ResultSet rs, int n) throws SQLException {
        String area = rs.getString("area");
        long campaign = rs.getLong("campaign_id");
        Long campaignId = rs.wasNull() ? null : campaign;
        return new Proposal(rs.getLong("id"), rs.getLong("run_id"), rs.getString("title"), area, areaLabel(area),
                rs.getInt("batch_size"), rs.getObject("start_on", LocalDate.class), rs.getObject("end_on", LocalDate.class),
                rs.getObject("window_from", LocalTime.class), rs.getObject("window_to", LocalTime.class), rs.getInt("daily_cap"),
                rs.getString("message_angle"), strings(rs.getString("reasons")), strings(rs.getString("risks")),
                rs.getBigDecimal("confidence").doubleValue(), rs.getBoolean("confidence_capped"), rs.getString("state"),
                rs.getString("decided_by"), local(rs.getObject("decided_at", OffsetDateTime.class)), rs.getString("decision_note"),
                campaignId, local(rs.getObject("created_at", OffsetDateTime.class)));
    }

    private Run mapRun(ResultSet rs, int n) throws SQLException {
        return new Run(rs.getLong("id"), local(rs.getObject("created_at", OffsetDateTime.class)), rs.getString("created_by"),
                rs.getString("status"), rs.getString("skip_reason"), rs.getString("detail"), strings(rs.getString("rejected")),
                rs.getInt("proposals"));
    }

    private static String areaLabel(String code) {
        return GeoArea.parse(code).map(GeoArea::label).orElse(code);
    }

    private static LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(AdvisorSnapshotService.ZONE).toLocalDateTime();
    }

    private static List<String> strings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, STRINGS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Cho người dùng đọc đúng số liệu đã gửi cho AI. Lỗi định dạng thì hiện nguyên văn. */
    private static String pretty(String json) {
        try {
            return PRETTY.writeValueAsString(JSON.readTree(json));
        } catch (RuntimeException e) {
            return json;
        }
    }
}
