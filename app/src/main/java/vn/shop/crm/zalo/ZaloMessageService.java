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
import java.util.OptionalInt;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cổng DUY NHẤT để Adh gửi tin cho khách qua Zalo (D18, ADR-0002 mục 12). Chiến dịch thật và chiến dịch thử đều đi qua đây nên dùng
 * CHUNG một công tắc, một hạn mức ngày, một khoảng nghỉ và một cầu dao.
 *
 * <p>Chốt chặn, tất cả công khai và cố định, KHÔNG có yếu tố ngẫu nhiên và không cố giống người thật: công tắc tắt/bật (mặc định
 * TẮT), nấc bộ nối không phải "Tắt" và cầu dao không nhảy, khung giờ (mặc định 08:00–22:00), hạn mức tin mỗi ngày (mặc định 10, trần
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
    /**
     * Khoảng nghỉ CỐ ĐỊNH (25/11/2026, chủ dự án chốt) giữa hai việc liên tiếp của CÙNG MỘT khách (Tin 1 rồi kết bạn, kết bạn rồi gửi lại Tin 1);
     * ngắn hơn khoảng nghỉ giữa các khách. Cầu nối có khoảng riêng
     * (BRIDGE_SAME_PERSON_GAP_SEC, mặc định bằng số này).
     */
    public static final int SAME_CUSTOMER_GAP_SECONDS = 7;
    static final String FRIEND_DAILY = "zalo.friend.daily";
    /** Hết giờ này (ISO, UTC) thì mới gửi lại lời mời kết bạn: đặt khi Zalo báo danh sách lời mời đầy (Z223); người bấm "Gửi lại lời mời" thì xóa. */
    static final String FRIEND_PAUSED_UNTIL = "zalo.friend.paused_until";
    /** Mã của cầu nối: Zalo báo danh sách lời mời kết bạn của tài khoản phụ đã đầy (Z223). Chưa gửi gì, KHÔNG phải lỗi hệ thống nên không dừng cầu dao. */
    public static final String FRIEND_LIST_FULL = "FRIEND_LIST_FULL";
    /** Số ngày chưa ai đồng ý thì tự thu hồi lời mời kết bạn (0 = tắt). */
    static final String FRIEND_WITHDRAW_DAYS = "zalo.friend.withdraw_days";
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
        NOT_READY("Cầu nối chưa nghe được hoặc chưa chắc đúng tài khoản phụ"),
        FRIEND_PAUSED("Zalo báo danh sách lời mời kết bạn đã đầy, đang tạm dừng gửi lời mời kết bạn");

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

    // ---------- danh sách lời mời đầy (Z223, 25/11/2026) ----------

    /** Lúc được gửi lời mời kết bạn lại sau khi Zalo báo danh sách đầy; rỗng nếu không đang tạm dừng. */
    public Optional<Instant> friendPausedUntil() {
        return settings.get(FRIEND_PAUSED_UNTIL).map(String::strip).filter(v -> !v.isEmpty()).flatMap(v -> {
            try {
                return Optional.of(Instant.parse(v));
            } catch (java.time.format.DateTimeParseException e) {
                return Optional.<Instant>empty();
            }
        }).filter(t -> t.isAfter(clock.instant()));
    }

    /**
     * Zalo báo danh sách lời mời đầy (Z223): chỉ tạm dừng RIÊNG lời mời kết bạn đến giờ mở cửa của ngày hôm sau (hoặc đến khi người bấm gửi lại).
     * Không dừng cầu dao, Tin 1 và thêm nhóm vẫn chạy theo luật của chúng.
     */
    public void pauseFriendRequests(String by) {
        Instant until = clock.instant().atZone(clock.getZone()).toLocalDate().plusDays(1)
                .atTime(cfg.props().msgWindowStart()).atZone(clock.getZone()).toInstant();
        settings.set(FRIEND_PAUSED_UNTIL, until.toString(), by);
        cfg.log(ZaloAutoSettings.A_CONFIG, null, null, "KET_BAN_DAY_TAM_DUNG", "Z223", by);
    }

    /** Người bấm "Gửi lại lời mời kết bạn": bỏ tạm dừng sau khi đã dọn danh sách lời mời trên Zalo. */
    public void resumeFriendRequests(String by) {
        if (settings.get(FRIEND_PAUSED_UNTIL).filter(v -> !v.isBlank()).isEmpty()) {
            return;
        }
        settings.set(FRIEND_PAUSED_UNTIL, "", by);
        cfg.log(ZaloAutoSettings.A_CONFIG, null, null, "KET_BAN_MO_LAI", null, by);
    }

    /** Quá bao nhiêu ngày chưa ai đồng ý thì tự thu hồi lời mời kết bạn (mặc định 7; 0 = tắt). */
    public int friendWithdrawDays() {
        return clamp(intSetting(FRIEND_WITHDRAW_DAYS, cfg.props().friendWithdrawDays()), 0, 90);
    }

    public int setFriendWithdrawDays(int days, String by) {
        if (days < 0 || days > 90) {
            throw new ImportException("Số ngày thu hồi lời mời kết bạn phải từ 0 (tắt) đến 90.");
        }
        int old = friendWithdrawDays();
        settings.set(FRIEND_WITHDRAW_DAYS, Integer.toString(days), by);
        cfg.log(ZaloAutoSettings.A_CONFIG, null, null, "THU_HOI_LOI_MOI_SAU " + days, "CU " + old, by);
        return friendWithdrawDays();
    }

    /** Số lời mời kết bạn đã gửi mà khách chưa đồng ý (đang nằm trong danh sách lời mời đang chờ của Zalo). Chỉ để hiển thị. */
    public int friendPending() {
        return jdbc.sql("SELECT count(*) FROM invitation WHERE friend_request_outcome = 'SENT' AND friended_at IS NULL AND joined_at IS NULL")
                .query(Integer.class).single();
    }

    /** Số lần thu hồi lời mời đã gọi hôm nay (tính cả lần lỗi). */
    public int undoUsedToday() {
        return jdbc.sql("SELECT count(*) FROM zalo_msg_log WHERE at >= :s AND kind = 'UNDO_FRIEND'")
                .param("s", OffsetDateTime.ofInstant(startOfDay(), ZoneOffset.UTC)).query(Integer.class).single();
    }

    /** Số lần nhận lời mời kết bạn đã gọi hôm nay (tính cả lần lỗi). */
    public int acceptUsedToday() {
        return jdbc.sql("SELECT count(*) FROM zalo_msg_log WHERE at >= :s AND kind = 'ACCEPT_FRIEND'")
                .param("s", OffsetDateTime.ofInstant(startOfDay(), ZoneOffset.UTC)).query(Integer.class).single();
    }

    /** Số tin nhờ khách kết bạn lại đã gọi hôm nay (tính cả lần lỗi). */
    public int askFriendUsedToday() {
        return jdbc.sql("SELECT count(*) FROM zalo_msg_log WHERE at >= :s AND kind = 'ASK_FRIEND'")
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

    /** Đang trong khung giờ tự gửi (dùng cho việc thả tim). */
    public boolean inSendWindow() {
        return inWindow();
    }

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

    /**
     * Khoảng nghỉ riêng cho hai việc liên tiếp của CÙNG MỘT khách (chủ dự án chốt 25/11/2026: đổi 2 phút thành 7 giây): dòng sổ gần nhất là Tin 1 hoặc
     * lời mời kết bạn của chính lời mời {@code refId} (kể cả Tin 1 bị Zalo từ chối vì khách không nhận tin người lạ, Z122). Rỗng nếu dòng gần nhất thuộc khách khác (khi đó dùng khoảng nghỉ chung giữa các khách).
     */
    private OptionalInt sameCustomerGap(Kind kind, Long refId) {
        if (refId == null || (kind != Kind.FRIEND && kind != Kind.INVITE && kind != Kind.ASK_FRIEND)) {
            return OptionalInt.empty();
        }
        return jdbc.sql("""
                        SELECT kind, outcome, code, ref_id FROM zalo_msg_log ORDER BY at DESC, id DESC LIMIT 1""")
                .query((rs, n) -> {
                    long ref = rs.getLong("ref_id");
                    if (rs.wasNull() || ref != refId || "DANG_GOI".equals(rs.getString("outcome"))) {
                        return OptionalInt.empty();
                    }
                    String lastKind = rs.getString("kind");
                    if ((kind == Kind.FRIEND || kind == Kind.ASK_FRIEND) && "INVITE".equals(lastKind)) {
                        return OptionalInt.of(SAME_CUSTOMER_GAP_SECONDS); // kể cả khi Tin 1 bị chặn người lạ (Z122): vẫn chờ 7 giây (chủ dự án chốt 25/11/2026)
                    }
                    if (kind == Kind.INVITE && "FRIEND".equals(lastKind)) {
                        return OptionalInt.of(SAME_CUSTOMER_GAP_SECONDS); // gửi lại Tin 1 ngay khi khách vừa đồng ý kết bạn
                    }
                    return OptionalInt.empty();
                }).optional().orElse(OptionalInt.empty());
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

    /** Lần gửi kế tiếp: lúc dự kiến ({@code at}, rỗng nếu chưa biết) và lý do đang chờ, luôn có để hiện cho người đọc. */
    public record NextSend(Instant at, String reason) {
    }

    /** Mã lời mời của dòng sổ gần nhất (rỗng nếu không có hoặc đang gọi dở): khách duy nhất được hưởng khoảng nghỉ 7 giây. Chỉ đọc. */
    public Optional<Long> lastRefId() {
        return jdbc.sql("SELECT ref_id FROM zalo_msg_log WHERE outcome <> 'DANG_GOI' AND ref_id IS NOT NULL ORDER BY at DESC, id DESC LIMIT 1")
                .query(Long.class).optional();
    }

    /** Khoảng nghỉ thật áp cho việc {@code kind} của lời mời {@code refId} lúc này (nghỉ 7 giây cùng khách hoặc nghỉ giữa các khách). Chỉ đọc. */
    public int gapSecondsFor(Kind kind, Long refId) {
        int gap = kind.answersInbound() ? Math.min(REPLY_GAP_SECONDS, limits().gapSeconds()) : limits().gapSeconds();
        return Math.min(gap, sameCustomerGap(kind, refId).orElse(gap));
    }

    /**
     * Để hiện bộ đếm ngược trên giao diện: việc {@code kind} của lời mời {@code refId} (null = một Tin 1 của khách khác) đang chờ gì và khi nào
     * được gửi. Dùng đúng {@link #blocker} và đúng khoảng nghỉ của chính việc đó. Chỉ đọc, không đổi gì.
     */
    public NextSend waitFor(Kind kind, Long refId) {
        if (settings.paused()) {
            return new NextSend(null, "Đang tạm dừng tất cả. Bỏ tạm dừng thì hệ thống gửi tiếp");
        }
        Optional<Block> b = blocker(SCOPE_REAL, kind, refId, null, null);
        Instant now = clock.instant();
        if (b.isEmpty()) {
            return new NextSend(now, "Đã đến lượt, hệ thống gửi ở nhịp kiểm tra kế tiếp");
        }
        switch (b.get()) {
            case GAP -> {
                Instant last = lastAt(null);
                int gap = gapSecondsFor(kind, refId);
                String why = gap == SAME_CUSTOMER_GAP_SECONDS && sameCustomerGap(kind, refId).isPresent()
                        ? "Nghỉ " + gap + " giây giữa hai việc của cùng một khách"
                        : "Nghỉ " + gap + " giây giữa hai khách";
                return new NextSend(last == null ? now : last.plusSeconds(gap), why);
            }
            case DAILY_LIMIT, OUTSIDE_WINDOW -> {
                LocalDateTime n = LocalDateTime.ofInstant(now, clock.getZone());
                LocalTime ws = cfg.props().msgWindowStart();
                boolean today = b.get() == Block.OUTSIDE_WINDOW && n.toLocalTime().isBefore(ws);
                LocalDate day = today ? n.toLocalDate() : n.toLocalDate().plusDays(1);
                String why;
                if (b.get() == Block.OUTSIDE_WINDOW) {
                    why = "Ngoài khung giờ gửi (" + ws + " đến " + cfg.props().msgWindowEnd() + "), tiếp tục lúc " + ws;
                } else if (kind == Kind.FRIEND || kind == Kind.ASK_FRIEND) {
                    why = "Đã đủ " + friendDaily() + " lời mời kết bạn hôm nay, tiếp tục vào ngày mai";
                } else {
                    why = "Đã đủ " + limits().daily() + " khách được nhắn hôm nay, tiếp tục vào ngày mai";
                }
                return new NextSend(day.atTime(ws).atZone(clock.getZone()).toInstant(), why);
            }
            case FRIEND_PAUSED -> {
                Instant until = friendPausedUntil().orElse(null);
                return new NextSend(until, "Zalo báo danh sách lời mời kết bạn đã đầy (Z223), tạm dừng gửi lời mời kết bạn; tự mở lại lúc "
                        + (until == null ? "sáng mai" : LocalDateTime.ofInstant(until, clock.getZone()).toString().replace('T', ' ').substring(0, 16))
                        + " hoặc khi bạn bấm \"Gửi lại lời mời\" ở trang Zalo");
            }
            default -> {
                return new NextSend(null, b.get().label());
            }
        }
    }

    /** Như {@link #waitFor} cho lời mời kết bạn của lời mời {@code refId} (để nơi hiển thị không phải nhắc tới loại tin kết bạn). */
    public NextSend waitForFriendRequest(long refId) {
        return waitFor(Kind.FRIEND, refId);
    }

    /** Khi không có việc nào xếp hàng: chỉ cho biết hệ thống có đang bị chặn chung hay không. */
    public NextSend nextSend() {
        return waitFor(Kind.INVITE, null);
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
        return blocker(scope, kind, null, scopeDaily, scopeGap);
    }

    /**
     * Như trên, thêm {@code refId} (mã lời mời của chính việc sắp gửi): nếu dòng sổ gần nhất là Tin 1 hoặc lời mời kết bạn của CHÍNH lời mời đó
     * (kể cả Tin 1 bị Zalo từ chối vì khách không nhận tin người lạ, Z122) thì chỉ chờ {@link #SAME_CUSTOMER_GAP_SECONDS} giây cố định (25/11/2026),
     * ngược lại chờ khoảng nghỉ chung giữa các KHÁCH. Hạn mức ngày, cầu dao, khung giờ giữ nguyên.
     */
    public Optional<Block> blocker(String scope, Kind kind, Long refId, Integer scopeDaily, Integer scopeGap) {
        if (!enabled()) {
            return Optional.of(Block.SWITCH_OFF);
        }
        if (!cfg.effectiveMode().listens()) {
            return Optional.of(Block.MODE_OFF);
        }
        if (!inWindow()) {
            return Optional.of(Block.OUTSIDE_WINDOW);
        }
        if (kind == Kind.FRIEND) {
            // Danh sách lời mời của Zalo có hạn (Z223): đang tạm dừng thì không gửi lời mời. Chỉ chặn lời mời kết bạn, không đụng loại tin khác.
            if (friendPausedUntil().isPresent()) {
                return Optional.of(Block.FRIEND_PAUSED);
            }
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
        if (kind == Kind.UNDO_FRIEND && undoUsedToday() >= cfg.props().friendUndoDaily()) {
            return Optional.of(Block.DAILY_LIMIT);
        }
        if (kind == Kind.ACCEPT_FRIEND && acceptUsedToday() >= cfg.props().friendAcceptDaily()) {
            return Optional.of(Block.DAILY_LIMIT);
        }
        // Tin nhờ kết bạn lại thay cho lời mời kết bạn nên dùng chung trần mỗi ngày của lời mời (0 = tắt cả hai); không tính vào số khách được nhắn Tin 1.
        if (kind == Kind.ASK_FRIEND && askFriendUsedToday() >= friendDaily()) {
            return Optional.of(Block.DAILY_LIMIT);
        }
        Instant last = lastAt(null);
        int gap = kind.answersInbound() ? Math.min(REPLY_GAP_SECONDS, l.gapSeconds()) : l.gapSeconds();
        gap = Math.min(gap, sameCustomerGap(kind, refId).orElse(gap));
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

    /** Thu hồi lời mời kết bạn đã gửi cho người này (theo mã Zalo): cùng chốt chặn như tin, thêm trần thu hồi mỗi ngày riêng. */
    public Delivery undoFriendRequest(String scope, Long refId, String uid) {
        return deliver(scope, Kind.UNDO_FRIEND, refId, null, null, () -> link.undoFriendRequest(uid));
    }

    /** Mã Zalo của khách vừa tự gửi lời mời kết bạn tới tài khoản phụ (chỉ đọc từ bộ nhớ của bộ nối, không gọi Zalo). */
    public java.util.List<String> incomingRequests() {
        return link.incomingRequests();
    }

    /** Nhận lời mời kết bạn của khách (theo mã Zalo): cùng chốt chặn như tin, thêm trần nhận mỗi ngày riêng, khoảng nghỉ cố định ngắn như tin trả lời. */
    public Delivery acceptFriendRequest(String scope, Long refId, String uid) {
        return deliver(scope, Kind.ACCEPT_FRIEND, refId, null, null, () -> link.acceptFriendRequest(uid));
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
            Optional<Block> block = blocker(scope, kind, refId, scopeDaily, scopeGap);
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
