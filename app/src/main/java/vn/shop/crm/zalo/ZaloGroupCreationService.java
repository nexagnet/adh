package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.GeoArea;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tự tạo nhóm Zalo theo khu vực (D19, docs/THIET-KE-TU-TAO-NHOM-ZALO.md). Adh QUYẾT ĐỊNH ({@link GroupPlanner}); {@link ZaloGroupMaker}
 * chỉ là tay chân. Mỗi lần xét (mặc định 10 phút một lần, chỉ 08:00–20:00): nếu chưa có đề xuất nào đang mở thì tìm khu vực đủ khách chờ và
 * ghi MỘT đề xuất. Nấc xác nhận: mẹ duyệt thì mới tạo. Nấc tự động: tạo luôn, trong trần mỗi ngày. Cùng luật D18: tắt mặc định, trần thấp,
 * khoảng nghỉ cố định (không ngẫu nhiên hóa), cầu dao dùng chung với gửi tin và thêm người, lỗi không rõ kết quả thì đánh dấu thất bại và
 * KHÔNG tự thử lại. Không ghi tên khách, số điện thoại hay mã Zalo vào nhật ký.
 */
@Service
public class ZaloGroupCreationService implements ZaloTickHook {

    private static final Logger log = LoggerFactory.getLogger(ZaloGroupCreationService.class);

    public enum Step { NOT_ACTIVE, NOT_DUE, NOTHING, PROPOSED, WAIT, UNREACHABLE, CREATED, FAILED, TRIPPED }

    public record Proposal(long id, String scope, String scopeLabel, String groupName, String reason, int waitingCount,
                           String state, LocalDateTime createdAt, String decidedBy, String resultCode, int members,
                           Long groupId, Integer addedCount, Boolean linkOk, String deputyState) {
        public boolean awaitingApproval() {
            return "PROPOSED".equals(state);
        }

        public boolean needsCheck() {
            return "CREATING".equals(state);
        }
    }

    private record Member(long invitationId, long customerId, String uid) {
    }

    private final JdbcClient jdbc;
    private final ZaloGroupMaker maker;
    private final ZaloGateway gateway;
    private final ZaloSession session;
    private final ZaloAutoSettings auto;
    private final ZaloGroupSettings cfg;
    private final GroupService groups;
    private final InviteService invites;
    private final ContactService contacts;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZaloDeputyService deputies;
    private final ReentrantLock lock = new ReentrantLock();

    public ZaloGroupCreationService(JdbcClient jdbc, ZaloGroupMaker maker, ZaloGateway gateway, ZaloSession session,
                                    ZaloAutoSettings auto, ZaloGroupSettings cfg, GroupService groups, InviteService invites,
                                    ContactService contacts, TransactionTemplate tx, Clock clock, ZaloDeputyService deputies) {
        this.jdbc = jdbc;
        this.maker = maker;
        this.gateway = gateway;
        this.session = session;
        this.auto = auto;
        this.cfg = cfg;
        this.groups = groups;
        this.invites = invites;
        this.contacts = contacts;
        this.tx = tx;
        this.clock = clock;
        this.deputies = deputies;
    }

    public ZaloGroupSettings settings() {
        return cfg;
    }

    @Override
    public void tick() {
        try {
            step();
        } catch (RuntimeException e) {
            log.warn("Tự tạo nhóm Zalo gặp lỗi: {}", e.getClass().getSimpleName());
        }
    }

    /** Một nhịp: tạo nhóm cho đề xuất đã duyệt (nếu được phép lúc này), nếu không có thì xét đề xuất mới. Tối đa MỘT lần gọi Zalo. */
    public Step step() {
        ZaloGroupMode mode = cfg.effectiveMode();
        if (mode == ZaloGroupMode.OFF) {
            return Step.NOT_ACTIVE;
        }
        if (!lock.tryLock()) {
            return Step.NOTHING;
        }
        try {
            if (count("SELECT count(*) FROM zalo_group_proposal WHERE state = 'CREATING'") > 0) {
                // Một lần tạo trước đó không có kết quả (sập giữa chừng): không biết đã tạo hay chưa, dừng, không tự thử lại.
                tripAndStop("TAO_NHOM_INTERRUPTED");
                return Step.TRIPPED;
            }
            expireOld();
            Optional<Long> approved = jdbc.sql("SELECT id FROM zalo_group_proposal WHERE state = 'APPROVED' ORDER BY id LIMIT 1")
                    .query(Long.class).optional();
            if (approved.isPresent()) {
                return execute(approved.get());
            }
            if (count("SELECT count(*) FROM zalo_group_proposal WHERE state = 'PROPOSED'") > 0) {
                return Step.WAIT; // đang chờ mẹ duyệt
            }
            if (mode == ZaloGroupMode.AUTO && !mayCreateNow()) {
                return Step.NOTHING; // tự động chỉ xét khi được phép tạo ngay: không để đề xuất đã duyệt nằm chờ
            }
            Optional<Long> proposed = planIfDue(mode);
            if (proposed.isPresent() && mode == ZaloGroupMode.AUTO) {
                return execute(proposed.get());
            }
            return proposed.isPresent() ? Step.PROPOSED : Step.NOTHING;
        } finally {
            lock.unlock();
        }
    }

    // ---------- đề xuất ----------

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private boolean inWindow() {
        LocalTime t = clock.instant().atZone(clock.getZone()).toLocalTime();
        return !t.isBefore(cfg.props().windowStart()) && t.isBefore(cfg.props().windowEnd());
    }

    private int startedToday() {
        Instant start = clock.instant().atZone(clock.getZone()).toLocalDate().atStartOfDay(clock.getZone()).toInstant();
        return jdbc.sql("SELECT count(*) FROM zalo_group_proposal WHERE started_at >= :s")
                .param("s", OffsetDateTime.ofInstant(start, ZoneOffset.UTC)).query(Integer.class).single();
    }

    private Instant lastStarted() {
        return jdbc.sql("SELECT max(started_at) FROM zalo_group_proposal").query(OffsetDateTime.class).optional()
                .map(OffsetDateTime::toInstant).orElse(null);
    }

    /** Còn được tạo một nhóm ngay lúc này không: trong khung giờ, còn trần ngày, đủ khoảng nghỉ cố định. */
    public boolean mayCreateNow() {
        if (!inWindow() || startedToday() >= cfg.daily()) {
            return false;
        }
        Instant last = lastStarted();
        return last == null || !clock.instant().isBefore(last.plusSeconds(cfg.props().gapMinutes() * 60L));
    }

    private Optional<Long> planIfDue(ZaloGroupMode mode) {
        if (!inWindow() || startedToday() >= cfg.daily()) {
            return Optional.empty();
        }
        Instant last = cfg.lastPlanAt();
        Instant now = clock.instant();
        if (last != null && now.isBefore(last.plusSeconds(cfg.props().planEveryMinutes() * 60L))) {
            return Optional.empty();
        }
        cfg.markPlanned(now);
        List<GroupPlanner.Waiting> waiting = waitingCustomers();
        Set<String> rejected = new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT scope FROM zalo_group_proposal
                        WHERE state = 'REJECTED' AND decided_at > :since""")
                .param("since", OffsetDateTime.ofInstant(now.minusSeconds(24 * 3600L), ZoneOffset.UTC)).query(String.class).list());
        Optional<GroupPlanner.Plan> plan = GroupPlanner.plan(waiting, cfg.minCustomers(), cfg.props().maxMembers(), rejected);
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        GroupPlanner.Plan p = plan.get();
        String label = p.scope().label();
        String name = nextName(p.scope());
        String reason = "Tạo nhóm vì có " + p.waitingInScope() + " khách chờ ở " + label
                + (p.waitingInScope() > p.invitationIds().size() ? ", đưa " + p.invitationIds().size() + " người kết bạn sớm nhất vào nhóm đầu" : "");
        boolean auto = mode == ZaloGroupMode.AUTO;
        long id = tx.execute(s -> {
            long pid = jdbc.sql("""
                            INSERT INTO zalo_group_proposal (scope, group_name, reason, waiting_count, state, decided_by, decided_at, created_at)
                            VALUES (:scope, :name, :reason, :n, :state, :by, CASE WHEN :auto THEN CAST(:now AS TIMESTAMPTZ) END, :now) RETURNING id""")
                    .param("scope", p.scope().code()).param("name", name).param("reason", reason).param("n", p.waitingInScope())
                    .param("state", auto ? "APPROVED" : "PROPOSED").param("by", auto ? "system" : null, java.sql.Types.VARCHAR)
                    .param("auto", auto).param("now", now()).query(Long.class).single();
            for (long inv : p.invitationIds()) {
                jdbc.sql("INSERT INTO zalo_group_proposal_member (proposal_id, invitation_id) VALUES (:p, :i)")
                        .param("p", pid).param("i", inv).update();
            }
            return pid;
        });
        this.auto.log(ZaloGroupSettings.A_GROUP, null, null, auto ? "DE_XUAT_TU_DONG" : "DE_XUAT", null, "system");
        return Optional.of(id);
    }

    /** Khách đã kết bạn, có mã Zalo, chưa vào nhóm nào và KHÔNG có nhóm hợp khu vực đang nhận khách. */
    List<GroupPlanner.Waiting> waitingCustomers() {
        List<GroupService.Group> all = groups.list();
        List<GroupPlanner.Waiting> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT i.id, i.customer_id, c.province, i.friended_at
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.status = 'FRIENDED' AND i.zalo_uid IS NOT NULL AND i.group_id IS NULL
                          AND i.group_link_sent_at IS NULL
                          AND (i.reserved_group_id IS NULL OR i.reserved_until <= now())
                          AND (i.zalo_add_state IS NULL OR i.zalo_add_state IN ('WAITING_APPROVAL', 'QUEUED'))
                        ORDER BY i.friended_at NULLS LAST, i.id""")
                .query((rs, n) -> new Object[]{rs.getLong("id"), rs.getLong("customer_id"), rs.getString("province"),
                        rs.getObject("friended_at", OffsetDateTime.class)}).list().forEach(r -> {
                    var province = VnGeo.byCode((String) r[2]).orElse(null);
                    if (province == null || groups.pickGroup(all, province).isPresent() || !contacts.mayContact((Long) r[1])) {
                        return;
                    }
                    OffsetDateTime at = (OffsetDateTime) r[3];
                    out.add(new GroupPlanner.Waiting((Long) r[0], (String) r[2], at == null ? null : at.toInstant()));
                });
        return out;
    }

    private String nextName(GeoArea scope) {
        int existing = jdbc.sql("SELECT count(*) FROM zalo_group WHERE geo_scope = :s").param("s", scope.code())
                .query(Integer.class).single();
        return cfg.props().groupName(scope.label(), existing + 1);
    }

    private void expireOld() {
        jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'EXPIRED', result_code = 'QUA_HAN', finished_at = :now
                        WHERE state IN ('PROPOSED', 'APPROVED') AND created_at < :before""")
                .param("before", OffsetDateTime.ofInstant(clock.instant().minusSeconds(cfg.props().proposalExpireHours() * 3600L),
                        ZoneOffset.UTC)).param("now", now()).update();
    }

    // ---------- hành động của người ở trang quản trị ----------

    /** Mẹ duyệt một đề xuất (nấc xác nhận). Nhóm được tạo ở nhịp kế tiếp nếu còn trần mỗi ngày, đúng giờ và đủ khoảng nghỉ. */
    public void approve(long id, String by) {
        if (cfg.chosenMode() == ZaloGroupMode.OFF) {
            throw new ImportException("Việc tự tạo nhóm đang tắt.");
        }
        int n = jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'APPROVED', decided_by = :by, decided_at = :now
                        WHERE id = :id AND state = 'PROPOSED'""").param("by", by).param("id", id).param("now", now()).update();
        if (n == 0) {
            throw new ImportException("Đề xuất này không còn ở trạng thái chờ duyệt.");
        }
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "DUYET", null, by);
    }

    public void reject(long id, String by) {
        int n = jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'REJECTED', decided_by = :by, decided_at = :now, finished_at = :now
                        WHERE id = :id AND state IN ('PROPOSED', 'APPROVED')""").param("by", by).param("id", id).param("now", now()).update();
        if (n == 0) {
            throw new ImportException("Đề xuất này không còn chờ xử lý.");
        }
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "TU_CHOI", null, by);
    }

    /** Người đã kiểm tra trên Zalo một lần tạo không rõ kết quả: đóng lại (thất bại), để cầu dao có thể mở lại. Không tự thử lại. */
    public void acknowledge(long id, String by) {
        int n = jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'FAILED', result_code = 'KIEM_TRA_TAY', finished_at = :now
                        WHERE id = :id AND state = 'CREATING'""").param("id", id).param("now", now()).update();
        if (n == 0) {
            throw new ImportException("Đề xuất này không ở trạng thái chờ kiểm tra.");
        }
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "DA_KIEM_TRA", null, by);
    }

    // ---------- tạo nhóm ----------

    private Step execute(long id) {
        if (!mayCreateNow()) {
            return Step.WAIT;
        }
        ZaloGateway.Status st = gateway.status();
        if (st.state() == ZaloGateway.State.TRIPPED) {
            tripAndStop("BRIDGE_" + (st.tripReason() == null ? "TRIPPED" : st.tripReason()));
            return Step.TRIPPED;
        }
        if (Boolean.FALSE.equals(st.accountOk())) {
            tripAndStop("ACCOUNT_MISMATCH");
            return Step.TRIPPED;
        }
        if (!st.listening() || !Boolean.TRUE.equals(st.accountOk())) {
            return Step.UNREACHABLE; // chưa chắc đúng tài khoản phụ thì không tạo gì
        }
        Map<String, Object> row = jdbc.sql("SELECT scope, group_name FROM zalo_group_proposal WHERE id = :id AND state = 'APPROVED'")
                .param("id", id).query((rs, n) -> Map.<String, Object>of("scope", rs.getString("scope"),
                        "name", rs.getString("group_name"))).optional().orElse(null);
        if (row == null) {
            return Step.NOTHING;
        }
        GeoArea scope = GeoArea.parse((String) row.get("scope")).orElse(null);
        List<Member> members = eligibleMembers(id);
        if (scope == null || members.size() < 2) {
            finish(id, "EXPIRED", scope == null ? "KHU_VUC_LA" : "KHONG_CON_DU_KHACH");
            auto.log(ZaloGroupSettings.A_GROUP, null, null, "HET_HAN", null, "system");
            return Step.NOTHING;
        }
        String name = nextName(scope);
        // Ghi TRƯỚC khi gọi Zalo: sập giữa chừng thì dòng CREATING còn lại, lần sau dừng thay vì thử lại.
        jdbc.sql("UPDATE zalo_group_proposal SET state = 'CREATING', started_at = :now, group_name = :n WHERE id = :id")
                .param("n", name).param("id", id).param("now", now()).update();
        String deputy = deputyUid(members);
        ZaloGroupMaker.Created r = maker.createGroup(name, members.stream().map(Member::uid).toList(), deputy);
        if (r.outcome() == ZaloGroupMaker.GroupOutcome.UNREACHABLE || r.outcome() == ZaloGroupMaker.GroupOutcome.RATE_LIMITED
                || (r.notCreated() && r.outcome() == ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR)) {
            // chắc chắn chưa tạo gì (chưa chạm Zalo hoặc cầu nối từ chối trước): trả về hàng đợi, không tính trần ngày, thử lại nhịp sau
            jdbc.sql("UPDATE zalo_group_proposal SET state = 'APPROVED', started_at = NULL WHERE id = :id").param("id", id).update();
            return Step.UNREACHABLE;
        }
        try {
            switch (r.outcome()) {
                case OK -> {
                    record(id, scope, name, members, r);
                    auto.log(ZaloGroupSettings.A_GROUP, null, null, "OK", null, "system");
                    return Step.CREATED;
                }
                case PERSON_ERROR -> {
                    finish(id, "FAILED", r.code() == null ? "DAU_VAO_SAI" : r.code());
                    auto.log(ZaloGroupSettings.A_GROUP, null, null, "PERSON_ERROR", r.code(), "system");
                    return Step.FAILED;
                }
                default -> {
                    // phiên mất, Zalo cảnh báo, lỗi lạ hoặc không rõ kết quả: dừng ngay, người kiểm tra tay; không tự thử lại
                    String code = r.outcome().name() + (r.code() == null ? "" : "_" + r.code());
                    finish(id, "FAILED", code);
                    auto.log(ZaloGroupSettings.A_GROUP, null, null, r.outcome().name(), r.code(), "system");
                    tripAndStop(r.outcome().name() + (r.code() == null ? "" : ":" + r.code()));
                    return Step.TRIPPED;
                }
            }
        } catch (RuntimeException e) {
            // Đã gọi Zalo nhưng ghi kết quả lỗi: không biết trạng thái thật, dừng. Đề xuất ở lại CREATING để người kiểm tra tay.
            log.warn("Lỗi sau khi gọi Zalo tạo nhóm: {}", e.getClass().getSimpleName());
            tripAndStop("TAO_NHOM_INTERRUPTED");
            return Step.TRIPPED;
        }
    }

    /** Chỉ những khách còn đủ điều kiện ngay lúc tạo: vẫn là "đã kết bạn", chưa vào nhóm, còn đồng ý nhận tin. */
    private List<Member> eligibleMembers(long proposalId) {
        List<Member> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT i.id, i.customer_id, i.zalo_uid FROM zalo_group_proposal_member m
                        JOIN invitation i ON i.id = m.invitation_id
                        WHERE m.proposal_id = :p AND i.status = 'FRIENDED' AND i.zalo_uid IS NOT NULL AND i.group_id IS NULL
                        ORDER BY i.friended_at NULLS LAST, i.id""")
                .param("p", proposalId).query((rs, n) -> new Member(rs.getLong(1), rs.getLong(2), rs.getString(3))).list()
                .forEach(m -> {
                    if (contacts.mayContact(m.customerId())) {
                        out.add(m);
                    }
                });
        return out;
    }

    private String deputyUid(List<Member> members) {
        String d = deputies.effectiveUid();
        if (d.isEmpty() || !d.matches("\\d{1,32}") || members.stream().anyMatch(m -> m.uid().equals(d))) {
            return null;
        }
        return d;
    }

    /** Ghi nhóm mới vào Adh, đưa các khách đã vào nhóm vào đúng nhóm này; khách chưa vào được ở lại hàng đợi thêm người sẵn có. */
    private void record(long proposalId, GeoArea scope, String name, List<Member> members, ZaloGroupMaker.Created r) {
        tx.executeWithoutResult(s -> {
            long groupId;
            String link = safeLink(r.link());
            boolean linkOk = link != null;
            groupId = groups.create(name, link, 5, scope.code());
            groups.setZaloGroupId(groupId, r.groupId());
            if (linkOk) {
                groups.setStatus(groupId, GroupStatus.OPEN);
            } else {
                jdbc.sql("UPDATE zalo_group SET note = :n WHERE id = :id")
                        .param("n", "Tạo tự động. Chưa có link mời: hãy bật link trong Zalo, dán vào đây rồi mở nhóm nhận khách.")
                        .param("id", groupId).update();
            }
            int added = 0;
            for (Member m : members) {
                if (r.added().contains(m.uid())) {
                    invites.markJoinedExact(m.invitationId(), groupId);
                    jdbc.sql("UPDATE invitation SET zalo_add_state = 'ADDED', zalo_add_code = NULL, zalo_add_at = now() WHERE id = :id")
                            .param("id", m.invitationId()).update();
                    added++;
                } else {
                    // mẹ đã duyệt đề xuất có người này: để bộ nối thêm vào nhóm mới theo đường thêm người sẵn có (vẫn qua hạn mức)
                    jdbc.sql("""
                                    UPDATE invitation SET zalo_add_state = 'QUEUED'
                                    WHERE id = :id AND zalo_add_state = 'WAITING_APPROVAL'""").param("id", m.invitationId()).update();
                }
            }
            jdbc.sql("""
                            UPDATE zalo_group_proposal SET state = 'DONE', finished_at = :now, group_id = :g, added_count = :a,
                                   link_ok = :l, deputy_state = :d WHERE id = :id""")
                    .param("now", now()).param("g", groupId).param("a", added).param("l", linkOk).param("d", r.deputy()).param("id", proposalId).update();
        });
    }

    /** Chỉ nhận link https của zalo.me (cùng luật khi mẹ nhập tay); link lạ thì bỏ. Kiểm TRƯỚC giao dịch để lỗi không làm hỏng giao dịch. */
    private static String safeLink(String link) {
        if (link == null) {
            return null;
        }
        try {
            return GroupService.validateLink(link);
        } catch (ImportException e) {
            return null;
        }
    }

    private void finish(long id, String state, String code) {
        jdbc.sql("UPDATE zalo_group_proposal SET state = :s, result_code = :c, finished_at = :now WHERE id = :id")
                .param("s", state).param("c", code).param("now", now()).param("id", id).update();
    }

    private void tripAndStop(String reason) {
        try {
            auto.trip(reason, "system");
        } finally {
            session.stop();
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ---------- để hiển thị ----------

    public List<Proposal> recent(int limit) {
        java.time.ZoneId zone = clock.getZone();
        Map<Long, Proposal> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT p.*, (SELECT count(*) FROM zalo_group_proposal_member m WHERE m.proposal_id = p.id) AS members
                        FROM zalo_group_proposal p ORDER BY p.id DESC LIMIT :n""").param("n", limit)
                .query((rs, n) -> new Proposal(rs.getLong("id"), rs.getString("scope"),
                        GeoArea.parse(rs.getString("scope")).map(GeoArea::label).orElse("Khu vực lạ"), rs.getString("group_name"),
                        rs.getString("reason"), rs.getInt("waiting_count"), rs.getString("state"),
                        rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDateTime(),
                        rs.getString("decided_by"), rs.getString("result_code"), rs.getInt("members"),
                        (Long) rs.getObject("group_id"), (Integer) rs.getObject("added_count"),
                        (Boolean) rs.getObject("link_ok"), rs.getString("deputy_state"))).list()
                .forEach(p -> out.put(p.id(), p));
        return new ArrayList<>(out.values());
    }

    public static String stateLabel(String state) {
        return switch (state) {
            case "PROPOSED" -> "Chờ mẹ duyệt";
            case "APPROVED" -> "Đã duyệt, chờ tới lượt";
            case "CREATING" -> "Đã gọi Zalo, chưa rõ kết quả";
            case "DONE" -> "Đã tạo";
            case "FAILED" -> "Lỗi";
            case "REJECTED" -> "Đã từ chối";
            case "EXPIRED" -> "Hết hạn";
            default -> state;
        };
    }
}
