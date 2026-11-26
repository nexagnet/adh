package vn.shop.crm.map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.GroupService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bản đồ phân bố khách theo tỉnh và nhắc việc thời tiết. Chỉ hiện số đếm tổng hợp, không địa chỉ/số điện thoại. */
@Controller
public class MapController {

    public record GroupFit(String name, String statusLabel, String scopeLabel, int spotsLeft, boolean openNow) {
    }

    /** Một tỉnh trong dữ liệu JSON cho bản đồ: chỉ tên, miền, số đếm và tọa độ tỉnh lỵ. */
    public record ProvinceJson(String code, String name, String region, long count, int level, double lat, double lon) {
    }

    /** Nhãn thời tiết tiếng Việt kèm biểu tượng; các mốc giờ trỏ tới nhãn bằng số thứ tự cho gọn. */
    public record LabelJson(String text, String icon) {
    }

    /**
     * Thời tiết theo giờ. {@code series[mã tỉnh]} là danh sách [giây, nhiệt độ °C, mưa mm/giờ, mây %, số thứ tự nhãn].
     */
    public record WeatherJson(boolean enabled, String updatedAt, List<LabelJson> labels,
                              Map<String, List<List<Number>>> series) {
    }

    public record DataJson(List<ProvinceJson> provinces, WeatherJson weather) {
    }

    private final MapService map;
    private final WeatherService weather;
    private final GroupService groups;
    private final String tileUrl;
    private final String tileAttribution;

    public MapController(MapService map, WeatherService weather, GroupService groups,
                         @Value("${app.map.tile-url:" + MapSettings.DEFAULT_TILE_URL + "}") String tileUrl,
                         @Value("${app.map.tile-attribution:" + MapSettings.DEFAULT_TILE_ATTRIBUTION + "}") String tileAttribution) {
        this.map = map;
        this.weather = weather;
        this.groups = groups;
        this.tileUrl = tileUrl;
        this.tileAttribution = tileAttribution;
    }

    @GetMapping("/map")
    public String page(@RequestParam(required = false) String province, Model model) {
        MapService.MapData data = map.build();
        model.addAttribute("data", data);
        model.addAttribute("tileUrl", tileUrl);
        model.addAttribute("tileAttribution", tileAttribution);
        model.addAttribute("weatherOn", weather.enabled());
        model.addAttribute("weatherRefresh", weather.refreshStatus());
        model.addAttribute("alerts", weather.alerts());
        model.addAttribute("coldBac", weather.coldBac());
        model.addAttribute("coldTrung", weather.coldTrung());
        model.addAttribute("coldNam", weather.coldNam());
        model.addAttribute("hot", weather.hotThreshold());
        model.addAttribute("minCustomers", weather.minCustomers());
        Province selected = VnGeo.byCode(province).orElse(null);
        if (selected != null) {
            MapService.Dot dot = data.dots().stream().filter(d -> d.code().equals(selected.code())).findFirst().orElseThrow();
            model.addAttribute("selected", dot);
            model.addAttribute("forecast", weather.forecast(selected.code()).orElse(null));
            List<GroupFit> fits = groups.list().stream().filter(g -> groups.rank(g, selected) >= 0)
                    .sorted(java.util.Comparator.comparingInt((GroupService.Group g) -> groups.rank(g, selected))
                            .thenComparingLong(GroupService.Group::id))
                    .map(g -> new GroupFit(g.name(), g.status().label(), g.scopeLabel(), g.spotsLeft(),
                            g.status() == vn.shop.crm.invite.GroupStatus.OPEN && g.hasLink() && !g.full()))
                    .toList();
            model.addAttribute("fits", fits);
        }
        return "map";
    }

    /** Số đếm theo tỉnh và thời tiết từng giờ cho trang bản đồ. Không có địa chỉ hay số điện thoại. */
    @GetMapping("/map/data.json")
    @ResponseBody
    public DataJson data() {
        List<ProvinceJson> provinces = map.build().dots().stream()
                .map(d -> new ProvinceJson(d.code(), d.name(), d.regionLabel(), d.count(), d.level(), d.lat(), d.lon()))
                .toList();
        List<LabelJson> labels = new ArrayList<>();
        Map<String, List<List<Number>>> series = new LinkedHashMap<>();
        OffsetDateTime updated = null;
        if (weather.enabled()) {
            Map<String, Integer> labelIndex = new LinkedHashMap<>();
            for (Map.Entry<String, Forecast> e : weather.allForecasts().entrySet()) {
                Forecast f = e.getValue();
                if (f.hourly().isEmpty()) {
                    continue;
                }
                if (updated == null || f.fetchedAt().isAfter(updated)) {
                    updated = f.fetchedAt();
                }
                List<List<Number>> rows = new ArrayList<>();
                for (Forecast.Hour h : f.hourly()) {
                    String text = Forecast.describe(h.symbol());
                    String icon = Forecast.icon(h.symbol());
                    Integer idx = labelIndex.get(text + "|" + icon);
                    if (idx == null) {
                        idx = labels.size();
                        labelIndex.put(text + "|" + icon, idx);
                        labels.add(new LabelJson(text, icon));
                    }
                    rows.add(List.of(h.epoch(), h.temp(), h.rain(), h.cloud(), idx));
                }
                series.put(e.getKey(), rows);
            }
        }
        return new DataJson(provinces,
                new WeatherJson(weather.enabled(), updated == null ? null : updated.toString(), labels, series));
    }

    @PostMapping("/map/weather")
    public String refreshWeather(@RequestParam(required = false) String province, RedirectAttributes redirect) {
        if (!weather.enabled()) {
            redirect.addFlashAttribute("flash", "Chưa bật thời tiết. Hãy đặt APP_WEATHER_ENABLED=true rồi khởi động lại.");
        } else {
            weather.requestRefresh();
        }
        VnGeo.byCode(province).ifPresent(p -> redirect.addAttribute("province", p.code()));
        return "redirect:/map";
    }

    @GetMapping("/map/weather/status")
    @ResponseBody
    public ResponseEntity<WeatherService.RefreshStatus> weatherStatus() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(weather.refreshStatus());
    }
}
