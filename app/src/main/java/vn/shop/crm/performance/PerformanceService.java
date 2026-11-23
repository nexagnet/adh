package vn.shop.crm.performance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.geo.GeoArea;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bảng theo dõi hiệu quả chiến dịch (chỉ đọc cơ sở dữ liệu, không gọi mạng, không ghi gì ngoài các giá trị nhập ở
 * {@link #save}). Mọi số là kết quả đếm/tổng bằng SQL trên dữ liệu đã có: lời mời ({@code invitation}), vận đơn
 * ({@code shipment}) và đối soát J&amp;T ({@code settlement_line}).
 *
 * <p>Hàm cho luồng AI đề xuất chiến dịch: {@link #overview()} (đầy đủ, cho trang quản trị) và {@link #anonymized()}
 * (đã ẩn ô nhỏ, chỉ số và mã khu vực, dùng cho báo cáo xuất và cho AI).
 *
 * <p>Cách gán một lần mua cho chiến dịch: khách có lời mời của chiến dịch đó, đã vào nhóm ({@code joined_at}), và có lần
 * mua giao thành công tạo SAU lúc vào nhóm (và trước lúc rời nhóm nếu có). Một mã đơn bán hàng là một lần mua; không có mã
 * thì mỗi vận đơn là một lần mua (cùng quy tắc với {@code CustomerClassifier}). Nếu chủ shop đã đặt tiền tố mã đơn của đơn
 * qua nhóm thì chỉ tính đơn có tiền tố đó; chưa đặt thì tính mọi đơn sau khi vào nhóm và ghi rõ là số ước tính.
 */
@Service
public class PerformanceService {

    /** Ô có ít hơn số khách này thì bị ẩn khỏi báo cáo xuất và khỏi dữ liệu cho AI. */
    public static final int MIN_CELL = 5;
    /** Số ngày quan sát mua lại sau khi nhận lời mời, để so khách vào nhóm với khách không vào nhóm. */
    public static final int COHORT_DAYS = 30;

    static final String KEY_DISCOUNT = "performance.group_discount_percent";
    static final String KEY_FREE_SHIP = "performance.free_ship";
    static final String KEY_FEE = "performance.tiktok_fee_percent";
    static final String KEY_PREFIX = "performance.group_order_prefix";

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern PREFIX = Pattern.compile("[A-Z0-9_-]{0,20}");

    /**
     * Mỗi lần mua của một khách: mã đơn bán hàng (hoặc từng vận đơn), ngày tạo sớm nhất, đã giao thành công chưa, có đúng
     * tiền tố "đơn qua nhóm" không, và tiền đã đối soát (nếu có). Khóa đối soát gồm hãng + mã vận đơn, nên không nhân đôi dòng.
     */
    private static final String PURCHASES = """
            WITH money AS (
                SELECT l.tracking_no, sp.carrier, sum(l.cod_collected) AS cod, sum(l.freight_fee + l.cod_fee) AS cost
                FROM settlement_line l JOIN settlement_period sp ON sp.id = l.period_id
                GROUP BY l.tracking_no, sp.carrier
            ), purchase AS (
                SELECT s.receiver_phone AS phone,
                       coalesce(nullif(upper(btrim(s.order_ref)), ''), 'T:' || s.carrier || ':' || s.tracking_no) AS pkey,
                       min(s.created_on) AS created_on,
                       bool_or(s.canonical_status = 'DELIVERED') AS ok,
                       bool_or(:prefix <> '' AND starts_with(upper(btrim(coalesce(s.order_ref, ''))), :prefix)) AS tagged,
                       bool_or(m.tracking_no IS NOT NULL) AS settled,
                       coalesce(sum(m.cod), 0) AS cod,
                       coalesce(sum(m.cost), 0) AS cost
                FROM shipment s LEFT JOIN money m ON m.tracking_no = s.tracking_no AND m.carrier = s.carrier
                GROUP BY 1, 2
            )
            """;

    private final JdbcClient jdbc;
    private final AppSettings settings;

    public PerformanceService(JdbcClient jdbc, AppSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    // ---- giá trị chủ shop nhập ----

    public PerformanceSettings settings() {
        PerformanceSettings d = PerformanceSettings.defaults();
        return new PerformanceSettings(
                number(KEY_DISCOUNT).orElse(d.discountPercent()),
                settings.get(KEY_FREE_SHIP).map("true"::equals).orElse(d.freeShip()),
                number(KEY_FEE).orElse(null),
                settings.get(KEY_PREFIX).orElse(""));
    }

    private Optional<BigDecimal> number(String key) {
        return settings.get(key).filter(v -> !v.isBlank()).map(v -> {
            try {
                return new BigDecimal(v.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        });
    }

    /** Lưu các giá trị tính toán. Ném {@link ImportException} (thông báo tiếng Việt) khi nhập sai. */
    public void save(String discount, boolean freeShip, String fee, String prefix, String by) {
        BigDecimal d = parse(discount, "Ưu đãi", 0, 90).orElseThrow(() -> new ImportException("Hãy nhập % ưu đãi khi mua qua nhóm (từ 0 đến 90)."));
        Optional<BigDecimal> f = parse(fee, "Phí TikTok", 0, 60);
        String p = prefix == null ? "" : prefix.strip().toUpperCase(Locale.ROOT);
        if (!PREFIX.matcher(p).matches()) {
            throw new ImportException("Tiền tố mã đơn chỉ gồm chữ không dấu, số, gạch ngang và gạch dưới, tối đa 20 ký tự.");
        }
        settings.set(KEY_DISCOUNT, d.toPlainString(), by);
        settings.set(KEY_FREE_SHIP, String.valueOf(freeShip), by);
        settings.set(KEY_FEE, f.map(BigDecimal::toPlainString).orElse(""), by);
        settings.set(KEY_PREFIX, p, by);
    }

    private static Optional<BigDecimal> parse(String text, String label, int min, int max) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal v = new BigDecimal(text.strip().replace(',', '.'));
            if (v.compareTo(BigDecimal.valueOf(min)) < 0 || v.compareTo(BigDecimal.valueOf(max)) > 0) {
                throw new ImportException(label + " phải từ " + min + " đến " + max + " (%).");
            }
            return Optional.of(v);
        } catch (NumberFormatException e) {
            throw new ImportException(label + " phải là một số, ví dụ 10.");
        }
    }

    // ---- số liệu ----

    /** Trang quản trị: từng chiến dịch (mới nhất trước) và dòng tổng. */
    public Overview overview() {
        return build(false);
    }

    /** Báo cáo xuất và AI đề xuất: như {@link #overview()} nhưng chiến dịch quá nhỏ bị ẩn số mua và tiền. */
    public Overview anonymized() {
        return build(true);
    }

    public record Overview(List<CampaignPerformance> campaigns, CampaignPerformance total, PerformanceSettings settings) {
        /** Số mua là ước tính khi chưa đặt tiền tố mã đơn của đơn qua nhóm. */
        public boolean estimated() {
            return !settings.hasPrefix();
        }
    }

    private Overview build(boolean mask) {
        PerformanceSettings s = settings();
        String prefix = s.hasPrefix() ? s.groupOrderPrefix() : "";
        Map<Long, long[]> buys = new HashMap<>();
        Map<Long, BigDecimal[]> money = new HashMap<>();
        jdbc.sql(PURCHASES + """
                SELECT i.campaign_id, count(DISTINCT c.id) AS buyers, count(*) AS orders,
                       count(*) FILTER (WHERE p.settled) AS settled, sum(p.cod) AS cod, sum(p.cost) AS cost
                FROM invitation i
                JOIN customer c ON c.id = i.customer_id
                JOIN purchase p ON p.phone = c.phone
                WHERE i.joined_at IS NOT NULL AND p.ok
                  AND p.created_on > (i.joined_at AT TIME ZONE 'Asia/Ho_Chi_Minh')
                  AND (i.left_at IS NULL OR p.created_on < (i.left_at AT TIME ZONE 'Asia/Ho_Chi_Minh'))
                  AND (:prefix = '' OR p.tagged)
                GROUP BY i.campaign_id""")
                .param("prefix", prefix)
                .query(rs -> {
                    long id = rs.getLong("campaign_id");
                    buys.put(id, new long[]{rs.getLong("buyers"), rs.getLong("orders"), rs.getLong("settled")});
                    money.put(id, new BigDecimal[]{rs.getBigDecimal("cod"), rs.getBigDecimal("cost")});
                });

        // Mua lại trong COHORT_DAYS ngày sau lúc nhận lời mời: chỉ tính lời mời đã đủ ngày quan sát, để hai nhóm công bằng.
        Map<Long, CampaignPerformance.Cohort[]> cohorts = new HashMap<>();
        jdbc.sql(PURCHASES + """
                SELECT i.campaign_id, (i.joined_at IS NOT NULL) AS joined, count(DISTINCT i.id) AS n,
                       count(DISTINCT i.id) FILTER (WHERE p.phone IS NOT NULL) AS bought
                FROM invitation i
                JOIN customer c ON c.id = i.customer_id
                LEFT JOIN purchase p ON p.phone = c.phone AND p.ok
                     AND p.created_on > (i.sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh')
                     AND p.created_on <= (i.sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh') + make_interval(days => :days)
                WHERE i.sent_at IS NOT NULL AND i.sent_at <= now() - make_interval(days => :days)
                GROUP BY i.campaign_id, (i.joined_at IS NOT NULL)""")
                .param("prefix", prefix).param("days", COHORT_DAYS)
                .query(rs -> {
                    var pair = cohorts.computeIfAbsent(rs.getLong("campaign_id"),
                            k -> new CampaignPerformance.Cohort[]{CampaignPerformance.Cohort.EMPTY, CampaignPerformance.Cohort.EMPTY});
                    pair[rs.getBoolean("joined") ? 0 : 1] = new CampaignPerformance.Cohort(rs.getLong("n"), rs.getLong("bought"));
                });

        List<CampaignPerformance> rows = new ArrayList<>();
        jdbc.sql("""
                SELECT c.id, c.name, c.geo_scope, (c.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS created_on,
                       count(i.id) FILTER (WHERE i.sent_at IS NOT NULL) AS invited,
                       count(i.id) FILTER (WHERE i.friended_at IS NOT NULL) AS friended,
                       count(i.id) FILTER (WHERE i.joined_at IS NOT NULL) AS joined
                FROM invitation_campaign c LEFT JOIN invitation i ON i.campaign_id = c.id
                GROUP BY c.id ORDER BY c.id DESC""")
                .query(rs -> {
                    long id = rs.getLong("id");
                    long[] b = buys.getOrDefault(id, new long[3]);
                    BigDecimal[] m = money.getOrDefault(id, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    var c = cohorts.getOrDefault(id, new CampaignPerformance.Cohort[]{CampaignPerformance.Cohort.EMPTY, CampaignPerformance.Cohort.EMPTY});
                    LocalDate created = rs.getObject("created_on", LocalDate.class);
                    rows.add(new CampaignPerformance(id, rs.getString("name"), scopeLabel(rs.getString("geo_scope")), created,
                            rs.getLong("invited"), rs.getLong("friended"), rs.getLong("joined"),
                            b[0], b[1], b[2], m[0], m[1], Economics.of(m[0], m[1], s), c[0], c[1], false));
                });

        CampaignPerformance total = total(rows, s);
        List<CampaignPerformance> shown = rows;
        if (mask) {
            shown = rows.stream().map(c -> c.joined() < MIN_CELL ? c.mask() : c).toList();
            if (total.joined() < MIN_CELL) {
                total = total.mask();
            }
        }
        return new Overview(List.copyOf(shown), total, s);
    }

    private static CampaignPerformance total(List<CampaignPerformance> list, PerformanceSettings s) {
        long invited = 0, friended = 0, joined = 0, buyers = 0, orders = 0, settled = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        var joinedCohort = CampaignPerformance.Cohort.EMPTY;
        var otherCohort = CampaignPerformance.Cohort.EMPTY;
        for (CampaignPerformance c : list) {
            invited += c.invited();
            friended += c.friended();
            joined += c.joined();
            buyers += c.buyers();
            orders += c.orders();
            settled += c.settledOrders();
            revenue = revenue.add(c.revenue());
            cost = cost.add(c.carrierCost());
            joinedCohort = joinedCohort.plus(c.joinedCohort());
            otherCohort = otherCohort.plus(c.notJoinedCohort());
        }
        // Mỗi khách chỉ có một lời mời (một chiến dịch), nên cộng số người mua giữa các chiến dịch không đếm trùng.
        return new CampaignPerformance(0, "Tất cả chiến dịch", "", null, invited, friended, joined, buyers, orders, settled,
                revenue, cost, Economics.of(revenue, cost, s), joinedCohort, otherCohort, false);
    }

    private static String scopeLabel(String scope) {
        if (scope == null || scope.isBlank()) {
            return "Mọi khu vực";
        }
        if (GeoArea.UNKNOWN.equals(scope)) {
            return "Chưa rõ khu vực";
        }
        return GeoArea.parse(scope).map(GeoArea::label).orElse("Mọi khu vực");
    }
}
