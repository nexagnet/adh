package vn.shop.crm.invite;

import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chữ trên giấy giới thiệu, sửa được ở trang "In giấy giới thiệu". Lưu trong cài đặt (khóa/giá trị), có chữ mặc định.
 * Chữ chỉ là văn bản thuần, hiển thị đã được mã hóa HTML; {@code {cua_hang}} được thay bằng tên cửa hàng.
 * Nội dung phải là lời mời kết nối sau mua hàng, không quảng cáo sản phẩm (BR-16).
 */
@Service
public class PrintTexts {

    /** Một ô chữ: khóa lưu, nhãn hiện cho mẹ, độ dài tối đa, số dòng ô nhập, chữ mặc định. */
    public record Field(String key, String label, String hint, int max, int rows, String def) {
        public String name() {
            return key.substring(PREFIX.length());
        }
    }

    static final String PREFIX = "print.";

    public static final java.util.List<Field> FIELDS = java.util.List.of(
            new Field(PREFIX + "title", "Tiêu đề", "Dòng chữ to nhất", 80, 1,
                    "Cảm ơn bạn đã mua hàng!"),
            new Field(PREFIX + "intro", "Nội dung chính", "Vài câu giới thiệu nhóm. {cua_hang} sẽ thành tên cửa hàng", 300, 4,
                    "{cua_hang} có một nhóm Zalo nhỏ để hướng dẫn sử dụng và giải đáp thắc mắc cho khách đã mua hàng."),
            new Field(PREFIX + "scan", "Dòng hướng dẫn quét", "Chữ ngay trên mã QR", 100, 1,
                    "Mở Zalo hoặc camera điện thoại, quét mã này"),
            new Field(PREFIX + "type", "Dòng hướng dẫn gõ link", "Chữ ngay trên link ngắn, cho người không quét được", 100, 1,
                    "Không quét được? Gõ vào trình duyệt:"),
            new Field(PREFIX + "note", "Lời nhắn", "Ví dụ nói rõ không bắt buộc", 200, 2,
                    "Bạn không bắt buộc tham gia. Nhóm chỉ có vài khách, không bán hàng ồn ào."),
            new Field(PREFIX + "footer", "Chữ cuối tờ", "Ví dụ lời chúc hoặc giờ làm việc", 150, 2,
                    "Chúc bạn dùng sản phẩm vui vẻ!"));

    private final AppSettings settings;

    public PrintTexts(AppSettings settings) {
        this.settings = settings;
    }

    /** Chữ đang dùng (đã có mặc định), chưa thay {cua_hang}. */
    public Map<String, String> current() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            m.put(f.name(), settings.get(f.key()).orElse(f.def()));
        }
        return m;
    }

    /** Chữ để in: đã thay {cua_hang} bằng tên cửa hàng. */
    public Map<String, String> rendered(String shopName) {
        Map<String, String> m = new LinkedHashMap<>();
        current().forEach((k, v) -> m.put(k, v.replace("{cua_hang}", shopName)));
        return m;
    }

    public void save(Map<String, String> values, String by) {
        // Kiểm tra hết trước rồi mới ghi, để không lưu dở dang.
        Map<String, String> clean = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            String v = values.get(f.name());
            if (v == null) {
                continue;
            }
            v = v.replace("\r", "").replaceAll("[\\p{Cntrl}&&[^\n]]", "").strip();
            if (v.isEmpty()) {
                throw new ImportException("Ô \"" + f.label() + "\" không được để trống. Muốn dùng chữ có sẵn, bấm \"Dùng lại chữ mặc định\".");
            }
            if (v.length() > f.max()) {
                throw new ImportException("Ô \"" + f.label() + "\" quá dài (tối đa " + f.max() + " ký tự).");
            }
            clean.put(f.key(), v);
        }
        clean.forEach((k, v) -> settings.set(k, v, by));
    }

    public void reset(String by) {
        for (Field f : FIELDS) {
            settings.set(f.key(), f.def(), by);
        }
    }
}
