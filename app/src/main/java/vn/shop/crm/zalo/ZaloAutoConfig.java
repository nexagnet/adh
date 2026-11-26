package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.safety.ZaloSafetyProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chọn bộ nối (D23: mỗi tài khoản một bộ nối, định tuyến ở {@link ZaloAccountRoutes}): mặc định {@link ManualZaloGateway} (không làm gì); chỉ khi bật cờ môi trường mới có {@link ZcaBridgeGateway}.
 * Cả hai đều được bọc bởi {@link SafeZaloLink} (chế độ thử): không có đường nào tới Zalo mà không đi qua lớp bọc này.
 */
@Configuration
@EnableConfigurationProperties({ZaloAutoProperties.class, ZaloAccountProperties.class, ZaloGroupProperties.class, ZaloInboxProperties.class, ZaloSafetyProperties.class})
public class ZaloAutoConfig {

    /**
     * Một {@link ZaloLink} (đã bọc {@link SafeZaloLink}) cho từng tài khoản (D23). Tài khoản 1 dùng cấu hình cũ nên khi không khai báo thêm gì thì
     * hệ thống chạy y như trước; tài khoản thêm chỉ có khi bật bộ nối và khai báo cầu nối riêng.
     */
    @Bean
    ZaloAccountRoutes zaloAccountRoutes(ZaloAutoProperties props, ZaloAccountProperties accounts, TestMode mode,
                                        ZaloBridgeIdentity identity, ZaloAutoSettings settings) {
        if (props.enabled() && (props.bridgeKey() == null || props.bridgeKey().isBlank())) {
            // thất bại kín: bật bộ nối mà không có khóa chung thì không khởi động
            throw new IllegalStateException("Đã bật APP_ZALO_AUTO_ENABLED nhưng chưa đặt APP_ZALO_BRIDGE_KEY.");
        }
        Map<Long, ZaloLink> links = new LinkedHashMap<>();
        ZaloLink first = primaryRoute(props, accounts, identity, settings);
        links.put(ZaloAccountContext.DEFAULT_ACCOUNT, new SafeZaloLink(first, mode));
        for (ZaloAccountProperties.Account a : accounts.accounts()) {
            ZaloLink raw = props.enabled() ? new ZcaBridgeGateway(a.bridgeUrl(), a.bridgeKey(), () -> identity.pinnedTag(a.id()), true,
                    () -> first.status().accountTag(), settings::tripped)
                    : new ManualZaloGateway();
            links.put(a.id(), new SafeZaloLink(raw, mode));
        }
        return new ZaloAccountRoutes(links);
    }

    private ZaloLink primaryRoute(ZaloAutoProperties props, ZaloAccountProperties accounts, ZaloBridgeIdentity identity,
                                  ZaloAutoSettings settings) {
        if (!props.enabled()) {
            return new ManualZaloGateway();
        }
        if (accounts.accounts().stream().anyMatch(a -> ZaloAccountProperties.endpoint(a.bridgeUrl())
                .equals(ZaloAccountProperties.endpoint(props.bridgeUrl())))) {
            throw new IllegalStateException("Địa chỉ cầu nối Zalo phụ trùng với cầu nối chính.");
        }
        return new ZcaBridgeGateway(props.bridgeUrl(), props.bridgeKey(),
                () -> accounts.accounts().isEmpty() ? null : identity.pinnedTag(ZaloAccountContext.DEFAULT_ACCOUNT),
                false, () -> null, settings::tripped);
    }

    @Bean
    ZaloLink zaloLink(ZaloAccountRoutes routes) {
        return routes.router();
    }
}
