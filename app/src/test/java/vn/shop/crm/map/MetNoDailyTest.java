package vn.shop.crm.map;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Dự báo nhiều ngày từ chuỗi MET Norway: nhóm theo ngày giờ Việt Nam, nhiệt độ thấp nhất/cao nhất, tổng mưa; bỏ ngày chỉ có một mốc. */
class MetNoDailyTest {

    private static String entry(String time, double temp, Double rain1h, Double rain6h) {
        StringBuilder sb = new StringBuilder("{\"time\":\"" + time + "\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":" + temp + ",\"cloud_area_fraction\":50}}");
        if (rain1h != null) {
            sb.append(",\"next_1_hours\":{\"summary\":{\"symbol_code\":\"cloudy\"},\"details\":{\"precipitation_amount\":").append(rain1h).append("}}");
        }
        if (rain6h != null) {
            sb.append(",\"next_6_hours\":{\"summary\":{\"symbol_code\":\"rain\"},\"details\":{\"precipitation_amount\":").append(rain6h).append("}}");
        }
        return sb.append("}}").toString();
    }

    @Test
    void gomTheoNgayGioVietNamVaBoNgayChiCoMotMoc() {
        // 17:00Z là 00:00 giờ Việt Nam của ngày hôm sau
        String json = "{\"properties\":{\"timeseries\":[" + String.join(",",
                entry("2026-11-24T00:00:00Z", 25.0, 0.5, null),   // 24/11 07:00
                entry("2026-11-24T06:00:00Z", 31.0, 1.0, null),   // 24/11 13:00
                entry("2026-11-24T16:00:00Z", 24.0, 0.0, null),   // 24/11 23:00
                entry("2026-11-24T17:00:00Z", 23.0, null, 8.0),   // 25/11 00:00, mốc 6 giờ
                entry("2026-11-25T05:00:00Z", 33.0, null, 4.0),   // 25/11 12:00
                entry("2026-11-25T17:00:00Z", 22.0, null, 1.0))   // 26/11 00:00, chỉ một mốc trong ngày
                + "]}}";
        Optional<Forecast> f = MetNoWeatherClient.parse(json, OffsetDateTime.parse("2026-11-24T00:00:00Z"));
        assertThat(f).isPresent();
        var days = f.get().daily();
        assertThat(days).hasSize(2);
        assertThat(days.get(0)).isEqualTo(new Forecast.Day(LocalDate.of(2026, 11, 24), 24.0, 31.0, 1.5));
        assertThat(days.get(1)).isEqualTo(new Forecast.Day(LocalDate.of(2026, 11, 25), 23.0, 33.0, 12.0));
    }

    @Test
    void luuVaDocLaiDuBaoNgay() {
        var days = java.util.List.of(new Forecast.Day(LocalDate.of(2026, 11, 24), 24.0, 31.0, 1.5));
        assertThat(Forecast.dailyFromJson(Forecast.dailyToJson(days))).isEqualTo(days);
        assertThat(Forecast.dailyFromJson(null)).isEmpty();
        assertThat(Forecast.dailyFromJson("không phải json")).isEmpty();
    }
}
