package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Lịch và nhịp RIÊNG của một chiến dịch (D21 PR 4). Nguyên tắc cứng: hệ thống đặt TRẦN, chiến dịch chỉ chọn giá trị CHẶT hơn
 * (khung giờ hẹp hơn, hạn mức ngày thấp hơn, nghỉ giữa hai tin dài hơn, ngày bắt đầu/kết thúc) và KHÔNG BAO GIỜ nới. Ô để trống (NULL) nghĩa là
 * "theo hệ thống", nên chiến dịch cũ chạy y như trước. Giá trị hiệu lực tính ở MỘT chỗ ({@link #effective}); đường gửi
 * ({@link ZaloInviteSender}) chỉ dùng lớp này để BỎ QUA chiến dịch chưa tới lượt, không bao giờ để gửi nhiều hơn: mọi chốt chặn
 * của hệ thống ({@link ZaloMessageService#blocker}: công tắc, nấc, khung giờ, hạn mức ngày, khoảng nghỉ, cầu dao) vẫn chạy sau đó.
 * Không có ngẫu nhiên ở đây (khóa bởi {@code ZaloGatewayShapeTest}).
 */
@Service
public class CampaignPolicy {

    /** Khung giờ riêng của chiến dịch phải rộng ít nhất chừng này (khớp {@code ProposalValidator}). */
    public static final int MIN_WINDOW_MINUTES = 60;
    private static final int MAX_GAP_SECONDS = 3600;

    /** Giá trị chiến dịch tự đặt; null = theo hệ thống. */
    public record Raw(LocalDate startOn, LocalDate endOn, LocalTime windowFrom, LocalTime windowTo, Integer dailyCap, Integer gapSeconds) {
        public static final Raw NONE = new Raw(null, null, null, null, null, null);

        public boolean any() {
            return startOn != null || endOn != null || windowFrom != null || dailyCap != null || gapSeconds != null;
        }
    }

    /** Giá trị đang có hiệu lực sau khi so với trần hệ thống (để hiển thị). */
    public record Effective(LocalDate startOn, LocalDate endOn, LocalTime windowFrom, LocalTime windowTo, int dailyCap, int gapSeconds) {
    }

    public enum Block {
        NOT_STARTED("Chưa tới ngày bắt đầu của chiến dịch"),
        ENDED("Đã qua ngày kết thúc của chiến dịch"),
        OUTSIDE_WINDOW("Ngoài khung giờ riêng của chiến dịch"),
        DAILY_CAP("Đã đủ hạn mức ngày riêng của chiến dịch"),
        GAP("Chưa đủ khoảng nghỉ riêng của chiến dịch");

        private final String label;

        Block(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final JdbcClient jdbc;
    private final ZaloAutoSettings cfg;
    private final ZaloMessageService messages;
    private final Clock clock;

    public CampaignPolicy(JdbcClient jdbc, ZaloAutoSettings cfg, ZaloMessageService messages, Clock clock) {
        this.jdbc = jdbc;
        this.cfg = cfg;
        this.messages = messages;
        this.clock = clock;
    }

    // ---------- đọc ----------

    public Raw load(long campaignId) {
        return jdbc.sql("SELECT start_on, end_on, window_from, window_to, daily_cap, gap_seconds FROM invitation_campaign WHERE id = :id")
                .param("id", campaignId).query(this::map).optional().orElse(Raw.NONE);
    }

    private Raw map(ResultSet rs, int n) throws SQLException {
        return new Raw(rs.getObject("start_on", LocalDate.class), rs.getObject("end_on", LocalDate.class),
                rs.getObject("window_from", LocalTime.class), rs.getObject("window_to", LocalTime.class),
                (Integer) rs.getObject("daily_cap"), (Integer) rs.getObject("gap_seconds"));
    }

    /**
     * Giá trị hiệu lực = giá trị CHẶT hơn của hệ thống và chiến dịch: khung giờ là phần giao, hạn mức là số nhỏ hơn, khoảng nghỉ là số lớn hơn.
     * Khung giờ rỗng (không có phần giao) thể hiện bằng {@code windowFrom == windowTo}: không gửi được lúc nào.
     */
    public Effective effective(Raw raw) {
        ZaloAutoProperties p = cfg.props();
        ZaloMessageService.Limits sys = messages.limits();
        LocalTime from = raw.windowFrom() == null ? p.msgWindowStart() : max(p.msgWindowStart(), raw.windowFrom());
        LocalTime to = raw.windowTo() == null ? p.msgWindowEnd() : min(p.msgWindowEnd(), raw.windowTo());
        if (!from.isBefore(to)) {
            to = from;
        }
        int cap = raw.dailyCap() == null ? sys.daily() : Math.min(sys.daily(), raw.dailyCap());
        int gap = raw.gapSeconds() == null ? sys.gapSeconds() : Math.max(sys.gapSeconds(), raw.gapSeconds());
        return new Effective(raw.startOn(), raw.endOn(), from, to, cap, gap);
    }

    // ---------- chốt của chiến dịch (chỉ để BỎ QUA, không bao giờ để gửi thêm) ----------

    /**
     * Lý do chiến dịch này chưa được gửi lúc này theo lịch và nhịp riêng của nó, hoặc rỗng nếu được (chưa tính các chốt của hệ thống).
     * Ô nào để trống thì không kiểm gì, nên chiến dịch cũ không bị đổi hành vi.
     */
    public Optional<Block> blocker(long campaignId) {
        Raw raw = load(campaignId);
        if (!raw.any()) {
            return Optional.empty();
        }
        ZonedDateTime now = clock.instant().atZone(clock.getZone());
        LocalDate today = now.toLocalDate();
        if (raw.startOn() != null && today.isBefore(raw.startOn())) {
            return Optional.of(Block.NOT_STARTED);
        }
        if (raw.endOn() != null && today.isAfter(raw.endOn())) {
            return Optional.of(Block.ENDED);
        }
        if (raw.windowFrom() != null) {
            Effective e = effective(raw);
            LocalTime t = now.toLocalTime();
            if (t.isBefore(e.windowFrom()) || !t.isBefore(e.windowTo())) {
                return Optional.of(Block.OUTSIDE_WINDOW);
            }
        }
        if (raw.dailyCap() != null && sentToday(campaignId, today) >= effective(raw).dailyCap()) {
            return Optional.of(Block.DAILY_CAP);
        }
        if (raw.gapSeconds() != null) {
            Instant last = lastSent(campaignId);
            if (last != null && clock.instant().isBefore(last.plus(Duration.ofSeconds(effective(raw).gapSeconds())))) {
                return Optional.of(Block.GAP);
            }
        }
        return Optional.empty();
    }

    /**
     * Số tin chủ động của chiến dịch hôm nay: Tin 1 tự gửi (đếm theo lời mời, nên tin bị trả lại về READY không tính) cộng các tin nhắc
     * (D21 PR 5b: mỗi lần nhắc đã nhận phần việc, kể cả lần lỗi hay không rõ kết quả, là một dòng ở {@code invitation_touch}).
     */
    public int sentToday(long campaignId, LocalDate today) {
        OffsetDateTime since = OffsetDateTime.ofInstant(today.atStartOfDay(clock.getZone()).toInstant(), clock.getZone());
        int first = jdbc.sql("""
                        SELECT count(*) FROM invitation WHERE campaign_id = :id AND sent_channel = :ch AND sent_at >= :since""")
                .param("id", campaignId).param("ch", ZaloInviteSender.CHANNEL).param("since", since)
                .query(Integer.class).single();
        int follow = jdbc.sql("""
                        SELECT count(*) FROM invitation_touch t JOIN invitation i ON i.id = t.invitation_id
                        WHERE i.campaign_id = :id AND t.step_no >= 2 AND t.sent_at >= :since""")
                .param("id", campaignId).param("since", since).query(Integer.class).single();
        return first + follow;
    }

    private Instant lastSent(long campaignId) {
        Instant first = jdbc.sql("SELECT max(sent_at) FROM invitation WHERE campaign_id = :id AND sent_channel = :ch")
                .param("id", campaignId).param("ch", ZaloInviteSender.CHANNEL)
                .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
        Instant follow = jdbc.sql("""
                        SELECT max(t.sent_at) FROM invitation_touch t JOIN invitation i ON i.id = t.invitation_id
                        WHERE i.campaign_id = :id AND t.step_no >= 2""")
                .param("id", campaignId).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
        if (first == null) {
            return follow;
        }
        return follow != null && follow.isAfter(first) ? follow : first;
    }

    // ---------- ghi ----------

    /** Người dùng đặt lịch và nhịp riêng. Giá trị nới hơn trần hệ thống bị từ chối bằng thông báo tiếng Việt, không lưu. */
    public void save(long campaignId, Raw raw, String by) {
        validate(raw);
        write(campaignId, raw);
        messages.audit("LICH_CHIEN_DICH", campaignId, by);
    }

    /**
     * Dùng khi duyệt đề xuất của AI: đề xuất đã qua kiểm cứng lúc tạo nhưng hệ thống có thể đã chỉnh chặt hơn từ đó, nên KẸP giá trị về trong trần
     * thay vì từ chối (không bao giờ nới). Khung giờ sau khi kẹp mà hẹp hơn mức tối thiểu thì bỏ khung giờ riêng (theo hệ thống).
     */
    public void saveClamped(long campaignId, Raw raw, String by) {
        ZaloAutoProperties p = cfg.props();
        ZaloMessageService.Limits sys = messages.limits();
        LocalTime from = raw.windowFrom();
        LocalTime to = raw.windowTo();
        if (from != null && to != null) {
            from = max(p.msgWindowStart(), from);
            to = min(p.msgWindowEnd(), to);
            if (Duration.between(from, to).toMinutes() < MIN_WINDOW_MINUTES) {
                from = null;
                to = null;
            }
        } else {
            from = null;
            to = null;
        }
        Integer cap = raw.dailyCap() == null ? null : Math.min(sys.daily(), raw.dailyCap());
        Integer gap = raw.gapSeconds() == null ? null : Math.max(sys.gapSeconds(), Math.min(MAX_GAP_SECONDS, raw.gapSeconds()));
        LocalDate start = raw.startOn();
        LocalDate end = raw.endOn();
        if (start != null && end != null && end.isBefore(start)) {
            end = start;
        }
        Raw clamped = new Raw(start, end, from, to, cap, gap);
        validate(clamped);
        write(campaignId, clamped);
        messages.audit("LICH_CHIEN_DICH", campaignId, by);
    }

    private void write(long campaignId, Raw raw) {
        int n = jdbc.sql("""
                        UPDATE invitation_campaign SET start_on = :s, end_on = :e, window_from = :wf, window_to = :wt,
                            daily_cap = :cap, gap_seconds = :gap WHERE id = :id""")
                .param("s", raw.startOn(), java.sql.Types.DATE).param("e", raw.endOn(), java.sql.Types.DATE)
                .param("wf", raw.windowFrom(), java.sql.Types.TIME).param("wt", raw.windowTo(), java.sql.Types.TIME)
                .param("cap", raw.dailyCap(), java.sql.Types.INTEGER).param("gap", raw.gapSeconds(), java.sql.Types.INTEGER)
                .param("id", campaignId).update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
    }

    private void validate(Raw raw) {
        ZaloAutoProperties p = cfg.props();
        ZaloMessageService.Limits sys = messages.limits();
        LocalDate today = clock.instant().atZone(clock.getZone()).toLocalDate();
        if (raw.startOn() != null && raw.endOn() != null && raw.endOn().isBefore(raw.startOn())) {
            throw new ImportException("Ngày kết thúc phải sau hoặc bằng ngày bắt đầu.");
        }
        if (raw.endOn() != null && raw.endOn().isBefore(today)) {
            throw new ImportException("Ngày kết thúc phải từ hôm nay trở đi.");
        }
        if ((raw.windowFrom() == null) != (raw.windowTo() == null)) {
            throw new ImportException("Hãy nhập đủ cả giờ bắt đầu và giờ kết thúc của khung giờ riêng, hoặc để trống cả hai.");
        }
        if (raw.windowFrom() != null) {
            if (raw.windowFrom().isBefore(p.msgWindowStart()) || raw.windowTo().isAfter(p.msgWindowEnd())) {
                throw new ImportException("Khung giờ riêng phải nằm trong khung giờ gửi của hệ thống (" + p.msgWindowStart() + " đến "
                        + p.msgWindowEnd() + "). Chiến dịch chỉ được thu hẹp, không được nới.");
            }
            if (Duration.between(raw.windowFrom(), raw.windowTo()).toMinutes() < MIN_WINDOW_MINUTES) {
                throw new ImportException("Khung giờ riêng phải rộng ít nhất " + MIN_WINDOW_MINUTES + " phút.");
            }
        }
        if (raw.dailyCap() != null && (raw.dailyCap() < 1 || raw.dailyCap() > sys.daily())) {
            throw new ImportException("Hạn mức ngày riêng phải từ 1 đến " + sys.daily()
                    + " khách (không được cao hơn hạn mức chung đang đặt ở Zalo tài khoản phụ).");
        }
        if (raw.gapSeconds() != null && (raw.gapSeconds() < sys.gapSeconds() || raw.gapSeconds() > MAX_GAP_SECONDS)) {
            throw new ImportException("Khoảng nghỉ riêng phải từ " + sys.gapSeconds() + " đến " + MAX_GAP_SECONDS
                    + " giây (không được ngắn hơn khoảng nghỉ chung).");
        }
    }

    private static LocalTime max(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalTime min(LocalTime a, LocalTime b) {
        return a.isBefore(b) ? a : b;
    }
}
