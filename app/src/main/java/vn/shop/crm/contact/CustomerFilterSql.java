package vn.shop.crm.contact;

import vn.shop.crm.common.ImportException;
import vn.shop.crm.geo.GeoArea;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.VnGeo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Sinh các điều kiện SQL (đặt sau {@code customer c}) từ một {@link CustomerFilter}. Mọi giá trị đi vào bằng tham số có tên; không bao giờ ghép
 * chữ của người dùng vào câu lệnh, chỉ ghép các đoạn cố định dưới đây. Mọi điều kiện chỉ THU HẸP (đều là "AND ...").
 */
final class CustomerFilterSql {

    /** Điều kiện (mỗi dòng bắt đầu bằng " AND ") và tham số của chúng. */
    record Built(String where, Map<String, Object> params) {
    }

    private static final String OPEN_STATUSES = "('CREATED', 'IN_TRANSIT', 'RETURNING')";
    private static final String S_OF_C = "FROM shipment s WHERE s.receiver_phone = c.phone";

    private CustomerFilterSql() {
    }

    static Built build(CustomerFilter f, Function<ProductGroup, List<String>> goodsOf, ExchangeMarkers.Markers markers) {
        StringBuilder w = new StringBuilder();
        Map<String, Object> p = new LinkedHashMap<>();

        if (!f.excludeAreas().isEmpty()) {
            boolean unknown = false;
            List<String> provinces = new ArrayList<>();
            for (String code : f.excludeAreas()) {
                if (GeoArea.UNKNOWN.equals(code)) {
                    unknown = true;
                } else {
                    GeoArea a = GeoArea.parse(code).orElseThrow(() -> new ImportException("Khu vực loại trừ không hợp lệ."));
                    VnGeo.in(a).stream().map(Province::code).forEach(provinces::add);
                }
            }
            if (!provinces.isEmpty()) {
                w.append(" AND (c.province IS NULL OR c.province NOT IN (:exProvinces))");
                p.put("exProvinces", provinces);
            }
            if (unknown) {
                w.append(" AND c.province IS NOT NULL");
            }
        }
        if (!f.carriers().isEmpty()) {
            w.append(" AND EXISTS (SELECT 1 ").append(S_OF_C).append(" AND s.carrier IN (:carriers))");
            p.put("carriers", f.carriers());
        }
        tri(w, f.cancelled(), "s.canonical_status = 'CANCELLED'");
        tri(w, f.returned(), "s.canonical_status IN ('RETURNING', 'RETURNED')");
        if (f.exchange() != CustomerFilter.Tri.ANY) {
            if (markers == null || markers.isEmpty()) {
                throw new ImportException("Chưa cài cách nhận biết đơn đổi. Hãy cài ở mục \"Cách nhận biết đơn đổi\" trước khi lọc theo đơn đổi.");
            }
            List<String> any = new ArrayList<>();
            if (!markers.orderRef().isEmpty()) {
                any.add("EXISTS (SELECT 1 FROM unnest(CAST(:exOrder AS text[])) AS m(v) WHERE strpos(lower(coalesce(s.order_ref, '')), m.v) > 0)");
                p.put("exOrder", markers.orderRef().toArray(new String[0]));
            }
            if (!markers.goods().isEmpty()) {
                any.add("EXISTS (SELECT 1 FROM unnest(CAST(:exGoods AS text[])) AS m(v) WHERE strpos(lower(coalesce(s.goods, '')), m.v) > 0)");
                p.put("exGoods", markers.goods().toArray(new String[0]));
            }
            if (!markers.status().isEmpty()) {
                any.add("EXISTS (SELECT 1 FROM unnest(CAST(:exStatus AS text[])) AS m(v) WHERE strpos(lower(coalesce(s.raw_status, '')), m.v) > 0)");
                p.put("exStatus", markers.status().toArray(new String[0]));
            }
            tri(w, f.exchange(), "(" + String.join(" OR ", any) + ")");
        }
        if (f.noOpenOrders()) {
            w.append(" AND NOT EXISTS (SELECT 1 ").append(S_OF_C).append(" AND s.canonical_status IN ").append(OPEN_STATUSES).append(")");
        }
        if (f.maxShipments() != null) {
            w.append(" AND (SELECT count(*) ").append(S_OF_C).append(") <= :maxShipments");
            p.put("maxShipments", f.maxShipments());
        }
        if (f.minDays() != null) {
            w.append(" AND c.last_order_on <= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - make_interval(days => :minDays)");
            p.put("minDays", f.minDays());
        }
        if (f.maxDays() != null) {
            w.append(" AND c.last_order_on >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - make_interval(days => :maxDays)");
            p.put("maxDays", f.maxDays());
        }
        if (!f.bought().isEmpty()) {
            if (f.boughtMode() == CustomerFilter.Mode.ALL) {
                int i = 0;
                for (ProductGroup g : f.bought()) {
                    String name = "bgAll" + i++;
                    w.append(" AND EXISTS (SELECT 1 ").append(S_OF_C).append(" AND s.canonical_status = 'DELIVERED' AND s.goods = ANY(:").append(name).append("))");
                    p.put(name, goodsOf.apply(g).toArray(new String[0]));
                }
            } else {
                List<String> goods = new ArrayList<>();
                f.bought().forEach(g -> goods.addAll(goodsOf.apply(g)));
                w.append(" AND EXISTS (SELECT 1 ").append(S_OF_C).append(" AND s.canonical_status = 'DELIVERED' AND s.goods = ANY(:bgAny))");
                p.put("bgAny", goods.toArray(new String[0]));
            }
        }
        if (!f.notBought().isEmpty()) {
            List<String> goods = new ArrayList<>();
            f.notBought().forEach(g -> goods.addAll(goodsOf.apply(g)));
            w.append(" AND NOT EXISTS (SELECT 1 ").append(S_OF_C).append(" AND s.canonical_status = 'DELIVERED' AND s.goods = ANY(:nbg))");
            p.put("nbg", goods.toArray(new String[0]));
            // Đơn giao thành công chưa rõ tên hàng thì không biết khách đã mua nhóm này chưa: loại cho chắc (chỉ làm hẹp).
            w.append(" AND NOT EXISTS (SELECT 1 ").append(S_OF_C)
                    .append(" AND s.canonical_status = 'DELIVERED' AND (s.goods IS NULL OR btrim(s.goods) = ''))");
        }
        if (f.codMin() != null || f.codMax() != null) {
            String sum = "(SELECT sum(x.cod) FROM (SELECT DISTINCT ON (l.tracking_no) l.cod_collected AS cod FROM settlement_line l "
                    + "JOIN shipment s ON s.tracking_no = l.tracking_no WHERE s.receiver_phone = c.phone ORDER BY l.tracking_no, l.id DESC) x)";
            w.append(" AND ").append(sum).append(" IS NOT NULL");
            if (f.codMin() != null) {
                w.append(" AND ").append(sum).append(" >= :codMin");
                p.put("codMin", f.codMin());
            }
            if (f.codMax() != null) {
                w.append(" AND ").append(sum).append(" <= :codMax");
                p.put("codMax", f.codMax());
            }
        }
        if (f.skipOpenCases()) {
            w.append(" AND NOT EXISTS (SELECT 1 FROM warranty_case wc WHERE wc.customer_id = c.id AND wc.state = 'OPEN')")
                    .append(" AND NOT EXISTS (SELECT 1 FROM reply_draft rd WHERE rd.customer_id = c.id AND rd.state = 'HANDOFF')")
                    .append(" AND NOT EXISTS (SELECT 1 FROM refusal_review rr WHERE rr.customer_id = c.id AND rr.state = 'HELD')");
        }
        return new Built(w.toString(), p);
    }

    /** "Có" thêm EXISTS, "Không có" thêm NOT EXISTS, "Không quan tâm" không thêm gì; điều kiện viết theo bí danh {@code s}. */
    private static void tri(StringBuilder w, CustomerFilter.Tri t, String cond) {
        if (t == CustomerFilter.Tri.YES) {
            w.append(" AND EXISTS (SELECT 1 ").append(S_OF_C).append(" AND ").append(cond).append(")");
        } else if (t == CustomerFilter.Tri.NO) {
            w.append(" AND NOT EXISTS (SELECT 1 ").append(S_OF_C).append(" AND ").append(cond).append(")");
        }
    }

    static String orderSql(CustomerFilter f) {
        return switch (f.pickOrder()) {
            case ContactService.Pick.NEWEST_PURCHASE -> " ORDER BY c.last_order_on DESC NULLS LAST, b.recorded_at, c.id";
            case ContactService.Pick.OLDEST_PURCHASE -> " ORDER BY c.last_order_on ASC NULLS LAST, b.recorded_at, c.id";
            default -> " ORDER BY b.recorded_at, c.id";
        };
    }
}
