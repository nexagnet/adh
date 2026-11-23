package vn.shop.crm.zalo;

import vn.shop.crm.geo.GeoArea;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.VnGeo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Thuật toán chọn khi nào tạo nhóm mới và tạo ở cấp nào (docs/THIET-KE-TU-TAO-NHOM-ZALO.md mục 2). Hàm thuần, không đụng cơ sở dữ liệu
 * hay Zalo, để test kỹ.
 *
 * <p>Đầu vào là các khách đã kết bạn mà CHƯA có nhóm hợp khu vực (bước lọc "nhóm còn chỗ" làm ở nơi gọi). Xét từ phạm vi NHỎ nhất:
 * tỉnh, rồi vùng (nhiều tỉnh cạnh nhau), rồi miền. Phạm vi đầu tiên có từ {@code minCustomers} khách chờ trở lên thì chọn; tỉnh nhỏ ít
 * khách tự gộp lên vùng, không bao giờ tạo nhóm 1 đến 2 người. Nhóm mới lấy tối đa {@code maxMembers} khách kết bạn sớm nhất.
 */
public final class GroupPlanner {

    private GroupPlanner() {
    }

    /** Một khách đang chờ nhóm. {@code provinceCode} rỗng hoặc lạ thì không xếp được vào khu vực nào. */
    public record Waiting(long invitationId, String provinceCode, Instant friendedAt) {
    }

    /** @param waitingInScope số khách chờ trong cả phạm vi (có thể nhiều hơn số được đưa vào nhóm đầu) */
    public record Plan(GeoArea scope, List<Long> invitationIds, int waitingInScope) {
    }

    public static Optional<Plan> plan(List<Waiting> waiting, int minCustomers, int maxMembers, Set<String> excludedScopes) {
        Map<Long, Province> province = new LinkedHashMap<>();
        for (Waiting w : waiting) {
            VnGeo.byCode(w.provinceCode()).ifPresent(p -> province.put(w.invitationId(), p));
        }
        List<Waiting> placed = waiting.stream().filter(w -> province.containsKey(w.invitationId())).toList();
        List<Function<Province, GeoArea>> levels = List.of(GeoArea::province, p -> GeoArea.sub(p.sub()), p -> GeoArea.region(p.region()));
        for (Function<Province, GeoArea> level : levels) {
            Map<String, List<Waiting>> byScope = new LinkedHashMap<>();
            Map<String, GeoArea> areas = new LinkedHashMap<>();
            for (Waiting w : placed) {
                GeoArea a = level.apply(province.get(w.invitationId()));
                areas.putIfAbsent(a.code(), a);
                byScope.computeIfAbsent(a.code(), k -> new ArrayList<>()).add(w);
            }
            Optional<Map.Entry<String, List<Waiting>>> best = byScope.entrySet().stream()
                    .filter(e -> e.getValue().size() >= minCustomers && !excludedScopes.contains(e.getKey()))
                    // nhiều khách chờ nhất trước; bằng nhau thì khu vực có người kết bạn sớm nhất, rồi theo mã cho ổn định
                    .min(Comparator.<Map.Entry<String, List<Waiting>>>comparingInt(e -> -e.getValue().size())
                            .thenComparing(e -> earliest(e.getValue()))
                            .thenComparing(Map.Entry::getKey));
            if (best.isPresent()) {
                List<Waiting> inScope = best.get().getValue().stream()
                        .sorted(Comparator.comparing(GroupPlanner::friendedOrMax).thenComparingLong(Waiting::invitationId)).toList();
                List<Long> ids = inScope.stream().limit(Math.max(1, maxMembers)).map(Waiting::invitationId).toList();
                return Optional.of(new Plan(areas.get(best.get().getKey()), ids, inScope.size()));
            }
        }
        return Optional.empty();
    }

    private static Instant friendedOrMax(Waiting w) {
        return w.friendedAt() == null ? Instant.MAX : w.friendedAt();
    }

    private static Instant earliest(List<Waiting> list) {
        return list.stream().map(GroupPlanner::friendedOrMax).min(Comparator.naturalOrder()).orElse(Instant.MAX);
    }
}
