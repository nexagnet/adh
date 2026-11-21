package vn.shop.crm.map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bản đồ khách theo tỉnh và nhắc việc thời tiết (dịch vụ thời tiết giả, không gọi mạng). */
class WeatherMapTest extends AbstractPostgresTest {

    static final String SAMPLE = """
            {"properties":{"timeseries":[
              {"time":"2026-11-21T15:00:00Z","data":{"instant":{"details":{"air_temperature":24.5}},
                "next_1_hours":{"summary":{"symbol_code":"partlycloudy_night"}}}},
              {"time":"2026-11-21T21:00:00Z","data":{"instant":{"details":{"air_temperature":21.0}},
                "next_6_hours":{"summary":{"symbol_code":"rain"}}}},
              {"time":"2026-11-22T15:00:00Z","data":{"instant":{"details":{"air_temperature":31.2}}}},
              {"time":"2026-11-30T15:00:00Z","data":{"instant":{"details":{"air_temperature":5.0}}}}
            ]}}""";

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CustomerService customers;
    @Autowired
    GroupService groups;
    @Autowired
    MapService map;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private void customers(String province, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.sql("""
                    INSERT INTO customer (phone, name, address, segment, successful_purchases, province)
                    VALUES (:p, 'K', 'x', 'ONE_TIME', 1, :prov)""")
                    .param("p", "+8490" + province.hashCode() % 1000 + String.format("%04d", i))
                    .param("prov", province).update();
        }
    }

    @Test
    void docDuBaoMet() {
        Forecast f = MetNoWeatherClient.parse(SAMPLE, OffsetDateTime.now()).orElseThrow();
        // mốc sau 48 giờ (5°C ngày 30/11) không được tính
        assertThat(f.minTemp()).isEqualTo(21.0);
        assertThat(f.maxTemp()).isEqualTo(31.2);
        assertThat(f.symbol()).isEqualTo("partlycloudy_night");
        assertThat(f.description()).isEqualTo("Ít mây");
        assertThat(MetNoWeatherClient.parse("không phải json", OffsetDateTime.now())).isEmpty();
        assertThat(MetNoWeatherClient.parse("{\"properties\":{\"timeseries\":[]}}", OffsetDateTime.now())).isEmpty();
        assertThat(Forecast.describe("heavyrainandthunder")).isEqualTo("Có dông");
        assertThat(Forecast.describe("lightrain")).isEqualTo("Có mưa");
        assertThat(Forecast.describe("heavyrain")).isEqualTo("Mưa to");
        assertThat(Forecast.describe("fog")).isEqualTo("Sương mù");
        assertThat(Forecast.describe("clearsky_day")).isEqualTo("Trời quang");
        assertThat(Forecast.describe(null)).isEqualTo("Chưa rõ");
    }

    @Test
    void banDoChiCoSoDemTheoTinh() {
        customers("can-tho", 4);
        customers("ha-noi", 1);
        var data = map.build();
        assertThat(data.dots()).hasSize(63);
        assertThat(data.total()).isEqualTo(5);
        assertThat(data.ranking().get(0).code()).isEqualTo("can-tho");
        assertThat(data.ranking().get(0).level()).isEqualTo(4);
        assertThat(data.regions().stream().filter(r -> r.label().equals("Miền Nam")).findFirst().orElseThrow().count())
                .isEqualTo(4);
        assertThat(data.outline()).startsWith("M").endsWith("Z");
        assertThat(MapService.level(0, 10)).isZero();
        assertThat(MapService.level(1, 10)).isEqualTo(1);
        assertThat(MapService.level(3, 10)).isEqualTo(2);
        assertThat(MapService.level(5, 10)).isEqualTo(3);
        assertThat(MapService.level(10, 10)).isEqualTo(4);
    }

    @Test
    void nhacLanhONhieuKhachMienNamVaKhongNhacKhiItKhach() {
        AtomicInteger calls = new AtomicInteger();
        WeatherClient fake = (lat, lon) -> {
            calls.incrementAndGet();
            return Optional.of(new WeatherClient.Result(new Forecast(20.0, 28.0, "cloudy", OffsetDateTime.now()),
                    OffsetDateTime.now().plusHours(3)));
        };
        WeatherService weather = new WeatherService(jdbc, fake, customers, true, 13, 16, 22, 37, 10) {
            @Override
            void sleepQuietly() {
            }
        };
        customers("can-tho", 12);   // miền Nam, 20°C <= 22 -> nhắc
        customers("ha-noi", 12);    // miền Bắc, 20°C > 13 -> không nhắc
        customers("da-nang", 3);    // ít khách -> không nhắc

        assertThat(weather.refresh(false)).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(3);
        // dự báo còn hạn: không gọi lại
        assertThat(weather.refresh(false)).isZero();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(weather.refresh(true)).isEqualTo(3);

        List<WeatherService.Alert> alerts = weather.alerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).province().code()).isEqualTo("can-tho");
        assertThat(alerts.get(0).kind()).isEqualTo(WeatherService.Kind.COLD);
        assertThat(alerts.get(0).message()).contains("Cần Thơ").contains("12 khách").contains("Mẹ cân nhắc");
        assertThat(weather.forecast("ha-noi")).isPresent();
    }

    @Test
    void nhacNongVaThoiTietTatThiKhongGoi() {
        WeatherClient fake = (lat, lon) -> Optional.of(new WeatherClient.Result(
                new Forecast(27.0, 38.5, "clearsky_day", OffsetDateTime.now()), OffsetDateTime.now().plusHours(3)));
        WeatherService on = new WeatherService(jdbc, fake, customers, true, 13, 16, 22, 37, 5) {
            @Override
            void sleepQuietly() {
            }
        };
        customers("nghe-an", 6);
        on.refresh(false);
        assertThat(on.alerts()).hasSize(1);
        assertThat(on.alerts().get(0).kind()).isEqualTo(WeatherService.Kind.HOT);
        assertThat(on.alerts().get(0).message()).contains("nóng");

        WeatherService off = new WeatherService(jdbc, (a, b) -> {
            throw new AssertionError("không được gọi khi tắt");
        }, customers, false, 13, 16, 22, 37, 5);
        assertThat(off.refresh(true)).isZero();
    }

    @Test
    void nguonLoiThiGiuDuBaoCuVaKhongHong() {
        WeatherService weather = new WeatherService(jdbc, (a, b) -> Optional.empty(), customers, true, 13, 16, 22, 37, 1) {
            @Override
            void sleepQuietly() {
            }
        };
        customers("hue", 2);
        assertThat(weather.refresh(false)).isZero();
        assertThat(weather.allForecasts()).isEmpty();
    }

    @Test
    void trangBanDoVaTongQuanHienNhacViec() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/map")).andExpect(status().is3xxRedirection());
        customers("can-tho", 3);
        long g = groups.create("Nhóm Nam", "https://zalo.me/g/nam", 5, "R:NAM");
        groups.setStatus(g, GroupStatus.OPEN);
        long b = groups.create("Nhóm Bắc", "https://zalo.me/g/bac", 5, "R:BAC");
        groups.setStatus(b, GroupStatus.OPEN);
        jdbc.sql("""
                INSERT INTO weather_forecast (province, min_temp, max_temp, symbol, expires_at)
                VALUES ('can-tho', 20.0, 27.0, 'rain', now() + interval '3 hours')""").update();

        mvc.perform(get("/map").with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bản đồ khách theo tỉnh")))
                .andExpect(content().string(containsString("Cần Thơ")))
                .andExpect(content().string(containsString("MET Norway")))
                .andExpect(content().string(containsString("Thời tiết đang")))
                .andExpect(content().string(not(containsString("+84"))));
        mvc.perform(get("/map").param("province", "can-tho").with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nhóm Nam")))
                .andExpect(content().string(not(containsString("Nhóm Bắc"))))
                .andExpect(content().string(containsString("Có mưa")))
                .andExpect(content().string(containsString("thấp nhất")));
        // mã tỉnh lạ không làm hỏng trang
        mvc.perform(get("/map").param("province", "khong-co").with(owner)).andExpect(status().isOk());
        // thời tiết tắt: nút cập nhật chỉ báo cách bật
        mvc.perform(post("/map/weather").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/").with(owner)).andExpect(status().isOk());
    }
}
