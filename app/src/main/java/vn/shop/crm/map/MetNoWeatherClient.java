package vn.shop.crm.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Dự báo từ MET Norway (api.met.no locationforecast), miễn phí, không cần khóa, giấy phép CC BY 4.0 (phải ghi nguồn).
 * Điều khoản: gửi User-Agent nhận diện ứng dụng, tọa độ tối đa 4 chữ số thập phân, không gọi lại trước thời điểm
 * {@code Expires}, dưới 20 yêu cầu/giây. Chỉ gửi tọa độ thành phố tỉnh lỵ.
 */
@Component
public class MetNoWeatherClient implements WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(MetNoWeatherClient.class);
    private static final String URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%s&lon=%s";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String userAgent;
    private final int defaultRefreshHours;

    public MetNoWeatherClient(@Value("${app.weather.user-agent:adh-shop-crm/1.0}") String userAgent,
                              @Value("${app.weather.refresh-hours:3}") int refreshHours) {
        this.userAgent = userAgent;
        this.defaultRefreshHours = Math.max(1, refreshHours);
    }

    @Override
    public Optional<Result> fetch(double lat, double lon) {
        String url = URL.formatted(String.format(Locale.ROOT, "%.4f", lat), String.format(Locale.ROOT, "%.4f", lon));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Dịch vụ thời tiết trả mã {}", response.statusCode());
                return Optional.empty();
            }
            OffsetDateTime now = OffsetDateTime.now();
            Optional<Forecast> forecast = parse(response.body(), now);
            OffsetDateTime expires = response.headers().firstValue("Expires").map(MetNoWeatherClient::parseHttpDate)
                    .filter(e -> e.isAfter(now)).orElse(now.plusHours(defaultRefreshHours));
            return forecast.map(f -> new Result(f, expires));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Không lấy được dự báo thời tiết: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static OffsetDateTime parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.MIN;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Lượng mưa (mm) trong một giờ: lấy mốc 1 giờ, nếu không có thì chia đều mốc 6 giờ. */
    private static double rainPerHour(JsonNode data) {
        JsonNode one = data.path("next_1_hours").path("details").path("precipitation_amount");
        if (one.isNumber()) {
            return round1(one.asDouble());
        }
        JsonNode six = data.path("next_6_hours").path("details").path("precipitation_amount");
        return six.isNumber() ? round1(six.asDouble() / 6.0) : 0.0;
    }

    private static final java.time.ZoneId VN = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
    /** Ngày chỉ có một mốc đo (đầu hoặc cuối chuỗi dự báo) thì bỏ, vì không đủ để nói thấp nhất hay cao nhất. */
    private static final int MIN_POINTS_PER_DAY = 2;
    private static final int MAX_DAYS = 9;

    /**
     * Dự báo theo ngày (giờ Việt Nam) cho cả chuỗi MET Norway (khoảng 9 ngày): nhiệt độ thấp nhất/cao nhất từ các mốc đo và tổng mưa. Mưa lấy mốc 1 giờ
     * nếu có, nếu không thì mốc 6 giờ (các mốc 6 giờ cách nhau 6 giờ nên không cộng trùng).
     */
    static java.util.List<Forecast.Day> daily(JsonNode series) {
        java.util.Map<java.time.LocalDate, double[]> acc = new java.util.TreeMap<>(); // min, max, mưa, số mốc
        for (JsonNode entry : series) {
            JsonNode temp = entry.path("data").path("instant").path("details").path("air_temperature");
            if (!temp.isNumber()) {
                continue;
            }
            java.time.LocalDate day = OffsetDateTime.parse(entry.path("time").asString()).atZoneSameInstant(VN).toLocalDate();
            double[] a = acc.computeIfAbsent(day, k -> new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 0});
            a[0] = Math.min(a[0], temp.asDouble());
            a[1] = Math.max(a[1], temp.asDouble());
            JsonNode one = entry.path("data").path("next_1_hours").path("details").path("precipitation_amount");
            JsonNode six = entry.path("data").path("next_6_hours").path("details").path("precipitation_amount");
            if (one.isNumber()) {
                a[2] += one.asDouble();
            } else if (six.isNumber()) {
                a[2] += six.asDouble();
            }
            a[3]++;
        }
        java.util.List<Forecast.Day> out = new java.util.ArrayList<>();
        for (var e : acc.entrySet()) {
            if (e.getValue()[3] >= MIN_POINTS_PER_DAY && out.size() < MAX_DAYS) {
                out.add(new Forecast.Day(e.getKey(), round1(e.getValue()[0]), round1(e.getValue()[1]), round1(e.getValue()[2])));
            }
        }
        return out;
    }

    /** Lấy nhiệt độ thấp nhất/cao nhất trong 48 giờ kể từ mốc đầu tiên và loại thời tiết gần nhất. */
    static Optional<Forecast> parse(String json, OffsetDateTime fetchedAt) {
        try {
            JsonNode series = JSON.readTree(json).path("properties").path("timeseries");
            if (!series.isArray() || series.isEmpty()) {
                return Optional.empty();
            }
            OffsetDateTime first = OffsetDateTime.parse(series.get(0).path("time").asString());
            OffsetDateTime until = first.plusHours(48);
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            String symbol = null;
            java.util.List<Forecast.Hour> hours = new java.util.ArrayList<>();
            for (JsonNode entry : series) {
                OffsetDateTime time = OffsetDateTime.parse(entry.path("time").asString());
                if (time.isAfter(until)) {
                    break;
                }
                JsonNode temp = entry.path("data").path("instant").path("details").path("air_temperature");
                if (temp.isNumber()) {
                    min = Math.min(min, temp.asDouble());
                    max = Math.max(max, temp.asDouble());
                }
                JsonNode data = entry.path("data");
                String entrySymbol = null;
                for (String period : new String[]{"next_1_hours", "next_6_hours", "next_12_hours"}) {
                    JsonNode code = data.path(period).path("summary").path("symbol_code");
                    if (code.isString()) {
                        entrySymbol = code.asString();
                        break;
                    }
                }
                if (symbol == null) {
                    symbol = entrySymbol;
                }
                if (temp.isNumber()) {
                    JsonNode cloud = data.path("instant").path("details").path("cloud_area_fraction");
                    hours.add(new Forecast.Hour(time.toEpochSecond(), round1(temp.asDouble()), rainPerHour(data),
                            cloud.isNumber() ? (int) Math.round(cloud.asDouble()) : 0, entrySymbol));
                }
            }
            if (min == Double.POSITIVE_INFINITY) {
                return Optional.empty();
            }
            return Optional.of(new Forecast(min, max, symbol, fetchedAt, hours, daily(series)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
