package vn.shop.crm.map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cập nhật dự báo định kỳ (chỉ khi bật {@code app.weather.enabled}). Mỗi tỉnh chỉ được hỏi lại khi dự báo cũ hết hạn. */
@Component
@ConditionalOnProperty(prefix = "app.weather", name = "enabled", havingValue = "true")
public class WeatherScheduler {

    private final WeatherService weather;

    public WeatherScheduler(WeatherService weather) {
        this.weather = weather;
    }

    @Scheduled(initialDelayString = "${app.weather.initial-delay-millis:60000}",
            fixedDelayString = "${app.weather.tick-millis:3600000}")
    public void tick() {
        weather.refresh(false);
    }
}
