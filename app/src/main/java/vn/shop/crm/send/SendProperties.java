package vn.shop.crm.send;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalTime;

/** Cấu hình cổng chặn gửi (D3–D6, mục 3.3 file 03). Mặc định an toàn: không gửi thật. */
@ConfigurationProperties(prefix = "app.sending")
public record SendProperties(
        /* Gửi thật qua dịch vụ bên ngoài. Chỉ chủ cửa hàng bật bằng biến môi trường APP_SENDING_ENABLED. */
        @DefaultValue("false") boolean enabled,
        @DefaultValue("07:00") LocalTime windowStart,
        @DefaultValue("22:00") LocalTime windowEnd,
        @DefaultValue("20") int dailyLimit,
        /* Tối đa bao nhiêu tin trong 60 phút gần nhất (nhắn tay theo nhịp chậm). 0 = không giới hạn theo giờ. */
        @DefaultValue("6") int hourlyLimit,
        /* Khoảng nghỉ CỐ ĐỊNH (giây) giữa hai tin khi gửi từng tin một. Công khai, không ngẫu nhiên hóa. */
        @DefaultValue("120") int gapSeconds,
        /* Bật bộ hẹn giờ gửi từng tin một. Chỉ có tác dụng khi có kênh gửi tự động (chưa có). */
        @DefaultValue("false") boolean schedulerEnabled,
        @DefaultValue("") String autoChannel,
        @DefaultValue("1000000") long monthlyBudgetVnd,
        @DefaultValue("3") int stopAfterConsecutiveFailures,
        @DefaultValue("0.3") double stopFailureRate,
        @DefaultValue("0.05") double stopOptOutRate,
        /* Nhịp GỢI Ý cho người nhắn tay: hai tin cách nhau ít nhất bấy nhiêu phút. Chỉ hiển thị, không ép buộc. */
        @DefaultValue("3") int suggestedGapMinutes) {
}
