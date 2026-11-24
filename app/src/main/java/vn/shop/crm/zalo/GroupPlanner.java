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
        return plan(waiting, minCustomers, maxMembers, excludedScopes, null, null);
    }

    /** Số khách chờ tối thiểu khi đã có người chờ quá lâu (khách dư không bị kẹt mãi): không bao giờ tạo nhóm 1 người. */
    public static final int RELAXED_MIN = 2;

    /**
     * Như {@link #plan(List, int, int, Set)}, thêm "tuổi chờ" (24/11/2026): khu vực có người chờ từ {@code relaxAfter} trở lên chỉ cần
     * {@value #RELAXED_MIN} khách (thay vì {@code minCustomers}) và được ưu tiên trước khu vực nhiều khách hơn nhưng mới chờ, để khách dư
     * của một tỉnh ít khách không bị bỏ đói. {@code now} hoặc {@code relaxAfter} là null thì tắt tính năng này.
     */
    public static Optional<Plan> plan(List<Waiting> waiting, int minCustomers, int maxMembers, Set<String> excludedScopes,
                                      Instant now, java.time.Duration relaxAfter) {
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
                    .filter(e -> e.getValue().size() >= neededFor(e.getValue(), minCustomers, now, relaxAfter) && !excludedScopes.contains(e.getKey()))
                    // khu vực có người chờ quá lâu trước; rồi nhiều khách chờ nhất; bằng nhau thì khu vực có người kết bạn sớm nhất, rồi theo mã cho ổn định
                    .min(Comparator.<Map.Entry<String, List<Waiting>>>comparingInt(e -> overdue(e.getValue(), now, relaxAfter) ? 0 : 1)
                            .thenComparingInt(e -> -e.getValue().size())
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

    private static boolean overdue(List<Waiting> list, Instant now, java.time.Duration relaxAfter) {
        if (now == null || relaxAfter == null) {
            return false;
        }
        Instant first = earliest(list);
        return first != Instant.MAX && !first.plus(relaxAfter).isAfter(now);
    }

    private static int neededFor(List<Waiting> list, int minCustomers, Instant now, java.time.Duration relaxAfter) {
        return overdue(list, now, relaxAfter) ? Math.min(minCustomers, RELAXED_MIN) : minCustomers;
    }

    private static Instant friendedOrMax(Waiting w) {
        return w.friendedAt() == null ? Instant.MAX : w.friendedAt();
    }

    private static Instant earliest(List<Waiting> list) {
        return list.stream().map(GroupPlanner::friendedOrMax).min(Comparator.naturalOrder()).orElse(Instant.MAX);
    }
}
