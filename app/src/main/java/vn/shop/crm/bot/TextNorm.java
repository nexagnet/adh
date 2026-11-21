package vn.shop.crm.bot;

import java.text.Normalizer;
import java.util.Locale;

/** Chuẩn hóa tiếng Việt để so khớp từ khóa: bỏ dấu, chữ thường, bỏ ký tự lạ. */
final class TextNorm {

    private TextNorm() {
    }

    /** "Hàng bị LỖI!" -> " hang bi loi " (có khoảng trắng hai đầu để khớp theo từ). */
    static String words(String text) {
        if (text == null) {
            return "  ";
        }
        String d = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd');
        return " " + d.replaceAll("[^a-z0-9]+", " ").strip() + " ";
    }

    static boolean containsPhrase(String normalized, String phrase) {
        String p = words(phrase).strip();
        return !p.isEmpty() && normalized.contains(" " + p + " ");
    }
}
