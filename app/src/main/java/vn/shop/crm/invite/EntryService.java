package vn.shop.crm.invite;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.VnGeo;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Trang đích của mã QR/link CHUNG in trên giấy. Không biết khách là ai: chỉ chọn nhóm còn chỗ (cùng cách chọn với
 * lời mời riêng) và nhớ trình duyệt bằng một mã ngẫu nhiên để quét lại không bị đếm thêm.
 *
 * <p>Lượt vào chỉ là ước lượng "đang chờ", không phải số người đã vào nhóm Zalo.
 */
@Service
public class EntryService {

    public enum Outcome { REDIRECT, NO_GROUP, TOO_MANY }

    /** {@code stored}: đã ghi nhận lượt vào (trình duyệt cần nhớ mã ngẫu nhiên bằng cookie). */
    public record Result(Outcome outcome, String url, String visitor, boolean stored) {
    }

    private static final Pattern VISITOR = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final GroupService groups;
    private final EntryThrottle throttle;

    public EntryService(JdbcClient jdbc, GroupService groups, EntryThrottle throttle) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.throttle = throttle;
    }

    public Result enter(String visitorCookie, String ip) {
        return enter(visitorCookie, ip, null);
    }

    /**
     * @param provinceCode mã tỉnh khách tự chọn (có thể rỗng). Chỉ lưu mã tỉnh, không lưu gì khác về khách.
     */
    @Transactional
    public Result enter(String visitorCookie, String ip, String provinceCode) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:k)) AS locked").param("k", InviteService.GROUP_PICK_LOCK)
                .query(Integer.class).single();
        String visitor = visitorCookie != null && VISITOR.matcher(visitorCookie).matches() ? visitorCookie : null;
        Province chosen = VnGeo.byCode(provinceCode == null || provinceCode.isBlank() ? null : provinceCode.strip())
                .orElse(null);

        if (visitor != null) {
            record Prev(Long groupId, String province) {
            }
            Optional<Prev> prev = jdbc.sql("SELECT group_id, province FROM open_entry WHERE visitor = :v")
                    .param("v", visitor)
                    .query((rs, i) -> new Prev((Long) rs.getObject("group_id"), rs.getString("province"))).optional();
            if (prev.isPresent()) {
                Province province = chosen != null ? chosen : VnGeo.byCode(prev.get().province()).orElse(null);
                if (prev.get().groupId() != null) {
                    // Quét lại: về đúng nhóm cũ nếu nhóm còn dùng được, không đếm thêm.
                    Optional<GroupService.Group> same = groups.find(prev.get().groupId()).filter(EntryService::usable);
                    if (same.isPresent()) {
                        return new Result(Outcome.REDIRECT, same.get().inviteLink(), visitor, true);
                    }
                }
                Optional<GroupService.Group> other = groups.pickGroup(province);
                if (other.isEmpty()) {
                    // Vẫn chưa có nhóm hợp: giữ dòng chờ (không đếm vào nhóm nào).
                    jdbc.sql("UPDATE open_entry SET group_id = NULL, province = :p WHERE visitor = :v")
                            .param("p", province == null ? null : province.code(), java.sql.Types.VARCHAR)
                            .param("v", visitor).update();
                    return new Result(Outcome.NO_GROUP, null, visitor, true);
                }
                jdbc.sql("UPDATE open_entry SET group_id = :g, province = :p, created_at = now() WHERE visitor = :v")
                        .param("g", other.get().id()).param("p", province == null ? null : province.code(), java.sql.Types.VARCHAR)
                        .param("v", visitor).update();
                return new Result(Outcome.REDIRECT, other.get().inviteLink(), visitor, true);
            }
        }

        Optional<GroupService.Group> group = groups.pickGroup(chosen);
        if (group.isEmpty() && chosen == null) {
            return new Result(Outcome.NO_GROUP, null, visitor, false);
        }
        if (!throttle.tryAcquire(ip)) {
            return new Result(Outcome.TOO_MANY, null, visitor, false);
        }
        String id = visitor != null ? visitor : newVisitor();
        // Chưa có nhóm hợp mà khách đã chọn tỉnh: ghi một dòng "chờ nhóm" để mẹ biết khu vực nào đang thiếu nhóm.
        jdbc.sql("INSERT INTO open_entry (visitor, group_id, province) VALUES (:v, :g, :p)")
                .param("v", id).param("g", group.map(GroupService.Group::id).orElse(null), java.sql.Types.BIGINT)
                .param("p", chosen == null ? null : chosen.code(), java.sql.Types.VARCHAR).update();
        return group.isPresent()
                ? new Result(Outcome.REDIRECT, group.get().inviteLink(), id, true)
                : new Result(Outcome.NO_GROUP, null, id, true);
    }

    private static boolean usable(GroupService.Group g) {
        return g.hasLink() && g.status() != GroupStatus.CLOSED && g.status() != GroupStatus.PREPARING;
    }

    private static String newVisitor() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
