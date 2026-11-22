package vn.shop.crm.zalo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/** Bộ nối giả và đồng hồ giả cho test D16. Cờ môi trường bật bằng thuộc tính test; bộ hẹn giờ không chạy trong test. */
@TestConfiguration
public class ZaloTestConfig {

    @Bean
    @Primary
    FakeZaloGateway fakeZaloGateway() {
        return new FakeZaloGateway();
    }

    @Bean
    @Primary
    ZaloTestClock zaloTestClock() {
        return new ZaloTestClock();
    }
}
