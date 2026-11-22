package vn.shop.crm.shipping;

import java.text.Normalizer;
import java.util.Locale;

/** Chuẩn hóa chuỗi (trạng thái, tiêu đề cột) về dạng so khớp: bỏ dấu, chữ thường, gộp khoảng trắng. */
public final class StatusKey {

    private StatusKey() {
    }

    public static String of(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('ð', 'd').replace('Ð', 'D')
                .replace('Đ', 'D');
        return decomposed.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }
}
