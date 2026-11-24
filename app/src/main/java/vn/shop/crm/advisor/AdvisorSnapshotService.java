package vn.shop.crm.advisor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.GeoArea;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.SubRegion;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.map.Forecast;
import vn.shop.crm.map.WeatherService;
import vn.shop.crm.zalo.ZaloAutoSettings;
import vn.shop.crm.zalo.ZaloMessageService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dựng {@link AdvisorSnapshot}: gom khách, lời mời và thời tiết thành SỐ LIỆU TỔNG HỢP ẨN DANH theo vùng. Chỉ đọc, không gọi AI,
 * không gửi gì ra ngoài. Mọi con số đều là kết quả đếm; không đọc tên, số điện thoại hay địa chỉ vào bộ nhớ của lớp này.
 */
@Service
public class AdvisorSnapshotService {

    /** Vùng hoặc tỉnh có ít hơn số này khách thì không được liệt kê riêng (tránh suy ngược ra từng người). */
    static final int MIN_CELL = 10;
    static final int TOP_PROVINCES = 3;
    /** Dịp sắp tới được báo cho AI trong bấy nhiêu ngày. */
    static final int OCCASION_DAYS = 45;
    /** Tổng lượng mưa 48 giờ tới (mm) từ mức này trở lên coi là "có mưa đáng kể". */
    static final double RAIN_MM = 5.0;
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcClient jdbc;
    private final ContactService contacts;
    private final WeatherService weather;
    private final ZaloMessageService messages;
    private final ZaloAutoSettings autoSettings;
    private final InviteService invites;
    private final Clock clock;

    public AdvisorSnapshotService(JdbcClient jdbc, ContactService contacts, WeatherService weather,
                                  ZaloMessageService messages, ZaloAutoSettings autoSettings, InviteService invites,
                                  Clock clock) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.weather = weather;
        this.messages = messages;
        this.autoSettings = autoSettings;
        this.invites = invites;
        this.clock = clock;
    }

    /** Bộ đếm cộng dồn cho một tỉnh hoặc một vùng. */
    private static final class Acc {
        long customers, oneTime, eligible, awaiting, new30;
        long r30, r90, r180, rOld, rUnknown;
        long sent, friended, joined, optedOut;

        void add(Acc o) {
            customers += o.customers;
            oneTime += o.oneTime;
            eligible += o.eligible;
            awaiting += o.awaiting;
            new30 += o.new30;
            r30 += o.r30;
            r90 += o.r90;
            r180 += o.r180;
            rOld += o.rOld;
            rUnknown += o.rUnknown;
            sent += o.sent;
            friended += o.friended;
            joined += o.joined;
            optedOut += o.optedOut;
        }

        AdvisorSnapshot.Funnel funnel() {
            return new AdvisorSnapshot.Funnel(sent, friended, joined, optedOut);
        }
    }

    public AdvisorSnapshot build() {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZONE));
        LocalDate today = now.toLocalDate();

        Map<String, Acc> byProvince = loadProvinces(now);
        Acc total = new Acc();
        Acc unknown = new Acc();
        Map<SubRegion, Acc> bySub = new EnumMap<>(SubRegion.class);
        Map<SubRegion, List<Map.Entry<Province, Acc>>> provincesOfSub = new EnumMap<>(SubRegion.class);
        byProvince.forEach((code, acc) -> {
            total.add(acc);
            Optional<Province> p = code.isEmpty() ? Optional.empty() : VnGeo.byCode(code);
            if (p.isEmpty()) {
                unknown.add(acc);
                return;
            }
            bySub.computeIfAbsent(p.get().sub(), k -> new Acc()).add(acc);
            provincesOfSub.computeIfAbsent(p.get().sub(), k -> new ArrayList<>()).add(Map.entry(p.get(), acc));
        });

        Map<String, Forecast> forecasts = weather.allForecasts();
        List<AdvisorSnapshot.Area> areas = new ArrayList<>();
        int hidden = 0;
        for (SubRegion sub : SubRegion.values()) {
            Acc acc = bySub.get(sub);
            if (acc == null || acc.customers == 0) {
                continue;
            }
            if (acc.customers < MIN_CELL) {
                hidden++;
                continue;
            }
            areas.add(area(sub, acc, provincesOfSub.getOrDefault(sub, List.of()), forecasts));
        }
        areas.sort(Comparator.comparingLong(AdvisorSnapshot.Area::customers).reversed());

        ZaloMessageService.Limits msg = messages.limits();
        AdvisorSnapshot.Limits limits = new AdvisorSnapshot.Limits(msg.daily(), msg.gapSeconds(),
                autoSettings.props().msgWindowStart().toString(), autoSettings.props().msgWindowEnd().toString(),
                invites.maxCampaign());

        List<AdvisorSnapshot.Season> seasons = new ArrayList<>();
        AdvisorCalendar.seasons(today.getMonthValue()).forEach((r, label) -> seasons.add(new AdvisorSnapshot.Season(r.label(), label)));
        AdvisorSnapshot.Calendar calendar = new AdvisorSnapshot.Calendar(today.getMonthValue(), seasons,
                AdvisorCalendar.upcoming(today, OCCASION_DAYS));

        AdvisorSnapshot.Overall overall = new AdvisorSnapshot.Overall(total.customers, total.oneTime, total.eligible,
                total.awaiting, unknown.customers, hidden, total.funnel());
        return new AdvisorSnapshot(today.toString(), calendar, limits, overall, areas, slots(), campaigns());
    }

    private AdvisorSnapshot.Area area(SubRegion sub, Acc acc, List<Map.Entry<Province, Acc>> provinces,
                                      Map<String, Forecast> forecasts) {
        List<AdvisorSnapshot.ProvinceInfo> top = provinces.stream()
                .filter(e -> e.getValue().customers >= MIN_CELL)
                .sorted((a, b) -> Long.compare(b.getValue().customers, a.getValue().customers))
                .limit(TOP_PROVINCES)
                .map(e -> {
                    Forecast f = forecasts.get(e.getKey().code());
                    return new AdvisorSnapshot.ProvinceInfo(e.getKey().code(), e.getKey().name(), e.getValue().customers,
                            e.getValue().eligible, f == null ? null : (int) Math.round(f.minTemp()),
                            f == null ? null : (int) Math.round(f.maxTemp()));
                }).toList();
        return new AdvisorSnapshot.Area(GeoArea.sub(sub).code(), sub.label(), sub.region().label(), acc.customers,
                acc.oneTime, acc.eligible, acc.awaiting, acc.new30,
                new AdvisorSnapshot.Recency(acc.r30, acc.r90, acc.r180, acc.rOld, acc.rUnknown), acc.funnel(),
                weatherOf(provinces, forecasts), top);
    }

    /** Trung bình theo số khách của từng tỉnh; null nếu chưa có dự báo nào cho vùng này. */
    private static AdvisorSnapshot.Weather weatherOf(List<Map.Entry<Province, Acc>> provinces, Map<String, Forecast> forecasts) {
        double weightAll = 0;
        double weightWith = 0;
        double weightedMin = 0;
        double weightedMax = 0;
        double weightRainy = 0;
        for (Map.Entry<Province, Acc> e : provinces) {
            double w = e.getValue().customers;
            weightAll += w;
            Forecast f = forecasts.get(e.getKey().code());
            if (f == null || w == 0) {
                continue;
            }
            weightWith += w;
            weightedMin += w * f.minTemp();
            weightedMax += w * f.maxTemp();
            double rain = f.hourly().stream().mapToDouble(Forecast.Hour::rain).sum();
            if (rain >= RAIN_MM) {
                weightRainy += w;
            }
        }
        if (weightWith == 0) {
            return null;
        }
        return new AdvisorSnapshot.Weather(round1(weightedMin / weightWith), round1(weightedMax / weightWith),
                (int) Math.round(100 * weightRainy / weightWith), (int) Math.round(100 * weightWith / weightAll));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Đếm khách, độ mới của lần mua, kho khách mời được và phễu lời mời theo từng tỉnh (khóa rỗng = chưa rõ tỉnh). */
    private Map<String, Acc> loadProvinces(LocalDateTime now) {
        Map<String, Acc> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT coalesce(province, '') AS p,
                               count(*) AS customers,
                               count(*) FILTER (WHERE segment = 'ONE_TIME') AS one_time,
                               count(*) FILTER (WHERE first_seen_at >= :since30) AS new30,
                               count(*) FILTER (WHERE segment = 'ONE_TIME' AND last_order_on >= :c30) AS r30,
                               count(*) FILTER (WHERE segment = 'ONE_TIME' AND last_order_on < :c30 AND last_order_on >= :c90) AS r90,
                               count(*) FILTER (WHERE segment = 'ONE_TIME' AND last_order_on < :c90 AND last_order_on >= :c180) AS r180,
                               count(*) FILTER (WHERE segment = 'ONE_TIME' AND last_order_on < :c180) AS r_old,
                               count(*) FILTER (WHERE segment = 'ONE_TIME' AND last_order_on IS NULL) AS r_unknown
                        FROM customer GROUP BY 1""")
                .param("since30", OffsetDateTime.of(now.minusDays(30), ZoneOffset.ofHours(7)))
                .param("c30", now.minusDays(30)).param("c90", now.minusDays(90)).param("c180", now.minusDays(180))
                .query((rs, i) -> {
                    Acc a = new Acc();
                    a.customers = rs.getLong("customers");
                    a.oneTime = rs.getLong("one_time");
                    a.new30 = rs.getLong("new30");
                    a.r30 = rs.getLong("r30");
                    a.r90 = rs.getLong("r90");
                    a.r180 = rs.getLong("r180");
                    a.rOld = rs.getLong("r_old");
                    a.rUnknown = rs.getLong("r_unknown");
                    return Map.entry(rs.getString("p"), a);
                }).list().forEach(e -> out.put(e.getKey(), e.getValue()));

        contacts.eligibleNotInvitedByProvince().forEach((p, n) -> out.computeIfAbsent(p, k -> new Acc()).eligible += n);
        contacts.candidatesByProvince().forEach((p, n) -> out.computeIfAbsent(p, k -> new Acc()).awaiting += n);
        contacts.optedOutAfterInviteByProvince().forEach((p, n) -> out.computeIfAbsent(p, k -> new Acc()).optedOut += n);

        jdbc.sql("""
                        SELECT coalesce(c.province, '') AS p,
                               count(*) FILTER (WHERE i.sent_at IS NOT NULL) AS sent,
                               count(*) FILTER (WHERE i.friended_at IS NOT NULL) AS friended,
                               count(*) FILTER (WHERE i.joined_at IS NOT NULL) AS joined
                        FROM invitation i JOIN customer c ON c.id = i.customer_id GROUP BY 1""")
                .query((rs, i) -> {
                    Acc a = new Acc();
                    a.sent = rs.getLong("sent");
                    a.friended = rs.getLong("friended");
                    a.joined = rs.getLong("joined");
                    return Map.entry(rs.getString("p"), a);
                }).list().forEach(e -> {
                    Acc dst = out.computeIfAbsent(e.getKey(), k -> new Acc());
                    dst.sent += e.getValue().sent;
                    dst.friended += e.getValue().friended;
                    dst.joined += e.getValue().joined;
                });
        return out;
    }

    /** Số chiến dịch gần đây (hoặc còn khách chờ) báo cho AI, tối đa. */
    static final int MAX_CAMPAIGNS = 10;
    static final int CAMPAIGN_RECENT_DAYS = 30;

    /** Chiến dịch còn khách chờ hoặc mới tạo trong {@link #CAMPAIGN_RECENT_DAYS} ngày: chỉ ngày, khung giờ và số đếm, không tên chiến dịch, không khách. */
    private List<AdvisorSnapshot.CampaignInfo> campaigns() {
        return jdbc.sql("""
                        SELECT k.geo_scope, k.auto_send_approved_at IS NOT NULL AS approved, k.start_on, k.end_on, k.window_from, k.window_to,
                               k.daily_cap, count(i.id) AS total, count(i.id) FILTER (WHERE i.status = 'READY') AS ready,
                               count(i.id) FILTER (WHERE i.sent_at IS NOT NULL) AS sent
                        FROM invitation_campaign k LEFT JOIN invitation i ON i.campaign_id = k.id
                        GROUP BY k.id
                        HAVING count(i.id) FILTER (WHERE i.status = 'READY') > 0 OR k.created_at >= now() - make_interval(days => :days)
                        ORDER BY k.id DESC LIMIT :n""")
                .param("days", CAMPAIGN_RECENT_DAYS).param("n", MAX_CAMPAIGNS)
                .query((rs, i) -> {
                    long ready = rs.getLong("ready");
                    String state = ready == 0 ? "XONG" : rs.getBoolean("approved") ? "DANG_GUI" : "CHUA_DUYET";
                    String scope = rs.getString("geo_scope");
                    return new AdvisorSnapshot.CampaignInfo(scope == null || scope.isBlank() ? "ALL" : scope, state,
                            str(rs.getObject("start_on", LocalDate.class)), str(rs.getObject("end_on", LocalDate.class)),
                            str(rs.getObject("window_from", java.time.LocalTime.class)), str(rs.getObject("window_to", java.time.LocalTime.class)),
                            (Integer) rs.getObject("daily_cap"), rs.getLong("total"), ready, rs.getLong("sent"));
                }).list();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Số tin đã nhắn và số khách kết bạn theo bốn khung giờ gửi (giờ Việt Nam); ngoài 08–20 dồn vào "khác". */
    private List<AdvisorSnapshot.Slot> slots() {
        String[] labels = {"08-11", "11-14", "14-17", "17-20", "khac"};
        long[] sent = new long[5];
        long[] friended = new long[5];
        jdbc.sql("""
                        SELECT extract(hour FROM sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::int AS h,
                               count(*) AS sent,
                               count(*) FILTER (WHERE friended_at IS NOT NULL) AS friended
                        FROM invitation WHERE sent_at IS NOT NULL GROUP BY 1""")
                .query((rs, i) -> new long[]{rs.getLong("h"), rs.getLong("sent"), rs.getLong("friended")}).list()
                .forEach(r -> {
                    int idx = r[0] >= 8 && r[0] < 11 ? 0 : r[0] >= 11 && r[0] < 14 ? 1 : r[0] >= 14 && r[0] < 17 ? 2
                            : r[0] >= 17 && r[0] < 20 ? 3 : 4;
                    sent[idx] += r[1];
                    friended[idx] += r[2];
                });
        List<AdvisorSnapshot.Slot> out = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            out.add(new AdvisorSnapshot.Slot(labels[i], sent[i], friended[i]));
        }
        return out;
    }
}
