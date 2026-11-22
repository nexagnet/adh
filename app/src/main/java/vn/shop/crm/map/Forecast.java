package vn.shop.crm.map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Dự báo gọn cho một tỉnh trong khoảng 48 giờ tới, kèm số liệu từng giờ để chạy thời tiết trên bản đồ.
 *
 * @param hourly số liệu theo giờ (có thể rỗng với dự báo lưu từ bản cũ)
 */
public record Forecast(double minTemp, double maxTemp, String symbol, OffsetDateTime fetchedAt, List<Hour> hourly) {

    /** Một mốc giờ: nhiệt độ (°C), lượng mưa trong giờ đó (mm), độ phủ mây (%) và mã biểu tượng của MET Norway. */
    public record Hour(long epoch, double temp, double rain, int cloud, String symbol) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public Forecast {
        hourly = hourly == null ? List.of() : List.copyOf(hourly);
    }

    public Forecast(double minTemp, double maxTemp, String symbol, OffsetDateTime fetchedAt) {
        this(minTemp, maxTemp, symbol, fetchedAt, List.of());
    }

    /** Chuỗi JSON gọn để lưu vào cơ sở dữ liệu: [[giây, nhiệt độ, mưa, mây, biểu tượng], ...]. */
    static String hourlyToJson(List<Hour> hours) {
        List<List<Object>> rows = new ArrayList<>();
        for (Hour h : hours) {
            rows.add(java.util.Arrays.asList(h.epoch(), h.temp(), h.rain(), h.cloud(), h.symbol()));
        }
        return JSON.writeValueAsString(rows);
    }

    /** Đọc lại chuỗi do {@link #hourlyToJson} ghi; dữ liệu hỏng thì trả rỗng, không làm hỏng trang. */
    static List<Hour> hourlyFromJson(String json) {
        List<Hour> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            for (JsonNode row : JSON.readTree(json)) {
                out.add(new Hour(row.get(0).asLong(), row.get(1).asDouble(), row.get(2).asDouble(), row.get(3).asInt(),
                        row.get(4).isNull() ? null : row.get(4).asString()));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Biểu tượng nhỏ cho từng loại thời tiết, dùng trên bản đồ. */
    public static String icon(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "";
        }
        String s = symbol.toLowerCase();
        if (s.contains("thunder")) {
            return "⛈️";
        }
        if (s.contains("snow") || s.contains("sleet")) {
            return "🌨️";
        }
        if (s.contains("heavyrain")) {
            return "🌧️";
        }
        if (s.contains("rain")) {
            return "🌦️";
        }
        if (s.startsWith("fog")) {
            return "🌫️";
        }
        if (s.startsWith("clearsky")) {
            return s.endsWith("_night") ? "🌙" : "☀️";
        }
        if (s.startsWith("fair") || s.startsWith("partlycloudy")) {
            return "⛅";
        }
        if (s.startsWith("cloudy")) {
            return "☁️";
        }
        return "";
    }

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
