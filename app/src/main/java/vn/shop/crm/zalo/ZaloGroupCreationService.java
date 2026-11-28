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
 * ghi MỘT đề xuất. Nấc xác nhận: mẹ duyệt thì mới tạo. Nấc tự động: tạo luôn (không có trần số nhóm mỗi ngày). Cùng luật D18: tắt mặc định,
 * khoảng nghỉ cố định (không ngẫu nhiên hóa), cầu dao dùng chung với gửi tin và thêm người, lỗi không rõ kết quả thì đánh dấu thất bại và
 * KHÔNG tự thử lại. Không ghi tên khách, số điện thoại hay mã Zalo vào nhật ký.
 */
@Service
public class ZaloGroupCreationService implements ZaloTickHook {

    private static final Logger log = LoggerFactory.getLogger(ZaloGroupCreationService.class);

    public enum Step { NOT_ACTIVE, NOT_DUE, NOTHING, PROPOSED, WAIT, UNREACHABLE, CREATED, FAILED, TRIPPED }

    public record Proposal(long id, String scope, String scopeLabel, String groupName, String reason, int waitingCount,
                           String state, LocalDateTime createdAt, String decidedBy, String resultCode, int members,
                           Long groupId, Integer addedCount, Boolean linkOk, String deputyState, String deputyCode, int deputyTries) {
        public boolean awaitingApproval() {
            return "PROPOSED".equals(state);
        }

        public boolean needsCheck() {
            return "CREATING".equals(state);
        }

        /** Nhóm đã tạo nhưng chưa đặt được mẹ làm phó nhóm: hiện nút "Thử lại phó nhóm" (tối đa {@link #MAX_DEPUTY_TRIES} lần). */
        public boolean canRetryDeputy() {
            // NONE: nhóm tạo lúc chưa chọn mẹ, nay đã chọn thì cũng cho thử đặt
            return "DONE".equals(state) && ("FAILED".equals(deputyState) || "NONE".equals(deputyState)) && deputyTries < MAX_DEPUTY_TRIES;
        }
    }

    /** Số lần tối đa bấm "Thử lại phó nhóm" cho một nhóm. */
    public static final int MAX_DEPUTY_TRIES = 5;
    /** Số lần tối đa hệ thống TỰ thử mỗi nhóm; phần còn lại dành cho nút bấm tay. */
    public static final int MAX_AUTO_DEPUTY_TRIES = 3;
    /** Cầu nối từ chối trước khi chạm Zalo (ngoài giờ, đang ngắt, bận, chưa nối): không phải lỗi của Zalo, không tính lần, không dừng cầu dao. */
    private static final Set<String> NOT_REACHED_CODES = Set.of("WINDOW", "NOT_ACTIVE", "BUSY", "NOT_CONNECTED", "LOGIN_REQUIRED", "BRIDGE_DOWN", "TEST_MODE");

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
    private final ZaloDeputyReconcileService deputyReconcile;
    private final ZaloAccountRegistry accounts;
    private final ZaloSharedGroupGrants grants;
    private final vn.shop.crm.safety.TestMode testMode;
    private final ReentrantLock lock = new ReentrantLock();

    public ZaloGroupCreationService(JdbcClient jdbc, ZaloGroupMaker maker, ZaloGateway gateway, ZaloSession session,
                                    ZaloAutoSettings auto, ZaloGroupSettings cfg, GroupService groups, InviteService invites,
                                    ContactService contacts, TransactionTemplate tx, Clock clock, ZaloDeputyService deputies,
                                    ZaloAccountRegistry accounts, ZaloSharedGroupGrants grants, vn.shop.crm.safety.TestMode testMode,
                                    ZaloDeputyReconcileService deputyReconcile) {
        this.grants = grants;
        this.deputyReconcile = deputyReconcile;
        this.testMode = testMode;
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
        this.accounts = accounts;
    }

    /** Trần mềm số khách mỗi nhóm (lưu ở GroupService vì chính nó chọn nhóm). */
    public int softCap() {
        return groups.softCap();
    }

    public void setSoftCap(int cap, String by) {
        int old = groups.softCap();
        groups.setSoftCap(cap, by);
        if (old != cap) {
            auto.log(ZaloAutoSettings.A_CONFIG, null, null, "TAO_NHOM_TRAN_MEM " + cap, "CU " + old, by);
        }
    }

    public ZaloGroupSettings settings() {
        return cfg;
    }

    @Override
    public void tick() {
        try {
            Step s = step();
            if (s == Step.NOTHING || s == Step.WAIT || s == Step.NOT_DUE) {
                autoRetryDeputy(); // rảnh việc tạo nhóm thì mới xét thử lại phó nhóm
            }
        } catch (RuntimeException e) {
            log.warn("Tự tạo nhóm Zalo gặp lỗi: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Tự thử lại đặt phó nhóm cho MỘT nhóm chưa có phó nhóm (FAILED hoặc NONE) khi đã chọn mẹ: khoảng nghỉ cố định 60 phút giữa hai lần,
     * trong khung giờ, tối đa {@link #MAX_AUTO_DEPUTY_TRIES} lần mỗi nhóm, không khi cầu dao ngắt hay mã mẹ thuộc tài khoản cũ.
     * Mẹ chưa kết bạn thì lần nào cũng lỗi, nên có trần; sau đó nút bấm tay còn 2 lần.
     */
    private void autoRetryDeputy() {
        if (deputyReconcile.managesBackfill() || deputies.effectiveUid().isEmpty() || !inWindow() || !deputies.autoRetryDue() || auto.tripped()) {
            return;
        }
        Optional<Long> next = jdbc.sql("""
                        SELECT p.id FROM zalo_group_proposal p JOIN zalo_group g ON g.id = p.group_id AND g.account_id = p.account_id
                        WHERE p.account_id = :acct AND p.state = 'DONE' AND p.deputy_state IN ('FAILED', 'NONE') AND p.deputy_tries < :max
                          AND g.status = 'OPEN' AND g.zalo_group_id IS NOT NULL ORDER BY p.id LIMIT 1""")
                .param("max", MAX_AUTO_DEPUTY_TRIES).param("acct", ZaloAccountContext.current()).query(Long.class).optional();
        if (next.isEmpty()) {
            return;
        }
        deputies.markAutoRetry(); // ghi trước khi gọi Zalo
        try {
            retryDeputy(next.get(), "tự động");
        } catch (ImportException e) {
            // chưa sẵn sàng (cầu nối đang bận hoặc ngắt): bỏ qua, lần sau thử lại
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
            if (count("SELECT count(*) FROM zalo_group_proposal WHERE " + ZaloAccountContext.sql() + " AND state = 'CREATING'") > 0) {
                // Một lần tạo trước đó không có kết quả (sập giữa chừng): không biết đã tạo hay chưa, dừng, không tự thử lại.
                tripAndStop("TAO_NHOM_INTERRUPTED");
                return Step.TRIPPED;
            }
            expireOld();
            Optional<Long> approved = jdbc.sql("SELECT id FROM zalo_group_proposal WHERE " + ZaloAccountContext.sql() + " AND state = 'APPROVED' ORDER BY id LIMIT 1")
                    .query(Long.class).optional();
            if (approved.isPresent()) {
                return execute(approved.get());
            }
            if (count("SELECT count(*) FROM zalo_group_proposal WHERE " + ZaloAccountContext.sql() + " AND state = 'PROPOSED'") > 0) {
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

    private Instant lastStarted() {
        return jdbc.sql("SELECT max(started_at) FROM zalo_group_proposal WHERE " + ZaloAccountContext.sql()).query(OffsetDateTime.class).optional()
                .map(OffsetDateTime::toInstant).orElse(null);
    }

    /**
     * Còn được tạo một nhóm ngay lúc này không: trong khung giờ và đủ khoảng nghỉ cố định. Không có trần số nhóm mỗi ngày (chủ dự án chốt
     * 24/11/2026); khung 08:00 đến 20:00 và khoảng nghỉ 60 phút tự giới hạn tối đa 12 nhóm mỗi ngày.
     */
    public boolean mayCreateNow() {
        if (!inWindow()) {
            return false;
        }
        Instant last = lastStarted();
        return last == null || !clock.instant().isBefore(last.plusSeconds(cfg.props().gapMinutes() * 60L));
    }

    private Optional<Long> planIfDue(ZaloGroupMode mode) {
        if (!inWindow()) {
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
                        WHERE account_id = :acct AND state = 'REJECTED' AND decided_at > :since""")
                .param("acct", ZaloAccountContext.current()).param("since", OffsetDateTime.ofInstant(now.minusSeconds(24 * 3600L), ZoneOffset.UTC)).query(String.class).list());
        Optional<GroupPlanner.Plan> plan = GroupPlanner.plan(waiting, cfg.minCustomers(), cfg.props().maxMembers(), rejected, now,
                java.time.Duration.ofDays(cfg.relaxDays()));
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
                            INSERT INTO zalo_group_proposal (scope, group_name, reason, waiting_count, state, decided_by, decided_at, created_at, account_id)
                            VALUES (:scope, :name, :reason, :n, :state, :by, CASE WHEN :auto THEN CAST(:now AS TIMESTAMPTZ) END, :now, :acct) RETURNING id""")
                    .param("acct", ZaloAccountContext.current()).param("scope", p.scope().code()).param("name", name).param("reason", reason).param("n", p.waitingInScope())
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
        List<GroupService.Group> everyGroup = groups.listOfAccount(ZaloAccountContext.current());
        // Cùng định nghĩa "còn chỗ" với bộ nối thêm người (ZaloAutoService.addNext): nhóm có link, đang mở, CÓ mã nhóm Zalo, chưa đầy.
        // Nhóm chỉ có link (mẹ tạo tay, chưa gán mã Zalo) không được tính, nếu không khách chờ mãi mà không ai đề xuất nhóm mới.
        List<GroupService.Group> all = everyGroup.stream().filter(GroupService.Group::hasZaloId).toList();
        // Nhóm tự tạo xong mà chưa có link (đang chờ mẹ dán link và mở nhận khách): cùng khu vực thì CHỜ nhóm đó, đừng tạo thêm nhóm trùng.
        List<GroupService.Group> pendingGroups = everyGroup.stream()
                .filter(g -> g.hasZaloId() && !g.hasLink() && g.status() == GroupStatus.PREPARING).toList();
        List<GroupPlanner.Waiting> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT i.id, i.customer_id, c.province, i.friended_at
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.status = 'FRIENDED' AND i.zalo_uid IS NOT NULL AND i.group_id IS NULL
                          AND k.allow_group_creation
                          AND i.group_link_sent_at IS NULL
                          AND (i.reserved_group_id IS NULL OR i.reserved_until <= now())
                          AND (i.zalo_add_state IS NULL OR i.zalo_add_state IN ('WAITING_APPROVAL', 'QUEUED'))
                          AND i.account_id = :acct
                        ORDER BY i.friended_at NULLS LAST, i.id""")
                .param("acct", ZaloAccountContext.current())
                .query((rs, n) -> new Object[]{rs.getLong("id"), rs.getLong("customer_id"), rs.getString("province"),
                        rs.getObject("friended_at", OffsetDateTime.class)}).list().forEach(r -> {
                    var province = VnGeo.byCode((String) r[2]).orElse(null);
                    if (province == null || groups.pickGroup(all, province).isPresent() || canUseSharedGroup((Long) r[0], province, false) || !contacts.mayContact((Long) r[1])
                            || pendingGroups.stream().anyMatch(g -> groups.rank(g, province) >= 0)) {
                        return;
                    }
                    OffsetDateTime at = (OffsetDateTime) r[3];
                    out.add(new GroupPlanner.Waiting((Long) r[0], (String) r[2], at == null ? null : at.toInstant()));
                });
        // Khách dư lẻ đã có nhóm dưới trần mềm để vào (xem ZaloAutoService.addNext) thì không tính là đang chờ lập nhóm mới.
        Map<vn.shop.crm.geo.Region, Integer> byRegion = new java.util.HashMap<>();
        for (GroupPlanner.Waiting w : out) {
            VnGeo.byCode(w.provinceCode()).ifPresent(p -> byRegion.merge(p.region(), 1, Integer::sum));
        }
        int min = cfg.minCustomers();
        out.removeIf(w -> {
            var p = VnGeo.byCode(w.provinceCode()).orElse(null);
            return p != null && byRegion.getOrDefault(p.region(), 0) < min && (groups.pickGroup(all, p, true).isPresent() || canUseSharedGroup(w.invitationId(), p, true));
        });
        return out;
    }

    private boolean canUseSharedGroup(long invitation, vn.shop.crm.geo.Province province, boolean overflow) {
        long account = ZaloAccountContext.current();
        var qualified = grants.grantedGroupIds(account).stream()
                .filter(id -> grants.grant(id, account).filter(grant -> testMode.sharedGroupTargetAllowed(account,
                        invitation, id, grant.bridgeGroupId())).isPresent())
                .flatMap(id -> groups.find(id).stream()).filter(GroupService.Group::hasZaloId).toList();
        return groups.pickGroup(qualified, province, overflow, Integer.MAX_VALUE, true).isPresent();
    }

    private String nextName(GeoArea scope) {
        int existing = jdbc.sql("SELECT count(*) FROM zalo_group WHERE geo_scope = :s").param("s", scope.code())
                .query(Integer.class).single();
        // Tên chưa dùng: bỏ nhóm giữa chừng thì số thứ tự không lặp lại tên nhóm còn tồn tại.
        int seq = existing + 1;
        while (jdbc.sql("SELECT count(*) FROM zalo_group WHERE name = :n").param("n", cfg.props().groupName(scope.label(), seq))
                .query(Integer.class).single() > 0 && seq < existing + 1000) {
            seq++;
        }
        return cfg.props().groupName(scope.label(), seq);
    }

    private void expireOld() {
        jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'EXPIRED', result_code = 'QUA_HAN', finished_at = :now
                        WHERE account_id = :acct AND state IN ('PROPOSED', 'APPROVED') AND created_at < :before""")
                .param("before", OffsetDateTime.ofInstant(clock.instant().minusSeconds(cfg.props().proposalExpireHours() * 3600L),
                        ZoneOffset.UTC)).param("now", now()).param("acct", ZaloAccountContext.current()).update();
    }

    // ---------- hành động của người ở trang quản trị ----------

    /** Mẹ duyệt một đề xuất (nấc xác nhận). Nhóm được tạo ở nhịp kế tiếp nếu đúng giờ và đủ khoảng nghỉ. */
    public void approve(long id, String by) {
        if (cfg.chosenMode() == ZaloGroupMode.OFF) {
            throw new ImportException("Việc tự tạo nhóm đang tắt.");
        }
        int n = jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'APPROVED', decided_by = :by, decided_at = :now
                        WHERE id = :id AND account_id = :acct AND state = 'PROPOSED'""")
                .param("by", by).param("id", id).param("now", now()).param("acct", ZaloAccountContext.current()).update();
        if (n == 0) {
            throw new ImportException("Đề xuất này không còn ở trạng thái chờ duyệt.");
        }
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "DUYET", null, by);
    }

    public void reject(long id, String by) {
        int n = jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'REJECTED', decided_by = :by, decided_at = :now, finished_at = :now
                        WHERE id = :id AND account_id = :acct AND state IN ('PROPOSED', 'APPROVED')""")
                .param("by", by).param("id", id).param("now", now()).param("acct", ZaloAccountContext.current()).update();
        if (n == 0) {
            throw new ImportException("Đề xuất này không còn chờ xử lý.");
        }
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "TU_CHOI", null, by);
    }

    /** Người đã kiểm tra trên Zalo một lần tạo không rõ kết quả: đóng lại (thất bại), để cầu dao có thể mở lại. Không tự thử lại. */
    public void acknowledge(long id, String by) {
        int n = jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'FAILED', result_code = 'KIEM_TRA_TAY', finished_at = :now
                        WHERE id = :id AND account_id = :acct AND state = 'CREATING'""")
                .param("id", id).param("now", now()).param("acct", ZaloAccountContext.current()).update();
        if (n == 0) {
            throw new ImportException("Đề xuất này không ở trạng thái chờ kiểm tra.");
        }
        auto.log(ZaloGroupSettings.A_GROUP, null, null, "DA_KIEM_TRA", null, by);
    }

    /**
     * Nút "Thử lại phó nhóm": nhóm đã tạo nhưng mẹ chưa là phó nhóm. Cầu nối thêm mẹ vào nhóm nếu chưa có (Zalo chỉ cho đặt phó nhóm với
     * người đang trong nhóm) rồi đặt phó nhóm. Chỉ một việc, có trần số lần mỗi nhóm, qua cùng cổng chặn (cầu nối đang nghe, không ngắt cầu dao,
     * chế độ thử). Ghi mã lỗi thật vào nhật ký. Hạn chế hay mất phiên thì dừng cầu dao như các việc khác.
     */
    public ZaloGroupMaker.DeputyResult retryDeputy(long proposalId, String by) {
        if (deputyReconcile.managesBackfill()) {
            throw new ImportException("Tài khoản này dùng cơ chế đối chiếu phó nhóm. Kiểm tra hoặc bật lại ở mục Phó nhóm.");
        }
        if (!accounts.active().contains(ZaloAccountContext.current())) {
            throw new ImportException("Tài khoản Zalo này đang tạm ngưng.");
        }
        Map<String, Object> row = jdbc.sql("""
                        SELECT p.state, p.deputy_state, p.deputy_tries, g.zalo_group_id FROM zalo_group_proposal p
                        LEFT JOIN zalo_group g ON g.id = p.group_id AND g.account_id = p.account_id
                        WHERE p.id = :id AND p.account_id = :acct""")
                .param("id", proposalId).param("acct", ZaloAccountContext.current())
                .query((rs, n) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("state", rs.getString("state"));
                    m.put("deputy", rs.getString("deputy_state"));
                    m.put("tries", rs.getInt("deputy_tries"));
                    m.put("zalo", rs.getString("zalo_group_id"));
                    return m;
                }).optional().orElse(null);
        if (row == null || !"DONE".equals(row.get("state")) || !("FAILED".equals(row.get("deputy")) || "NONE".equals(row.get("deputy")))) {
            throw new ImportException("Nhóm này không cần thử lại phó nhóm.");
        }
        String zaloGroup = (String) row.get("zalo");
        if (zaloGroup == null || !zaloGroup.matches("\\d{1,32}")) {
            throw new ImportException("Nhóm này chưa có mã nhóm Zalo nên không đặt được phó nhóm. Hãy thêm mẹ làm phó nhóm bằng tay trong Zalo.");
        }
        if ((int) row.get("tries") >= MAX_DEPUTY_TRIES) {
            throw new ImportException("Đã thử lại " + MAX_DEPUTY_TRIES + " lần. Hãy thêm mẹ làm phó nhóm bằng tay trong Zalo.");
        }
        String deputy = deputies.effectiveUid();
        if (deputy.isEmpty() || !deputy.matches("\\d{1,32}")) {
            throw new ImportException(deputies.stale()
                    ? "Mã của mẹ được tra từ tài khoản Zalo phụ cũ nên không dùng được. Hãy nhập lại số điện thoại của mẹ ở mục phó nhóm rồi xác nhận."
                    : "Chưa chọn mẹ làm phó nhóm. Nhập số điện thoại của mẹ ở mục phó nhóm rồi xác nhận.");
        }
        if (!inWindow()) {
            throw new ImportException("Đang ngoài giờ cho phép (" + cfg.props().windowStart() + " đến " + cfg.props().windowEnd() + "). Thử lại trong giờ.");
        }
        if (!lock.tryLock()) {
            throw new ImportException("Hệ thống đang bận tạo nhóm. Thử lại sau ít phút.");
        }
        try {
            if (deputyReconcile.managesBackfill() || gatewayBlocker() != null) {
                throw new ImportException("Cầu nối Zalo chưa sẵn sàng (đang ngắt, mất phiên hoặc chưa nối). Mở lại cầu nối rồi thử lại.");
            }
            // Ghi số lần TRƯỚC khi gọi Zalo: sập giữa chừng vẫn tính một lần.
            int claimed = jdbc.sql("""
                            UPDATE zalo_group_proposal SET deputy_tries = deputy_tries + 1
                            WHERE id = :id AND account_id = :acct AND state = 'DONE'
                              AND deputy_state IN ('FAILED', 'NONE') AND deputy_tries < :max""")
                    .param("id", proposalId).param("acct", ZaloAccountContext.current()).param("max", MAX_DEPUTY_TRIES).update();
            if (claimed == 0) {
                throw new ImportException("Nhóm này không còn lượt thử lại phó nhóm.");
            }
            ZaloGroupMaker.DeputyResult r = maker.setDeputy(zaloGroup, deputy);
            if (!r.ok() && notReachedZalo(r)) {
                // chưa chạm Zalo: trả lại lần đã tính, giữ nguyên mã lỗi cũ, không dừng cầu dao
                jdbc.sql("UPDATE zalo_group_proposal SET deputy_tries = greatest(deputy_tries - 1, 0) WHERE id = :id AND "
                                + ZaloAccountContext.sql()).param("id", proposalId).update();
                auto.log(ZaloGroupSettings.A_GROUP, null, null, "PHO_NHOM_CHUA_GOI", r.code() == null ? "KHONG_RO" : r.code(), by);
                return r;
            }
            if (r.ok()) {
                jdbc.sql("UPDATE zalo_group_proposal SET deputy_state = 'OK', deputy_code = NULL WHERE id = :id AND "
                                + ZaloAccountContext.sql()).param("id", proposalId).update();
                auto.log(ZaloGroupSettings.A_GROUP, null, null, "PHO_NHOM_OK", null, by);
            } else {
                String code = r.code() == null ? "KHONG_RO" : r.code();
                jdbc.sql("UPDATE zalo_group_proposal SET deputy_code = :c WHERE id = :id AND " + ZaloAccountContext.sql())
                        .param("c", code).param("id", proposalId).update();
                auto.log(ZaloGroupSettings.A_GROUP, null, null, "PHO_NHOM_LOI", code, by);
                if (r.outcome() == ZaloGroupMaker.GroupOutcome.RESTRICTED || r.outcome() == ZaloGroupMaker.GroupOutcome.SESSION_LOST
                        || r.outcome() == ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR) {
                    tripAndStop(r.outcome().name() + ":" + code);
                }
            }
            return r;
        } finally {
            lock.unlock();
        }
    }

    private static boolean notReachedZalo(ZaloGroupMaker.DeputyResult r) {
        ZaloGroupMaker.GroupOutcome o = r.outcome();
        return o == ZaloGroupMaker.GroupOutcome.UNREACHABLE || o == ZaloGroupMaker.GroupOutcome.RATE_LIMITED
                || (r.code() != null && NOT_REACHED_CODES.contains(r.code()));
    }

    // ---------- tạo nhóm ----------

    private Step execute(long id) {
        if (!mayCreateNow()) {
            return Step.WAIT;
        }
        Step blocked = gatewayBlocker();
        if (blocked != null) {
            return blocked;
        }
        Map<String, Object> row = jdbc.sql("SELECT scope, group_name FROM zalo_group_proposal WHERE id = :id AND state = 'APPROVED' AND "
                        + ZaloAccountContext.sql())
                .param("id", id).query((rs, n) -> Map.<String, Object>of("scope", rs.getString("scope"),
                        "name", rs.getString("group_name"))).optional().orElse(null);
        if (row == null) {
            return Step.NOTHING;
        }
        GeoArea scope = GeoArea.parse((String) row.get("scope")).orElse(null);
        CreateDispatch dispatch = tx.execute(ignored -> claimCreate(id, scope));
        if (dispatch == null) {
            return Step.NOTHING;
        }
        List<Member> members = dispatch.members();
        String name = dispatch.name();
        String deputy = deputyUid(members);
        ZaloGroupMaker.Created r = maker.createGroup(name, members.stream().map(Member::uid).toList(), deputy, cfg.welcomeText());
        if (r.outcome() == ZaloGroupMaker.GroupOutcome.UNREACHABLE || r.outcome() == ZaloGroupMaker.GroupOutcome.RATE_LIMITED
                || (r.notCreated() && r.outcome() == ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR)) {
            // chắc chắn chưa tạo gì (chưa chạm Zalo hoặc cầu nối từ chối trước): trả về hàng đợi, không tính trần ngày, thử lại nhịp sau
            jdbc.sql("UPDATE zalo_group_proposal SET state = 'APPROVED', started_at = NULL WHERE id = :id AND "
                            + ZaloAccountContext.sql()).param("id", id).update();
            return Step.UNREACHABLE;
        }
        try {
            switch (r.outcome()) {
                case OK -> {
                    record(id, scope, name, members, r);
                    auto.log(ZaloGroupSettings.A_GROUP, null, null, "OK", null, "system");
                    // Cầu nối có thể tạo nhóm thành công rồi dừng khi bật link, đặt phó nhóm hoặc gửi tin chào bị hạn chế.
                    return gatewayBlocker() == Step.TRIPPED ? Step.TRIPPED : Step.CREATED;
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

    private record CreateDispatch(String name, List<Member> members) {}

    /** Chọn lại khách và đánh dấu phát lệnh dưới cùng khóa với đường tự thêm. */
    private CreateDispatch claimCreate(long id, GeoArea scope) {
        GroupService.lockAllocation(jdbc);
        if (!mayCreateNow() || count("SELECT count(*) FROM zalo_group_proposal WHERE " + ZaloAccountContext.sql()
                + " AND state = 'CREATING'") > 0) {
            return null;
        }
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM zalo_group_proposal WHERE id = :id AND state = 'APPROVED' AND "
                        + ZaloAccountContext.sql() + ")").param("id", id).query(Boolean.class).single()) {
            return null;
        }
        List<Member> members = eligibleMembers(id);
        if (grants.enabled()) {
            members = members.stream().filter(member -> {
                var province = jdbc.sql("""
                                SELECT c.province FROM invitation i JOIN customer c ON c.id = i.customer_id
                                WHERE i.id = :id
                                """).param("id", member.invitationId()).query(String.class).optional()
                        .flatMap(VnGeo::byCode).orElse(null);
                return !canUseSharedGroup(member.invitationId(), province, false);
            }).toList();
        }
        if (scope == null || members.size() < 2) {
            finish(id, "EXPIRED", scope == null ? "KHU_VUC_LA" : "KHONG_CON_DU_KHACH");
            auto.log(ZaloGroupSettings.A_GROUP, null, null, "HET_HAN", null, "system");
            return null;
        }
        String name = nextName(scope);
        // Ghi TRƯỚC khi gọi Zalo: sập giữa chừng thì dòng CREATING còn lại, lần sau dừng thay vì thử lại.
        int claimed = jdbc.sql("""
                        UPDATE zalo_group_proposal SET state = 'CREATING', started_at = :now, group_name = :n
                        WHERE id = :id AND account_id = :acct AND state = 'APPROVED'""")
                .param("n", name).param("id", id).param("now", now()).param("acct", ZaloAccountContext.current()).update();
        return claimed == 0 ? null : new CreateDispatch(name, members);
    }

    /** Cầu nối chưa sẵn sàng hoặc đã ngắt: trả bước phải dừng ở đó (đã ngắt cầu dao nếu cần); null = được phép gọi Zalo. */
    private Step gatewayBlocker() {
        if (auto.tripped()) {
            return Step.TRIPPED;
        }
        ZaloGateway.Status st = gateway.status();
        if (st.state() == ZaloGateway.State.TRIPPED) {
            tripAndStop(ZaloAutoService.bridgeTripReason(st));
            return Step.TRIPPED;
        }
        if (Boolean.FALSE.equals(st.accountOk())) {
            tripAndStop("ACCOUNT_MISMATCH");
            return Step.TRIPPED;
        }
        if (!st.listening() || !Boolean.TRUE.equals(st.accountOk())) {
            return Step.UNREACHABLE; // chưa chắc đúng tài khoản phụ thì không tạo gì
        }
        return null;
    }

    /** Chỉ những khách còn đủ điều kiện ngay lúc tạo: vẫn là "đã kết bạn", chưa vào nhóm, còn đồng ý nhận tin. */
    private List<Member> eligibleMembers(long proposalId) {
        List<Member> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT i.id, i.customer_id, i.zalo_uid FROM zalo_group_proposal_member m
                        JOIN invitation i ON i.id = m.invitation_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE m.proposal_id = :p AND i.account_id = :acct AND i.status = 'FRIENDED' AND i.zalo_uid IS NOT NULL AND i.group_id IS NULL
                          AND k.allow_group_creation
                          AND (i.reserved_group_id IS NULL OR i.reserved_until <= now())
                          AND NOT EXISTS (SELECT 1 FROM zalo_group_add_attempt a WHERE a.invitation_id = i.id AND a.state IN ('DISPATCHED', 'REVIEW'))
                          AND (i.zalo_add_state IS NULL OR i.zalo_add_state IN ('WAITING_APPROVAL', 'QUEUED'))
                        ORDER BY i.friended_at NULLS LAST, i.id""")
                .param("p", proposalId).param("acct", ZaloAccountContext.current())
                .query((rs, n) -> new Member(rs.getLong(1), rs.getLong(2), rs.getString(3))).list()
                .forEach(m -> {
                    if (contacts.mayContact(m.customerId())) {
                        out.add(m);
                    }
                });
        return out;
    }

    private String deputyUid(List<Member> members) {
        return deputyFor(members.stream().map(Member::uid).toList());
    }

    private String deputyFor(List<String> uids) {
        String d = deputies.effectiveUid();
        if (d.isEmpty() || !d.matches("\\d{1,32}") || uids.contains(d)) {
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
            groupId = groups.create(name, link, cfg.targetCustomers(), scope.code(), ZaloAccountContext.current());
            groups.setZaloGroupId(groupId, r.groupId(), ZaloAccountContext.current());
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
                                   link_ok = :l, deputy_state = :d, deputy_code = :dc WHERE id = :id AND account_id = :acct""")
                    .param("now", now()).param("g", groupId).param("a", added).param("l", linkOk).param("d", r.deputy())
                     .param("acct", ZaloAccountContext.current())
                    .param("dc", "FAILED".equals(r.deputy()) ? r.deputyCode() : null, java.sql.Types.VARCHAR).param("id", proposalId).update();
        });
        if ("OK".equals(r.welcome()) || "FAILED".equals(r.welcome()) || "SKIPPED".equals(r.welcome())) {
            // tin chào đầu nhóm chỉ gửi một lần lúc tạo; lỗi chỉ ghi nhận, không chặn việc tạo nhóm và không tự gửi lại
            auto.log(ZaloGroupSettings.A_GROUP, null, null, "TIN_CHAO_" + r.welcome(), null, "system");
        }
        if ("FAILED".equals(r.deputy())) {
            // ghi mã lỗi Zalo thật để biết vì sao không đặt được phó nhóm (không chứa mã người hay tên)
            auto.log(ZaloGroupSettings.A_GROUP, null, null, "PHO_NHOM_LOI", r.deputyCode() == null ? "KHONG_RO" : r.deputyCode(), "system");
        }
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
        jdbc.sql("UPDATE zalo_group_proposal SET state = :s, result_code = :c, finished_at = :now WHERE id = :id AND "
                        + ZaloAccountContext.sql())
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
                        FROM zalo_group_proposal p WHERE p.account_id = :acct ORDER BY p.id DESC LIMIT :n""")
                .param("n", limit).param("acct", ZaloAccountContext.current())
                .query((rs, n) -> new Proposal(rs.getLong("id"), rs.getString("scope"),
                        GeoArea.parse(rs.getString("scope")).map(GeoArea::label).orElse("Khu vực lạ"), rs.getString("group_name"),
                        rs.getString("reason"), rs.getInt("waiting_count"), rs.getString("state"),
                        rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDateTime(),
                        rs.getString("decided_by"), rs.getString("result_code"), rs.getInt("members"),
                        (Long) rs.getObject("group_id"), (Integer) rs.getObject("added_count"),
                        (Boolean) rs.getObject("link_ok"), rs.getString("deputy_state"), rs.getString("deputy_code"),
                        rs.getInt("deputy_tries"))).list()
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
