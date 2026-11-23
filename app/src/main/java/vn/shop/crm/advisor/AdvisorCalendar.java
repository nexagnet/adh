package vn.shop.crm.advisor;

import vn.shop.crm.geo.Region;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Mùa theo miền và các dịp bán hàng sắp tới, chỉ để AI có ngữ cảnh. Đây là bảng XẤP XỈ đặt cứng trong mã (không phải dữ liệu
 * khách): ngày Tết âm lịch cần bổ sung khi hết năm 2029, và chủ shop có thể sửa mùa nếu thấy chưa sát.
 */
final class AdvisorCalendar {

    private AdvisorCalendar() {
    }

    /** Ngày mùng 1 Tết Nguyên đán theo dương lịch. */
    private static final List<LocalDate> TET = List.of(
            LocalDate.of(2027, 2, 6), LocalDate.of(2028, 1, 26), LocalDate.of(2029, 2, 13));

    /** Mùa của một miền trong một tháng, nói theo thời tiết thường gặp. */
    static String season(Region region, int month) {
        return switch (region) {
            case BAC -> switch (month) {
                case 12, 1, 2 -> "Mùa đông, có đợt rét";
                case 3, 4 -> "Mùa xuân, ẩm";
                case 5, 6, 7, 8 -> "Mùa hè, nóng và mưa";
                default -> "Mùa thu, mát dần";
            };
            case TRUNG -> switch (month) {
                case 1, 2, 3, 4 -> "Mùa khô, ấm dần";
                case 5, 6, 7, 8 -> "Mùa nắng nóng";
                default -> "Mùa mưa bão";
            };
            case NAM -> switch (month) {
                case 12, 1, 2, 3 -> "Mùa khô";
                case 4, 5 -> "Cao điểm nóng";
                default -> "Mùa mưa";
            };
        };
    }

    static Map<Region, String> seasons(int month) {
        Map<Region, String> out = new java.util.EnumMap<>(Region.class);
        for (Region r : Region.values()) {
            out.put(r, season(r, month));
        }
        return out;
    }

    /** Các dịp trong {@code withinDays} ngày tới (kể cả hôm nay), gần nhất trước. */
    static List<AdvisorSnapshot.Occasion> upcoming(LocalDate today, int withinDays) {
        List<AdvisorSnapshot.Occasion> out = new ArrayList<>();
        for (int year = today.getYear(); year <= today.getYear() + 1; year++) {
            add(out, today, withinDays, "Ngày đôi 2.2 trên sàn", LocalDate.of(year, 2, 2));
            add(out, today, withinDays, "Valentine 14.2", LocalDate.of(year, 2, 14));
            add(out, today, withinDays, "Quốc tế Phụ nữ 8.3", LocalDate.of(year, 3, 8));
            add(out, today, withinDays, "Ngày đôi 6.6 trên sàn", LocalDate.of(year, 6, 6));
            add(out, today, withinDays, "Ngày đôi 7.7 trên sàn", LocalDate.of(year, 7, 7));
            add(out, today, withinDays, "Ngày đôi 8.8 trên sàn", LocalDate.of(year, 8, 8));
            add(out, today, withinDays, "Ngày đôi 9.9 trên sàn", LocalDate.of(year, 9, 9));
            add(out, today, withinDays, "Ngày đôi 10.10 trên sàn", LocalDate.of(year, 10, 10));
            add(out, today, withinDays, "Phụ nữ Việt Nam 20.10", LocalDate.of(year, 10, 20));
            add(out, today, withinDays, "Ngày đôi 11.11 trên sàn", LocalDate.of(year, 11, 11));
            add(out, today, withinDays, "Nhà giáo Việt Nam 20.11", LocalDate.of(year, 11, 20));
            LocalDate blackFriday = LocalDate.of(year, Month.NOVEMBER, 1)
                    .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)).plusDays(1);
            add(out, today, withinDays, "Black Friday", blackFriday);
            add(out, today, withinDays, "Ngày đôi 12.12 trên sàn", LocalDate.of(year, 12, 12));
            add(out, today, withinDays, "Giáng sinh", LocalDate.of(year, 12, 24));
            add(out, today, withinDays, "Tết dương lịch", LocalDate.of(year, 1, 1));
        }
        for (LocalDate tet : TET) {
            add(out, today, withinDays, "Tết Nguyên đán (mùng 1)", tet);
        }
        out.sort(Comparator.comparingInt(AdvisorSnapshot.Occasion::inDays).thenComparing(AdvisorSnapshot.Occasion::name));
        return out;
    }

    private static void add(List<AdvisorSnapshot.Occasion> out, LocalDate today, int withinDays, String name, LocalDate date) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, date);
        if (days >= 0 && days <= withinDays) {
            out.add(new AdvisorSnapshot.Occasion(name, (int) days));
        }
    }
}
