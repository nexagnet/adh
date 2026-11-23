package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình đọc tin riêng của khách để nhận diện từ chối (D19). MẶC ĐỊNH TẮT. Muốn chạy phải bật CẢ BA: APP_ZALO_INBOX_ENABLED ở Adh,
 * BRIDGE_INBOX_ENABLED ở cầu nối và APP_AI_ENABLED (kèm khóa và tên mô hình). Thiếu một thì không đọc gì.
 */
@ConfigurationProperties(prefix = "app.zalo.inbox")
public record ZaloInboxProperties(
        @DefaultValue("false") boolean enabled,
        /* Độ chắc tối thiểu để AI tự ghi sổ từ chối (chỉ nhận từ 0,5 tới 0,99). Dưới ngưỡng thì tạm dừng và báo mẹ. */
        @DefaultValue("0.8") double threshold,
        /* Độ chắc tối thiểu để AI tự gỡ cờ khi khách từng từ chối nhắn lại muốn mua (chỉ nhận từ 0,7 tới 0,99). */
        @DefaultValue("0.85") double comebackThreshold) {

    public ZaloInboxProperties {
        if (Double.isNaN(threshold)) {
            threshold = 0.8;
        }
        threshold = Math.max(0.5, Math.min(0.99, threshold));
        if (Double.isNaN(comebackThreshold)) {
            comebackThreshold = 0.85;
        }
        comebackThreshold = Math.max(0.7, Math.min(0.99, comebackThreshold));
    }
}
