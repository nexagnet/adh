package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
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

    /** {@code source}: SETTING (đã xác nhận ở trang này), ENV (biến môi trường cũ) hoặc NONE. */
    public record Current(Person person, String source) {
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

    public ZaloDeputyService(AppSettings settings, ZaloGroupProperties props, ZaloAutoSettings auto, ZaloPersonFinder finder,
                             ZaloSession session, JdbcClient jdbc, Clock clock) {
        this.settings = settings;
        this.props = props;
        this.auto = auto;
        this.finder = finder;
        this.session = session;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---------- đọc ----------

    /** Mã Zalo của phó nhóm dùng khi tạo nhóm: người đã xác nhận ở trang Zalo, nếu không có thì biến môi trường. Rỗng = không thêm phó nhóm. */
    public String effectiveUid() {
        String saved = settings.get(UID).orElse("").strip();
        if (saved.matches("\\d{1,32}")) {
            return saved;
        }
        String env = props.deputyId();
        return env.matches("\\d{1,32}") ? env : "";
    }

    public Current current() {
        String saved = settings.get(UID).orElse("").strip();
        if (saved.matches("\\d{1,32}")) {
            return new Current(new Person(saved, settings.get(NAME).orElse(""), validAvatar(settings.get(AVATAR).orElse(null))), "SETTING");
        }
        if (props.deputyId().matches("\\d{1,32}")) {
            return new Current(new Person(props.deputyId(), "", null), "ENV");
        }
        return new Current(null, "NONE");
    }

    /** Người vừa tra được, đang chờ chủ shop xác nhận (hết hạn thì coi như không có). */
    public Optional<Person> candidate() {
        String uid = settings.get(CAND_UID).orElse("").strip();
        Instant at = settings.get(CAND_AT).filter(v -> !v.isBlank()).map(v -> {
            try {
                return Instant.parse(v.strip());
            } catch (RuntimeException e) {
                return null;
            }
        }).orElse(null);
        if (!uid.matches("\\d{1,32}") || at == null || clock.instant().isAfter(at.plus(CANDIDATE_TTL))) {
            return Optional.empty();
        }
        return Optional.of(new Person(uid, settings.get(CAND_NAME).orElse(""), validAvatar(settings.get(CAND_AVATAR).orElse(null))));
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
        ZaloPersonFinder.Found f = finder.findByPhone(digits);
        String code = f.code() == null ? "" : f.code();
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, A_LOOKUP + "_KQ", f.result().name() + (code.isEmpty() ? "" : ":" + code), by);
        switch (f.result()) {
            case OK -> {
                settings.set(CAND_UID, f.uid(), by);
                settings.set(CAND_NAME, f.name() == null ? "" : f.name(), by);
                settings.set(CAND_AVATAR, validAvatar(f.avatar()) == null ? "" : f.avatar(), by);
                settings.set(CAND_AT, now.toString(), by);
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
    public void confirm(String by) {
        Person p = candidate().orElseThrow(() -> new ImportException("Chưa có người nào chờ xác nhận hoặc đã hết hạn. Hãy tra lại bằng số điện thoại."));
        settings.set(UID, p.uid(), by);
        settings.set(NAME, p.name() == null ? "" : p.name(), by);
        settings.set(AVATAR, p.avatar() == null ? "" : p.avatar(), by);
        clearCandidate(by);
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, "PHO_NHOM_XAC_NHAN", "OK", by);
    }

    public void cancelCandidate(String by) {
        clearCandidate(by);
    }

    /** Bỏ người đã xác nhận (nhóm mới tạo sau đó sẽ không có phó nhóm, trừ khi còn biến môi trường). */
    public void clear(String by) {
        settings.set(UID, "", by);
        settings.set(NAME, "", by);
        settings.set(AVATAR, "", by);
        clearCandidate(by);
        auto.log(ZaloAutoSettings.A_CONFIG, null, null, "PHO_NHOM_BO", "OK", by);
    }

    private void clearCandidate(String by) {
        settings.set(CAND_UID, "", by);
        settings.set(CAND_NAME, "", by);
        settings.set(CAND_AVATAR, "", by);
        settings.set(CAND_AT, "", by);
    }

    private long usedToday(Instant now) {
        return jdbc.sql("SELECT count(*) FROM zalo_auto_log WHERE action = :a AND result = :r AND at >= :since")
                .param("a", ZaloAutoSettings.A_CONFIG).param("r", A_LOOKUP)
                .param("since", OffsetDateTime.ofInstant(now.minus(Duration.ofHours(24)), ZoneOffset.UTC)).query(Long.class).single();
    }

    /** Chỉ nhận ảnh nhỏ đúng dạng data URI (CSP chỉ cho ảnh 'self' và data:). */
    static String validAvatar(String a) {
        return a != null && a.length() <= 150_100 && a.matches("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$") ? a : null;
    }
}
