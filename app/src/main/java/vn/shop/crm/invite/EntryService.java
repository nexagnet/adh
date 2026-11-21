package vn.shop.crm.invite;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public record Result(Outcome outcome, String url, String visitor) {
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

    @Transactional
    public Result enter(String visitorCookie, String ip) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:k)) AS locked").param("k", InviteService.GROUP_PICK_LOCK)
                .query(Integer.class).single();
        String visitor = visitorCookie != null && VISITOR.matcher(visitorCookie).matches() ? visitorCookie : null;

        if (visitor != null) {
            Optional<Long> groupId = jdbc.sql("SELECT group_id FROM open_entry WHERE visitor = :v").param("v", visitor)
                    .query(Long.class).optional();
            if (groupId.isPresent()) {
                // Quét lại: về đúng nhóm cũ nếu nhóm còn dùng được, không đếm thêm.
                Optional<GroupService.Group> same = groups.find(groupId.get()).filter(EntryService::usable);
                if (same.isPresent()) {
                    return new Result(Outcome.REDIRECT, same.get().inviteLink(), visitor);
                }
                Optional<GroupService.Group> other = groups.pickGroup();
                if (other.isEmpty()) {
                    return new Result(Outcome.NO_GROUP, null, visitor);
                }
                jdbc.sql("UPDATE open_entry SET group_id = :g, created_at = now() WHERE visitor = :v")
                        .param("g", other.get().id()).param("v", visitor).update();
                return new Result(Outcome.REDIRECT, other.get().inviteLink(), visitor);
            }
        }

        Optional<GroupService.Group> group = groups.pickGroup();
        if (group.isEmpty()) {
            return new Result(Outcome.NO_GROUP, null, visitor);
        }
        if (!throttle.tryAcquire(ip)) {
            return new Result(Outcome.TOO_MANY, null, visitor);
        }
        String id = visitor != null ? visitor : newVisitor();
        jdbc.sql("INSERT INTO open_entry (visitor, group_id) VALUES (:v, :g)")
                .param("v", id).param("g", group.get().id()).update();
        return new Result(Outcome.REDIRECT, group.get().inviteLink(), id);
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
