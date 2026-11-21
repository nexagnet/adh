package vn.shop.crm.map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.GroupService;

import java.util.List;

/** Bản đồ phân bố khách theo tỉnh và nhắc việc thời tiết. Chỉ hiện số đếm tổng hợp, không địa chỉ/số điện thoại. */
@Controller
public class MapController {

    public record GroupFit(String name, String statusLabel, String scopeLabel, int spotsLeft, boolean openNow) {
    }

    private final MapService map;
    private final WeatherService weather;
    private final GroupService groups;

    public MapController(MapService map, WeatherService weather, GroupService groups) {
        this.map = map;
        this.weather = weather;
        this.groups = groups;
    }

    @GetMapping("/map")
    public String page(@RequestParam(required = false) String province, Model model) {
        MapService.MapData data = map.build();
        model.addAttribute("data", data);
        model.addAttribute("width", MapService.WIDTH);
        model.addAttribute("height", MapService.HEIGHT);
        model.addAttribute("weatherOn", weather.enabled());
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

    @PostMapping("/map/weather")
    public String refreshWeather(RedirectAttributes redirect) {
        if (!weather.enabled()) {
            redirect.addFlashAttribute("flash", "Chưa bật thời tiết. Hãy đặt APP_WEATHER_ENABLED=true rồi khởi động lại.");
        } else {
            int n = weather.refresh(true);
            redirect.addFlashAttribute("flash", "Đã cập nhật thời tiết cho " + n + " tỉnh.");
        }
        return "redirect:/map";
    }
}
