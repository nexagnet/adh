package vn.shop.crm.invite;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.contact.CustomerFilter;
import vn.shop.crm.contact.ExchangeMarkers;
import vn.shop.crm.contact.ProductGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mẫu chiến dịch (PR 5): bộ lọc khách đặt sẵn để chọn rồi sửa tiếp. Có 8 mẫu dựng sẵn trong mã và các mẫu chủ shop tự lưu.
 * Mẫu chỉ chứa bộ lọc và thứ tự lấy khách (không khách, không số điện thoại, không nội dung tin); chọn mẫu chỉ điền vào form tạo chiến dịch,
 * không tạo gì và không đổi hạn mức hay lịch gửi nào.
 */
@Service
public class CampaignTemplates {

    public static final int MAX_NAME = 60;
    public static final int MAX_SAVED = 30;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final ExchangeMarkers markers;

    public CampaignTemplates(JdbcClient jdbc, ExchangeMarkers markers) {
        this.jdbc = jdbc;
        this.markers = markers;
    }

    /**
     * Một mẫu để hiện ở trang. {@code values} là các giá trị điền vào ô của form (theo tên ô); {@code blocked} là lý do chưa dùng được (null = dùng được).
     */
    public record Template(String key, String name, String description, CustomerFilter filter, boolean builtIn, Long id, String blocked, String valuesJson) {
    }

    private record Builtin(String key, String name, String description, CustomerFilter filter, boolean needsExchange, boolean needsSettlement) {
    }

    private static List<Builtin> builtins() {
        var yes = CustomerFilter.Tri.YES;
        var no = CustomerFilter.Tri.NO;
        return List.of(
                new Builtin("khach-moi", "Khách mới mua",
                        "Mua đúng 1 lần, lần mua cách đây không quá 60 ngày. Cách làm quen thuộc của phần lớn chiến dịch.",
                        CustomerFilter.builder().days(0, 60).build(), false, false),
                new Builtin("quay-lai-90", "Khách quay lại sau 90 ngày",
                        "Mua 1 lần, lần mua gần nhất cách đây 90 đến 365 ngày (lúc có thể đã quên shop).",
                        CustomerFilter.builder().days(90, 365).build(), false, false),
                new Builtin("ban-them", "Mua áo điều hòa, chưa mua phụ kiện hay pin",
                        "Đã mua áo điều hòa, chưa từng mua pin và phụ kiện khác: dịp bán thêm.",
                        CustomerFilter.builder().bought(CustomerFilter.Mode.ANY, ProductGroup.AO_DIEU_HOA)
                                .notBought(ProductGroup.PIN, ProductGroup.PHU_KIEN_KHAC).build(), false, false),
                new Builtin("on-dinh", "Khách ổn định (mua 1 đến 3 lần, không đơn đổi, không đơn hủy)",
                        "Mời cả khách mua 2 đến 3 lần, bỏ khách có đơn đổi hoặc đơn hủy.",
                        CustomerFilter.builder().purchases(1, 3).exchange(no).cancelled(no).build(), true, false),
                new Builtin("mua-nhieu", "Khách mua nhiều lần (từ 2 lần)",
                        "Tri ân khách thân thiết: mua từ 2 lần, không đơn hủy, không đơn đổi.",
                        CustomerFilter.builder().purchases(2, CustomerFilter.MAX_PURCHASES).exchange(no).cancelled(no).build(), true, false),
                new Builtin("tung-doi-hang", "Khách từng đổi hàng",
                        "Có đơn đổi (mua 1 đến 3 lần). Dùng để chăm sóc, không phải để quảng cáo.",
                        CustomerFilter.builder().purchases(1, 3).exchange(yes).build(), true, false),
                new Builtin("gia-tri-cao", "Khách giá trị cao",
                        "Tổng tiền đã thu từ 1.000.000 đồng (sửa số này ở ô \"Tổng tiền đã thu\"). Cần đã nhập file đối soát.",
                        CustomerFilter.builder().purchases(1, 3).cod(1_000_000L, null).build(), false, true),
                new Builtin("chi-theo-khu-vuc", "Chỉ theo khu vực",
                        "Không lọc thêm gì: chỉ chọn khu vực ở bước 1, để ghép với lịch và hoãn khi mưa của chiến dịch.",
                        CustomerFilter.NONE, false, false));
    }

    /** 8 mẫu dựng sẵn; mẫu cần dấu hiệu đơn đổi hay file đối soát mà chưa có thì ghi lý do ở {@code blocked}. */
    public List<Template> builtIn() {
        boolean exchange = !markers.get().isEmpty();
        boolean settlement = jdbc.sql("SELECT EXISTS (SELECT 1 FROM settlement_line)").query(Boolean.class).single();
        List<Template> out = new ArrayList<>();
        for (Builtin b : builtins()) {
            String blocked = null;
            if (b.needsExchange() && !exchange) {
                blocked = "Cần cài cách nhận biết đơn đổi trước (mục bên dưới).";
            } else if (b.needsSettlement() && !settlement) {
                blocked = "Cần nhập file đối soát J&T trước.";
            }
            out.add(new Template(b.key(), b.name(), b.description(), b.filter(), true, null, blocked, valuesJson(b.filter())));
        }
        return out;
    }

    /** Mẫu chủ shop đã lưu, mới nhất trước. Bộ lọc lưu hỏng (không đọc được) thì bỏ qua mẫu đó. */
    public List<Template> saved() {
        List<Template> out = new ArrayList<>();
        jdbc.sql("SELECT id, name, filter::text AS filter FROM campaign_template ORDER BY id DESC")
                .query((rs, i) -> new Object[]{rs.getLong("id"), rs.getString("name"), rs.getString("filter")}).list().forEach(r -> {
                    CustomerFilter f = CustomerFilter.fromJson((String) r[2]);
                    String blocked = f.exchange() != CustomerFilter.Tri.ANY && markers.get().isEmpty()
                            ? "Cần cài cách nhận biết đơn đổi trước (mục bên dưới)." : null;
                    out.add(new Template("saved-" + r[0], (String) r[1], String.join("; ", f.describe()), f, false, (Long) r[0], blocked, valuesJson(f)));
                });
        return out;
    }

    /** Lưu bộ lọc hiện tại thành mẫu. Tên 1 đến {@value #MAX_NAME} ký tự, không trùng (không phân biệt hoa thường), tối đa {@value #MAX_SAVED} mẫu. */
    public long save(String name, CustomerFilter filter, String by) {
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw new ImportException("Tên mẫu phải từ 1 đến " + MAX_NAME + " ký tự.");
        }
        if (builtins().stream().anyMatch(b -> b.name().equalsIgnoreCase(n))) {
            throw new ImportException("Tên này trùng với một mẫu có sẵn, hãy đặt tên khác.");
        }
        if (jdbc.sql("SELECT count(*) FROM campaign_template").query(Long.class).single() >= MAX_SAVED) {
            throw new ImportException("Đã có " + MAX_SAVED + " mẫu, hãy xóa bớt mẫu không dùng trước khi lưu thêm.");
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM campaign_template WHERE lower(name) = lower(:n))").param("n", n).query(Boolean.class).single()) {
            throw new ImportException("Đã có mẫu tên \"" + n + "\", hãy đặt tên khác hoặc xóa mẫu cũ.");
        }
        return jdbc.sql("INSERT INTO campaign_template (name, filter, created_by) VALUES (:n, CAST(:f AS jsonb), :by) RETURNING id")
                .param("n", n).param("f", filter.toJson()).param("by", by == null ? ContactService.actor() : by).query(Long.class).single();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM campaign_template WHERE id = :id").param("id", id).update();
    }

    /**
     * Giá trị điền vào các ô của form tạo chiến dịch (khóa là tên ô). Mẫu luôn ghi đủ mọi ô (ô không dùng thì rỗng) để chọn mẫu mới xóa sạch mẫu cũ.
     * Chỉ gồm lựa chọn của bộ lọc, không dữ liệu khách.
     */
    static String valuesJson(CustomerFilter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purchasesMin", f.purchasesMin() == null ? "" : f.purchasesMin().toString());
        m.put("purchasesMax", f.purchasesMax() == null ? "" : f.purchasesMax().toString());
        m.put("exchange", f.exchange().name());
        m.put("cancelled", f.cancelled().name());
        m.put("returned", f.returned().name());
        m.put("noOpenOrders", f.noOpenOrders());
        m.put("maxShipments", f.maxShipments() == null ? "" : f.maxShipments().toString());
        m.put("carriers", f.carriers());
        m.put("minDays", f.minDays() == null ? "" : f.minDays().toString());
        m.put("maxDays", f.maxDays() == null ? "" : f.maxDays().toString());
        m.put("bought", f.bought().stream().map(Enum::name).toList());
        m.put("boughtMode", f.boughtMode().name());
        m.put("notBought", f.notBought().stream().map(Enum::name).toList());
        m.put("codMin", f.codMin() == null ? "" : f.codMin().toString());
        m.put("codMax", f.codMax() == null ? "" : f.codMax().toString());
        m.put("excludeAreas", f.excludeAreas());
        m.put("skipOpenCases", f.skipOpenCases());
        m.put("pickOrder", f.pickOrder());
        return JSON.writeValueAsString(m);
    }
}
