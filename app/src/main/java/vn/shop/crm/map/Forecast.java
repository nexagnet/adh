package vn.shop.crm.map;

import java.time.OffsetDateTime;

/** Dự báo gọn cho một tỉnh trong khoảng 48 giờ tới. */
public record Forecast(double minTemp, double maxTemp, String symbol, OffsetDateTime fetchedAt) {

    /** Mô tả bằng tiếng Việt từ mã biểu tượng của MET Norway (ví dụ {@code partlycloudy_day}). */
    public String description() {
        return describe(symbol);
    }

    static String describe(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "Chưa rõ";
        }
        String s = symbol.toLowerCase();
        if (s.contains("thunder")) {
            return "Có dông";
        }
        if (s.contains("snow") || s.contains("sleet")) {
            return "Mưa lạnh";
        }
        if (s.contains("heavyrain")) {
            return "Mưa to";
        }
        if (s.contains("rain")) {
            return "Có mưa";
        }
        if (s.startsWith("fog")) {
            return "Sương mù";
        }
        if (s.startsWith("clearsky")) {
            return "Trời quang";
        }
        if (s.startsWith("fair")) {
            return "Trời đẹp";
        }
        if (s.startsWith("partlycloudy")) {
            return "Ít mây";
        }
        if (s.startsWith("cloudy")) {
            return "Nhiều mây";
        }
        return "Chưa rõ";
    }
}
