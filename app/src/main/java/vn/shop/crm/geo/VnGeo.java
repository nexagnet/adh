package vn.shop.crm.geo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Danh sách 63 tỉnh/thành và cách đoán tỉnh từ một địa chỉ viết tay trên vận đơn.
 *
 * <p>Nguyên tắc đoán: chỉ nhận khi gặp tên tỉnh (hoặc cách viết tắt thông dụng) trong địa chỉ; lấy tên xuất hiện
 * CUỐI CÙNG vì tỉnh/thành đứng cuối địa chỉ; bỏ qua tên đứng ngay sau "xã/phường/quận/huyện/đường/phố..." (ví dụ
 * "phường Hòa Bình", "đường Hồ Chí Minh") vì đó là tên xã, phố chứ không phải tỉnh. Không chắc thì trả rỗng ("chưa
 * rõ"), không đoán bừa. Sau khi sáp nhập tỉnh (1/7/2025) tên tỉnh mới đều trùng một tên tỉnh cũ nên vẫn nhận được,
 * nhưng khách cũ ở tỉnh bị sáp nhập có thể hiện tên tỉnh mới (thuộc cùng miền hoặc vùng kế bên).
 */
public final class VnGeo {

    /** Tăng số này khi sửa cách đoán để hệ thống tự tính lại cho khách cũ. */
    public static final int VERSION = 2;

    // mã | tên | vùng | vĩ độ | kinh độ | tên gọi khác (cách nhau bằng ; , dấu = đầu: chỉ khớp khi có dấu tiếng Việt)
    private static final String TABLE = """
            lai-chau|Lai Châu|TAY_BAC|22.40|103.45|
            dien-bien|Điện Biên|TAY_BAC|21.39|103.02|
            son-la|Sơn La|TAY_BAC|21.33|103.91|
            hoa-binh|Hòa Bình|TAY_BAC|20.81|105.34|
            ha-giang|Hà Giang|DONG_BAC|22.82|104.98|
            cao-bang|Cao Bằng|DONG_BAC|22.67|106.26|
            bac-kan|Bắc Kạn|DONG_BAC|22.15|105.83|
            lang-son|Lạng Sơn|DONG_BAC|21.85|106.76|
            tuyen-quang|Tuyên Quang|DONG_BAC|21.82|105.21|
            thai-nguyen|Thái Nguyên|DONG_BAC|21.59|105.85|
            phu-tho|Phú Thọ|DONG_BAC|21.32|105.40|
            bac-giang|Bắc Giang|DONG_BAC|21.27|106.19|
            quang-ninh|Quảng Ninh|DONG_BAC|21.01|107.30|
            lao-cai|Lào Cai|DONG_BAC|22.48|103.97|
            yen-bai|Yên Bái|DONG_BAC|21.72|104.91|
            ha-noi|Hà Nội|DB_SONG_HONG|21.03|105.85|hn;thu do ha noi
            hai-phong|Hải Phòng|DB_SONG_HONG|20.86|106.68|
            vinh-phuc|Vĩnh Phúc|DB_SONG_HONG|21.31|105.60|
            bac-ninh|Bắc Ninh|DB_SONG_HONG|21.19|106.07|
            hai-duong|Hải Dương|DB_SONG_HONG|20.94|106.33|
            hung-yen|Hưng Yên|DB_SONG_HONG|20.65|106.05|
            thai-binh|Thái Bình|DB_SONG_HONG|20.45|106.34|
            ha-nam|Hà Nam|DB_SONG_HONG|20.54|105.92|
            nam-dinh|Nam Định|DB_SONG_HONG|20.42|106.17|
            ninh-binh|Ninh Bình|DB_SONG_HONG|20.25|105.97|
            thanh-hoa|Thanh Hóa|BAC_TRUNG_BO|19.81|105.78|
            nghe-an|Nghệ An|BAC_TRUNG_BO|18.68|105.69|
            ha-tinh|Hà Tĩnh|BAC_TRUNG_BO|18.34|105.91|
            quang-binh|Quảng Bình|BAC_TRUNG_BO|17.47|106.62|
            quang-tri|Quảng Trị|BAC_TRUNG_BO|16.75|107.19|
            hue|Huế|BAC_TRUNG_BO|16.46|107.59|thua thien hue
            da-nang|Đà Nẵng|NAM_TRUNG_BO|16.05|108.20|
            quang-nam|Quảng Nam|NAM_TRUNG_BO|15.57|108.47|
            quang-ngai|Quảng Ngãi|NAM_TRUNG_BO|15.12|108.80|
            binh-dinh|Bình Định|NAM_TRUNG_BO|13.78|109.22|
            phu-yen|Phú Yên|NAM_TRUNG_BO|13.09|109.31|
            khanh-hoa|Khánh Hòa|NAM_TRUNG_BO|12.24|109.19|
            ninh-thuan|Ninh Thuận|NAM_TRUNG_BO|11.56|108.99|
            binh-thuan|Bình Thuận|NAM_TRUNG_BO|10.93|108.10|
            kon-tum|Kon Tum|TAY_NGUYEN|14.35|108.00|
            gia-lai|Gia Lai|TAY_NGUYEN|13.98|108.00|
            dak-lak|Đắk Lắk|TAY_NGUYEN|12.67|108.04|dac lac;daklak
            dak-nong|Đắk Nông|TAY_NGUYEN|12.00|107.69|dac nong;daknong
            lam-dong|Lâm Đồng|TAY_NGUYEN|11.94|108.44|
            binh-phuoc|Bình Phước|DONG_NAM_BO|11.75|106.72|
            tay-ninh|Tây Ninh|DONG_NAM_BO|11.31|106.10|
            binh-duong|Bình Dương|DONG_NAM_BO|10.97|106.65|
            dong-nai|Đồng Nai|DONG_NAM_BO|10.95|106.82|
            ba-ria-vung-tau|Bà Rịa - Vũng Tàu|DONG_NAM_BO|10.35|107.08|ba ria;vung tau;brvt;br vt
            tphcm|TP. Hồ Chí Minh|DONG_NAM_BO|10.78|106.70|ho chi minh;tp hcm;tphcm;hcm;hcmc;sai gon;saigon
            long-an|Long An|DB_SONG_CUU_LONG|10.54|106.41|
            tien-giang|Tiền Giang|DB_SONG_CUU_LONG|10.36|106.36|
            ben-tre|Bến Tre|DB_SONG_CUU_LONG|10.24|106.38|
            tra-vinh|Trà Vinh|DB_SONG_CUU_LONG|9.93|106.34|
            vinh-long|Vĩnh Long|DB_SONG_CUU_LONG|10.25|105.97|
            dong-thap|Đồng Tháp|DB_SONG_CUU_LONG|10.46|105.63|
            an-giang|An Giang|DB_SONG_CUU_LONG|10.38|105.43|
            kien-giang|Kiên Giang|DB_SONG_CUU_LONG|10.01|105.08|
            can-tho|Cần Thơ|DB_SONG_CUU_LONG|10.03|105.78|
            hau-giang|Hậu Giang|DB_SONG_CUU_LONG|9.78|105.47|
            soc-trang|Sóc Trăng|DB_SONG_CUU_LONG|9.60|105.97|
            bac-lieu|Bạc Liêu|DB_SONG_CUU_LONG|9.29|105.72|
            ca-mau|Cà Mau|DB_SONG_CUU_LONG|9.18|105.15|
            """;

    /** Từ đứng ngay trước tên cho biết đó là xã/phố/quận chứ không phải tỉnh. */
    private static final Set<String> UNIT_WORDS = Set.of("xa", "phuong", "quan", "huyen", "duong", "pho", "ap", "thon",
            "ngo", "hem", "to", "kp", "tdp", "tt");

    private record Alias(Province province, String phrase, boolean needMarks) {
    }

    private static final Map<String, Province> BY_CODE = new LinkedHashMap<>();
    private static final List<Alias> ALIASES = new ArrayList<>();

    /** Tên không dấu quá dễ trùng chữ khác (Huệ, Hué...): chỉ nhận khi viết có dấu. */
    private static final Set<String> NEEDS_MARKS = Set.of("hue");

    static {
        for (String line : TABLE.strip().split("\n")) {
            String[] f = line.split("\\|", -1);
            Province p = new Province(f[0], f[1], SubRegion.valueOf(f[2]), Double.parseDouble(f[3]),
                    Double.parseDouble(f[4]));
            BY_CODE.put(p.code(), p);
            if (NEEDS_MARKS.contains(p.code())) {
                ALIASES.add(new Alias(p, lowerKeepMarks(p.name()), true));
            } else {
                ALIASES.add(new Alias(p, plain(p.name()), false));
            }
            for (String a : f[5].split(";")) {
                if (!a.isBlank()) {
                    ALIASES.add(new Alias(p, plain(a), false));
                }
            }
        }
    }

    private VnGeo() {
    }

    public static List<Province> all() {
        return List.copyOf(BY_CODE.values());
    }

    public static Optional<Province> byCode(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code));
    }

    public static List<Province> in(GeoArea area) {
        return BY_CODE.values().stream().filter(area::contains).toList();
    }

    public static List<Province> in(Region region) {
        return BY_CODE.values().stream().filter(p -> p.region() == region).toList();
    }

    /** Đoán tỉnh từ địa chỉ. Rỗng nghĩa là "chưa rõ". */
    public static Optional<Province> detect(String address) {
        if (address == null || address.isBlank() || address.length() > 1000) {
            return Optional.empty();
        }
        String plainText = " " + plainKeepCommas(address) + " ";
        String marked = " " + lowerKeepMarksAndCommas(address) + " ";
        Province best = null;
        int bestEnd = -1;
        int bestLen = -1;
        for (Alias a : ALIASES) {
            String text = a.needMarks() ? marked : plainText;
            String needle = " " + a.phrase() + " ";
            int from = 0;
            while (true) {
                int at = text.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                from = at + 1;
                String before = a.needMarks() ? plainKeepCommas(text.substring(0, at)) : text.substring(0, at).strip();
                if (isUnitName(before)) {
                    continue;
                }
                int end = at + needle.length();
                if (end > bestEnd || (end == bestEnd && needle.length() > bestLen)) {
                    best = a.province();
                    bestEnd = end;
                    bestLen = needle.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Phần chữ ngay trước tên kết thúc bằng "xã/phường/quận/huyện/đường/phố...": tên đó là đơn vị nhỏ, không phải tỉnh.
     * Dấu phẩy được giữ lại thành một từ riêng ","; nếu đứng ngay trước tên thì tên là một cấp địa chỉ riêng (thường là
     * tỉnh), nên chữ cuối của cấp trước ("Điền Xá", "Sơn Dương", "Đan Phượng") không được tính là tiền tố "xã/đường/phường".
     */
    private static boolean isUnitName(String before) {
        if (before.isEmpty()) {
            return false;
        }
        if (before.endsWith("thanh pho")) {
            return false; // "thành phố Hòa Bình": thành phố cùng tên tỉnh
        }
        int i = before.lastIndexOf(' ');
        String prev = before.substring(i + 1);
        if (UNIT_WORDS.contains(prev)) {
            return true;
        }
        return before.endsWith("thi tran") || before.endsWith("thi xa") || before.endsWith("khu pho");
    }

    /** Như {@link #plain} nhưng dấu phẩy, chấm phẩy, xuống dòng thành từ "," riêng để biết ranh giới giữa các cấp địa chỉ. */
    static String plainKeepCommas(String s) {
        String lower = s.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String d = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return separators(d.replaceAll("[,;\\r\\n]+", ",").replaceAll("[^a-z0-9,]+", " "));
    }

    /** Như {@link #lowerKeepMarks} nhưng giữ ranh giới cấp địa chỉ như {@link #plainKeepCommas}. */
    static String lowerKeepMarksAndCommas(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        return separators(n.replaceAll("[,;\\r\\n]+", ",").replaceAll("[^\\p{L}\\p{N},]+", " "));
    }

    private static String separators(String s) {
        return s.replaceAll("\\s*,[\\s,]*", " , ").replaceAll(" +", " ").strip();
    }

    /** Chữ thường, bỏ dấu tiếng Việt, mọi ký tự không phải chữ/số thành một dấu cách. */
    static String plain(String s) {
        String lower = s.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String d = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.replaceAll("[^a-z0-9]+", " ").strip();
    }

    /** Chữ thường, giữ dấu tiếng Việt (chuẩn NFC), ký tự khác chữ/số thành dấu cách. */
    static String lowerKeepMarks(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        return n.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
