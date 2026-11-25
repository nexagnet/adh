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
import vn.shop.crm.zalo.CampaignPolicy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Xem, duyệt và bỏ qua đề xuất của AI (D21, PR 3). Duyệt chỉ tạo chiến dịch bằng đường HIỆN CÓ ({@link InviteService#createCampaign}):
 * chiến dịch CHƯA duyệt tự gửi, nên chưa có tin nào đi cho ai; người dùng vẫn phải vào chiến dịch bấm duyệt tự gửi như mọi chiến dịch khác.
 * Lịch, khung giờ và hạn mức ngày của đề xuất được đặt vào chiến dịch qua {@link CampaignPolicy#saveClamped} (kẹp trong trần hệ thống).
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
                    String decidedBy, LocalDateTime decidedAt, String decisionNote, Long campaignId, LocalDateTime createdAt,
                    Integer weekdays, String pickOrder, Integer minDays, Integer maxDays, String productGroup,
                    vn.shop.crm.contact.CustomerFilter filter) {
        public ContactService.Pick pick() {
            return new ContactService.Pick(pickOrder, minDays, maxDays,
                    productGroup == null ? null : vn.shop.crm.contact.ProductGroup.valueOf(productGroup));
        }

        /** Ngày gửi trong tuần bằng chữ, ví dụ "Thứ Hai, Thứ Tư"; null nếu mọi ngày. */
        public String weekdaysLabel() {
            if (weekdays == null) {
                return null;
            }
            String[] names = {"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ nhật"};
            List<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < 7; i++) {
                if ((weekdays & (1 << i)) != 0) {
                    out.add(names[i]);
                }
            }
            return String.join(", ", out);
        }

        public String pickLabel() {
            String order = switch (pickOrder) {
                case "NEWEST_PURCHASE" -> "khách mua gần đây nhất trước";
                case "OLDEST_PURCHASE" -> "khách mua đã lâu trước";
                default -> "khách được duyệt sớm nhất trước";
            };
            String range = minDays == null && maxDays == null ? ""
                    : "; lần mua gần nhất cách đây " + (minDays != null && maxDays != null ? "từ " + minDays + " đến " + maxDays + " ngày"
                    : minDays != null ? "ít nhất " + minDays + " ngày" : "không quá " + maxDays + " ngày");
            String product = productGroup == null ? "" : "; chỉ khách đã mua " + vn.shop.crm.contact.ProductGroup.valueOf(productGroup).label().toLowerCase(java.util.Locale.ROOT);
            return "Lấy " + order + range + product;
        }

        public boolean customPick() {
            return pick().any();
        }

        /** Bộ lọc đầy đủ khi triển khai: các núm cũ (độ mới, nhóm hàng, thứ tự) gộp với bộ lọc mới của AI. */
        public vn.shop.crm.contact.CustomerFilter fullFilter() {
            return vn.shop.crm.contact.CustomerFilter.and(pick().toFilter(), filter == null ? vn.shop.crm.contact.CustomerFilter.NONE : filter);
        }

        /** Đề xuất mời cả khách mua nhiều lần (cần người xem kỹ trước khi duyệt). */
        public boolean multi() {
            return filter != null && filter.multi();
        }

        /** Bộ lọc mới của AI bằng chữ tiếng Việt, mỗi điều kiện một dòng (rỗng nếu không có). */
        public List<String> filterLines() {
            return filter == null ? List.of() : filter.describe();
        }

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

    /** Kết quả thật của một đề xuất đã triển khai (chiến dịch đã tạo từ đề xuất): để biết AI đoán trúng hay trượt. */
    public record Outcome(long proposalId, String title, String areaLabel, int confidencePercent, long campaignId,
                          long sent, long friended, long joined, long optedOut, Long buyers) {
        /** Chiến dịch có ít hơn chừng này khách đã nhắn thì chưa kết luận được. */
        public static final int MIN_SENT = 5;

        public boolean enough() {
            return sent >= MIN_SENT;
        }

        public Double friendedPercent() {
            return sent == 0 ? null : 100.0 * friended / sent;
        }

        public Double joinedPercent() {
            return sent == 0 ? null : 100.0 * joined / sent;
        }

        public Double refusalPercent() {
            return sent == 0 ? null : 100.0 * optedOut / sent;
        }
    }

    /** Kết quả các đề xuất đã triển khai và mốc so sánh: tỉ lệ chung của các chiến dịch KHÔNG tạo từ đề xuất AI. */
    public record Track(List<Outcome> outcomes, long baselineSent, Double baselineFriendedPercent, Double baselineJoinedPercent) {
    }

    private final JdbcClient jdbc;
    private final ContactService contacts;
    private final InviteService invites;
    private final CampaignPolicy policy;
    private final Clock clock;
    private final vn.shop.crm.performance.PerformanceService performance;

    ProposalService(JdbcClient jdbc, ContactService contacts, InviteService invites, CampaignPolicy policy, Clock clock,
                    vn.shop.crm.performance.PerformanceService performance) {
        this.performance = performance;
        this.policy = policy;
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

    /** Kết quả thật của các đề xuất đã triển khai (mới nhất trước) và mốc so sánh. Chỉ số đếm, không khách nào. */
    public Track track() {
        Map<Long, Long> optedOut = contacts.optedOutAfterInviteByCampaign();
        Map<Long, vn.shop.crm.performance.CampaignPerformance> perf = new java.util.HashMap<>();
        performance.anonymized().campaigns().forEach(c -> perf.put(c.id(), c));
        List<Outcome> outcomes = jdbc.sql("""
                        SELECT p.id, p.title, p.area, p.confidence, p.campaign_id,
                               count(i.id) FILTER (WHERE i.sent_at IS NOT NULL) AS sent,
                               count(i.id) FILTER (WHERE i.friended_at IS NOT NULL) AS friended,
                               count(i.id) FILTER (WHERE i.joined_at IS NOT NULL) AS joined
                        FROM campaign_proposal p LEFT JOIN invitation i ON i.campaign_id = p.campaign_id
                        WHERE p.state = 'APPROVED' AND p.campaign_id IS NOT NULL
                        GROUP BY p.id ORDER BY p.decided_at DESC NULLS LAST, p.id DESC LIMIT 20""")
                .query((rs, n) -> {
                    long cid = rs.getLong("campaign_id");
                    var cp = perf.get(cid);
                    return new Outcome(rs.getLong("id"), rs.getString("title"), areaLabel(rs.getString("area")),
                            (int) Math.round(rs.getBigDecimal("confidence").doubleValue() * 100), cid, rs.getLong("sent"),
                            rs.getLong("friended"), rs.getLong("joined"), optedOut.getOrDefault(cid, 0L),
                            cp != null && !cp.masked() ? cp.buyers() : null);
                }).list();
        long[] base = jdbc.sql("""
                        SELECT count(i.id) FILTER (WHERE i.sent_at IS NOT NULL), count(i.id) FILTER (WHERE i.friended_at IS NOT NULL),
                               count(i.id) FILTER (WHERE i.joined_at IS NOT NULL)
                        FROM invitation i
                        WHERE NOT EXISTS (SELECT 1 FROM campaign_proposal p WHERE p.campaign_id = i.campaign_id AND p.state = 'APPROVED')""")
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        return new Track(outcomes, base[0], base[0] == 0 ? null : 100.0 * base[1] / base[0], base[0] == 0 ? null : 100.0 * base[2] / base[0]);
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
        long available = contacts.countEligibleNotInvited(p.area(), p.fullFilter());
        int count = (int) Math.min(p.batchSize(), Math.min(available, invites.maxCampaign()));
        if (count < 1) {
            throw new ImportException("Hiện không còn khách nào ở khu vực này (theo cách chọn khách của đề xuất) đủ điều kiện mời và chưa từng được mời, nên chưa tạo được chiến dịch. "
                    + "Hãy duyệt thêm khách ở mục Danh sách mời, hoặc bỏ qua đề xuất này.");
        }
        String name = p.title().length() <= MAX_NAME ? p.title() : p.title().substring(0, MAX_NAME);
        long campaignId = invites.createCampaign(name, null, expiresDaysFor(p, today()), count, p.area(), p.fullFilter());
        // Lịch, khung giờ và hạn mức ngày AI đề xuất được đặt vào chiến dịch, nhưng luôn KẸP trong trần hệ thống (chỉ chặt hơn, không bao giờ nới).
        policy.saveClamped(campaignId, new CampaignPolicy.Raw(p.startOn(), p.endOn(), p.windowFrom(), p.windowTo(), p.dailyCap(), null), by);
        // Cấu hình sẵn thêm: hoãn khi mưa lớn (chỉ HOÃN, không bao giờ gửi nhanh hơn hay nhiều hơn); ngày trong tuần và ngưỡng tự dừng theo hệ thống.
        policy.saveExtra(campaignId, new CampaignPolicy.Extra(p.weekdays(), true, null), by);
        jdbc.sql("""
                        UPDATE campaign_proposal SET state = 'APPROVED', decided_by = :by, decided_at = now(), campaign_id = :cid
                        WHERE id = :id""")
                .param("by", by).param("cid", campaignId).param("id", id).update();
        return campaignId;
    }

    /**
     * Số ngày lời mời còn hạn. Lời mời tính hạn từ lúc TẠO chiến dịch, mà đề xuất có thể bắt đầu muộn tới 30 ngày sau: nếu để mặc định
     * 14 ngày thì lời mời hết hạn trước khi tới ngày gửi và bị đóng "Link đã hết hạn" mà không gửi. Nên hạn = tới hết ngày kết thúc
     * của đề xuất + {@link #EXPIRES_DAYS} ngày để khách kịp phản hồi (tối thiểu {@link #EXPIRES_DAYS}, tối đa 90 theo {@link InviteService}).
     */
    static int expiresDaysFor(Proposal p, LocalDate today) {
        long untilEnd = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(today, p.endOn()));
        return (int) Math.min(90, Math.max(EXPIRES_DAYS, untilEnd + EXPIRES_DAYS));
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
                   p.decided_by, p.decided_at, p.decision_note, p.campaign_id, r.created_at,
                   p.weekdays, p.pick_order, p.min_days, p.max_days, p.product_group, p.filter_json::text AS filter_json
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
                campaignId, local(rs.getObject("created_at", OffsetDateTime.class)),
                (Integer) rs.getObject("weekdays"), rs.getString("pick_order"), (Integer) rs.getObject("min_days"), (Integer) rs.getObject("max_days"), rs.getString("product_group"),
                vn.shop.crm.contact.CustomerFilter.fromJson(rs.getString("filter_json")));
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
