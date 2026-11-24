package vn.shop.crm.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.Region;
import vn.shop.crm.geo.VnGeo;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dự báo thời tiết theo tỉnh và nhắc việc cho mẹ. Chỉ hỏi những tỉnh đang có khách, chỉ gửi tọa độ tỉnh lỵ ra ngoài.
 * Nhắc việc chỉ hiện trên web, KHÔNG tự gửi gì cho khách.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    public enum Kind { COLD, HOT }

    public record Alert(Province province, Kind kind, double temp, long customers) {
        public String message() {
            if (kind == Kind.COLD) {
                return String.format("%s (%s): dự báo lạnh, thấp nhất khoảng %.0f°C trong 48 giờ tới. Có %d khách ở đây. "
                        + "Mẹ cân nhắc chuẩn bị hàng hoặc nhắn trong nhóm Zalo của khu vực.",
                        province.name(), province.region().label(), temp, customers);
            }
            return String.format("%s (%s): dự báo nóng, cao nhất khoảng %.0f°C trong 48 giờ tới. Có %d khách ở đây. "
                    + "Mẹ cân nhắc chuẩn bị hàng hoặc nhắn trong nhóm Zalo của khu vực.",
                    province.name(), province.region().label(), temp, customers);
        }

        public String kindLabel() {
            return kind == Kind.COLD ? "Lạnh bất thường" : "Nóng bất thường";
        }
    }

    private final JdbcClient jdbc;
    private final WeatherClient client;
    private final CustomerService customers;
    private final boolean enabled;
    private final double coldBac;
    private final double coldTrung;
    private final double coldNam;
    private final double hot;
    private final long minCustomers;
    private final boolean allProvinces;

    /** Chỉ hỏi những tỉnh đang có khách (không hỏi tỉnh khác). */
    public WeatherService(JdbcClient jdbc, WeatherClient client, CustomerService customers, boolean enabled,
                          double coldBac, double coldTrung, double coldNam, double hot, long minCustomers) {
        this(jdbc, client, customers, enabled, coldBac, coldTrung, coldNam, hot, minCustomers, false);
    }

    @Autowired
    public WeatherService(JdbcClient jdbc, WeatherClient client, CustomerService customers,
                          @Value("${app.weather.enabled:false}") boolean enabled,
                          @Value("${app.weather.cold-bac:13}") double coldBac,
                          @Value("${app.weather.cold-trung:16}") double coldTrung,
                          @Value("${app.weather.cold-nam:22}") double coldNam,
                          @Value("${app.weather.hot:37}") double hot,
                          @Value("${app.weather.alert-min-customers:10}") long minCustomers,
                          @Value("${app.weather.all-provinces:true}") boolean allProvinces) {
        this.allProvinces = allProvinces;
        this.jdbc = jdbc;
        this.client = client;
        this.customers = customers;
        this.enabled = enabled;
        this.coldBac = coldBac;
        this.coldTrung = coldTrung;
        this.coldNam = coldNam;
        this.hot = hot;
        this.minCustomers = minCustomers;
    }

    public boolean enabled() {
        return enabled;
    }

    double coldThreshold(Region region) {
        return switch (region) {
            case BAC -> coldBac;
            case TRUNG -> coldTrung;
            case NAM -> coldNam;
        };
    }

    public double coldBac() {
        return coldBac;
    }

    public double coldTrung() {
        return coldTrung;
    }

    public double coldNam() {
        return coldNam;
    }

    public double hotThreshold() {
        return hot;
    }

    public long minCustomers() {
        return minCustomers;
    }

    /**
     * Hỏi thời tiết các tỉnh mà dự báo cũ đã hết hạn (hoặc tất cả nếu {@code force}). Tỉnh có khách được hỏi trước; nếu
     * bật {@code app.weather.all-provinces} thì hỏi nốt các tỉnh còn lại để bản đồ có đủ 63 tỉnh. Trả số tỉnh cập nhật được.
     */
    public int refresh(boolean force) {
        if (!enabled) {
            return 0;
        }
        int updated = 0;
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>(customers.countByProvince().keySet());
        if (allProvinces) {
            VnGeo.all().forEach(p -> codes.add(p.code()));
        }
        for (String code : codes) {
            Optional<Province> province = VnGeo.byCode(code);
            if (province.isEmpty() || (!force && isFresh(code))) {
                continue;
            }
            Optional<WeatherClient.Result> result = client.fetch(province.get().lat(), province.get().lon());
            if (result.isPresent()) {
                store(code, result.get());
                updated++;
            } else {
                log.warn("Chưa cập nhật được thời tiết cho {}", code);
            }
            sleepQuietly();
        }
        return updated;
    }

    /** Tôn trọng giới hạn của nguồn: cách nhau một chút giữa các lần gọi. */
    void sleepQuietly() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isFresh(String code) {
        return jdbc.sql("SELECT count(*) FROM weather_forecast WHERE province = :p AND expires_at > now() AND hourly IS NOT NULL AND daily IS NOT NULL")
                .param("p", code).query(Long.class).single() > 0;
    }

    private void store(String code, WeatherClient.Result r) {
        jdbc.sql("""
                        INSERT INTO weather_forecast (province, min_temp, max_temp, symbol, hourly, daily, fetched_at, expires_at)
                        VALUES (:p, :min, :max, :sym, CAST(:hourly AS jsonb), CAST(:daily AS jsonb), now(), :exp)
                        ON CONFLICT (province) DO UPDATE SET min_temp = EXCLUDED.min_temp, max_temp = EXCLUDED.max_temp,
                            symbol = EXCLUDED.symbol, hourly = EXCLUDED.hourly, daily = EXCLUDED.daily, fetched_at = now(),
                            expires_at = EXCLUDED.expires_at""")
                .param("p", code).param("min", r.forecast().minTemp()).param("max", r.forecast().maxTemp())
                .param("hourly", Forecast.hourlyToJson(r.forecast().hourly())).param("daily", Forecast.dailyToJson(r.forecast().daily()))
                .param("sym", r.forecast().symbol(), java.sql.Types.VARCHAR).param("exp", r.expiresAt()).update();
    }

    public Optional<Forecast> forecast(String code) {
        return jdbc.sql("SELECT min_temp, max_temp, symbol, fetched_at, hourly::text AS hourly, daily::text AS daily FROM weather_forecast "
                        + "WHERE province = :p")
                .param("p", code)
                .query((rs, i) -> row(rs))
                .optional();
    }

    public Map<String, Forecast> allForecasts() {
        Map<String, Forecast> out = new LinkedHashMap<>();
        jdbc.sql("SELECT province, min_temp, max_temp, symbol, fetched_at, hourly::text AS hourly, daily::text AS daily FROM weather_forecast")
                .query((rs, i) -> Map.entry(rs.getString("province"), row(rs)))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static Forecast row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Forecast(rs.getDouble("min_temp"), rs.getDouble("max_temp"), rs.getString("symbol"),
                rs.getObject("fetched_at", OffsetDateTime.class), Forecast.hourlyFromJson(rs.getString("hourly")),
                Forecast.dailyFromJson(rs.getString("daily")));
    }

    /** Tỉnh có nhiều khách (từ ngưỡng cấu hình) mà dự báo lạnh/nóng bất thường. Sắp theo số khách giảm dần. */
    public List<Alert> alerts() {
        Map<String, Long> counts = customers.countByProvince();
        List<Alert> out = new ArrayList<>();
        allForecasts().forEach((code, f) -> {
            Province p = VnGeo.byCode(code).orElse(null);
            long n = counts.getOrDefault(code, 0L);
            if (p == null || n < minCustomers) {
                return;
            }
            if (f.minTemp() <= coldThreshold(p.region())) {
                out.add(new Alert(p, Kind.COLD, f.minTemp(), n));
            } else if (f.maxTemp() >= hot) {
                out.add(new Alert(p, Kind.HOT, f.maxTemp(), n));
            }
        });
        out.sort(Comparator.comparingLong(Alert::customers).reversed());
        return out;
    }
}
