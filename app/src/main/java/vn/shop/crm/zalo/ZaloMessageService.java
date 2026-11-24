package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.zalo.ZaloMessenger.Kind;
import vn.shop.crm.zalo.ZaloMessenger.Result;
import vn.shop.crm.zalo.ZaloMessenger.Sent;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cổng DUY NHẤT để Adh gửi tin cho khách qua Zalo (D18, ADR-0002 mục 12). Chiến dịch thật và chiến dịch thử đều đi qua đây nên dùng
 * CHUNG một công tắc, một hạn mức ngày, một khoảng nghỉ và một cầu dao.
 *
 * <p>Chốt chặn, tất cả công khai và cố định, KHÔNG có yếu tố ngẫu nhiên và không cố giống người thật: công tắc tắt/bật (mặc định
 * TẮT), nấc bộ nối không phải "Tắt" và cầu dao không nhảy, khung giờ (mặc định 08:00–20:00), hạn mức tin mỗi ngày (mặc định 10, trần
 * cứng 25, tính cả Tin 1 và Tin 2, cả lần lỗi), khoảng nghỉ CỐ ĐỊNH giữa hai tin (mặc định 120 giây, sàn 100), mỗi lần chỉ MỘT
 * tin. Lỗi lạ, Zalo cảnh báo, mất phiên, không biết kết quả: cầu dao nhảy và giữ nguyên cho đến khi người bấm "Mở lại". Sổ
 * {@code zalo_msg_log} không chứa số điện thoại, tên, mã Zalo hay nội dung tin.
 */
@Service
public class ZaloMessageService {

    private static final Logger log = LoggerFactory.getLogger(ZaloMessageService.class);

    static final String ENABLED = "zalo.msg.enabled";
    static final String DAILY = "zalo.msg.daily";
    static final String GAP = "zalo.msg.gap_seconds";
    /**
     * Khoảng nghỉ CỐ ĐỊNH (không ngẫu nhiên) riêng cho tin trả lời người vừa tự nhắn tới (chủ dự án chốt 24/11/2026), tính từ tin gần nhất bất kể loại.
     * Ngắn hơn khoảng nghỉ của tin shop chủ động nhắn để khách đang chờ không phải đợi cả giờ khi nhiều người nhắn cùng lúc. Cầu nối có sàn riêng
     * (BRIDGE_REPLY_GAP_SEC, mặc định bằng số này) và phải ≤ số này.
     */
    public static final int REPLY_GAP_SECONDS = 30;
    static final String FRIEND_DAILY = "zalo.friend.daily";
    public static final String SCOPE_REAL = "REAL";
    /** Số lần liền lỗi riêng người (mã lạ) thì dừng, và số lần liền "không tìm thấy" thì dừng để người kiểm tra. */
    static final int MAX_CONSECUTIVE_PERSON_ERRORS = 3;
    static final int MAX_CONSECUTIVE_NOT_FOUND = 5;

    public record Limits(int daily, int gapSeconds) {
    }

    public enum Block {
        SWITCH_OFF("Công tắc tự gửi tin đang TẮT"),
        MODE_OFF("Bộ nối Zalo đang tắt hoặc đã dừng (cầu dao)"),
        OUTSIDE_WINDOW("Ngoài khung giờ được gửi"),
        DAILY_LIMIT("Đã đủ số khách được nhắn trong ngày"),
        GAP("Chưa đủ khoảng nghỉ sau tin trước"),
        BUSY("Đang có một tin khác được gửi"),
        NOT_READY("Cầu nối chưa nghe được hoặc chưa chắc đúng tài khoản phụ");

        private final String label;

        Block(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Status {
        /** Cầu nối nhận và đã gửi. */
        SENT,
        /** Lỗi riêng người nhận (không tìm thấy trên Zalo...). Đã tính vào hạn mức nếu đã chạm Zalo. */
        PERSON_ERROR,
        /** Chưa gửi, thử lại ở nhịp sau (cầu nối bận, gửi quá sớm, không nối được). */
        RETRY,
        /** Cầu dao đã nhảy. {@link Delivery#sent()} cho biết cầu nối có CHẮC CHẮN chưa gửi hay không. */
        TRIPPED,
        /** Bị chặn trước khi gọi, xem {@link Delivery#block()}. */
        BLOCKED
    }

    public record Delivery(Status status, Block block, Sent sent) {
        static Delivery blocked(Block b) {
            return new Delivery(Status.BLOCKED, b, null);
        }

        public String uid() {
            return sent == null ? null : sent.uid();
        }

        public String code() {
            return sent == null ? null : sent.code();
        }

        /** Chắc chắn chưa có tin nào đi (để trả lời mời về hàng đợi); false = đã đi hoặc không biết. */
        public boolean certainlyNotSent() {
            return status == Status.BLOCKED || status == Status.RETRY || (sent != null && sent.notSent());
        }
    }

    public record Quota(boolean enabled, Limits limits, int usedToday, int maxDaily, Instant lastAt, Instant nextPossible,
                        boolean inWindow, LocalTime windowStart, LocalTime windowEnd) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final ZaloAutoSettings cfg;
    private final ZaloLink link;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public ZaloMessageService(JdbcClient jdbc, AppSettings settings, ZaloAutoSettings cfg, ZaloLink link, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.cfg = cfg;
        this.link = link;
        this.clock = clock;
    }

    // ---------- công tắc và hạn mức ----------

    public boolean enabled() {
        return cfg.props().enabled() && settings.flag(ENABLED);
    }

    /** Bật/tắt tự gửi tin. Chỉ bật được khi bộ nối đã bật ở cấu hình triển khai. Mặc định TẮT. */
    public void setEnabled(boolean on, String by) {
        if (on && !cfg.props().enabled()) {
            throw new ImportException("Bộ nối Zalo chưa được bật ở cấu hình triển khai (APP_ZALO_AUTO_ENABLED).");
        }
        if (settings.flag(ENABLED) == on) {
            return;
        }
        settings.set(ENABLED, Boolean.toString(on), by);
        cfg.log(ZaloAutoSettings.A_CONFIG, null, null, on ? "TIN_BAT" : "TIN_TAT", null, by);
    }

    public Limits limits() {
        ZaloAutoProperties p = cfg.props();
        return new Limits(clamp(intSetting(DAILY, p.msgDefaultDaily()), 1, p.msgMaxDaily()),
                Math.max(p.msgMinGapSeconds(), intSetting(GAP, p.msgDefaultGapSeconds())));
    }

    /** Trần lời mời kết bạn mỗi ngày (mặc định 10, trần cứng đặt ở cấu hình triển khai; 0 = tắt). */
    public int friendDaily() {
        ZaloAutoProperties p = cfg.props();
        return clamp(intSetting(FRIEND_DAILY, p.friendDefaultDaily()), 0, p.friendMaxDaily());
    }

    public int setFriendDaily(int daily, String by) {
        ZaloAutoProperties p = cfg.props();
        if (daily < 0 || daily > p.friendMaxDaily()) {
            throw new ImportException("Số lời mời kết bạn mỗi ngày phải từ 0 (tắt) đến " + p.friendMaxDaily() + " (trần cứng đặt ở cấu hình triển khai).");
        }
        int old = friendDaily();
        settings.set(FRIEND_DAILY, Integer.toString(daily), by);
        cfg.log(ZaloAutoSettings.A_CONFIG, null, null, "HAN_MUC_KET_BAN " + daily, "CU " + old, by);
        return friendDaily();
    }

    /** Số lời mời kết bạn đã gọi hôm nay (tính cả lần lỗi). */
    public int friendUsedToday() {
        return jdbc.sql("SELECT count(*) FROM zalo_msg_log WHERE at >= :s AND kind = 'FRIEND'")
                .param("s", OffsetDateTime.ofInstant(startOfDay(), ZoneOffset.UTC)).query(Integer.class).single();
    }

    public Limits setLimits(int daily, int gapSeconds, String by) {
        ZaloAutoProperties p = cfg.props();
        if (daily < 1 || daily > p.msgMaxDaily()) {
            throw new ImportException("Số tin mỗi ngày phải từ 1 đến " + p.msgMaxDaily() + " (trần cứng đặt ở cấu hình triển khai).");
        }
        if (gapSeconds < p.msgMinGapSeconds() || gapSeconds > 3600) {
            throw new ImportException("Khoảng nghỉ giữa hai tin phải từ " + p.msgMinGapSeconds() + " đến 3600 giây.");
        }
        Limits old = limits();
        settings.set(DAILY, Integer.toString(daily), by);
        settings.set(GAP, Integer.toString(gapSeconds), by);
        cfg.log(ZaloAutoSettings.A_CONFIG, null, null, "HAN_MUC_TIN " + daily + "/" + gapSeconds,
                "CU " + old.daily() + "/" + old.gapSeconds(), by);
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

    /** Ghi một dòng nhật ký bộ nối cho việc của người dùng liên quan tới tự gửi tin (duyệt chiến dịch...). Không có số, tên, nội dung. */
    public void audit(String result, long refId, String by) {
        cfg.log(ZaloAutoSettings.A_CONFIG, null, null, result + " " + refId, null, by);
    }

    // ---------- đọc để hiển thị và để quyết định ----------

    private boolean inWindow() {
        LocalTime t = clock.instant().atZone(clock.getZone()).toLocalTime();
        return !t.isBefore(cfg.props().msgWindowStart()) && t.isBefore(cfg.props().msgWindowEnd());
    }

    private Instant startOfDay() {
        return clock.instant().atZone(clock.getZone()).toLocalDate().atStartOfDay(clock.getZone()).toInstant();
    }

    /** Đếm các lần gọi trong sổ tin. {@code invitesOnly}: chỉ tin shop chủ động nhắn (Tin 1 và tin nhắc), không tính Tin 2 và tin trả lời. */
    private int countSince(Instant since, String scope, boolean invitesOnly) {
        String where = (scope == null ? "" : " AND scope = :sc") + (invitesOnly ? " AND kind IN ('INVITE', 'FOLLOWUP2', 'FOLLOWUP3')" : "");
        var spec = jdbc.sql("SELECT count(*) FROM zalo_msg_log WHERE at >= :s" + where)
                .param("s", OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        if (scope != null) {
            spec = spec.param("sc", scope);
        }
        return spec.query(Integer.class).single();
    }

    private Instant lastAt(String scope) {
        String where = scope == null ? "" : " WHERE scope = :sc";
        var spec = jdbc.sql("SELECT max(at) FROM zalo_msg_log" + where);
        if (scope != null) {
            spec = spec.param("sc", scope);
        }
        return spec.query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
    }

    /** Số tin chủ động hôm nay (Tin 1 và tin nhắc của chiến dịch nhiều lần). Tin 2 và tin mẫu trả lời không tính vào hạn mức ngày. */
    public int usedToday(String scope) {
        return countSince(startOfDay(), scope, true);
    }

    /** Lần gửi tin chủ động kế tiếp: lúc dự kiến ({@code at}, rỗng nếu chưa biết) và lý do đang chờ ({@code reason}, rỗng nếu chỉ đang đếm khoảng nghỉ). */
    public record NextSend(Instant at, String reason) {
    }

    /** Để hiện bộ đếm ngược trên giao diện. Chỉ đọc, không đổi gì. */
    public NextSend nextSend() {
        if (settings.paused()) {
            return new NextSend(null, "Đang tạm dừng tất cả. Bỏ tạm dừng thì hệ thống gửi tiếp");
        }
        Optional<Block> b = blocker(SCOPE_REAL, Kind.INVITE, null, null);
        Instant now = clock.instant();
        if (b.isEmpty()) {
            return new NextSend(now, null);
        }
        Limits l = limits();
        switch (b.get()) {
            case GAP -> {
                Instant last = lastAt(null);
                return new NextSend(last == null ? now : last.plusSeconds(l.gapSeconds()), null);
            }
            case DAILY_LIMIT, OUTSIDE_WINDOW -> {
                LocalDateTime n = LocalDateTime.ofInstant(now, clock.getZone());
                LocalTime ws = cfg.props().msgWindowStart();
                boolean today = b.get() == Block.OUTSIDE_WINDOW && n.toLocalTime().isBefore(ws);
                LocalDate day = today ? n.toLocalDate() : n.toLocalDate().plusDays(1);
                return new NextSend(day.atTime(ws).atZone(clock.getZone()).toInstant(),
                        b.get() == Block.DAILY_LIMIT ? "Đã đủ hạn mức ngày, tiếp tục vào ngày mai" : "Ngoài khung giờ gửi, tiếp tục khi mở khung giờ");
            }
            default -> {
                return new NextSend(null, b.get().label());
            }
        }
    }

    public Quota quota() {
        Limits l = limits();
        Instant last = lastAt(null);
        Instant now = clock.instant();
        Instant next = last == null ? now : last.plusSeconds(l.gapSeconds());
        if (next.isBefore(now)) {
            next = now;
        }
        return new Quota(enabled(), l, usedToday(null), cfg.props().msgMaxDaily(), last, next, inWindow(),
                cfg.props().msgWindowStart(), cfg.props().msgWindowEnd());
    }

    /**
     * Lý do KHÔNG được gửi ngay lúc này, hoặc rỗng nếu được. {@code scopeDaily}/{@code scopeGap} là hạn mức riêng của một
     * đợt thử (chỉ làm chặt hơn, không bao giờ nới hạn mức chung); null = không có.
     */
    public Optional<Block> blocker(String scope, Kind kind, Integer scopeDaily, Integer scopeGap) {
        if (!enabled()) {
            return Optional.of(Block.SWITCH_OFF);
        }
        if (!cfg.effectiveMode().listens()) {
            return Optional.of(Block.MODE_OFF);
        }
        if (!inWindow()) {
            return Optional.of(Block.OUTSIDE_WINDOW);
        }
        Limits l = limits();
        Instant now = clock.instant();
        // Hạn mức ngày là số tin shop chủ động nhắn (Tin 1 và tin nhắc, D21: tin nhắc tính vào hạn mức). Tin 2 (link nhóm) và tin mẫu trả lời người đã nhắn mình không bị giới hạn riêng,
        // nhưng vẫn chịu khung giờ, khoảng nghỉ cố định, cầu dao, và trần cứng của cầu nối.
        boolean invite = kind.proactive();
        if (invite && countSince(startOfDay(), null, true) >= l.daily()) {
            return Optional.of(Block.DAILY_LIMIT);
        }
        if (kind == Kind.FRIEND && friendUsedToday() >= friendDaily()) {
            return Optional.of(Block.DAILY_LIMIT);
        }
        Instant last = lastAt(null);
        int gap = kind.answersInbound() ? Math.min(REPLY_GAP_SECONDS, l.gapSeconds()) : l.gapSeconds();
        if (last != null && now.isBefore(last.plusSeconds(gap))) {
            return Optional.of(Block.GAP);
        }
        if (invite && scopeDaily != null && countSince(startOfDay(), scope, true) >= scopeDaily) {
            return Optional.of(Block.DAILY_LIMIT);
        }
        Instant lastScope = lastAt(scope);
        if (scopeGap != null && lastScope != null && now.isBefore(lastScope.plusSeconds(scopeGap))) {
            return Optional.of(Block.GAP);
        }
        return Optional.empty();
    }

    // ---------- gửi ----------

    /** Tin 1: tra số rồi gửi. {@code phoneDigits} dạng 849... */
    public Delivery sendByPhone(String scope, Kind kind, Long refId, String phoneDigits, String text, Integer scopeDaily, Integer scopeGap) {
        return deliver(scope, kind, refId, scopeDaily, scopeGap, () -> link.sendByPhone(kind, phoneDigits, text));
    }

    /** Tin 2: gửi cho mã Zalo đã biết. */
    public Delivery sendByUid(String scope, Kind kind, Long refId, String uid, String text, Integer scopeDaily, Integer scopeGap) {
        return deliver(scope, kind, refId, scopeDaily, scopeGap, () -> link.sendByUid(kind, uid, text));
    }

    /** Lời mời kết bạn cho người đã nhận Tin 1 (theo mã Zalo): cùng chốt chặn như tin, thêm trần lời mời mỗi ngày. */
    public Delivery sendFriendRequest(String scope, Long refId, String uid, String text) {
        return deliver(scope, Kind.FRIEND, refId, null, null, () -> link.sendFriendRequest(uid, text));
    }

    private Delivery deliver(String scope, Kind kind, Long refId, Integer scopeDaily, Integer scopeGap,
                             java.util.function.Supplier<Sent> call) {
        if (!lock.tryLock()) {
            return Delivery.blocked(Block.BUSY);
        }
        try {
            if (leftover()) {
                // Một lần gọi trước đó không có kết quả (sập giữa chừng): không biết tin đã đi hay chưa, dừng, không tự gửi lại.
                tripAndStop("TIN_INTERRUPTED");
                return new Delivery(Status.TRIPPED, null, Sent.of(Result.SYSTEM_ERROR, "INTERRUPTED", true));
            }
            Optional<Block> block = blocker(scope, kind, scopeDaily, scopeGap);
            if (block.isPresent()) {
                return Delivery.blocked(block.get());
            }
            ZaloGateway.Status st = link.status();
            if (st.state() == ZaloGateway.State.TRIPPED) {
                tripAndStop("BRIDGE_" + (st.tripReason() == null ? "TRIPPED" : st.tripReason()));
                return Delivery.blocked(Block.MODE_OFF);
            }
            if (Boolean.FALSE.equals(st.accountOk())) {
                tripAndStop("ACCOUNT_MISMATCH");
                return Delivery.blocked(Block.MODE_OFF);
            }
            if (!st.listening() || !Boolean.TRUE.equals(st.accountOk())) {
                return Delivery.blocked(Block.NOT_READY); // thất bại kín: chưa chắc đúng tài khoản phụ thì không gửi
            }
            // Ghi "đang gọi" TRƯỚC khi gọi: sập giữa chừng thì lần sau thấy dấu này mà dừng; sổ này cũng chính là hạn mức đã dùng.
            long logId = jdbc.sql("""
                            INSERT INTO zalo_msg_log (at, scope, kind, ref_id, outcome) VALUES (:at, :sc, :k, :r, 'DANG_GOI')
                            RETURNING id""")
                    .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("sc", scope)
                    .param("k", kind.name()).param("r", refId, java.sql.Types.BIGINT).query(Long.class).single();
            Sent s;
            try {
                s = call.get();
            } catch (RuntimeException e) {
                log.warn("Gửi tin qua cầu nối báo lỗi: {}", e.getClass().getSimpleName());
                s = Sent.of(Result.SYSTEM_ERROR, "BRIDGE_ERROR", false);
            }
            return finish(logId, s);
        } finally {
            lock.unlock();
        }
    }

    private Delivery finish(long logId, Sent s) {
        try {
            if (s.result() == Result.OK) {
                setOutcome(logId, "OK", null);
                return new Delivery(Status.SENT, null, s);
            }
            if (s.notSent()) {
                // Cầu nối chắc chắn chưa chạm Zalo: không tính hạn mức.
                jdbc.sql("DELETE FROM zalo_msg_log WHERE id = :id").param("id", logId).update();
                return switch (s.result()) {
                    case RATE_LIMITED, UNREACHABLE -> new Delivery(Status.RETRY, null, s);
                    case PERSON_ERROR -> new Delivery(Status.PERSON_ERROR, null, s); // đầu vào sai, chưa chạm Zalo
                    default -> {
                        if ("BUSY".equals(s.code()) || "NOT_CONNECTED".equals(s.code())) {
                            yield new Delivery(Status.RETRY, null, s);
                        }
                        // cầu nối đã dừng/đăng xuất/hết hạn mức của nó: Adh cũng dừng để người xem
                        tripAndStop(s.result().name() + (s.code() == null ? "" : ":" + s.code()));
                        yield new Delivery(Status.TRIPPED, null, s);
                    }
                };
            }
            setOutcome(logId, s.result().name(), s.code());
            if (s.result() == Result.PERSON_ERROR) {
                String stop = consecutivePersonErrors();
                if (stop != null) {
                    tripAndStop(stop);
                    return new Delivery(Status.TRIPPED, null, s);
                }
                return new Delivery(Status.PERSON_ERROR, null, s);
            }
            // phiên mất, Zalo cảnh báo, lỗi lạ, hết giờ chờ, Zalo không xác nhận: dừng ngay
            tripAndStop(s.result().name() + (s.code() == null ? "" : ":" + s.code()));
            return new Delivery(Status.TRIPPED, null, s);
        } catch (RuntimeException e) {
            // Đã gọi Zalo mà ghi kết quả lỗi (cơ sở dữ liệu chớp): dòng "đang gọi" còn đó, dừng.
            log.warn("Lỗi sau khi gửi tin: {}", e.getClass().getSimpleName());
            tripAndStop("TIN_INTERRUPTED");
            return new Delivery(Status.TRIPPED, null, Sent.of(Result.SYSTEM_ERROR, "INTERRUPTED", false));
        }
    }

    private void setOutcome(long logId, String outcome, String code) {
        jdbc.sql("UPDATE zalo_msg_log SET outcome = :o, code = :c WHERE id = :id")
                .param("o", outcome).param("c", code, java.sql.Types.VARCHAR).param("id", logId).update();
    }

    private boolean leftover() {
        return jdbc.sql("SELECT count(*) FROM zalo_msg_log WHERE outcome = 'DANG_GOI'").query(Long.class).single() > 0;
    }

    /** Lỗi riêng người liên tiếp: nhiều khả năng cấu hình hoặc Zalo đang chặn, đừng đốt hạn mức. Trả lý do dừng, hoặc null. */
    private String consecutivePersonErrors() {
        List<String[]> last = jdbc.sql("SELECT outcome, code FROM zalo_msg_log ORDER BY id DESC LIMIT :n")
                .param("n", MAX_CONSECUTIVE_NOT_FOUND)
                .query((rs, i) -> new String[]{rs.getString("outcome"), rs.getString("code")}).list();
        boolean allPerson = last.stream().allMatch(r -> "PERSON_ERROR".equals(r[0]));
        if (last.size() == MAX_CONSECUTIVE_NOT_FOUND && allPerson) {
            return "NHIEU_KHONG_TIM_THAY";
        }
        List<String[]> head = last.stream().limit(MAX_CONSECUTIVE_PERSON_ERRORS).toList();
        if (head.size() == MAX_CONSECUTIVE_PERSON_ERRORS
                && head.stream().allMatch(r -> "PERSON_ERROR".equals(r[0]) && !"NOT_FOUND".equals(r[1]))) {
            return "NHIEU_LOI_NGUOI_TIN";
        }
        return null;
    }

    private void tripAndStop(String reason) {
        try {
            cfg.trip(reason, "system");
        } finally {
            link.stop(); // đóng kết nối nghe, ngừng mọi thao tác phía cầu nối
        }
    }
}
