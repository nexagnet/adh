package vn.shop.crm.zalo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vn.shop.crm.safety.TestMode;

import java.time.Clock;

/**
 * Bộ nối giả và đồng hồ giả cho test D16. Cờ môi trường bật bằng thuộc tính test; bộ hẹn giờ không chạy trong test.
 * Bộ nối giả cũng được bọc bởi {@link SafeZaloLink} y như bản chạy thật, nên mọi test đều đi qua lưới an toàn của chế độ thử
 * (test cũ chạy ở chế độ chạy thật nên lưới chỉ chuyển tiếp; test chế độ thử đặt {@code app.zalo.safety.live=false}).
 */
@TestConfiguration
public class ZaloTestConfig {

    @Bean
    FakeZaloGateway fakeZaloGateway() {
        return new FakeZaloGateway();
    }

    @Bean
    @Primary
    ZaloLink safeFakeZaloLink(FakeZaloGateway fake, TestMode mode) {
        return new SafeZaloLink(fake, mode);
    }

    @Bean
    @Primary
    ZaloTestClock zaloTestClock() {
        return new ZaloTestClock();
    }
}
