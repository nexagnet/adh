package vn.shop.crm.zalo;

import org.springframework.stereotype.Service;
import vn.shop.crm.map.Forecast;
import vn.shop.crm.map.WeatherService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Hoãn gửi cho khách ở tỉnh đang có mưa lớn (D21 PR 4 phần còn lại), chỉ khi chiến dịch bật {@code hold_on_rain}.
 * Chỉ HOÃN: khách ở tỉnh đang mưa lớn bị bỏ qua nhịp này, khách ở tỉnh khác vẫn đi tiếp trong cùng hạn mức, khoảng nghỉ và khung giờ;
 * hết mưa thì gửi tiếp. Không bao giờ làm gửi nhanh hơn hay nhiều hơn. Chưa có dự báo (hoặc dự báo quá cũ, hoặc tắt thời tiết)
 * thì KHÔNG hoãn gì, vì không có căn cứ. Lời mời hết hạn trong lúc hoãn thì bị đóng như bình thường.
 * Dự báo là số liệu theo tỉnh, không liên quan dữ liệu khách; không ngẫu nhiên.
 */
@Service
public class RainHold {

    /** Mưa từ chừng này mm trong một giờ là "mưa lớn", hoặc biểu tượng mưa to / giông của MET Norway. */
    public static final double HEAVY_MM_PER_HOUR = 4.0;
    static final Duration LOOK_BACK = Duration.ofHours(1);
    static final Duration LOOK_AHEAD = Duration.ofHours(3);
    static final Duration STALE_AFTER = Duration.ofHours(12);

    private final WeatherService weather;
    private final Clock clock;

    public RainHold(WeatherService weather, Clock clock) {
        this.weather = weather;
        this.clock = clock;
    }

    /** Mã các tỉnh đang (hoặc sắp trong 3 giờ tới) có mưa lớn. Rỗng khi tắt thời tiết hoặc chưa có dự báo. */
    public Set<String> rainyProvinces() {
        Set<String> out = new HashSet<>();
        if (!weather.enabled()) {
            return out;
        }
        Instant now = clock.instant();
        long from = now.minus(LOOK_BACK).getEpochSecond();
        long to = now.plus(LOOK_AHEAD).getEpochSecond();
        for (Map.Entry<String, Forecast> e : weather.allForecasts().entrySet()) {
            Forecast f = e.getValue();
            if (f.fetchedAt() == null || f.fetchedAt().toInstant().isBefore(now.minus(STALE_AFTER))) {
                continue;
            }
            for (Forecast.Hour h : f.hourly()) {
                if (h.epoch() >= from && h.epoch() <= to && heavy(h)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    static boolean heavy(Forecast.Hour h) {
        String s = h.symbol() == null ? "" : h.symbol().toLowerCase();
        return h.rain() >= HEAVY_MM_PER_HOUR || s.contains("heavyrain") || s.contains("thunder");
    }
}
