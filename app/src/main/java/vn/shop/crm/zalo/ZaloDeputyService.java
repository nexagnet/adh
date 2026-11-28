package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneNormalizer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Chọn mẹ làm phó nhóm bằng SỐ ĐIỆN THOẠI thay vì mã Zalo (D19). Chủ shop nhập số của mẹ ở trang Zalo; bộ nối tra tên và ảnh đại diện (qua
 * tài khoản phụ, chỉ được khi mẹ đã bật cho tìm bằng số điện thoại); chủ shop nhìn tên và ảnh rồi bấm xác nhận thì Adh mới lưu mã Zalo.
 *
 * <p>Chốt chặn: việc tra là một lần gọi Zalo nên tính vào cầu dao chung (lỗi lạ thì dừng cả gửi tin lẫn tạo nhóm), có khoảng nghỉ cố định
 * giữa hai lần tra và trần mỗi ngày (cả lần lỗi), không tra lặp khi đã có người chờ xác nhận. Số điện thoại KHÔNG được lưu ở đâu cả và
 * không vào nhật ký; mã Zalo, tên và ảnh chỉ hiện cho admin và không vào nhật ký. Xác nhận lấy người đã tra được ở phía máy chủ, không nhận
 * mã từ trình duyệt. Nếu chưa xác nhận người nào thì dùng biến môi trường {@code ZALO_DEPUTY_ID} như trước.
 */
@Service
public class ZaloDeputyService {

    static final String UID = "zalo.deputy.uid";
    static final String NAME = "zalo.deputy.name";
    static final String AVATAR = "zalo.deputy.avatar";
    static final String CAND_UID = "zalo.deputy.cand_uid";
    static final String CAND_NAME = "zalo.deputy.cand_name";
    static final String CAND_AVATAR = "zalo.deputy.cand_avatar";
    static final String CAND_AT = "zalo.deputy.cand_at";
    /** Dấu tài khoản phụ lúc tra mã của mẹ (mã Zalo của một người khác nhau theo từng tài khoản đang hỏi). */
    static final String ACCT = "zalo.deputy.acct";
    static final String CAND_ACCT = "zalo.deputy.cand_acct";
    static final String CAND_REVISION = "zalo.deputy.cand_revision";
    static final String LAST_LOOKUP = "zalo.deputy.last_lookup_at";

    /** Khoảng nghỉ cố định giữa hai lần tra (không ngẫu nhiên hóa). */
    static final Duration GAP = Duration.ofSeconds(60);
    /** Người tra được chờ xác nhận tối đa bao lâu. */
    static final Duration CANDIDATE_TTL = Duration.ofMinutes(30);
    /** Trần mỗi ngày (24 giờ gần nhất), tính cả lần lỗi và lần không thấy. */
    static final int DAILY = 8;
    static final String A_LOOKUP = "TRA_SO_ME";

    /** Cầu nối từ chối trước khi chạm Zalo hoặc chưa đăng nhập: không phải lỗi lạ, không làm cầu dao nhảy. */
    private static final Set<String> NO_TRIP_CODES = Set.of("WINDOW", "NOT_ACTIVE", "BUSY", "NOT_CONNECTED", "LOGIN_REQUIRED", "BAD_PHONE");

    public record Person(String uid, String name, String avatar) {
    }

    public record Candidate(String uid, String name, String avatar, String accountTag, String revision) {
    }

    /** {@code source}: SETTING (đã xác nhận ở trang này), ENV (biến môi trường cũ) hoặc NONE. */
    public record Current(Person person, String source, boolean stale) {
        public Current(Person person, String source) {
            this(person, source, false);
        }
    }

    public enum Status { FOUND, NOT_FOUND, LIMIT, UNAVAILABLE, STOPPED }

    public record LookupOutcome(Status status, String message) {
    }

    private final AppSettings settings;
    private final ZaloGroupProperties props;
    private final ZaloAutoSettings auto;
    private final ZaloPersonFinder finder;
    private final ZaloSession session;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final ZaloGateway gateway;
    private final TransactionTemplate tx;

    public ZaloDeputyService(AppSettings settings, ZaloGroupProperties props, ZaloAutoSettings auto, ZaloPersonFinder finder,
                             ZaloSession session, JdbcClient jdbc, Clock clock, ZaloGateway gateway, TransactionTemplate tx) {
        this.gateway = gateway;
        this.settings = settings;
        this.props = props;
        this.auto = auto;
        this.finder = finder;
        this.session = session;
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = tx;
    }

    // ---------- đọc ----------

    /** Mã Zalo của phó nhóm dùng khi tạo nhóm: người đã xác nhận ở trang Zalo, nếu không có thì biến môi trường. Rỗng = không thêm phó nhóm. */
    public String effectiveUid() {
        String saved = settings.get(UID).orElse("").strip();
        if (saved.matches("\\d{1,32}")) {
            // Mã được tra từ tài khoản phụ cũ thì không dùng được với tài khoản mới: không gọi Zalo bằng mã sai.
            return stale() ? "" : saved;
        }
        String env = envDeputy();
        return env.matches("\\d{1,32}") ? env : "";
    }

    /**
     * Mã phó nhóm khai ở biến môi trường là mã mẹ NHÌN TỪ tài khoản 1 (mã người khác nhau theo tài khoản hỏi, D23), nên tài khoản khác không được
     * dùng nó; mỗi tài khoản thêm phải tra và xác nhận mẹ bằng số điện thoại riêng của mình.
     */
    private String envDeputy() {
        return ZaloAccountContext.current() == ZaloAccountContext.DEFAULT_ACCOUNT ? props.deputyId() : "";
    }

    /**
     * Mã mẹ đã lưu được tra từ MỘT tài khoản phụ khác với tài khoản đang đăng nhập. Chỉ báo lỗi khi biết chắc cả hai dấu và chúng khác nhau
     * (mã lưu từ trước khi có dấu, hoặc chưa biết tài khoản hiện tại, thì không kết luận).
     */
    public boolean stale() {
        String saved = settings.get(ACCT).orElse("").strip();
        if (saved.isEmpty()) {
            return false;
        }
        String now = currentTag();
        return now != null && !now.equals(saved);
    }

    private String currentTag() {
        try {
            String t = gateway.status().accountTag();
            return t == null || t.isBlank() ? null : t;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Current current() {
        String saved = settings.get(UID).orElse("").strip();
        if (saved.matches("\\d{1,32}")) {
            return new Current(new Person(saved, settings.get(NAME).orElse(""), validAvatar(settings.get(AVATAR).orElse(null))), "SETTING", stale());
        }
        if (envDeputy().matches("\\d{1,32}")) {
            return new Current(new Person(envDeputy(), "", null), "ENV");
        }
        return new Current(null, "NONE");
    }

    /** Người vừa tra được, đang chờ chủ shop xác nhận (hết hạn thì coi như không có). */
    public Optional<Candidate> candidate() {
        var keys = Set.of(CAND_UID, CAND_NAME, CAND_AVATAR, CAND_AT, CAND_ACCT, CAND_REVISION);
        var values = jdbc.sql("SELECT key, value FROM app_setting WHERE key IN (:keys)")
                .param("keys", keys.stream().map(ZaloAccountContext::settingKey).toList())
                .query((rs, n) -> java.util.Map.entry(rs.getString(1), rs.getString(2))).stream()
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
        java.util.function.Function<String, String> value = k -> values.getOrDefault(ZaloAccountContext.settingKey(k), "");
        String uid = value.apply(CAND_UID);
        String revision = value.apply(CAND_REVISION);
        Instant at = Optional.of(value.apply(CAND_AT)).map(v -> {
            try {
                return Instant.parse(v.strip());
            } catch (RuntimeException e) {
                return null;
            }
        }).orElse(null);
        if (!uid.matches("\\d{1,32}") || revision.isBlank() || at == null || clock.instant().isAfter(at.plus(CANDIDATE_TTL))) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(uid, value.apply(CAND_NAME), validAvatar(value.apply(CAND_AVATAR)),
                value.apply(CAND_ACCT), revision));
    }

    // ---------- tra, xác nhận, bỏ ----------

    /** Tra số của mẹ. Số điện thoại chỉ đi qua hàm này tới bộ nối, không lưu, không ghi nhật ký. */
    public LookupOutcome lookup(String rawPhone, String by) {
        if (!auto.props().enabled()) {
            throw new ImportException("Bộ nối Zalo chưa được bật ở cấu hình triển khai (APP_ZALO_AUTO_ENABLED).");
        }
        String e164 = PhoneNormalizer.toE164(rawPhone).orElseThrow(
                () -> new ImportException("Số điện thoại chưa đúng. Hãy nhập số di động Việt Nam, ví dụ 0912 345 678."));
        String digits = e164.replace("+", "");
        if (auto.tripped()) {
            return new LookupOutcome(Status.STOPPED, "Bộ nối đang dừng (cầu dao). Hãy mở lại ở đầu trang rồi tra lại.");
        }
        Instant now = clock.instant();
        Instant last = settings.get(LAST_LOOKUP).filter(v -> !v.isBlank()).map(v -> {
            try {
                return Instant.parse(v.strip());
            } catch (RuntimeException e) {
                return null;
            }
        }).orElse(null);
        if (last != null && now.isBefore(last.plus(GAP))) {
            long wait = Math.max(1, Duration.between(now, last.plus(GAP)).toSeconds());
            return new LookupOutcome(Status.LIMIT, "Vừa tra xong. Chờ khoảng " + wait + " giây rồi tra lại (không tra dồn để tránh Zalo cảnh báo).");
        }
        if (usedToday(now) >= DAILY) {
            return new LookupOutcome(Status.LIMIT, "Hôm nay đã tra " + DAILY + " lần, hết hạn mức. Mai tra tiếp, hoặc đặt ZALO_DEPUTY_ID ở cấu hình triển khai.");
        }
        // Ghi TRƯỚC khi gọi, tính cả lần lỗi.
        settings.set(LAST_LOOKUP, now.toString(), "system");
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, A_LOOKUP, "GOI", by);
        String before = currentTag();
        ZaloPersonFinder.Found f = finder.findByPhone(digits);
        String code = f.code() == null ? "" : f.code();
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, A_LOOKUP + "_KQ", f.result().name() + (code.isEmpty() ? "" : ":" + code), by);
        switch (f.result()) {
            case OK -> {
                String after = currentTag();
                if (!java.util.Objects.equals(before, after)) {
                    return new LookupOutcome(Status.UNAVAILABLE, "Phiên tài khoản Zalo đã thay đổi. Hãy tra lại từ tài khoản phụ đang chọn.");
                }
                tx.executeWithoutResult(status -> {
                    lockAccount();
                    settings.set(CAND_UID, f.uid(), by);
                    settings.set(CAND_NAME, f.name() == null ? "" : f.name(), by);
                    settings.set(CAND_AVATAR, validAvatar(f.avatar()) == null ? "" : f.avatar(), by);
                    settings.set(CAND_AT, now.toString(), by);
                    settings.set(CAND_ACCT, after == null ? "" : after, by);
                    settings.set(CAND_REVISION, UUID.randomUUID().toString(), by);
                });
                return new LookupOutcome(Status.FOUND, "Đã tìm thấy. Hãy xem tên và ảnh bên dưới có đúng mẹ không rồi bấm xác nhận.");
            }
            case PERSON_ERROR -> {
                return new LookupOutcome(Status.NOT_FOUND, "Không tìm thấy người nào với số này. Có thể số chưa có Zalo hoặc mẹ chưa bật cho phép tìm bằng số điện thoại (Zalo: Cài đặt > Quyền riêng tư > Cho phép tìm tôi qua số điện thoại).");
            }
            case RATE_LIMITED -> {
                long minutes = Math.max(1, f.retryAfterSeconds() / 60);
                return new LookupOutcome(Status.LIMIT, "Bộ nối giới hạn số lần tra mỗi giờ. Thử lại sau khoảng " + minutes + " phút.");
            }
            case UNREACHABLE -> {
                return new LookupOutcome(Status.UNAVAILABLE, "Chưa nối được tới bộ nối Zalo. Kiểm tra bộ nối rồi tra lại.");
            }
            default -> {
                if (NO_TRIP_CODES.contains(code)) {
                    return new LookupOutcome(Status.UNAVAILABLE, "Bộ nối chưa tra được lúc này (ngoài giờ cho phép, chưa đăng nhập hoặc đang bận). Thử lại sau.");
                }
                try {
                    auto.trip("TRA_SO_ME_" + f.result().name(), "system");
                } finally {
                    session.stop();
                }
                return new LookupOutcome(Status.STOPPED, "Zalo trả lỗi lạ nên bộ nối đã tự dừng để an toàn. Xem nhật ký rồi bấm Mở lại khi chắc chắn.");
            }
        }
    }

    /** Lưu người đã tra được làm phó nhóm. Lấy ở phía máy chủ, không nhận mã từ trình duyệt. */
    @Transactional
    public void confirm(String revision, String by) {
        String current = currentTag();
        lockAccount();
        Candidate p = candidate().filter(c -> c.revision().equals(revision)).orElseThrow(
                () -> new ImportException("Kết quả tra đã đổi hoặc hết hạn. Hãy mở lại mục Phó nhóm và kiểm tra người đang chờ xác nhận."));
        if (!p.accountTag().isEmpty() && !p.accountTag().equals(current)) {
            throw new ImportException("Phiên tài khoản đã đổi hoặc chưa kết nối. Hãy tra số của mẹ lại trên tài khoản phụ này.");
        }
        settings.set(UID, p.uid(), by);
        settings.set(NAME, p.name() == null ? "" : p.name(), by);
        settings.set(AVATAR, p.avatar() == null ? "" : p.avatar(), by);
        settings.set(ACCT, p.accountTag(), by);
        clearCandidate(by);
        // Mã mẹ mới thì các nhóm chưa có phó nhóm được thử lại từ đầu (số lần đã tính cho mã cũ không còn ý nghĩa).
        jdbc.sql("UPDATE zalo_group_proposal SET deputy_tries = 0 WHERE " + ZaloAccountContext.sql()
                + " AND state = 'DONE' AND deputy_state IN ('FAILED', 'NONE')").update();
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, "PHO_NHOM_XAC_NHAN", "OK", by);
    }

    static final String LAST_AUTO = "zalo.deputy.last_auto_at";
    /** Khoảng nghỉ cố định giữa hai lần TỰ thử lại đặt phó nhóm (không ngẫu nhiên hóa). */
    static final Duration AUTO_GAP = Duration.ofMinutes(60);

    /** Đã qua khoảng nghỉ cố định kể từ lần tự thử lại trước chưa. */
    public boolean autoRetryDue() {
        Instant last = settings.get(LAST_AUTO).filter(v -> !v.isBlank()).map(v -> {
            try {
                return Instant.parse(v.strip());
            } catch (RuntimeException e) {
                return null;
            }
        }).orElse(null);
        return last == null || !clock.instant().isBefore(last.plus(AUTO_GAP));
    }

    public void markAutoRetry() {
        settings.set(LAST_AUTO, clock.instant().toString(), "system");
    }

    @Transactional
    public void cancelCandidate(String by) {
        lockAccount();
        clearCandidate(by);
    }

    /** Bỏ người đã xác nhận (nhóm mới tạo sau đó sẽ không có phó nhóm, trừ khi còn biến môi trường). */
    @Transactional
    public void clear(String by) {
        lockAccount();
        settings.set(UID, "", by);
        settings.set(NAME, "", by);
        settings.set(AVATAR, "", by);
        settings.set(ACCT, "", by);
        clearCandidate(by);
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, "PHO_NHOM_BO", "OK", by);
    }

    private void clearCandidate(String by) {
        settings.set(CAND_UID, "", by);
        settings.set(CAND_NAME, "", by);
        settings.set(CAND_AVATAR, "", by);
        settings.set(CAND_AT, "", by);
        settings.set(CAND_ACCT, "", by);
        settings.set(CAND_REVISION, "", by);
    }

    private void lockAccount() {
        jdbc.sql("SELECT id FROM zalo_account WHERE id = :id FOR UPDATE")
                .param("id", ZaloAccountContext.current()).query(Long.class).single();
    }

    private long usedToday(Instant now) {
        return jdbc.sql("SELECT count(*) FROM zalo_auto_log WHERE " + ZaloAccountContext.sql() + " AND action = :a AND result = :r AND at >= :since")
                .param("a", ZaloAutoSettings.A_CONFIG).param("r", A_LOOKUP)
                .param("since", OffsetDateTime.ofInstant(now.minus(Duration.ofHours(24)), ZoneOffset.UTC)).query(Long.class).single();
    }

    /** Chỉ nhận ảnh nhỏ đúng dạng data URI (CSP chỉ cho ảnh 'self' và data:). */
    static String validAvatar(String a) {
        return a != null && a.length() <= 150_100 && a.matches("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$") ? a : null;
    }
}
