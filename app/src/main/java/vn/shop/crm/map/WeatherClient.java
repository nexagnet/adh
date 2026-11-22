package vn.shop.crm.map;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Lấy dự báo cho một tọa độ. Chỉ nhận tọa độ: không bao giờ gửi dữ liệu khách ra ngoài. */
public interface WeatherClient {

    record Result(Forecast forecast, OffsetDateTime expiresAt) {
    }

    /** Rỗng nếu dịch vụ lỗi hoặc không trả dữ liệu dùng được. */
    Optional<Result> fetch(double lat, double lon);
}
