package vn.shop.crm.inbox;

import vn.shop.crm.bot.PiiMasker;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ kiểm tra đầu ra CỨNG cho câu trả lời do AI soạn (D20). Không dùng AI để kiểm AI. Câu không qua thì KHÔNG bao giờ được gửi, việc chuyển người thật.
 *
 * <p>Quy tắc: không rỗng, không quá dài; không có đường dẫn, số điện thoại, email hay chuỗi số dài; MỌI con số phải có trong bảng thông tin cửa hàng
 * (nên không bịa giá, phần trăm, thời hạn); không nhắc nền tảng khác ngoài TikTok; không hứa hẹn hay cam kết; không nói về đổi trả, hoàn tiền
 * (chính sách của nền tảng); không nói về tồn kho, giá cụ thể, bảo hành nếu bảng không có chữ đó; không chê ai; không giả làm người hay chủ shop;
 * không xin địa chỉ, số điện thoại, mật khẩu, chuyển khoản.
 */
public final class ReplyValidator {

    private ReplyValidator() {
    }

    static final int MAX_CHARS = 500;
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern LINK = Pattern.compile(
            "(https?:|www\\.|zalo\\.me|\\b[a-z0-9-]+\\.(com|vn|net|me|org|info|app|link|ly|io)\\b|@[a-z0-9_.]{3,})");

    /** Luôn cấm, bất kể bảng thông tin có nhắc hay không (đã bỏ dấu, chữ thường). */
    private static final List<Pattern> ALWAYS = words(
            "doi tra", "hoan tien", "hoan tra", "tra hang", "cam ket", "dam bao", "chac chan", "tang", "qua tang", "voucher", "ma giam",
            "shopee", "lazada", "tiki", "facebook", "lua dao", "kem chat luong", "hang gia", "hang nhai",
            "em la nguoi", "em la chu shop", "em la me", "em la nhan vien", "toi la nguoi",
            "dia chi", "so dien thoai", "sdt", "mat khau", "otp", "chuyen khoan", "so tai khoan", "ngan hang", "cccd", "cmnd");

    /** Chỉ được nhắc khi bảng thông tin cửa hàng cũng có chữ đó. */
    private static final List<String> ONLY_IF_IN_INFO = List.of(
            "bao hanh", "con hang", "het hang", "ton kho", "trieu", "nghin", "vnd", "gia ban", "chiet khau", "khuyen mai", "tiktok");

    private static List<Pattern> words(String... ws) {
        return java.util.Arrays.stream(ws).map(ReplyValidator::word).toList();
    }

    private static Pattern word(String w) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(w) + "(?![a-z0-9])");
    }

    /** Bỏ dấu, chữ thường, đ thành d: để khớp từ khóa dù AI viết có dấu hay không. */
    static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(java.util.Locale.ROOT).replace('đ', 'd').replaceAll("\\s+", " ");
    }

    private static Set<String> numbers(String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            out.add(m.group().replace(',', '.'));
        }
        return out;
    }

    /** Rỗng nghĩa là qua. Có giá trị là mã lý do chặn (không chứa nội dung khách). */
    public static Optional<String> check(String reply, String infoText) {
        return check(reply, infoText, "");
    }

    /**
     * Như trên; {@code profileText} là hồ sơ của khách đang nhắn: chỉ để cho phép AI nhắc lại CON SỐ có trong hồ sơ (ví dụ ngày, số lần mua). Hồ sơ
     * không mở thêm từ nào khác: các chữ như "bảo hành", "TikTok" vẫn chỉ được nhắc khi bảng thông tin cửa hàng có.
     */
    public static Optional<String> check(String reply, String infoText, String profileText) {
        if (reply == null || reply.isBlank()) {
            return Optional.of("RONG");
        }
        if (reply.length() > MAX_CHARS) {
            return Optional.of("QUA_DAI");
        }
        String folded = fold(reply);
        if (LINK.matcher(folded).find()) {
            return Optional.of("CO_LINK");
        }
        if (PiiMasker.containsPii(reply)) {
            return Optional.of("CO_SO_DIEN_THOAI_HOAC_EMAIL");
        }
        String info = fold(infoText);
        Set<String> allowedNumbers = numbers(info);
        allowedNumbers.addAll(numbers(fold(profileText)));
        if (!allowedNumbers.containsAll(numbers(folded))) {
            return Optional.of("CON_SO_NGOAI_BANG");
        }
        for (Pattern w : ALWAYS) {
            if (w.matcher(folded).find()) {
                return Optional.of("TU_CAM");
            }
        }
        for (String w : ONLY_IF_IN_INFO) {
            if (word(w).matcher(folded).find() && !word(w).matcher(info).find()) {
                return Optional.of("NOI_NGOAI_BANG");
            }
        }
        return Optional.empty();
    }
}
