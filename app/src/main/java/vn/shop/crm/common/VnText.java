package vn.shop.crm.common;

import java.text.Normalizer;
import java.util.Locale;

/**
 * So khớp tiếng Việt không phân biệt dấu và chữ hoa/thường ("nguyen" khớp "Nguyễn"), dùng cho ô lọc/tìm kiếm.
 * Cùng một phép chuẩn hóa chạy ở hai phía: {@link #fold(String)} cho từ khóa, {@link #sqlFold(String)} cho cột trong SQL
 * (không cần tiện ích mở rộng nào của PostgreSQL).
 */
public final class VnText {

    private static final String FROM;
    private static final String TO;

    static {
        StringBuilder from = new StringBuilder();
        StringBuilder to = new StringBuilder();
        // Chỉ các ký tự tiếng Việt cần dùng: Latin-1 (à, é, ô...), ă, ĩ, ũ, ơ, ư và khối chữ có dấu thanh (ạ, ế, ợ...).
        for (char c = 0x00C0; c <= 0x1EF9; c++) {
            boolean vietnamese = c <= 0x00FF || c == 0x0102 || c == 0x0103 || c == 0x0128 || c == 0x0129
                    || c == 0x0168 || c == 0x0169 || c == 0x01A0 || c == 0x01A1 || c == 0x01AF || c == 0x01B0
                    || c >= 0x1EA0;
            if (!vietnamese || !Character.isLetter(c)) {
                continue;
            }
            String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
            if (d.length() == 1 && d.charAt(0) < 128) {
                from.append(c);
                to.append(Character.toLowerCase(d.charAt(0)));
            }
        }
        from.append('đ').append('Đ');
        to.append('d').append('d');
        FROM = from.toString();
        TO = to.toString();
    }

    private VnText() {
    }

    /** "Nguyễn Văn Đức" -> "nguyen van duc". */
    public static String fold(String text) {
        if (text == null) {
            return "";
        }
        String d = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder out = new StringBuilder(d.length());
        for (char c : d.toCharArray()) {
            int i = FROM.indexOf(c);
            out.append(i >= 0 ? TO.charAt(i) : Character.toLowerCase(c));
        }
        return Normalizer.normalize(out.toString().toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    /** Biểu thức SQL cho cột đã bỏ dấu, chữ thường (null giữ nguyên null). Chỉ truyền tên cột cố định, không truyền chuỗi người dùng. */
    public static String sqlFold(String column) {
        return "lower(translate(normalize(" + column + ", NFC), '" + FROM + "', '" + TO + "'))";
    }

    /** Thoát ký tự đặc biệt của LIKE (dùng với {@code ESCAPE '\'}). */
    public static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
