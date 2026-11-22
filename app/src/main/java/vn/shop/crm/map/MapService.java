package vn.shop.crm.map;

import org.springframework.stereotype.Service;
import vn.shop.crm.customers.CustomerService;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.Region;
import vn.shop.crm.geo.VnGeo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dữ liệu cho bản đồ phân bố khách: chỉ SỐ ĐẾM theo tỉnh (không địa chỉ, không số điện thoại). Bản đồ là sơ đồ minh
 * họa: mỗi tỉnh là một chấm đặt theo tọa độ thành phố tỉnh lỵ, nằm trong một đường viền gần đúng của đất liền Việt
 * Nam. Không gọi dịch vụ bản đồ bên ngoài.
 */
@Service
public class MapService {

    public static final int WIDTH = 420;
    public static final int HEIGHT = 820;
    private static final double LON0 = 102.0;
    private static final double LAT0 = 23.7;
    private static final double SCALE = 50.0;

    /** Đường viền gần đúng đất liền (kinh độ, vĩ độ), đi ngược chiều kim đồng hồ từ Tây Bắc. Chỉ để dễ nhìn. */
    private static final double[][] OUTLINE = {
            {102.15, 22.4}, {102.5, 22.75}, {103.0, 22.5}, {103.5, 22.7}, {104.0, 22.8}, {104.4, 22.85},
            {105.0, 23.2}, {105.5, 23.35}, {106.0, 22.95}, {106.6, 22.85}, {106.7, 22.5}, {107.4, 22.0},
            {108.0, 21.55}, {107.8, 21.2}, {107.3, 20.85}, {106.9, 20.65}, {106.5, 20.25}, {106.1, 20.0},
            {105.9, 19.3}, {106.0, 18.8}, {105.8, 18.3}, {106.5, 17.7}, {106.8, 17.4}, {107.2, 16.8},
            {108.0, 16.3}, {108.3, 16.1}, {108.7, 15.5}, {109.0, 15.0}, {109.3, 14.2}, {109.3, 13.4},
            {109.3, 12.7}, {109.4, 12.3}, {109.2, 11.8}, {109.0, 11.3}, {108.4, 11.0}, {108.0, 10.7},
            {107.5, 10.5}, {107.25, 10.3}, {106.8, 10.4}, {106.7, 9.95}, {106.3, 9.7}, {106.0, 9.3},
            {105.5, 8.7}, {105.0, 8.6}, {104.8, 8.8}, {104.8, 9.3}, {105.0, 9.9}, {104.9, 10.2},
            {104.5, 10.4}, {105.0, 10.9}, {105.5, 11.2}, {105.8, 11.6}, {106.4, 11.9}, {106.7, 11.9},
            {107.5, 12.3}, {107.6, 12.6}, {107.4, 13.0}, {107.5, 14.4}, {107.3, 15.0}, {107.5, 15.6},
            {107.0, 16.2}, {106.7, 16.6}, {106.5, 17.0}, {106.0, 17.5}, {105.6, 17.9}, {105.1, 18.4},
            {104.6, 18.9}, {104.1, 19.4}, {104.6, 19.9}, {104.3, 20.3}, {103.8, 20.5}, {103.2, 20.9},
            {102.6, 21.2}, {102.1, 21.6}, {102.2, 22.0}};

    /** Một chấm trên bản đồ. {@code level} từ 0 (chưa có khách) đến 4 (nhiều nhất), dùng để tô màu. */
    public record Dot(String code, String name, String regionLabel, long count, double x, double y, double r, int level) {
        public String cx() {
            return fmt(x);
        }

        public String cy() {
            return fmt(y);
        }

        public String radius() {
            return fmt(r);
        }

        public String levelClass() {
            return "lv" + level;
        }
    }

    public record RegionTotal(String label, long count) {
    }

    public record MapData(List<Dot> dots, String outline, long total, long unknown, List<RegionTotal> regions,
                          List<Dot> ranking) {
    }

    private final CustomerService customers;

    public MapService(CustomerService customers) {
        this.customers = customers;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static double x(double lon) {
        return (lon - LON0) * SCALE;
    }

    static double y(double lat) {
        return (LAT0 - lat) * SCALE;
    }

    static String outlinePath() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < OUTLINE.length; i++) {
            sb.append(i == 0 ? "M" : "L").append(fmt(x(OUTLINE[i][0]))).append(' ').append(fmt(y(OUTLINE[i][1])))
                    .append(' ');
        }
        return sb.append('Z').toString();
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
            double r = n == 0 ? 3.5 : 6 + 12 * Math.sqrt((double) n / max);
            dots.add(new Dot(p.code(), p.name(), p.region().label(), n, x(p.lon()), y(p.lat()), r, level(n, max)));
        }
        // Chấm nhỏ vẽ sau để không bị chấm lớn che khi chồng lên nhau.
        dots.sort(java.util.Comparator.comparingDouble(Dot::r).reversed());
        List<RegionTotal> regions = new ArrayList<>();
        for (Region region : Region.values()) {
            long n = VnGeo.in(region).stream().mapToLong(p -> counts.getOrDefault(p.code(), 0L)).sum();
            regions.add(new RegionTotal(region.label(), n));
        }
        List<Dot> ranking = dots.stream().filter(d -> d.count() > 0)
                .sorted(java.util.Comparator.comparingLong(Dot::count).reversed().thenComparing(Dot::name)).toList();
        return new MapData(dots, outlinePath(), total, customers.countUnknownRegion(), regions, ranking);
    }
}
