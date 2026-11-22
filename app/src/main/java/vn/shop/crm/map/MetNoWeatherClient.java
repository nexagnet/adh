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
                if (symbol == null) {
                    JsonNode data = entry.path("data");
                    for (String period : new String[]{"next_1_hours", "next_6_hours", "next_12_hours"}) {
                        JsonNode code = data.path(period).path("summary").path("symbol_code");
                        if (code.isString()) {
                            symbol = code.asString();
                            break;
                        }
                    }
                }
            }
            if (min == Double.POSITIVE_INFINITY) {
                return Optional.empty();
            }
            return Optional.of(new Forecast(min, max, symbol, fetchedAt));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
