package vn.shop.crm.send;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.PhoneHash;
import vn.shop.crm.common.PhoneNormalizer;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.send.InvitationSender.OutboundInvite;
import vn.shop.crm.send.InvitationSender.SendResult;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cổng chặn gửi (mục 3.3 file 03, D3–D6). MỌI lần đưa lời mời tới khách đều đi qua đây:
 * tạm dừng, bật gửi thật, giờ gửi 07:00–22:00, hạn mức ngày, trần ngân sách tháng, kiểm lại điều kiện liên hệ,
 * mỗi lời mời gửi tối đa một lần (khóa bằng cập nhật có điều kiện), nhật ký gửi, tự dừng khi lỗi/từ chối cao.
 *
 * <p>Không có thủ thuật né phát hiện (giả lập thao tác, xoay vòng tài khoản): chỉ gửi chậm, từng tin, nghỉ ngẫu
 * nhiên giữa các tin.
 */
@Service
public class SendService {

    private static final Logger LOG = LoggerFactory.getLogger(SendService.class);
    private static final String NEXT_AT = "sending.next_at";

    public enum StopReason {
        PAUSED("Đang tạm dừng tất cả chiến dịch"),
        DISABLED("Gửi thật đang TẮT (cấu hình app.sending.enabled)"),
        OUTSIDE_WINDOW("Ngoài giờ được phép gửi"),
        DAILY_LIMIT("Đã đạt hạn mức gửi trong ngày"),
        HOURLY_LIMIT("Đã đạt hạn mức gửi trong giờ vừa qua"),
        BUDGET("Đã đạt trần ngân sách tháng");

        private final String label;

        StopReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Kết quả một lượt chạy. {@code delivered} là những tin đã được chấp nhận gửi (hoặc sẽ gửi, nếu chạy thử). */
    public record BatchResult(int sent, int failed, int skipped, int dryRun, StopReason stoppedBy, String autoStopped,
                              boolean waiting, List<OutboundInvite> delivered) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Gửi: ").append(sent).append(", chạy thử: ").append(dryRun).append(", lỗi: ").append(failed)
                    .append(", bỏ qua: ").append(skipped).append('.');
            if (stoppedBy != null) {
                sb.append(" Dừng vì: ").append(stoppedBy.label()).append('.');
            }
            if (autoStopped != null) {
                sb.append(" TỰ DỪNG: ").append(autoStopped).append('.');
            }
            if (waiting) {
                sb.append(" Đang nghỉ giữa hai tin.");
            }
            return sb.toString();
        }
    }

    public record LogRow(LocalDateTime at, String phoneTail, String channel, String outcome, String reason,
                         int costVnd) {
    }

    public record Status(boolean enabled, boolean paused, String pausedReason, int sentToday, int dailyLimit,
                         long monthSpend, long monthlyBudget, LocalTime windowStart, LocalTime windowEnd,
                         LocalDateTime nextAt, List<LogRow> recent, int sentThisHour, int hourlyLimit,
                         int suggestedGapMinutes) {
    }

    /** Kênh ghi vào nhật ký khi người nhắn tay bằng tài khoản Zalo phụ bấm "Đã nhắn" (hệ thống không tự gửi). */
    public static final String CHANNEL_ZALO_PHU = "ZALO_PHU";

    /**
     * "Lô nhắn hôm nay": những lời mời hệ thống GỢI Ý nhắn ngay lúc này, đủ cho hạn mức còn lại trong ngày và trong giờ.
     * Người thật nhắn tay rồi bấm "Đã nhắn"; hệ thống không gửi gì.
     */
    public record Today(boolean paused, String pausedReason, boolean outsideWindow, LocalTime windowStart,
                        LocalTime windowEnd, int sentToday, int dailyLimit, int sentThisHour, int hourlyLimit,
                        int allowedNow, LocalDateTime nextSuggestedAt, int suggestedGapMinutes, long readyTotal,
                        List<Long> invitationIds) {
        /** Lý do chưa có ai để nhắn, bằng tiếng Việt; rỗng nếu có người. */
        public String emptyReason() {
            if (paused) {
                return "Đang tạm dừng tất cả chiến dịch.";
            }
            if (outsideWindow) {
                return "Ngoài giờ nhắn (" + windowStart + " đến " + windowEnd + "). Mời quay lại trong giờ.";
            }
            if (sentToday >= dailyLimit) {
                return "Hôm nay đã đủ hạn mức (" + dailyLimit + " tin). Mai nhắn tiếp.";
            }
            if (hourlyLimit > 0 && sentThisHour >= hourlyLimit) {
                return "Giờ này đã đủ hạn mức (" + hourlyLimit + " tin trong 60 phút). Nghỉ chút rồi nhắn tiếp.";
            }
            if (invitationIds.isEmpty()) {
                return "Không còn khách nào chưa nhắn trong chiến dịch.";
            }
            return "";
        }
    }

    private record Candidate(long id, long campaignId, long customerId, String phone, String name, String token,
                             OffsetDateTime expiresAt, String template, String province) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final ContactService contacts;
    private final InviteService invites;
    private final SendProperties props;
    private final Clock clock;
    private final List<InvitationSender> senders;

    public SendService(JdbcClient jdbc, AppSettings settings, ContactService contacts, InviteService invites,
                       SendProperties props, Clock clock, List<InvitationSender> senders) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.contacts = contacts;
        this.invites = invites;
        this.props = props;
        this.clock = clock;
        this.senders = senders;
    }

    public Optional<InvitationSender> sender(String channel) {
        return senders.stream().filter(s -> s.channel().equalsIgnoreCase(channel)).findFirst();
    }

    // ---------- chạy ----------

    /** Gửi (hoặc chạy thử) tối đa {@code limit} lời mời CHƯA gửi của một chiến dịch. */
    public BatchResult runBatch(long campaignId, InvitationSender sender, int limit) {
        List<Candidate> list = jdbc.sql(CANDIDATE_SELECT + " WHERE i.campaign_id = :c AND i.status = 'READY'"
                        + " ORDER BY i.id LIMIT :limit")
                .param("c", campaignId).param("limit", Math.max(1, limit))
                .query((rs, n) -> candidate(rs)).list();
        return process(list, sender);
    }

    /**
     * Gửi đúng MỘT lời mời kế tiếp trong mọi chiến dịch, nếu đã qua khoảng nghỉ ngẫu nhiên từ tin trước. Bộ hẹn giờ
     * gọi hàm này; sau mỗi tin gửi thật, đặt khoảng nghỉ mới giữa {@code minGapSeconds} và {@code maxGapSeconds}.
     */
    public BatchResult sendNext(InvitationSender sender) {
        Instant now = clock.instant();
        Optional<Instant> next = settings.get(NEXT_AT).map(Instant::parse);
        if (next.isPresent() && now.isBefore(next.get())) {
            return new BatchResult(0, 0, 0, 0, null, null, true, List.of());
        }
        List<Candidate> list = jdbc.sql(CANDIDATE_SELECT + " WHERE i.status = 'READY' ORDER BY i.campaign_id, i.id LIMIT 1")
                .query((rs, n) -> candidate(rs)).list();
        BatchResult result = process(list, sender);
        if (result.sent() + result.failed() > 0) {
            int min = Math.max(0, props.minGapSeconds());
            int max = Math.max(min, props.maxGapSeconds());
            int gap = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            settings.set(NEXT_AT, now.plusSeconds(gap).toString(), "he-thong");
        }
        return result;
    }

    private static final String CANDIDATE_SELECT = """
            SELECT i.id, i.campaign_id, i.customer_id, c.phone, c.name, c.province, i.token, i.expires_at, k.message_template
            FROM invitation i JOIN customer c ON c.id = i.customer_id
            JOIN invitation_campaign k ON k.id = i.campaign_id""";

    private static Candidate candidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Candidate(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("customer_id"),
                rs.getString("phone"), rs.getString("name"), rs.getString("token"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getString("message_template"), rs.getString("province"));
    }

    private BatchResult process(List<Candidate> candidates, InvitationSender sender) {
        int sent = 0;
        int failed = 0;
        int skipped = 0;
        int dry = 0;
        StopReason stopped = null;
        String auto = null;
        List<OutboundInvite> delivered = new ArrayList<>();
        for (Candidate c : candidates) {
            stopped = gate(sender).orElse(null);
            if (stopped != null) {
                break;
            }
            if (c.expiresAt().isBefore(OffsetDateTime.now(clock))) {
                log(c, sender, "SKIPPED", "Link đã hết hạn", 0);
                skipped++;
                continue;
            }
            if (!contacts.stillEligible(c.customerId())) {
                log(c, sender, "SKIPPED", "Không còn đủ điều kiện liên hệ", 0);
                skipped++;
                continue;
            }
            OutboundInvite invite = new OutboundInvite(c.id(), c.customerId(), c.phone(), c.name(),
                    invites.render(c.template(), c.name(), invites.linkFor(c.token()),
                            vn.shop.crm.geo.VnGeo.byCode(c.province()).orElse(null)),
                    invites.areaLabel(c.province()), invites.suggestGroupName(c.province()).orElse(""));
            if (!sender.realDelivery()) {
                log(c, sender, "DRY_RUN", "Chạy thử: sẽ gửi", 0);
                delivered.add(invite);
                dry++;
                continue;
            }
            // khóa lời mời trước khi gửi: hai lần chạy cùng lúc không thể gửi trùng một khách
            int claimed = jdbc.sql("""
                            UPDATE invitation SET status = 'SENT', sent_at = :at, sent_channel = :ch
                            WHERE id = :id AND status = 'READY'""")
                    .param("at", OffsetDateTime.now(clock)).param("ch", sender.channel()).param("id", c.id()).update();
            if (claimed == 0) {
                skipped++;
                continue;
            }
            SendResult result;
            try {
                result = sender.send(invite);
            } catch (RuntimeException e) {
                // không đưa thông điệp lỗi gốc vào nhật ký: có thể chứa SĐT hoặc nội dung tin
                LOG.warn("Bộ gửi {} báo lỗi: {}", sender.channel(), e.getClass().getSimpleName());
                result = SendResult.failure("Lỗi bộ gửi (" + e.getClass().getSimpleName() + ")");
            }
            if (result.ok()) {
                log(c, sender, "SENT", null, sender.costPerMessageVnd());
                contacts.event(c.customerId(), c.phone(), "INVITATION_SENT", "Kênh: " + vn.shop.crm.common.Labels.channelLabel(sender.channel()));
                delivered.add(invite);
                sent++;
            } else {
                String error = truncate(result.error());
                jdbc.sql("UPDATE invitation SET status = 'FAILED', last_error = :e WHERE id = :id")
                        .param("e", error).param("id", c.id()).update();
                log(c, sender, "FAILED", error, 0);
                failed++;
            }
            auto = evaluateAutoStop();
            if (auto != null) {
                break;
            }
        }
        return new BatchResult(sent, failed, skipped, dry, stopped, auto, false, delivered);
    }


    // ---------- lô nhắn hôm nay (người thật nhắn tay bằng tài khoản Zalo phụ) ----------

    /**
     * Danh sách gợi ý để nhắn ngay lúc này: không quá hạn mức còn lại của ngày và của giờ, chỉ trong giờ nhắn,
     * chỉ khi không tạm dừng. {@code campaignId} rỗng = mọi chiến dịch (hạn mức ngày dùng chung cho tất cả).
     * Nhịp nghỉ chỉ là gợi ý hiển thị (cách nhau ít nhất {@code suggestedGapMinutes} phút), không ép buộc.
     */
    public Today today(Long campaignId) {
        LocalTime now = LocalTime.now(clock);
        boolean outside = now.isBefore(props.windowStart()) || !now.isBefore(props.windowEnd());
        boolean paused = settings.paused();
        int sentToday = sentToday();
        int sentHour = sentLastHour();
        int allowed = Math.max(0, props.dailyLimit() - sentToday);
        if (props.hourlyLimit() > 0) {
            allowed = Math.min(allowed, Math.max(0, props.hourlyLimit() - sentHour));
        }
        if (paused || outside) {
            allowed = 0;
        }
        String scope = campaignId == null ? "" : " AND i.campaign_id = :camp";
        long readyTotal = bindCamp(jdbc.sql("SELECT count(*) FROM invitation i WHERE i.status = 'READY'" + scope), campaignId)
                .query(Long.class).single();
        List<Long> ids = new ArrayList<>();
        if (allowed > 0) {
            List<Candidate> candidates = bindCamp(jdbc.sql(CANDIDATE_SELECT + " WHERE i.status = 'READY'" + scope
                    + " ORDER BY i.campaign_id, i.id LIMIT :limit"), campaignId)
                    .param("limit", allowed * 3 + 10).query((rs, n) -> candidate(rs)).list();
            for (Candidate c : candidates) {
                if (ids.size() >= allowed) {
                    break;
                }
                if (c.expiresAt().isBefore(OffsetDateTime.now(clock)) || !contacts.stillEligible(c.customerId())) {
                    continue;
                }
                ids.add(c.id());
            }
        }
        LocalDateTime next = null;
        OffsetDateTime last = jdbc.sql("SELECT max(recorded_at) FROM send_log WHERE outcome = 'SENT'")
                .query(OffsetDateTime.class).optional().orElse(null);
        if (last != null) {
            LocalDateTime t = last.plusMinutes(props.suggestedGapMinutes()).atZoneSameInstant(clock.getZone())
                    .toLocalDateTime();
            if (t.isAfter(LocalDateTime.now(clock))) {
                next = t;
            }
        }
        return new Today(paused, settings.pausedReason(), outside, props.windowStart(), props.windowEnd(), sentToday,
                props.dailyLimit(), sentHour, props.hourlyLimit(), allowed, next, props.suggestedGapMinutes(),
                readyTotal, ids);
    }

    private static JdbcClient.StatementSpec bindCamp(JdbcClient.StatementSpec spec, Long campaignId) {
        return campaignId == null ? spec : spec.param("camp", campaignId);
    }

    /**
     * Người nhắn tay bấm "Đã nhắn" sau khi đã nhắn xong cho khách này bằng tài khoản Zalo phụ. Đây là GHI NHẬN việc đã
     * xảy ra nên không chặn theo hạn mức hay giờ (chặn thì tin đã gửi mà hệ thống không biết, có thể nhắn lần nữa);
     * hạn mức chỉ quyết định hệ thống gợi ý ai ở {@link #today}. Vẫn từ chối khách không còn đủ điều kiện liên hệ.
     */
    @Transactional
    public void markMessaged(long invitationId) {
        Candidate c = jdbc.sql(CANDIDATE_SELECT + " WHERE i.id = :id").param("id", invitationId)
                .query((rs, n) -> candidate(rs)).optional()
                .orElseThrow(() -> new ImportException("Không tìm thấy lời mời."));
        if (!contacts.stillEligible(c.customerId())) {
            throw new ImportException("Khách này không còn đủ điều kiện liên hệ (đã từ chối, bị chặn hoặc bị xóa). "
                    + "Đừng nhắn khách này.");
        }
        int claimed = jdbc.sql("""
                        UPDATE invitation SET status = 'SENT', sent_at = :at, sent_channel = :ch
                        WHERE id = :id AND status = 'READY'""")
                .param("at", OffsetDateTime.now(clock)).param("ch", CHANNEL_ZALO_PHU).param("id", invitationId).update();
        if (claimed == 0) {
            String current = jdbc.sql("SELECT status FROM invitation WHERE id = :id").param("id", invitationId)
                    .query(String.class).optional().orElse("");
            throw new ImportException(switch (current) {
                case "CANCELLED" -> "Lời mời này đã bị hủy, không nhắn khách này.";
                case "FAILED" -> "Lời mời này đã bị đánh dấu lỗi, không đánh dấu nhắn được.";
                default -> "Khách này đã được đánh dấu nhắn rồi (mỗi khách chỉ nhắn một lần).";
            });
        }
        log(c, CHANNEL_ZALO_PHU, "SENT", "Nhắn tay bằng tài khoản Zalo phụ", 0);
        contacts.event(c.customerId(), c.phone(), "INVITATION_SENT",
                "Kênh: " + vn.shop.crm.common.Labels.channelLabel(CHANNEL_ZALO_PHU));
        evaluateAutoStop();
    }

    // ---------- cổng chặn ----------

    /** Lý do KHÔNG được gửi ngay lúc này; rỗng nghĩa là được phép. */
    public Optional<StopReason> gate(InvitationSender sender) {
        if (settings.paused()) {
            return Optional.of(StopReason.PAUSED);
        }
        if (sender.automated()) {
            if (!props.enabled()) {
                return Optional.of(StopReason.DISABLED);
            }
            LocalTime now = LocalTime.now(clock);
            if (now.isBefore(props.windowStart()) || !now.isBefore(props.windowEnd())) {
                return Optional.of(StopReason.OUTSIDE_WINDOW);
            }
        }
        if (sender.realDelivery()) {
            if (sentToday() >= props.dailyLimit()) {
                return Optional.of(StopReason.DAILY_LIMIT);
            }
            if (props.hourlyLimit() > 0 && sentLastHour() >= props.hourlyLimit()) {
                return Optional.of(StopReason.HOURLY_LIMIT);
            }
            if (sender.costPerMessageVnd() > 0
                    && monthSpend() + sender.costPerMessageVnd() > props.monthlyBudgetVnd()) {
                return Optional.of(StopReason.BUDGET);
            }
        }
        return Optional.empty();
    }

    /**
     * Tự dừng (D5): lỗi liên tiếp, tỷ lệ lỗi cao trong 24 giờ, tỷ lệ khách từ chối cao trong 7 ngày, hết ngân sách.
     * Khi kích hoạt thì bật "tạm dừng tất cả" kèm lý do; chủ cửa hàng tự bỏ tạm dừng sau khi kiểm tra.
     */
    public String evaluateAutoStop() {
        if (settings.paused()) {
            return null;
        }
        String reason = null;
        int n = props.stopAfterConsecutiveFailures();
        List<String> last = jdbc.sql("SELECT outcome FROM send_log WHERE outcome IN ('SENT', 'FAILED') "
                + "ORDER BY id DESC LIMIT :n").param("n", n).query(String.class).list();
        if (n > 0 && last.size() == n && last.stream().allMatch("FAILED"::equals)) {
            reason = n + " lần gửi lỗi liên tiếp";
        }
        OffsetDateTime day = OffsetDateTime.now(clock).minusHours(24);
        long failed24 = count("SELECT count(*) FROM send_log WHERE outcome = 'FAILED' AND recorded_at > :t", day);
        long ok24 = count("SELECT count(*) FROM send_log WHERE outcome = 'SENT' AND recorded_at > :t", day);
        if (reason == null && failed24 + ok24 >= 5 && failed24 / (double) (failed24 + ok24) >= props.stopFailureRate()) {
            reason = "Tỷ lệ gửi lỗi cao trong 24 giờ (" + failed24 + "/" + (failed24 + ok24) + ")";
        }
        OffsetDateTime week = OffsetDateTime.now(clock).minusDays(7);
        long sent7 = count("SELECT count(*) FROM invitation WHERE sent_at > :t", week);
        long optOut7 = count("SELECT count(*) FROM invitation WHERE sent_at > :t AND status = 'CANCELLED' "
                + "AND cancelled_reason LIKE 'Khách yêu cầu%'", week);
        if (reason == null && sent7 >= 10 && optOut7 / (double) sent7 >= props.stopOptOutRate()) {
            reason = "Nhiều khách từ chối nhận tin (" + optOut7 + "/" + sent7 + " trong 7 ngày)";
        }
        if (reason == null && monthSpend() >= props.monthlyBudgetVnd() && monthSpend() > 0) {
            reason = "Đã hết ngân sách tháng";
        }
        if (reason != null) {
            settings.pauseAll("Tự dừng: " + reason, "he-thong");
            LOG.warn("Tự dừng gửi: {}", reason);
        }
        return reason;
    }

    // ---------- thống kê & nhật ký ----------

    public int sentToday() {
        OffsetDateTime start = OffsetDateTime.now(clock).toLocalDate().atStartOfDay(clock.getZone()).toOffsetDateTime();
        return (int) count("SELECT count(*) FROM send_log WHERE outcome = 'SENT' AND recorded_at >= :t", start);
    }

    /** Số tin đã gửi trong 60 phút gần nhất. */
    public int sentLastHour() {
        return (int) count("SELECT count(*) FROM send_log WHERE outcome = 'SENT' AND recorded_at > :t",
                OffsetDateTime.now(clock).minusHours(1));
    }

    public long monthSpend() {
        OffsetDateTime start = OffsetDateTime.now(clock).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(clock.getZone()).toOffsetDateTime();
        return jdbc.sql("SELECT coalesce(sum(cost_vnd), 0) FROM send_log WHERE outcome = 'SENT' AND recorded_at >= :t")
                .param("t", start).query(Long.class).single();
    }

    public Status status() {
        Optional<Instant> next = settings.get(NEXT_AT).map(Instant::parse);
        List<LogRow> recent = jdbc.sql("""
                        SELECT recorded_at, phone_tail, channel, outcome, reason, cost_vnd FROM send_log
                        ORDER BY id DESC LIMIT 30""")
                .query((rs, i) -> new LogRow(
                        rs.getObject("recorded_at", OffsetDateTime.class).atZoneSameInstant(clock.getZone())
                                .toLocalDateTime(),
                        rs.getString("phone_tail"), rs.getString("channel"), rs.getString("outcome"),
                        rs.getString("reason"), rs.getInt("cost_vnd")))
                .list();
        return new Status(props.enabled(), settings.paused(), settings.pausedReason(), sentToday(),
                props.dailyLimit(), monthSpend(), props.monthlyBudgetVnd(), props.windowStart(), props.windowEnd(),
                next.map(t -> LocalDateTime.ofInstant(t, clock.getZone())).orElse(null), recent,
                sentLastHour(), props.hourlyLimit(), props.suggestedGapMinutes());
    }

    private long count(String sql, OffsetDateTime t) {
        return jdbc.sql(sql).param("t", t).query(Long.class).single();
    }

    private void log(Candidate c, InvitationSender sender, String outcome, String reason, int cost) {
        log(c, sender.channel(), outcome, reason, cost);
    }

    private void log(Candidate c, String channel, String outcome, String reason, int cost) {
        jdbc.sql("""
                        INSERT INTO send_log (invitation_id, campaign_id, customer_id, phone_tail, channel, outcome,
                                              reason, cost_vnd, template_sha, recorded_by, recorded_at)
                        VALUES (:inv, :camp, :cust, :tail, :ch, :outcome, :reason, :cost, :sha, :by, :at)""")
                .param("inv", c.id()).param("camp", c.campaignId()).param("cust", c.customerId())
                .param("tail", PhoneHash.tail(c.phone())).param("ch", channel).param("outcome", outcome)
                .param("reason", reason).param("cost", cost).param("sha", sha(c.template()))
                .param("by", ContactService.actor()).param("at", OffsetDateTime.now(clock)).update();
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "Lỗi không rõ";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /**
     * CSV cho mẹ gửi tay: số điện thoại (dạng 09...), tên, nội dung tin đã ghép sẵn link riêng, khu vực của khách và nhóm
     * Zalo nên thêm vào nếu khách kết bạn (gợi ý, mẹ vẫn chọn lại được ở trang chiến dịch).
     */
    public static byte[] exportCsv(List<OutboundInvite> invites) {
        List<List<String>> rows = invites.stream()
                .map(i -> List.of(PhoneNormalizer.toDisplay(i.phone()).replaceAll("\\s", ""), i.name() == null ? "" : i.name(), i.message(),
                        i.area() == null ? "" : i.area(), i.groupName() == null ? "" : i.groupName()))
                .toList();
        return vn.shop.crm.common.CsvOut.write(List.of("so_dien_thoai", "ten", "noi_dung_tin", "khu_vuc", "nhom_goi_y"), rows);
    }
}
