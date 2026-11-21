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

    /**
     * Một ô chữ: khóa lưu, nhãn hiện cho mẹ, độ dài tối đa, số dòng ô nhập, chữ mặc định.
     * {@code optional}: được để trống (tên cửa hàng, số điện thoại, địa chỉ ngắn trống = dùng giá trị cấu hình).
     */
    public record Field(String key, String label, String hint, int max, int rows, String def, boolean optional) {
        public String name() {
            return key.substring(PREFIX.length());
        }
    }

    static final String PREFIX = "print.";

    /** Mười dòng chữ sửa được của giấy A5 (thiết kế Penpot đã duyệt). */
    public static final java.util.List<Field> FIELDS = java.util.List.of(
            new Field(PREFIX + "shop", "Tên cửa hàng", "Dòng nhỏ trên cùng. Để trống thì dùng tên đã cài sẵn", 80, 1, "", true),
            new Field(PREFIX + "title", "Tiêu đề", "Dòng chữ to nhất", 80, 1,
                    "Cảm ơn anh/chị đã mua hàng!", false),
            new Field(PREFIX + "intro", "Lời giới thiệu nhóm",
                    "Vài câu. {cua_hang} sẽ thành tên cửa hàng. Nói rõ trong nhóm có chủ cửa hàng và trợ lý AI", 300, 4,
                    "{cua_hang} có một nhóm Zalo nhỏ, khoảng 5 khách mua hàng, để anh/chị hỏi cách dùng. "
                            + "Trong nhóm có chủ cửa hàng và một trợ lý AI của cửa hàng.", false),
            new Field(PREFIX + "step1", "Bước 1", "Chữ ngay trên mã QR", 100, 1,
                    "1. Mở Zalo, quét mã vuông bên cạnh.", false),
            new Field(PREFIX + "step2", "Bước 2", "Sau khi quét", 100, 1,
                    "2. Bấm nút «Vào nhóm Zalo».", false),
            new Field(PREFIX + "nocode", "Dòng không cần mã", "Trấn an khách", 100, 1,
                    "Không cần nhập mã xác nhận hay số điện thoại.", false),
            new Field(PREFIX + "link", "Địa chỉ ngắn (mã QR sinh từ đây)",
                    "Cho người không quét được, ví dụ cuahang.vn/n. Để trống thì dùng địa chỉ web đã cài sẵn", 100, 1, "", true),
            new Field(PREFIX + "voluntary", "Dòng tự nguyện", "Nói rõ không bắt buộc", 200, 2,
                    "Vào nhóm là tự nguyện. Không vào cũng không sao, anh/chị vẫn gọi điện cho cửa hàng như bình thường.", false),
            new Field(PREFIX + "phone", "Số điện thoại cửa hàng", "Để trống thì dùng số đã cài sẵn", 30, 1, "", true),
            new Field(PREFIX + "stop", "Dòng ngừng nhận tin", "Cách khách xin ngừng nhận tin", 200, 2,
                    "Không muốn nhận tin nữa? Gọi hoặc nhắn Zalo vào số trên, cửa hàng sẽ ghi lại và không liên hệ nữa.", false));

    private static final java.util.regex.Pattern SHORT_LINK =
            java.util.regex.Pattern.compile("^[A-Za-z0-9.-]+(:\\d{2,5})?(/[A-Za-z0-9._~\\-/]*)?$");

    private final AppSettings settings;
    private final String defaultShop;
    private final String defaultPhone;
    private final String baseUrl;

    public PrintTexts(AppSettings settings,
                      @org.springframework.beans.factory.annotation.Value("${app.shop-name:Cửa hàng}") String defaultShop,
                      @org.springframework.beans.factory.annotation.Value("${app.shop-phone:}") String defaultPhone,
                      @org.springframework.beans.factory.annotation.Value("${app.public-base-url:http://localhost:8080}") String baseUrl) {
        this.settings = settings;
        this.defaultShop = defaultShop;
        this.defaultPhone = defaultPhone;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Địa chỉ đầy đủ đặt trong mã QR: địa chỉ ngắn do mẹ sửa nếu có, không thì địa chỉ web đã cài sẵn cộng đường chung. */
    public String entryUrl() {
        String custom = settings.get(PREFIX + "link").map(String::strip).orElse("");
        if (!custom.isEmpty()) {
            return "https://" + custom;
        }
        return baseUrl + "/n";
    }

    /** Bản ngắn để in và gõ tay: bỏ "https://". */
    public String shortLink() {
        return entryUrl().replaceFirst("^https?://", "");
    }

    /** Chữ đang dùng (đã có mặc định), chưa thay {cua_hang}. Tên cửa hàng, số điện thoại, địa chỉ ngắn trống thì lấy từ cấu hình. */
    public Map<String, String> current() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            m.put(f.name(), settings.get(f.key()).filter(v -> !v.isBlank()).orElse(f.def()));
        }
        return m;
    }

    /** Chữ để hiện trong ô sửa: ô tùy chọn để trống thì hiện giá trị đang được dùng ở dạng gợi ý (placeholder), không điền sẵn. */
    public Map<String, String> effective() {
        Map<String, String> m = current();
        if (m.get("shop").isBlank()) {
            m.put("shop", defaultShop);
        }
        if (m.get("phone").isBlank()) {
            m.put("phone", defaultPhone);
        }
        m.put("link", shortLink());
        return m;
    }

    /** Chữ để in: đã thay {cua_hang} bằng tên cửa hàng. */
    public Map<String, String> rendered(String shopName) {
        Map<String, String> eff = effective();
        String own = current().get("shop");
        String shop = own.isBlank() ? shopName : own;
        eff.put("shop", shop);
        Map<String, String> m = new LinkedHashMap<>();
        eff.forEach((k, v) -> m.put(k, v.replace("{cua_hang}", shop)));
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
            if ("link".equals(f.name())) {
                v = v.replaceFirst("^(?i)https?://", "").replaceAll("/+$", "");
            }
            if (v.isEmpty() && !f.optional()) {
                throw new ImportException("Ô \"" + f.label() + "\" không được để trống. Muốn dùng chữ có sẵn, bấm \"Dùng lại chữ mặc định\".");
            }
            if (v.length() > f.max()) {
                throw new ImportException("Ô \"" + f.label() + "\" quá dài (tối đa " + f.max() + " ký tự).");
            }
            if ("link".equals(f.name()) && !v.isEmpty() && !SHORT_LINK.matcher(v).matches()) {
                throw new ImportException("Địa chỉ ngắn chỉ gồm chữ, số, dấu chấm, gạch và dấu / (ví dụ cuahang.vn/n), "
                        + "không có dấu cách hay tiếng Việt có dấu.");
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
