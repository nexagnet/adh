package vn.shop.crm.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mặc định KHÔNG có AI ({@link AiClient} luôn báo không dùng được). Chỉ khi bật cờ môi trường mới nối DeepSeek. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    AiClient aiClient(AiProperties props) {
        if (!props.enabled()) {
            return (system, user, json) -> {
                throw new AiClient.AiUnavailableException("AI đang tắt");
            };
        }
        if (props.apiKey().isBlank() || props.model().isBlank()) {
            // thất bại kín: bật AI mà thiếu khóa hoặc tên mô hình thì không khởi động
            throw new IllegalStateException("Đã bật APP_AI_ENABLED nhưng chưa đặt APP_AI_API_KEY và APP_AI_MODEL.");
        }
        return new DeepSeekClient(props);
    }
}
