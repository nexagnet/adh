package vn.shop.crm.map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.security.SecurityConfig;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bản đồ thật: dữ liệu JSON cho Leaflet, ranh giới tỉnh đi kèm ứng dụng và chính sách bảo mật cho ô bản đồ. */
class MapDataTest extends AbstractPostgresTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;
    @Autowired
    CustomerService customers;
    @Autowired
    GroupService groups;
    @Autowired
    MapService map;
    @Autowired
    JdbcClient db;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private WeatherService weather(boolean enabled, boolean all, WeatherClient client) {
        return new WeatherService(db, client, customers, enabled, 13, 16, 22, 37, 10, all) {
            @Override
            void sleepQuietly() {
            }
        };
    }

    private MockMvc standalone(WeatherService weather) {
        return MockMvcBuilders.standaloneSetup(new MapController(map, weather, groups, MapSettings.DEFAULT_TILE_URL,
                MapSettings.DEFAULT_TILE_ATTRIBUTION)).build();
    }

    @Test
    void manualWeatherRefreshReturnsBeforeProviderAndDeduplicates() throws Exception {
        db.sql("""
                INSERT INTO customer (phone, name, address, segment, successful_purchases, province)
                VALUES ('+84901234000', 'K', 'x', 'ONE_TIME', 1, 'can-tho')""").update();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        WeatherService weather = weather(true, false, (lat, lon) -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test chưa mở khóa dự báo");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            var now = OffsetDateTime.now();
            return Optional.of(new WeatherClient.Result(new Forecast(20, 30, "rain", now, List.of()), now.plusHours(3)));
        });
        MockMvc api = standalone(weather);
        try {
            try (var requests = Executors.newSingleThreadExecutor()) {
                var submitted = requests.submit(() -> api.perform(post("/map/weather")).andReturn());
                try {
                    assertThat(submitted.get(5, TimeUnit.SECONDS).getResponse().getRedirectedUrl()).isEqualTo("/map");
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    api.perform(post("/map/weather")).andExpect(redirectedUrl("/map"));
                    api.perform(get("/map/weather/status")).andExpect(status().isOk())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.state").value("RUNNING"));
                    assertThat(weather.refresh(false)).isZero();
                    assertThat(calls).hasValue(1);
                } finally {
                    release.countDown();
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (weather.refreshStatus().running() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            api.perform(get("/map/weather/status")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("COMPLETE"))
                    .andExpect(jsonPath("$.updated").value(1));
            assertThat(weather.forecast("can-tho")).isPresent();
            assertThat(calls).hasValue(1);
        } finally {
            weather.stopManualRefresh();
        }
    }

    @Test
    void ranhGioi63TinhKhopMaTinhCuaUngDung() throws IOException {
        JsonNode geo;
        try (var in = new ClassPathResource("static/map/vn-provinces.geojson").getInputStream()) {
            geo = JSON.readTree(in);
        }
        Set<String> codes = new HashSet<>();
        for (JsonNode f : geo.get("features")) {
            String code = f.get("properties").get("code").asString();
            assertThat(VnGeo.byCode(code)).as(code).isPresent();
            assertThat(codes.add(code)).as("không trùng " + code).isTrue();
            assertThat(f.get("geometry").get("type").asString()).isEqualTo("MultiPolygon");
            // tọa độ [kinh độ, vĩ độ] nằm trong khung Việt Nam (kể cả đảo)
            for (JsonNode poly : f.get("geometry").get("coordinates")) {
                for (JsonNode ring : poly) {
                    assertThat(ring.size()).isGreaterThanOrEqualTo(4);
                    for (JsonNode pt : ring) {
                        assertThat(pt.get(0).asDouble()).isBetween(102.0, 110.0);
                        assertThat(pt.get(1).asDouble()).isBetween(8.0, 23.6);
                    }
                }
            }
        }
        assertThat(codes).hasSize(63).containsAll(VnGeo.all().stream().map(p -> p.code()).toList());
        // Leaflet được đóng gói cùng ứng dụng, không phụ thuộc CDN
        assertThat(new ClassPathResource("static/vendor/leaflet/leaflet.js").exists()).isTrue();
        assertThat(new ClassPathResource("static/vendor/leaflet/leaflet.css").exists()).isTrue();
        assertThat(new ClassPathResource("static/map.js").exists()).isTrue();
    }

    @Test
    void trangBanDoNapLeafletTuMayChuCuaMinh() throws Exception {
        mvc.perform(get("/map").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/vendor/leaflet/leaflet.js")))
                .andExpect(content().string(containsString("/vendor/leaflet/leaflet.css")))
                .andExpect(content().string(containsString("/map.js")))
                .andExpect(content().string(containsString("OpenStreetMap")))
                .andExpect(content().string(containsString("Natural Earth")))
                .andExpect(content().string(containsString("Nhiệt độ")))
                .andExpect(content().string(not(containsString("unpkg.com"))))
                .andExpect(content().string(not(containsString("cdn"))));
    }

    @Test
    void chinhSachBaoMatChiMoThemMayChuOCuaBanDo() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("img-src 'self' data: https://tile.openstreetmap.org;")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("script-src"))));
        assertThat(SecurityConfig.tileOrigin("https://tiles.example.org:8443/{z}/{x}/{y}.png"))
                .isEqualTo(" https://tiles.example.org:8443");
        assertThat(SecurityConfig.tileOrigin("http://khong-https/{z}/{x}/{y}.png")).isEmpty();
        assertThat(SecurityConfig.tileOrigin("/tile/{z}/{x}/{y}.png")).isEmpty();
        assertThat(SecurityConfig.tileOrigin("không phải địa chỉ")).isEmpty();
    }

    @Test
    void duLieuJsonCoSoDemVaThoiTietTungGio() throws Exception {
        for (int i = 0; i < 3; i++) {
            db.sql("""
                    INSERT INTO customer (phone, name, address, segment, successful_purchases, province)
                    VALUES (:p, 'K', 'x', 'ONE_TIME', 1, 'can-tho')""").param("p", "+849012340" + i).update();
        }
        long now = OffsetDateTime.now().toEpochSecond();
        Forecast forecast = new Forecast(20.0, 30.0, "rain", OffsetDateTime.now(), List.of(
                new Forecast.Hour(now, 24.5, 0.0, 40, "partlycloudy_day"),
                new Forecast.Hour(now + 3600, 23.0, 2.4, 95, "heavyrain")));
        WeatherService on = weather(true, true, (lat, lon) -> Optional.of(new WeatherClient.Result(forecast,
                OffsetDateTime.now().plusHours(3))));
        // tắt hỏi toàn bộ tỉnh trong test để nhanh: tỉnh có khách + tất cả = 63 lượt gọi giả
        assertThat(on.refresh(true)).isEqualTo(63);

        standalone(on).perform(get("/map/data.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provinces.length()").value(63))
                .andExpect(jsonPath("$.provinces[?(@.code=='can-tho')].count").value(3))
                .andExpect(jsonPath("$.provinces[?(@.code=='can-tho')].name").value("Cần Thơ"))
                .andExpect(jsonPath("$.provinces[?(@.code=='ha-noi')].level").value(0))
                .andExpect(jsonPath("$.weather.enabled").value(true))
                .andExpect(jsonPath("$.weather.series.length()").value(63))
                .andExpect(jsonPath("$.weather.series.can-tho.length()").value(2))
                .andExpect(jsonPath("$.weather.series.can-tho[0][1]").value(24.5))
                .andExpect(jsonPath("$.weather.series.can-tho[1][2]").value(2.4))
                .andExpect(jsonPath("$.weather.series.can-tho[1][3]").value(95))
                .andExpect(jsonPath("$.weather.labels[0].text").value("Ít mây"))
                .andExpect(jsonPath("$.weather.labels[1].text").value("Mưa to"))
                .andExpect(content().string(not(containsString("+84"))))
                .andExpect(content().string(not(containsString("address"))));
    }

    @Test
    void thoiTietTatHoacChiHoiTinhCoKhach() throws Exception {
        db.sql("""
                INSERT INTO customer (phone, name, address, segment, successful_purchases, province)
                VALUES ('+84901234999', 'K', 'x', 'ONE_TIME', 1, 'hue')""").update();
        standalone(weather(false, true, (a, b) -> {
            throw new AssertionError("không được gọi khi tắt");
        })).perform(get("/map/data.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weather.enabled").value(false))
                .andExpect(jsonPath("$.weather.series.length()").value(0));

        int[] calls = {0};
        WeatherService onlyCustomers = weather(true, false, (lat, lon) -> {
            calls[0]++;
            return Optional.of(new WeatherClient.Result(
                    new Forecast(20, 25, "cloudy", OffsetDateTime.now(), List.of(
                            new Forecast.Hour(OffsetDateTime.now().toEpochSecond(), 22, 0, 70, "cloudy"))),
                    OffsetDateTime.now().plusHours(3)));
        });
        assertThat(onlyCustomers.refresh(true)).isEqualTo(1);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(onlyCustomers.forecast("hue")).isPresent();
        assertThat(onlyCustomers.forecast("hue").orElseThrow().hourly()).hasSize(1);
    }

    @Test
    void dubaoCuKhongCoSoLieuGioPhaiDuocHoiLai() {
        db.sql("""
                INSERT INTO weather_forecast (province, min_temp, max_temp, symbol, expires_at)
                VALUES ('hue', 20.0, 27.0, 'rain', now() + interval '3 hours')""").update();
        int[] calls = {0};
        WeatherService service = weather(true, false, (lat, lon) -> {
            calls[0]++;
            return Optional.of(new WeatherClient.Result(new Forecast(20, 25, "cloudy", OffsetDateTime.now(), List.of(
                    new Forecast.Hour(OffsetDateTime.now().toEpochSecond(), 22, 0, 70, "cloudy"))),
                    OffsetDateTime.now().plusHours(3)));
        });
        db.sql("""
                INSERT INTO customer (phone, name, address, segment, successful_purchases, province)
                VALUES ('+84901234998', 'K', 'x', 'ONE_TIME', 1, 'hue')""").update();
        // dòng cũ còn hạn nhưng chưa có số liệu theo giờ: vẫn phải hỏi lại một lần
        assertThat(service.refresh(false)).isEqualTo(1);
        assertThat(service.refresh(false)).isZero();
        assertThat(calls[0]).isEqualTo(1);
    }
}
