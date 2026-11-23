package vn.shop.crm.safety;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Chế độ thử hay chạy thật của mọi đường liên hệ khách qua Zalo. MẶC ĐỊNH là chế độ THỬ ({@code live=false}): hệ thống chỉ được
 * liên hệ những số điện thoại chủ shop đã khai báo là số thử. Chạy thật ({@code APP_ZALO_LIVE=true}) chỉ bật được bằng biến môi trường
 * trên máy chủ, cố ý KHÔNG có nút trên giao diện, để một cú bấm nhầm hay một công tắc bật nhầm không đưa tin tới khách thật.
 */
@ConfigurationProperties(prefix = "app.zalo.safety")
public record ZaloSafetyProperties(@DefaultValue("false") boolean live) {
}
