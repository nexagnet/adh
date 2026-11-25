package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cảnh báo sớm khi tài khoản Zalo phụ có dấu hiệu bị hạn chế. KHÔNG làm gì với Zalo: chỉ ĐỌC trạng thái cầu dao, trạng thái cầu nối
 * (lần hỏi gần nhất) và sổ tin đã gửi, rồi ghi/đóng cảnh báo ở bảng {@code zalo_health_alert}. Cầu dao vẫn là thứ dừng việc gửi;
 * lớp này chỉ làm cho người thấy, bằng chữ dễ hiểu, và (nếu có cấu hình) báo ra ngoài.
 *
 * <p>Dấu hiệu: bị đăng xuất hoặc mất phiên; Zalo báo hạn chế hoặc từ chối hàng loạt; mã lỗi lạ hoặc không biết kết quả; đăng nhập nhầm tài khoản
 * hoặc mở nơi khác; tỷ lệ gửi lỗi cao trong 24 giờ (báo TRƯỚC khi cầu dao nhảy); cầu nối không gọi được một lúc lâu.
 * Người tự bấm dừng hoặc đăng xuất thì KHÔNG phải cảnh báo. Mỗi loại có tối đa một cảnh báo đang mở; mở mới hoặc nặng lên thì báo ngoài một lần (kênh báo lỗi thì thử lại mỗi nhịp cho tới khi được).
 * Không có dữ liệu khách trong cảnh báo: chỉ mã kỹ thuật ngắn hoặc tỷ lệ.
 */
@Service
public class ZaloHealthService implements ZaloTickHook {

    private static final Logger log = LoggerFactory.getLogger(ZaloHealthService.class);

    public static final String K_LOGIN = "MAT_PHIEN";
    public static final String K_RESTRICTED = "HAN_CHE";
    public static final String K_STRANGE = "LOI_LA";
    public static final String K_ACCOUNT = "SAI_TAI_KHOAN";
    public static final String K_STOPPED = "DUNG_KHAC";
    public static final String K_FAIL_RATE = "TY_LE_LOI";
    public static final String K_BRIDGE_DOWN = "CAU_NOI_MAT";
    public static final String K_REFUSAL = "TU_CHOI_CAO";

    /** Số tin tối thiểu trong 24 giờ để xét tỷ lệ lỗi, và các ngưỡng. */
    static final int MIN_ATTEMPTS = 5;
    static final int WARN_PERCENT = 30;
    static final int CRITICAL_PERCENT = 60;
    static final Duration BRIDGE_DOWN_AFTER = Duration.ofMinutes(15);
    static final Duration EVAL_EVERY = Duration.ofSeconds(60);

    private static final Set<String> BENIGN = Set.of("DUNG_TAY", "DANG_XUAT");
    private static final Set<String> LOGIN = Set.of("SESSION_LOST", "LOGIN_REQUIRED");
    private static final Set<String> RESTRICTED = Set.of("RESTRICTED", "NHIEU_KHONG_TIM_THAY", "NHIEU_LOI_NGUOI_TIN", "KICKED");
    private static final Set<String> STRANGE = Set.of("SYSTEM_ERROR", "INTERRUPTED", "TIN_INTERRUPTED", "TAO_NHOM_INTERRUPTED",
            "STATE_CORRUPT", "LEDGER_CORRUPT");
    private static final Set<String> ACCOUNT = Set.of("ACCOUNT_MISMATCH", "DUPLICATE_CONNECTION");

    /** Một dấu hiệu đang có ở thời điểm này. {@code detail} chỉ là mã kỹ thuật ngắn hoặc tỷ lệ. */
    public record Signal(String kind, String severity, String detail) {
    }

    public record Alert(long id, String kind, String severity, String detail, LocalDateTime openedAt,
                        LocalDateTime lastSeenAt, LocalDateTime resolvedAt) {
        public boolean critical() {
            return "CRITICAL".equals(severity);
        }

        public boolean open() {
            return resolvedAt == null;
        }
    }

    private final JdbcClient jdbc;
    private final ZaloAutoSettings cfg;
    private final ZaloAutoService service;
    private final ObjectProvider<ZaloHealthNotifier> notifier;
    private final ZaloLabels labels;
    private final Clock clock;

    private Instant unreachableSince;
    private Instant lastEval = Instant.EPOCH;

    public ZaloHealthService(JdbcClient jdbc, ZaloAutoSettings cfg, ZaloAutoService service,
                             ObjectProvider<ZaloHealthNotifier> notifier, ZaloLabels labels, Clock clock) {
        this.jdbc = jdbc;
        this.cfg = cfg;
        this.service = service;
        this.notifier = notifier;
        this.labels = labels;
        this.clock = clock;
    }

    @Override
    public void tick() {
        Instant now = clock.instant();
        synchronized (this) {
            if (Duration.between(lastEval, now).compareTo(EVAL_EVERY) < 0) {
                return;
            }
            lastEval = now;
        }
        evaluate();
    }

    // ---------- tính dấu hiệu ----------

    /** Các dấu hiệu hiện có. Rỗng khi bộ nối tắt (công tắc môi trường hoặc nấc OFF). Không gọi cầu nối, không ghi gì. */
    public synchronized List<Signal> signals() {
        Instant now = clock.instant();
        Map<String, Signal> out = new LinkedHashMap<>();
        if (!cfg.props().enabled() || cfg.chosenMode() == ZaloMode.OFF) {
            unreachableSince = null;
            return List.of();
        }
        boolean stoppedByPerson = false;
        if (cfg.tripped()) {
            Signal s = fromTrip(cfg.trippedReason());
            if (s != null) {
                out.put(s.kind(), s);
            } else {
                stoppedByPerson = true; // chính người bấm dừng hoặc đăng xuất: cần quét QR lại cũng không phải cảnh báo
            }
        }
        ZaloGateway.Status st = service.lastBridgeStatus();
        if (st != null && st.state() == ZaloGateway.State.LOGIN_REQUIRED && !stoppedByPerson) {
            out.putIfAbsent(K_LOGIN, new Signal(K_LOGIN, "CRITICAL", "LOGIN_REQUIRED"));
        }
        if (st != null && Boolean.FALSE.equals(st.accountOk())) {
            out.putIfAbsent(K_ACCOUNT, new Signal(K_ACCOUNT, "CRITICAL", "ACCOUNT_MISMATCH"));
        }
        if (st != null && st.state() == ZaloGateway.State.UNREACHABLE) {
            if (unreachableSince == null) {
                unreachableSince = now;
            }
            if (Duration.between(unreachableSince, now).compareTo(BRIDGE_DOWN_AFTER) >= 0) {
                out.put(K_BRIDGE_DOWN, new Signal(K_BRIDGE_DOWN, "WARN", null));
            }
        } else if (st != null) {
            unreachableSince = null;
        }
        failRate(now).ifPresent(s -> out.put(s.kind(), s));
        // chiến dịch bị hệ thống tự bỏ duyệt vì khách từ chối nhiều và chưa ai duyệt lại: cần người xem
        long stoppedCampaigns = jdbc.sql("""
                        SELECT count(*) FROM invitation_campaign WHERE auto_stopped_at IS NOT NULL AND auto_send_approved_at IS NULL""")
                .query(Long.class).single();
        if (stoppedCampaigns > 0) {
            out.put(K_REFUSAL, new Signal(K_REFUSAL, "WARN", Long.toString(stoppedCampaigns)));
        }
        return new ArrayList<>(out.values());
    }

    /** Lý do cầu dao nhảy → dấu hiệu; null nếu do chính người bấm (không phải cảnh báo). */
    Signal fromTrip(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String r = reason.startsWith("BRIDGE_") ? reason.substring("BRIDGE_".length()) : reason;
        if (r.startsWith("TRA_SO_ME_")) { // lỗi lạ khi tra số của mẹ: cùng ý nghĩa với kết quả gốc (RESTRICTED, SYSTEM_ERROR...)
            r = r.substring("TRA_SO_ME_".length());
        }
        int colon = r.indexOf(':');
        String head = colon > 0 ? r.substring(0, colon) : r;
        if (BENIGN.contains(head)) {
            return null;
        }
        String detail = sanitize(reason);
        if (LOGIN.contains(head)) {
            return new Signal(K_LOGIN, "CRITICAL", detail);
        }
        if (RESTRICTED.contains(head)) {
            return new Signal(K_RESTRICTED, "CRITICAL", detail);
        }
        if (STRANGE.contains(head)) {
            return new Signal(K_STRANGE, "CRITICAL", detail);
        }
        if (ACCOUNT.contains(head)) {
            return new Signal(K_ACCOUNT, "CRITICAL", detail);
        }
        return new Signal(K_STOPPED, "WARN", detail);
    }

    /** Tỷ lệ tin lỗi trong 24 giờ (không tính "không tìm thấy trên Zalo", vì khách không dùng Zalo là chuyện thường). */
    private java.util.Optional<Signal> failRate(Instant now) {
        var row = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE outcome <> 'DANG_GOI') AS attempts,
                               count(*) FILTER (WHERE outcome NOT IN ('OK', 'DANG_GOI')
                                                  AND NOT (outcome = 'PERSON_ERROR' AND code = 'NOT_FOUND')) AS fails
                        FROM zalo_msg_log WHERE account_id = :acct AND at >= :since""")
                .param("acct", ZaloAccountContext.current())
                .param("since", OffsetDateTime.ofInstant(now.minus(Duration.ofHours(24)), ZoneOffset.UTC))
                .query((rs, i) -> new int[]{rs.getInt("attempts"), rs.getInt("fails")}).single();
        int attempts = row[0];
        int fails = row[1];
        if (attempts < MIN_ATTEMPTS) {
            return java.util.Optional.empty();
        }
        int percent = fails * 100 / attempts;
        if (percent < WARN_PERCENT) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Signal(K_FAIL_RATE, percent >= CRITICAL_PERCENT ? "CRITICAL" : "WARN",
                fails + "/" + attempts));
    }

    /** Chỉ giữ ký tự an toàn của mã kỹ thuật (không để lọt chữ lạ vào cơ sở dữ liệu, trang hay tin báo). */
    static String sanitize(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_:.\\-/]", "");
        return s.length() <= 60 ? s : s.substring(0, 60);
    }

    // ---------- ghi, đóng và báo ----------

    /** Tính dấu hiệu rồi đồng bộ bảng cảnh báo; báo ra ngoài các cảnh báo mới mở hoặc nặng lên. Trả các dấu hiệu hiện có. */
    public synchronized List<Signal> evaluate() {
        List<Signal> now = signals();
        Instant t = clock.instant();
        OffsetDateTime at = OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
        Set<String> active = new java.util.HashSet<>();
        for (Signal s : now) {
            active.add(s.kind());
            var open = jdbc.sql("SELECT id, severity FROM zalo_health_alert WHERE " + ZaloAccountContext.sql() + " AND kind = :k AND resolved_at IS NULL")
                    .param("k", s.kind()).query((rs, i) -> new Object[]{rs.getLong("id"), rs.getString("severity")}).optional();
            if (open.isEmpty()) {
                jdbc.sql("""
                                INSERT INTO zalo_health_alert (kind, severity, detail, opened_at, last_seen_at, account_id)
                                VALUES (:k, :s, :d, :at, :at, :acct)""")
                        .param("acct", ZaloAccountContext.current())
                        .param("k", s.kind()).param("s", s.severity()).param("d", s.detail(), java.sql.Types.VARCHAR)
                        .param("at", at).update();
            } else {
                boolean worse = "WARN".equals(open.get()[1]) && "CRITICAL".equals(s.severity());
                jdbc.sql("""
                                UPDATE zalo_health_alert SET severity = :s, detail = :d, last_seen_at = :at,
                                       notified_at = CASE WHEN :worse THEN NULL ELSE notified_at END
                                WHERE id = :id""")
                        .param("s", s.severity()).param("d", s.detail(), java.sql.Types.VARCHAR).param("at", at)
                        .param("worse", worse).param("id", (Long) open.get()[0]).update();
            }
        }
        jdbc.sql("UPDATE zalo_health_alert SET resolved_at = :at WHERE " + ZaloAccountContext.sql() + " AND resolved_at IS NULL AND NOT (kind = ANY(:active))")
                .param("at", at).param("active", active.toArray(new String[0])).update();
        notifyOutside(at);
        return now;
    }

    /** Báo ra ngoài mọi cảnh báo đang mở mà chưa báo được (mới mở, nặng lên, hoặc lần trước kênh báo lỗi: thử lại mỗi nhịp). */
    private void notifyOutside(OffsetDateTime at) {
        ZaloHealthNotifier n = notifier.getIfAvailable();
        if (n == null) {
            return;
        }
        var pending = jdbc.sql("SELECT kind, severity, detail FROM zalo_health_alert WHERE " + ZaloAccountContext.sql() + " AND resolved_at IS NULL AND notified_at IS NULL")
                .query((rs, i) -> new Signal(rs.getString("kind"), rs.getString("severity"), rs.getString("detail"))).list();
        for (Signal s : pending) {
            String text = "Zalo tài khoản phụ" + (ZaloAccountContext.current() == ZaloAccountContext.DEFAULT_ACCOUNT ? "" : " số " + ZaloAccountContext.current()) + ("CRITICAL".equals(s.severity()) ? " (KHẨN)" : " (lưu ý)") + ": "
                    + labels.healthTitle(s.kind()) + (s.detail() == null ? "" : " [" + s.detail() + "]")
                    + ". Mở trang Zalo tài khoản phụ trong adh để xem việc cần làm.";
            boolean ok;
            try {
                ok = n.send(text);
            } catch (RuntimeException e) {
                log.warn("Kênh báo ngoài lỗi: {}", e.getClass().getSimpleName());
                ok = false;
            }
            if (ok) {
                jdbc.sql("UPDATE zalo_health_alert SET notified_at = :at WHERE " + ZaloAccountContext.sql() + " AND kind = :k AND resolved_at IS NULL")
                        .param("at", at).param("k", s.kind()).update();
            }
        }
    }

    // ---------- để hiển thị ----------

    /** Cảnh báo đang mở, nặng nhất trước. Chỉ đọc bảng nên mở trang không tốn thêm lệnh gọi Zalo. */
    public List<Alert> open() {
        return query("WHERE resolved_at IS NULL", 20);
    }

    /** Lịch sử gần đây (đã đóng và đang mở). */
    public List<Alert> recent(int limit) {
        return query("", limit);
    }

    public long openCriticalCount() {
        return jdbc.sql("SELECT count(*) FROM zalo_health_alert WHERE resolved_at IS NULL AND severity = 'CRITICAL'")
                .query(Long.class).single();
    }

    private List<Alert> query(String where, int limit) {
        var zone = clock.getZone();
        return jdbc.sql("SELECT * FROM zalo_health_alert " + where
                        + " ORDER BY (resolved_at IS NULL) DESC, (severity = 'CRITICAL') DESC, opened_at DESC LIMIT :n")
                .param("n", limit)
                .query((rs, i) -> new Alert(rs.getLong("id"), rs.getString("kind"), rs.getString("severity"),
                        rs.getString("detail"),
                        rs.getObject("opened_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDateTime(),
                        rs.getObject("last_seen_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDateTime(),
                        java.util.Optional.ofNullable(rs.getObject("resolved_at", OffsetDateTime.class))
                                .map(t -> t.atZoneSameInstant(zone).toLocalDateTime()).orElse(null)))
                .list();
    }
}
