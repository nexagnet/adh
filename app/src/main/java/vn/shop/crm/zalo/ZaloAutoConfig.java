package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chọn bộ nối: mặc định {@link ManualZaloGateway} (không làm gì); chỉ khi bật cờ môi trường mới có {@link ZcaBridgeGateway}. */
@Configuration
@EnableConfigurationProperties(ZaloAutoProperties.class)
public class ZaloAutoConfig {

    @Bean
    ZaloLink zaloLink(ZaloAutoProperties props) {
        if (props.enabled() && (props.bridgeKey() == null || props.bridgeKey().isBlank())) {
            // thất bại kín: bật bộ nối mà không có khóa chung thì không khởi động
            throw new IllegalStateException("Đã bật APP_ZALO_AUTO_ENABLED nhưng chưa đặt APP_ZALO_BRIDGE_KEY.");
        }
        return props.enabled() ? new ZcaBridgeGateway(props.bridgeUrl(), props.bridgeKey()) : new ManualZaloGateway();
    }
}
