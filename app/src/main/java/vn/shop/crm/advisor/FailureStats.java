package vn.shop.crm.advisor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.geo.GeoArea;
import vn.shop.crm.geo.VnGeo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Thống kê khách GỬI LỖI để đưa cho AI đề xuất chiến dịch (25/11/2026): để các lần đề xuất sau tránh khu vực hay lỗi, giảm số khách dự kiến khi nhiều khách
 * không nhắn được, và biết trần lời mời kết bạn. Chỉ ĐẾM: không đọc tên, số điện thoại hay nội dung tin ra khỏi lớp này; mã lỗi Zalo chỉ nhận dạng
 * {@code [A-Za-z0-9_]{1,20}}. Khách thuộc danh sách số thử bị loại để lần chạy thử không làm lệch số liệu thật.
 *
 * <p>Nguồn là các cột có sẵn của {@code invitation} (không migration): {@code status = FAILED} + {@code last_error} (Tin 1: không có Zalo, Zalo từ chối,
 * lý do khác), {@code tin1_blocked_at}/{@code tin1_resent_at} (Zalo chặn tin người lạ), {@code friend_request_outcome} (lời mời kết bạn lỗi hoặc không rõ),
 * {@code zalo_add_state = FAILED} + {@code zalo_add_code} (thêm vào nhóm lỗi), cùng mã lỗi lời mời kết bạn trong {@code send_log.reason}.
 */
@Service
public class FailureStats {

    /** Mã lỗi hợp lệ để đưa cho AI (chỉ chữ, số, gạch dưới). */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_]{1,20}");
    static final String OTHER_CODE = "KHAC";

    /** Điều kiện chung: khách không thuộc số thử. */
    private static final String FROM = """
            FROM invitation i JOIN customer c ON c.id = i.customer_id
            WHERE c.phone NOT IN (SELECT phone FROM zalo_test_number)""";

    private static final String NO_ZALO = "i.last_error LIKE 'Không tìm thấy trên Zalo%'";
    private static final String REJECTED = "i.last_error LIKE 'Zalo từ chối gửi cho người này%'";

    private static final String COUNTS = """
            count(*) FILTER (WHERE i.sent_at IS NOT NULL OR i.status = 'FAILED') AS tried,
            count(*) FILTER (WHERE i.status = 'FAILED' AND %s) AS no_zalo,
            count(*) FILTER (WHERE i.tin1_blocked_at IS NOT NULL) AS stranger,
            count(*) FILTER (WHERE i.tin1_resent_at IS NOT NULL) AS resent,
            count(*) FILTER (WHERE i.status = 'FAILED' AND %s) AS rejected,
            count(*) FILTER (WHERE i.status = 'FAILED' AND NOT coalesce(%s, FALSE) AND NOT coalesce(%s, FALSE)) AS other,
            count(*) FILTER (WHERE i.friend_request_outcome = 'FAILED') AS fr_failed,
            count(*) FILTER (WHERE i.friend_request_outcome = 'UNKNOWN') AS fr_unknown,
            count(*) FILTER (WHERE i.zalo_add_state = 'FAILED') AS add_failed""".formatted(NO_ZALO, REJECTED, NO_ZALO, REJECTED);

    private final JdbcClient jdbc;

    public FailureStats(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Bộ đếm cộng dồn (cho vùng gom nhiều tỉnh). */
    static final class Acc {
        final long[] n = new long[9];

        void add(Acc o) {
            for (int i = 0; i < n.length; i++) {
                n[i] += o.n[i];
            }
        }

        AdvisorSnapshot.Failures toFailures(Map<String, Long> friendCodes, Map<String, Long> addCodes) {
            return new AdvisorSnapshot.Failures(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], friendCodes, addCodes);
        }
    }

    private static Acc row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Acc a = new Acc();
        String[] cols = {"tried", "no_zalo", "stranger", "resent", "rejected", "other", "fr_failed", "fr_unknown", "add_failed"};
        for (int i = 0; i < cols.length; i++) {
            a.n[i] = rs.getLong(cols[i]);
        }
        return a;
    }

    /** Toàn hệ thống; null nếu chưa thử nhắn khách nào. */
    public AdvisorSnapshot.Failures overall() {
        Acc a = jdbc.sql("SELECT " + COUNTS + " " + FROM).query((rs, i) -> row(rs)).single();
        if (a.n[0] == 0) {
            return null;
        }
        return a.toFailures(friendRequestCodes(), addToGroupCodes());
    }

    /** Theo khu vực của {@link AdvisorSnapshot.Area#code()} (gộp các tỉnh trong vùng). Khu vực chưa rõ tỉnh bị bỏ. */
    public Map<String, Acc> byArea() {
        Map<String, Acc> out = new LinkedHashMap<>();
        jdbc.sql("SELECT coalesce(c.province, '') AS p, " + COUNTS + " " + FROM + " GROUP BY 1")
                .query((rs, i) -> Map.entry(rs.getString("p"), row(rs))).list().forEach(e -> {
                    if (e.getKey().isEmpty()) {
                        return;
                    }
                    VnGeo.byCode(e.getKey()).ifPresent(p -> out.computeIfAbsent(GeoArea.sub(p.sub()).code(), k -> new Acc()).add(e.getValue()));
                });
        return out;
    }

    /** Theo chiến dịch (mã chiến dịch -> số đếm); chỉ dùng cho chiến dịch đã nhắn đủ khách. */
    public Map<Long, AdvisorSnapshot.Failures> byCampaign() {
        Map<Long, AdvisorSnapshot.Failures> out = new HashMap<>();
        jdbc.sql("SELECT i.campaign_id AS cid, " + COUNTS + " " + FROM + " GROUP BY i.campaign_id")
                .query((rs, i) -> Map.entry(rs.getLong("cid"), row(rs).toFailures(null, null))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Lời mời kết bạn không gửi được, theo mã lỗi Zalo (ví dụ Z223); mỗi khách tính một lần. Không có thì null. */
    Map<String, Long> friendRequestCodes() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT substring(l.reason from '\\(mã ([A-Za-z0-9_]+)\\)') AS code, count(DISTINCT l.invitation_id) AS n
                        FROM send_log l JOIN invitation i ON i.id = l.invitation_id JOIN customer c ON c.id = i.customer_id
                        WHERE l.reason LIKE 'Lời mời kết bạn không gửi được%'
                          AND c.phone NOT IN (SELECT phone FROM zalo_test_number)
                        GROUP BY 1 ORDER BY n DESC""")
                .query((rs, i) -> Map.entry(codeOrOther(rs.getString("code")), rs.getLong("n"))).list()
                .forEach(e -> out.merge(e.getKey(), e.getValue(), Long::sum));
        return out.isEmpty() ? null : out;
    }

    /** Thêm vào nhóm lỗi theo mã lỗi lưu ở {@code zalo_add_code}. Không có thì null. */
    Map<String, Long> addToGroupCodes() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT i.zalo_add_code AS code, count(*) AS n " + FROM + " AND i.zalo_add_state = 'FAILED' GROUP BY 1 ORDER BY n DESC")
                .query((rs, i) -> Map.entry(codeOrOther(rs.getString("code")), rs.getLong("n"))).list()
                .forEach(e -> out.merge(e.getKey(), e.getValue(), Long::sum));
        return out.isEmpty() ? null : out;
    }

    private static String codeOrOther(String code) {
        return code != null && CODE.matcher(code).matches() ? code : OTHER_CODE;
    }
}
