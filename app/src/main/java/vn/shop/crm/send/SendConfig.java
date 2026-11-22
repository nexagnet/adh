package vn.shop.crm.send;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableConfigurationProperties(SendProperties.class)
@EnableScheduling
public class SendConfig {

    /** Đồng hồ theo giờ Việt Nam; test thay bằng đồng hồ giả để kiểm tra giờ gửi. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
    }
}
