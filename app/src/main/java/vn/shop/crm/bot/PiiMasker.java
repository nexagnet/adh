package vn.shop.crm.bot;

import java.util.regex.Pattern;

/**
 * Che thông tin cá nhân trước khi ghi nhận hoặc đưa cho bất kỳ dịch vụ AI nào: SĐT, email, chuỗi số dài.
 * Không che được địa chỉ chữ (không có cách chắc chắn); vì vậy chỉ giữ trích đoạn ngắn và bot không bao giờ hỏi địa chỉ.
 */
public final class PiiMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?84|0)[\\s.\\-]?\\d(?:[\\s.\\-]?\\d){7,9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+\\-]+@[\\w\\-]+(?:\\.[\\w\\-]+)+");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d(?:[\\s\\-]?\\d){7,}");

    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null) {
            return "";
        }
        String s = EMAIL.matcher(text).replaceAll("[email]");
        s = PHONE.matcher(s).replaceAll("[số điện thoại]");
        return LONG_DIGITS.matcher(s).replaceAll("[số]");
    }

    /** Tin có chứa SĐT/email/số dài không: bot nhắc khách đừng chia sẻ thông tin riêng trong nhóm. */
    public static boolean containsPii(String text) {
        return text != null && !mask(text).equals(text);
    }

    public static String excerpt(String text, int max) {
        String masked = mask(text).replaceAll("\\s+", " ").strip();
        return masked.length() <= max ? masked : masked.substring(0, max - 1) + "…";
    }
}
