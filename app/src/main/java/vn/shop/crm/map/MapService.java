package vn.shop.crm.map;

import org.springframework.stereotype.Service;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.Region;
import vn.shop.crm.geo.VnGeo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dữ liệu cho bản đồ phân bố khách: chỉ SỐ ĐẾM theo tỉnh (không địa chỉ, không số điện thoại). Hình bản đồ (nền
 * OpenStreetMap, ranh giới tỉnh) do trình duyệt vẽ bằng Leaflet; máy chủ chỉ đưa số đếm và tọa độ tỉnh lỵ.
 */
@Service
public class MapService {

    /** Một tỉnh trên bản đồ. {@code level} từ 0 (chưa có khách) đến 4 (nhiều nhất), dùng để tô màu. */
    public record Dot(String code, String name, String regionLabel, long count, double lat, double lon, int level) {
    }

    public record RegionTotal(String label, long count) {
    }

    public record MapData(List<Dot> dots, long total, long unknown, List<RegionTotal> regions, List<Dot> ranking) {
    }

    private final CustomerService customers;

    public MapService(CustomerService customers) {
        this.customers = customers;
    }

    /** Mức tô màu theo tỷ lệ so với tỉnh đông khách nhất. */
    static int level(long count, long max) {
        if (count <= 0 || max <= 0) {
            return 0;
        }
        double ratio = (double) count / max;
        if (ratio > 0.75) {
            return 4;
        }
        if (ratio > 0.4) {
            return 3;
        }
        if (ratio > 0.15) {
            return 2;
        }
        return 1;
    }

    public MapData build() {
        Map<String, Long> counts = customers.countByProvince();
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<Dot> dots = new ArrayList<>();
        for (Province p : VnGeo.all()) {
            long n = counts.getOrDefault(p.code(), 0L);
            dots.add(new Dot(p.code(), p.name(), p.region().label(), n, p.lat(), p.lon(), level(n, max)));
        }
        List<RegionTotal> regions = new ArrayList<>();
        for (Region region : Region.values()) {
            long n = VnGeo.in(region).stream().mapToLong(p -> counts.getOrDefault(p.code(), 0L)).sum();
            regions.add(new RegionTotal(region.label(), n));
        }
        List<Dot> ranking = dots.stream().filter(d -> d.count() > 0)
                .sorted(java.util.Comparator.comparingLong(Dot::count).reversed().thenComparing(Dot::name)).toList();
        return new MapData(dots, total, customers.countUnknownRegion(), regions, ranking);
    }
}
