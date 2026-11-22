package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalTime;

/**
 * Cấu hình bộ nối Zalo không chính thức (D16, ADR-0002). MẶC ĐỊNH TẮT: không đặt gì thì không có lệnh gọi Zalo nào.
 * Hạn mức mặc định do chủ shop chốt 22/11/2026 (50 người/ngày, rải 07:00–22:00, tối đa 5/giờ, cách nhau ≥ 2 phút);
 * trên trang quản trị chỉnh được đến các trần cứng ở đây (muốn nâng trần phải đổi cấu hình triển khai).
 */
@ConfigurationProperties(prefix = "app.zalo.auto")
public record ZaloAutoProperties(
        /* Công tắc thứ nhất (biến môi trường APP_ZALO_AUTO_ENABLED). Công tắc thứ hai là nấc chọn trên trang quản trị. */
        @DefaultValue("false") boolean enabled,
        /* Địa chỉ dịch vụ cầu nối (mạng nội bộ Docker) và khóa chung. */
        @DefaultValue("http://zalo-bridge:3100") String bridgeUrl,
        @DefaultValue("") String bridgeKey,
        @DefaultValue("07:00") LocalTime windowStart,
        @DefaultValue("22:00") LocalTime windowEnd,
        @DefaultValue("50") int defaultDaily,
        @DefaultValue("5") int defaultHourly,
        @DefaultValue("2") int defaultGapMinutes,
        /* Trần cứng: trang quản trị không cho chỉnh vượt các số này. */
        @DefaultValue("100") int maxDaily,
        @DefaultValue("10") int maxHourly,
        /* Khoảng cách tối thiểu giữa hai lần thêm mà trang quản trị cho đặt (không cho đặt thấp hơn). */
        @DefaultValue("1") int minGapFloorMinutes) {

    public ZaloAutoProperties {
        if (maxDaily < 1) {
            maxDaily = 1;
        }
        if (maxHourly < 1) {
            maxHourly = 1;
        }
        if (minGapFloorMinutes < 1) {
            minGapFloorMinutes = 1;
        }
    }
}
