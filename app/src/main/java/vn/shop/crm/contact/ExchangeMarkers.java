package vn.shop.crm.contact;

import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cách nhận biết ĐƠN ĐỔI. File đơn của hãng không có trạng thái "đổi hàng" riêng, nên chủ cửa hàng khai dấu hiệu: chữ nằm trong mã đơn của shop,
 * trong tên hàng, hoặc trong trạng thái gốc của hãng. Chưa khai thì bộ lọc "đơn đổi" không dùng được (không đoán). Chỉ là chữ do chủ cửa hàng
 * nhập, không bao giờ ghép vào SQL (luôn là tham số).
 */
@Service
public class ExchangeMarkers {

    static final String KEY = "filter.exchange_markers";
    private static final int MAX_WORDS = 10;
    private static final int MAX_LEN = 40;
    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Các từ khóa đã viết thường; một đơn là đơn đổi khi MỘT trong các chỗ chứa MỘT trong các từ khóa tương ứng. */
    public record Markers(List<String> orderRef, List<String> goods, List<String> status) {
        public Markers {
            orderRef = orderRef == null ? List.of() : List.copyOf(orderRef);
            goods = goods == null ? List.of() : List.copyOf(goods);
            status = status == null ? List.of() : List.copyOf(status);
        }

        public boolean isEmpty() {
            return orderRef.isEmpty() && goods.isEmpty() && status.isEmpty();
        }
    }

    private final AppSettings settings;

    public ExchangeMarkers(AppSettings settings) {
        this.settings = settings;
    }

    public Markers get() {
        return settings.get(KEY).map(s -> {
            try {
                return JSON.readValue(s, Markers.class);
            } catch (RuntimeException e) {
                return new Markers(null, null, null);
            }
        }).orElse(new Markers(null, null, null));
    }

    /** Lưu từ ba ô chữ (mỗi từ khóa cách nhau bằng dấu phẩy hoặc xuống dòng); để trống cả ba là tắt. */
    public void save(String orderRef, String goods, String status, String by) {
        Markers m = new Markers(words(orderRef), words(goods), words(status));
        settings.set(KEY, JSON.writeValueAsString(m), by);
    }

    static List<String> words(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String w : raw.split("[,\\n;]")) {
            String t = w.strip().toLowerCase(Locale.ROOT);
            if (t.isEmpty() || out.contains(t)) {
                continue;
            }
            if (t.length() > MAX_LEN) {
                throw new ImportException("Mỗi từ khóa nhận biết đơn đổi tối đa " + MAX_LEN + " ký tự.");
            }
            out.add(t);
        }
        if (out.size() > MAX_WORDS) {
            throw new ImportException("Mỗi ô tối đa " + MAX_WORDS + " từ khóa.");
        }
        return out;
    }
}
