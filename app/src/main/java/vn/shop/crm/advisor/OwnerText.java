package vn.shop.crm.advisor;

import vn.shop.crm.bot.PiiMasker;
import vn.shop.crm.common.ImportException;

import java.util.regex.Pattern;

/**
 * Kiểm chữ do CHỦ SHOP tự gõ để đưa cho AI đề xuất chiến dịch (ghi chú, hồ sơ cửa hàng). Từ chối (không tự che) chữ có số điện thoại, email, chuỗi số dài,
 * đường dẫn hay dấu {@code <<<} {@code >>>} (dấu ngăn của khối số liệu), để dữ liệu cá nhân không có đường lọt vào lời nhắn gửi AI.
 * Đây là chữ của người dùng, nên với AI nó vẫn chỉ là DỮ LIỆU: không đổi được giới hạn an toàn (xem {@link ProposalValidator}).
 */
final class OwnerText {

    private static final Pattern LINK = Pattern.compile("(?i)(https?:|www\\.|\\b[a-z0-9-]+\\.(com|vn|net|org|me|io)\\b)");

    private OwnerText() {
    }

    /** Chữ đã gọn khoảng trắng; ném {@link ImportException} (tiếng Việt) nếu trống, ngắn hơn {@code min}, dài hơn {@code max} hoặc có dữ liệu không được phép. */
    static String clean(String raw, int min, int max, String label) {
        String t = raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
        if (t.length() < min) {
            throw new ImportException(label + " quá ngắn: viết ít nhất " + min + " ký tự.");
        }
        if (t.length() > max) {
            throw new ImportException(label + " quá dài: tối đa " + max + " ký tự (đang " + t.length() + ").");
        }
        if (PiiMasker.containsPii(t)) {
            throw new ImportException(label + " không được chứa số điện thoại, email hay dãy số dài. Hãy bỏ chúng đi.");
        }
        if (LINK.matcher(t).find()) {
            throw new ImportException(label + " không được chứa đường dẫn hay tên trang web. Hãy bỏ chúng đi.");
        }
        if (t.contains("<<<") || t.contains(">>>")) {
            throw new ImportException(label + " không được chứa dấu <<< hoặc >>>.");
        }
        return t;
    }
}
