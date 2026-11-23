package vn.shop.crm.invite;

import vn.shop.crm.bot.PiiMasker;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiểm CỨNG nội dung tin nhắc (bước 2 và 3 của chiến dịch nhiều lần) và mẫu Tin 1 khi chiến dịch gửi nhiều lần. Không dùng AI để kiểm AI.
 * Trả về lý do tiếng Việt cho người dùng, hoặc rỗng nếu qua. Lý do chặn: tin nhắc không được tạo cảm giác ép, không link, không số điện thoại,
 * không hứa hẹn, không chỗ trống lạ, không trùng tin trước.
 */
public final class FollowupValidator {

    private FollowupValidator() {
    }

    public static final int MAX_CHARS = 500;
    private static final Pattern LINK = Pattern.compile(
            "(https?:|www\\.|zalo\\.me|\\b[a-z0-9-]+\\.(com|vn|net|me|org|info|app|link|ly|io)\\b|@[a-z0-9_.]{3,})");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final List<String> ALLOWED_PLACEHOLDERS = List.of("ten", "cua_hang", "mien");

    /** Chữ tạo cảm giác ép hoặc hứa hẹn (đã bỏ dấu, chữ thường), cấm trong tin nhắc. */
    private static final List<Pattern> PRESSURE = java.util.stream.Stream.of(
            "cuoi cung", "chi con", "han chot", "het han", "ngay hom nay", "ngay bay gio", "sap het", "nhanh tay", "bo lo",
            "co hoi cuoi", "duy nhat hom nay", "gio chot", "so luong co han", "khong thi mat", "se mat", "mat quyen loi",
            "cam ket", "dam bao", "chac chan").map(FollowupValidator::word).toList();

    /** Câu nói "không nhắn lại" sai khi còn tin nhắc (đã bỏ dấu). */
    private static final Pattern NO_MORE = Pattern.compile("khong (se )?nhan (tin )?lai|se khong nhan lai|khong lam phien nua|khong nhan them");

    private static Pattern word(String w) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(w) + "(?![a-z0-9])");
    }

    /** Bỏ dấu, chữ thường, đ thành d, gộp khoảng trắng. */
    public static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(java.util.Locale.ROOT).replace('đ', 'd').replaceAll("\\s+", " ").strip();
    }

    /** Tin nhắc: rỗng nghĩa là qua. {@code previous} là nội dung của lần gửi liền trước (bước 1 hoặc bước trước đó). */
    public static Optional<String> checkFollowup(String template, String previous) {
        if (template == null || template.isBlank()) {
            return Optional.of("Nội dung tin nhắc không được để trống.");
        }
        if (template.strip().length() > MAX_CHARS) {
            return Optional.of("Nội dung tin nhắc tối đa " + MAX_CHARS + " ký tự.");
        }
        String f = fold(template);
        if (LINK.matcher(f).find()) {
            return Optional.of("Tin nhắc không được có đường dẫn hay tên miền.");
        }
        if (PiiMasker.containsPii(template)) {
            return Optional.of("Tin nhắc không được có số điện thoại hay email.");
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (!ALLOWED_PLACEHOLDERS.contains(m.group(1))) {
                return Optional.of("Tin nhắc chỉ được dùng {ten}, {cua_hang}, {mien}; không dùng {" + m.group(1) + "}.");
            }
        }
        // "gấp" (vội) và "gặp" (gặp mặt) giống nhau sau khi bỏ dấu, nên "gấp" được kiểm riêng trên chữ có dấu.
        boolean urgent = template.toLowerCase(java.util.Locale.ROOT).contains("gấp");
        for (Pattern p : PRESSURE) {
            if (urgent || p.matcher(f).find()) {
                return Optional.of("Tin nhắc không được tạo cảm giác ép hay hứa hẹn (ví dụ \"cuối cùng\", \"chỉ còn\", \"gấp\", \"cam kết\"). Hãy viết nhẹ nhàng hơn.");
            }
        }
        if (NO_MORE.matcher(f).find()) {
            return Optional.of("Tin nhắc không được nói \"sẽ không nhắn lại\" vì còn có thể còn lần gửi sau.");
        }
        if (previous != null && fold(previous).equals(f)) {
            return Optional.of("Tin nhắc phải khác nội dung tin gửi liền trước.");
        }
        return Optional.empty();
    }

    /** Tin 1 của chiến dịch gửi nhiều lần không được còn câu "sẽ không nhắn lại". */
    public static Optional<String> checkFirstForMultiple(String template) {
        if (NO_MORE.matcher(fold(template)).find()) {
            return Optional.of("Chiến dịch gửi nhiều lần thì Tin 1 không được còn câu \"em sẽ không nhắn lại ạ\" (sẽ sai khi có tin nhắc). "
                    + "Hãy bỏ câu đó, hoặc bấm \"Dùng mẫu Tin 1 cho nhiều lần\".");
        }
        return Optional.empty();
    }
}
