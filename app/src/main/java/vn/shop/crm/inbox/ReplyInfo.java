package vn.shop.crm.inbox;

import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Bảng thông tin cửa hàng": NGUỒN DUY NHẤT mà trợ lý AI được phép dùng để trả lời khách (D20). Chủ shop sửa trên trang quản trị; AI không được
 * nói điều gì ngoài bảng này. Số, phần trăm và các từ như "bảo hành" chỉ được xuất hiện trong câu trả lời khi bảng có chúng (xem {@link ReplyValidator}).
 */
@Service
public class ReplyInfo {

    static final String PREFIX = "reply.info.";
    static final int MAX_FIELD = 600;

    /** Một ô của bảng: khóa, nhãn, gợi ý và giá trị mặc định (chủ shop chốt 23/11/2026). */
    public record Field(String key, String label, String hint, String defaultValue) {
    }

    public record Row(Field field, String value) {
    }

    static final List<Field> FIELDS = List.of(
            new Field("products", "Cửa hàng bán gì", "Nói đúng loại hàng.",
                    "Cửa hàng bán chủ yếu áo điều hòa và phụ kiện áo điều hòa (trong đó có pin)."),
            new Field("group", "Nhóm Zalo khách hàng là gì", "Nhóm theo khu vực mà khách được mời vào.",
                    "Nhóm Zalo khách hàng theo khu vực (Miền Bắc, Miền Trung, Miền Nam). Trong nhóm khách được hướng dẫn sử dụng sản phẩm và nhận ưu đãi."),
            new Field("offer", "Ưu đãi khi vào nhóm", "Ghi đúng con số. AI chỉ được nói những con số có trong bảng này.",
                    "Khách mua trực tiếp qua nhóm Zalo được giảm 10% so với giá bán trên TikTok và được miễn phí ship."),
            new Field("why", "Vì sao mua qua Zalo lợi hơn", "Lý do kinh doanh để AI giải thích khi khách hỏi. Đừng ghi chính sách đổi trả của nền tảng khác.",
                    "Trên TikTok cửa hàng phải trả phí sàn, nên khi khách mua trực tiếp qua Zalo, cửa hàng nhường lại 10% cho khách. "
                            + "Mua trên TikTok cũng khó thao tác hơn. Liên hệ bảo hành trực tiếp qua Zalo cũng thuận tiện hơn."),
            new Field("join", "Cách vào nhóm", "Khách phải làm gì để được vào nhóm.",
                    "Khách đồng ý kết bạn Zalo với tài khoản của cửa hàng, cửa hàng sẽ thêm khách vào nhóm theo khu vực. "
                            + "Không muốn thì khách cứ bỏ qua, cửa hàng sẽ không nhắn lại."),
            new Field("warranty", "Bảo hành (để trống nếu chưa muốn AI nói)", "Để trống thì AI không nói gì cụ thể về bảo hành, khách hỏi chi tiết sẽ chuyển người thật.", ""),
            new Field("extra", "Ghi chú thêm (không bắt buộc)", "Thông tin khác AI được phép nói. Không ghi giá hay số điện thoại nếu không muốn AI nhắc lại.", ""));

    private final AppSettings settings;

    public ReplyInfo(AppSettings settings) {
        this.settings = settings;
    }

    public List<Field> fields() {
        return FIELDS;
    }

    public String value(String key) {
        return settings.get(PREFIX + key).orElse(FIELDS.stream().filter(f -> f.key().equals(key)).findFirst().map(Field::defaultValue).orElse(""));
    }

    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Field f : FIELDS) {
            rows.add(new Row(f, value(f.key())));
        }
        return rows;
    }

    /** Lưu cả bảng. Khóa lạ bị bỏ qua; mỗi ô tối đa {@value #MAX_FIELD} ký tự. */
    public void save(Map<String, String> values, String by) {
        for (Field f : FIELDS) {
            String v = values.get(f.key());
            if (v == null) {
                continue;
            }
            String clean = v.replaceAll("\\s+", " ").strip();
            if (clean.length() > MAX_FIELD) {
                throw new ImportException("Ô \"" + f.label() + "\" dài quá " + MAX_FIELD + " ký tự.");
            }
            settings.set(PREFIX + f.key(), clean, by);
        }
    }

    /** Các dòng không rỗng, dùng làm tài liệu duy nhất cho AI. */
    public String block() {
        StringBuilder sb = new StringBuilder();
        for (Field f : FIELDS) {
            String v = value(f.key());
            if (!v.isBlank()) {
                sb.append("- ").append(f.label().replaceAll(" \\(.*\\)$", "")).append(": ").append(v).append('\n');
            }
        }
        return sb.toString();
    }

    /** Toàn bộ chữ trong bảng, để bộ kiểm tra biết con số và từ nào được phép nhắc lại. */
    public String allowedText() {
        StringBuilder sb = new StringBuilder();
        for (Field f : FIELDS) {
            sb.append(value(f.key())).append(' ');
        }
        return sb.toString();
    }
}
