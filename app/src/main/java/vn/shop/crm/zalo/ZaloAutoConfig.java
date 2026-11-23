package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.safety.ZaloSafetyProperties;

/**
 * Chọn bộ nối: mặc định {@link ManualZaloGateway} (không làm gì); chỉ khi bật cờ môi trường mới có {@link ZcaBridgeGateway}.
 * Cả hai đều được bọc bởi {@link SafeZaloLink} (chế độ thử): không có đường nào tới Zalo mà không đi qua lớp bọc này.
 */
@Configuration
@EnableConfigurationProperties({ZaloAutoProperties.class, ZaloGroupProperties.class, ZaloInboxProperties.class, ZaloSafetyProperties.class})
public class ZaloAutoConfig {

    @Bean
    ZaloLink zaloLink(ZaloAutoProperties props, TestMode mode) {
        if (props.enabled() && (props.bridgeKey() == null || props.bridgeKey().isBlank())) {
            // thất bại kín: bật bộ nối mà không có khóa chung thì không khởi động
            throw new IllegalStateException("Đã bật APP_ZALO_AUTO_ENABLED nhưng chưa đặt APP_ZALO_BRIDGE_KEY.");
        }
        ZaloLink raw = props.enabled() ? new ZcaBridgeGateway(props.bridgeUrl(), props.bridgeKey()) : new ManualZaloGateway();
        return new SafeZaloLink(raw, mode);
    }
}
