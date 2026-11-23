package vn.shop.crm.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình AI (DeepSeek, giao thức giống OpenAI). MẶC ĐỊNH TẮT: không đặt gì thì không có lệnh gọi AI nào ra ngoài.
 * Khóa API CHỈ đi qua biến môi trường (APP_AI_API_KEY): không ghi vào repo, log hay trang quản trị.
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        /* Công tắc (APP_AI_ENABLED). Bật mà thiếu khóa hoặc tên mô hình thì ứng dụng không khởi động. */
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        /* Tên mô hình do chủ shop đặt (APP_AI_MODEL), không có mặc định để khỏi đoán sai tên. */
        @DefaultValue("") String model,
        @DefaultValue("https://api.deepseek.com") String baseUrl,
        /* Trần số lượt gọi AI mỗi ngày (giờ Việt Nam), tính chung mọi việc. Hết thì AI im lặng, việc chuyển mẹ. */
        @DefaultValue("200") int dailyLimit,
        @DefaultValue("10") int timeoutSeconds,
        @DefaultValue("300") int maxOutputTokens) {

    public AiProperties {
        dailyLimit = Math.max(0, Math.min(dailyLimit, 5000));
        timeoutSeconds = Math.max(2, Math.min(timeoutSeconds, 30));
        maxOutputTokens = Math.max(50, Math.min(maxOutputTokens, 1000));
    }
}
