package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Nấc, hạn mức, cầu dao và nhật ký của bộ nối (D16, ADR-0002 mục 3.3–3.4). Lưu ở bảng cài đặt; mọi thay đổi ghi người làm.
 * Nhật ký không có số điện thoại, tên hay uid.
 */
@Service
public class ZaloAutoSettings {

    static final String MODE = "zalo.auto.mode";
    static final String DAILY = "zalo.auto.daily";
    static final String HOURLY = "zalo.auto.hourly";
    static final String GAP = "zalo.auto.gap_minutes";
    static final String CURSOR = "zalo.auto.cursor";
    static final String TRIPPED_AT = "zalo.auto.tripped_at";
    static final String TRIPPED_REASON = "zalo.auto.tripped_reason";

    public static final String A_LISTEN = "NGHE";
    public static final String A_MATCH = "GHEP";
    public static final String A_APPROVE = "DUYET";
    public static final String A_ADD = "THEM_NHOM";
    public static final String A_STOP = "DUNG";
    public static final String A_REOPEN = "MO_LAI";
    public static final String A_CONFIG = "CAU_HINH";
    public static final String A_LOGIN = "DANG_NHAP";
    public static final String A_LOGOUT = "DANG_XUAT";

    /** Số thao tác đã tính và giới hạn hiện hành. */
    public record Limits(int daily, int hourly, int gapMinutes) {
    }

    public record Quota(Limits limits, int usedToday, int usedLastHour, Instant lastAddAt, boolean inWindow,
                        Instant nextPossible) {
    }

    public record LogRow(long id, java.time.LocalDateTime at, String action, Long invitationId, Long groupId,
                         String result, String code, String actor) {
    }

    private final AppSettings settings;
    private final JdbcClient jdbc;
    private final ZaloAutoProperties props;
    private final Clock clock;

    public ZaloAutoSettings(AppSettings settings, JdbcClient jdbc, ZaloAutoProperties props, Clock clock) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public ZaloAutoProperties props() {
        return props;
    }

    public Clock clock() {
        return clock;
    }

    // ---------- nấc ----------

    /** Nấc người dùng chọn (chưa kể công tắc môi trường và cầu dao). Mặc định OFF. */
    public ZaloMode chosenMode() {
        return ZaloMode.parse(settings.get(MODE).orElse(null));
    }

    /**
     * Nấc đang có hiệu lực: OFF nếu chưa bật biến môi trường hoặc cầu dao đang nhảy. Bật cần CẢ HAI công tắc
     * (biến môi trường {@code APP_ZALO_AUTO_ENABLED} và nấc chọn ở trang quản trị).
     */
    public ZaloMode effectiveMode() {
        if (!props.enabled() || tripped()) {
            return ZaloMode.OFF;
        }
        return chosenMode();
    }

    /**
     * Đổi nấc. {@link ZaloMode#AUTO} chỉ được chọn khi người bấm xác nhận đã chạy thử xong các điểm chưa kiểm chứng
     * (ADR-0002 mục 3.3): mặc định vẫn là {@link ZaloMode#CONFIRM}.
     */
    public void setMode(ZaloMode mode, boolean confirmedTestedAuto, String by) {
        if (!props.enabled()) {
            throw new ImportException("Bộ nối Zalo chưa được bật ở cấu hình triển khai (APP_ZALO_AUTO_ENABLED).");
        }
        if (mode == ZaloMode.AUTO && !confirmedTestedAuto) {
            throw new ImportException("Muốn bật nấc tự thêm, hãy tick xác nhận đã chạy thử xong ở nấc mẹ duyệt.");
        }
        ZaloMode old = chosenMode();
        if (old == mode) {
            return;
        }
        settings.set(MODE, mode.name(), by);
        log(A_CONFIG, null, null, "NAC_" + mode.name(), "TU_" + old.name(), by);
    }

    // ---------- hạn mức ----------

    public Limits limits() {
        return new Limits(clamp(intSetting(DAILY, props.defaultDaily()), 1, props.maxDaily()),
                clamp(intSetting(HOURLY, props.defaultHourly()), 1, props.maxHourly()),
                Math.max(props.minGapFloorMinutes(), intSetting(GAP, props.defaultGapMinutes())));
    }

    /** Chỉnh hạn mức trên trang quản trị, không vượt trần cứng ở cấu hình. Ghi giá trị cũ và mới. */
    public Limits setLimits(int daily, int hourly, int gapMinutes, String by) {
        if (daily < 1 || daily > props.maxDaily()) {
            throw new ImportException("Số người mỗi ngày phải từ 1 đến " + props.maxDaily()
                    + " (trần cứng đặt ở cấu hình triển khai).");
        }
        if (hourly < 1 || hourly > props.maxHourly()) {
            throw new ImportException("Số người mỗi giờ phải từ 1 đến " + props.maxHourly() + ".");
        }
        if (gapMinutes < props.minGapFloorMinutes() || gapMinutes > 240) {
            throw new ImportException("Khoảng cách giữa hai lần thêm phải từ " + props.minGapFloorMinutes() + " đến 240 phút.");
        }
        Limits old = limits();
        settings.set(DAILY, Integer.toString(daily), by);
        settings.set(HOURLY, Integer.toString(hourly), by);
        settings.set(GAP, Integer.toString(gapMinutes), by);
        log(A_CONFIG, null, null, "HAN_MUC " + daily + "/" + hourly + "/" + gapMinutes,
                "CU " + old.daily() + "/" + old.hourly() + "/" + old.gapMinutes(), by);
        return limits();
    }

    private int intSetting(String key, int fallback) {
        return settings.get(key).map(v -> {
            try {
                return Integer.parseInt(v.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------- cầu dao ----------

    public boolean tripped() {
        return settings.get(TRIPPED_AT).filter(v -> !v.isBlank()).isPresent();
    }

    public String trippedReason() {
        return settings.get(TRIPPED_REASON).orElse("");
    }

    public Instant trippedAt() {
        return settings.get(TRIPPED_AT).filter(v -> !v.isBlank()).map(Instant::parse).orElse(null);
    }

    /** Dừng ngay và giữ nguyên cho đến khi người bấm mở lại. Không tự thử lại. */
    public void trip(String reason, String by) {
        if (tripped()) {
            return;
        }
        settings.set(TRIPPED_REASON, reason, by);
        settings.set(TRIPPED_AT, clock.instant().toString(), by);
        log(A_STOP, null, null, "TRIPPED", reason, by);
    }

    public void reopen(String by) {
        settings.set(TRIPPED_AT, "", by);
        settings.set(TRIPPED_REASON, "", by);
        log(A_REOPEN, null, null, "OK", null, by);
    }

    // ---------- con trỏ sự kiện ----------

    public long cursor() {
        return settings.get(CURSOR).map(v -> {
            try {
                return Long.parseLong(v.strip());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    public void setCursor(long seq) {
        settings.set(CURSOR, Long.toString(seq), "system");
    }

    // ---------- nhật ký và hạn mức đã dùng ----------

    public void log(String action, Long invitationId, Long groupId, String result, String code, String actor) {
        jdbc.sql("""
                        INSERT INTO zalo_auto_log (at, action, invitation_id, group_id, result, code, actor)
                        VALUES (:at, :a, :i, :g, :r, :c, :by)""")
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("a", action).param("i", invitationId, java.sql.Types.BIGINT).param("g", groupId, java.sql.Types.BIGINT)
                .param("r", truncate(result, 80)).param("c", code == null ? null : truncate(code, 80), java.sql.Types.VARCHAR)
                .param("by", actor == null ? "system" : actor).update();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public List<LogRow> recentLog(int limit) {
        ZoneId zone = clock.getZone();
        return jdbc.sql("SELECT * FROM zalo_auto_log ORDER BY id DESC LIMIT :n").param("n", limit)
                .query((rs, i) -> new LogRow(rs.getLong("id"),
                        rs.getObject("at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDateTime(),
                        rs.getString("action"), (Long) rs.getObject("invitation_id"), (Long) rs.getObject("group_id"),
                        rs.getString("result"), rs.getString("code"), rs.getString("actor"))).list();
    }

    /** Số lần thêm vào nhóm tính từ mốc (kể cả lần lỗi: đếm cả lần lỗi, ADR mục 3.4). */
    private int addsSince(Instant since) {
        return jdbc.sql("SELECT count(*) FROM zalo_auto_log WHERE action = :a AND at >= :s")
                .param("a", A_ADD).param("s", OffsetDateTime.ofInstant(since, ZoneOffset.UTC)).query(Integer.class).single();
    }

    private Instant lastAdd() {
        return jdbc.sql("SELECT max(at) FROM zalo_auto_log WHERE action = :a").param("a", A_ADD)
                .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
    }

    public boolean inWindow() {
        LocalTime t = clock.instant().atZone(clock.getZone()).toLocalTime();
        return !t.isBefore(props.windowStart()) && t.isBefore(props.windowEnd());
    }

    /** Hạn mức đã dùng và lúc sớm nhất được thêm tiếp (chỉ để hiển thị; việc quyết định là {@link #mayAddNow()}). */
    public Quota quota() {
        Limits l = limits();
        Instant now = clock.instant();
        LocalDate today = now.atZone(clock.getZone()).toLocalDate();
        int day = addsSince(today.atStartOfDay(clock.getZone()).toInstant());
        int hour = addsSince(now.minusSeconds(3600));
        Instant last = lastAdd();
        Instant next = last == null ? now : last.plusSeconds(l.gapMinutes() * 60L);
        if (next.isBefore(now)) {
            next = now;
        }
        return new Quota(l, day, hour, last, inWindow(), next);
    }

    /** Còn được thêm một người ngay lúc này không: trong khung giờ, còn hạn mức ngày và giờ, đủ khoảng cách. */
    public boolean mayAddNow() {
        Quota q = quota();
        return q.inWindow() && q.usedToday() < q.limits().daily() && q.usedLastHour() < q.limits().hourly()
                && !clock.instant().isBefore(q.nextPossible());
    }
}
