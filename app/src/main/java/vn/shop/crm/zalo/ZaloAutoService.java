package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneNormalizer;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.InviteService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bộ nối Zalo "kết bạn rồi vào nhóm" (D16, ADR-0002). Adh là nơi QUYẾT ĐỊNH; {@link ZaloGateway} chỉ là tay chân.
 *
 * <p>Luồng: bộ nối báo "ai đó thành bạn" ({@link #poll}) → ghép với một lời mời đã nhắn qua số điện thoại, không ghép
 * được thì vào hàng đợi xử lý tay, người lạ thì chỉ ghi nhật ký → thêm vào nhóm đúng khu vực khi đủ điều kiện, còn hạn
 * mức và (ở nấc CONFIRM) đã có người duyệt ({@link #addNext}). Lỗi lạ làm cầu dao nhảy, không tự thử lại.
 * Số điện thoại chỉ đi qua bộ nhớ lúc ghép, không được lưu ở đâu cả.
 */
@Service
public class ZaloAutoService {

    private static final Logger log = LoggerFactory.getLogger(ZaloAutoService.class);

    public enum Step { NOT_ACTIVE, LIMIT, NOTHING, NO_GROUP, ADDED, PERSON_FAILED, TRIPPED, UNREACHABLE }

    public record WaitingRow(long invitationId, String customerName, String provinceName, String state, String code,
                             LocalDateTime friendedAt, String groupName, boolean groupReady, boolean mayApprove) {
    }

    public record ManualEvent(long id, LocalDateTime at, String uidTail) {
    }

    public record Candidate(long invitationId, String customerName, String provinceName, LocalDateTime sentAt) {
    }

    private final JdbcClient jdbc;
    private final ZaloGateway gateway;
    private final ZaloSession session;
    private final ZaloAutoSettings cfg;
    private final InviteService invites;
    private final GroupService groups;
    private final ContactService contacts;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ReentrantLock addLock = new ReentrantLock();
    /** Trạng thái cầu nối lần hỏi gần nhất (do bộ hẹn giờ cập nhật) để các trang không gọi cầu nối mỗi lần mở. */
    private volatile ZaloGateway.Status lastStatus;

    public ZaloAutoService(JdbcClient jdbc, ZaloGateway gateway, ZaloSession session, ZaloAutoSettings cfg,
                           InviteService invites, GroupService groups, ContactService contacts, TransactionTemplate tx,
                           Clock clock) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.session = session;
        this.cfg = cfg;
        this.invites = invites;
        this.groups = groups;
        this.contacts = contacts;
        this.tx = tx;
        this.clock = clock;
    }

    public ZaloAutoSettings settings() {
        return cfg;
    }

    /** Một nhịp của bộ hẹn giờ: nghe sự kiện rồi thêm tối đa một người. */
    public void tick() {
        try {
            poll();
            addNext();
        } catch (RuntimeException e) {
            log.warn("Bộ nối Zalo gặp lỗi ở một nhịp: {}", e.getClass().getSimpleName());
        }
    }

    // ---------- nghe sự kiện kết bạn ----------

    /** Hỏi cầu nối, ghép từng sự kiện mới. Trả số sự kiện đã xử lý. Không làm gì khi nấc OFF hoặc cầu dao đang nhảy. */
    public int poll() {
        if (!cfg.effectiveMode().listens()) {
            return 0;
        }
        ZaloGateway.Status st = gateway.status();
        lastStatus = st;
        if (st.state() == ZaloGateway.State.TRIPPED) {
            tripAndStop("BRIDGE_" + (st.tripReason() == null ? "TRIPPED" : st.tripReason()), false);
            return 0;
        }
        if (Boolean.FALSE.equals(st.accountOk())) {
            tripAndStop("ACCOUNT_MISMATCH", true);
            return 0;
        }
        if (st.state() != ZaloGateway.State.LISTENING) {
            return 0; // chưa đăng nhập, mất kết nối tạm (cầu nối tự nối lại) hoặc không gọi được: chờ nhịp sau
        }
        int handled = 0;
        for (ZaloGateway.FriendEvent e : gateway.friendEvents(cfg.cursor())) {
            if (e.seq() <= cfg.cursor()) {
                continue;
            }
            tx.executeWithoutResult(status -> ingest(e));
            cfg.setCursor(e.seq());
            handled++;
        }
        return handled;
    }

    /** Ghép một sự kiện. Gọi trong giao dịch. */
    void ingest(ZaloGateway.FriendEvent e) {
        Integer seen = jdbc.sql("SELECT count(*) FROM zalo_friend_event WHERE bridge_seq = :s").param("s", e.seq())
                .query(Integer.class).single();
        if (seen > 0) {
            return;
        }
        cfg.log(ZaloAutoSettings.A_LISTEN, null, null, "DA_THANH_BAN", null, "system");
        Optional<String> phone = normalizePhone(e.phone());
        if (phone.isEmpty()) {
            // đa số khách ẩn số (đo thật: khoảng 5/32): không ghép được thì để người xử lý tay, không dò thêm
            record(e, "NO_PHONE", e.uid(), null);
            cfg.log(ZaloAutoSettings.A_MATCH, null, null, "KHONG_CO_SO", null, "system");
            return;
        }
        Optional<Match> m = findMatch(phone.get());
        if (m.isEmpty()) {
            record(e, "STRANGER", null, null);
            cfg.log(ZaloAutoSettings.A_MATCH, null, null, "NGUOI_LA", null, "system");
            return;
        }
        Match match = m.get();
        if (!match.matchable() || !contacts.stillEligible(match.customerId())) {
            record(e, "BLOCKED", null, match.invitationId());
            cfg.log(ZaloAutoSettings.A_MATCH, match.invitationId(), null, "KHONG_DU_DIEU_KIEN", null, "system");
            return;
        }
        if (!attach(match.invitationId(), e.uid(), "bộ nối Zalo")) {
            record(e, "BLOCKED", null, match.invitationId());
            cfg.log(ZaloAutoSettings.A_MATCH, match.invitationId(), null, "TRUNG_UID", null, "system");
            return;
        }
        record(e, "MATCHED", null, match.invitationId());
        cfg.log(ZaloAutoSettings.A_MATCH, match.invitationId(), null, "KHOP", null, "system");
    }

    private void record(ZaloGateway.FriendEvent e, String outcome, String uid, Long invitationId) {
        jdbc.sql("""
                        INSERT INTO zalo_friend_event (bridge_seq, uid, event_at, received_at, outcome, invitation_id)
                        VALUES (:s, :u, :at, :now, :o, :i) ON CONFLICT (bridge_seq) DO NOTHING""")
                .param("s", e.seq()).param("u", uid, java.sql.Types.VARCHAR)
                .param("at", OffsetDateTime.ofInstant(e.at(), ZoneOffset.UTC))
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("o", outcome).param("i", invitationId, java.sql.Types.BIGINT).update();
    }

    private record Match(long invitationId, long customerId, String status, boolean hasUid) {
        /** Lời mời đã nhắn (hoặc người gửi đã đánh dấu kết bạn tay) và chưa gắn uid nào. */
        boolean matchable() {
            return !hasUid && List.of("SENT", "CLICKED", "FRIENDED").contains(status);
        }
    }

    private Optional<Match> findMatch(String e164) {
        return jdbc.sql("""
                        SELECT i.id, i.customer_id, i.status, i.zalo_uid IS NOT NULL AS has_uid
                        FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p""")
                .param("p", e164)
                .query((rs, n) -> new Match(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("status"),
                        rs.getBoolean("has_uid")))
                .optional();
    }

    /** Số Zalo trả về thường là "84912345678" (không có dấu +); chuẩn hóa về E.164 như số khách. */
    static Optional<String> normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.startsWith("84") && !digits.startsWith("+")) {
            Optional<String> viaPlus = PhoneNormalizer.toE164("+" + digits);
            if (viaPlus.isPresent()) {
                return viaPlus;
            }
        }
        return PhoneNormalizer.toE164(digits);
    }

    /**
     * Gắn uid vào lời mời, đánh dấu đã kết bạn nếu chưa, và đặt việc "thêm vào nhóm" theo nấc hiện tại:
     * AUTO = hàng đợi luôn; CONFIRM và LISTEN_ONLY = chờ người duyệt (LISTEN_ONLY thì người thêm tay).
     * Trả false nếu uid này đã gắn vào lời mời khác.
     */
    private boolean attach(long invitationId, String uid, String via) {
        ZaloMode mode = cfg.effectiveMode();
        String state = mode == ZaloMode.AUTO ? "QUEUED" : "WAITING_APPROVAL";
        try {
            int n = jdbc.sql("""
                            UPDATE invitation SET zalo_uid = :u, zalo_add_state = :s, zalo_add_code = NULL, zalo_add_at = NULL
                            WHERE id = :id AND zalo_uid IS NULL""")
                    .param("u", uid).param("s", state).param("id", invitationId).update();
            if (n == 0) {
                return false;
            }
        } catch (DuplicateKeyException e) {
            return false;
        }
        String status = jdbc.sql("SELECT status FROM invitation WHERE id = :id").param("id", invitationId)
                .query(String.class).single();
        if (List.of("SENT", "CLICKED").contains(status)) {
            invites.markFriended(invitationId, via);
        }
        return true;
    }

    // ---------- hàng đợi xử lý tay (không ghép được số) ----------

    public List<ManualEvent> manualQueue() {
        return jdbc.sql("SELECT id, event_at, uid FROM zalo_friend_event WHERE outcome = 'NO_PHONE' ORDER BY id")
                .query((rs, n) -> new ManualEvent(rs.getLong("id"),
                        rs.getObject("event_at", OffsetDateTime.class).atZoneSameInstant(clock.getZone()).toLocalDateTime(),
                        tail(rs.getString("uid")))).list();
    }

    /** Vài số cuối của uid chỉ để phân biệt các dòng; không phải thông tin nhận ra người. */
    private static String tail(String uid) {
        return uid == null || uid.length() <= 4 ? "" : "…" + uid.substring(uid.length() - 4);
    }

    public long manualCount() {
        return jdbc.sql("SELECT count(*) FROM zalo_friend_event WHERE outcome = 'NO_PHONE'").query(Long.class).single();
    }

    /** Lời mời đã nhắn, chưa gắn uid: người xử lý tay chọn một trong số này cho sự kiện không có số. */
    public List<Candidate> candidates() {
        return jdbc.sql("""
                        SELECT i.id, c.name, c.province, i.sent_at
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.status IN ('SENT', 'CLICKED') AND i.zalo_uid IS NULL
                        ORDER BY i.sent_at DESC NULLS LAST, i.id DESC LIMIT 200""")
                .query((rs, n) -> new Candidate(rs.getLong("id"), rs.getString("name"),
                        VnGeo.byCode(rs.getString("province")).map(p -> p.name()).orElse("Chưa rõ khu vực"),
                        local(rs.getObject("sent_at", OffsetDateTime.class)))).list();
    }

    /** Người xử lý nói sự kiện không có số này là của khách nào. Vẫn qua cùng cổng điều kiện như ghép tự động. */
    public void resolve(long eventId, long invitationId, String by) {
        tx.executeWithoutResult(status -> {
            String uid = jdbc.sql("SELECT uid FROM zalo_friend_event WHERE id = :id AND outcome = 'NO_PHONE'")
                    .param("id", eventId).query(String.class).optional()
                    .orElseThrow(() -> new ImportException("Dòng này đã được xử lý."));
            Match m = jdbc.sql("""
                            SELECT i.id, i.customer_id, i.status, i.zalo_uid IS NOT NULL AS has_uid
                            FROM invitation i WHERE i.id = :id""").param("id", invitationId)
                    .query((rs, n) -> new Match(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("status"),
                            rs.getBoolean("has_uid")))
                    .optional().orElseThrow(() -> new ImportException("Không tìm thấy lời mời."));
            if (!m.matchable() || !contacts.stillEligible(m.customerId())) {
                throw new ImportException("Khách này không còn đủ điều kiện hoặc đã được ghép với một người khác.");
            }
            if (!attach(invitationId, uid, "bộ nối Zalo, người ghép tay")) {
                throw new ImportException("Mã Zalo này đã gắn với một khách khác.");
            }
            jdbc.sql("UPDATE zalo_friend_event SET outcome = 'RESOLVED', uid = NULL, invitation_id = :i WHERE id = :id")
                    .param("i", invitationId).param("id", eventId).update();
            cfg.log(ZaloAutoSettings.A_MATCH, invitationId, null, "GHEP_TAY", null, by);
        });
    }

    public void dismiss(long eventId, String by) {
        int n = jdbc.sql("UPDATE zalo_friend_event SET outcome = 'DISMISSED', uid = NULL WHERE id = :id AND outcome = 'NO_PHONE'")
                .param("id", eventId).update();
        if (n == 0) {
            throw new ImportException("Dòng này đã được xử lý.");
        }
        cfg.log(ZaloAutoSettings.A_MATCH, null, null, "BO_QUA", null, by);
    }

    // ---------- duyệt và thêm vào nhóm ----------

    /** Người duyệt cho thêm khách này vào nhóm (nấc CONFIRM; AUTO không cần). Việc thêm vẫn theo hạn mức. */
    public void approve(long invitationId, String by) {
        if (!cfg.effectiveMode().adds()) {
            throw new ImportException("Nấc hiện tại không cho bộ nối thêm người. Hãy thêm tay trên Zalo rồi bấm \"Đã vào nhóm\".");
        }
        int n = jdbc.sql("""
                        UPDATE invitation SET zalo_add_state = 'QUEUED', zalo_approved_by = :by
                        WHERE id = :id AND zalo_add_state = 'WAITING_APPROVAL' AND status IN ('SENT', 'CLICKED', 'FRIENDED')""")
                .param("by", by).param("id", invitationId).update();
        if (n == 0) {
            throw new ImportException("Khách này không ở trạng thái chờ duyệt.");
        }
        cfg.log(ZaloAutoSettings.A_APPROVE, invitationId, null, "OK", null, by);
    }

    /** Người bấm thử lại một lần sau lỗi (không bao giờ tự thử lại). Quay về chờ duyệt. */
    public void retry(long invitationId, String by) {
        int n = jdbc.sql("""
                        UPDATE invitation SET zalo_add_state = 'WAITING_APPROVAL', zalo_add_code = NULL
                        WHERE id = :id AND zalo_add_state = 'FAILED' AND status IN ('SENT', 'CLICKED', 'FRIENDED')""")
                .param("id", invitationId).update();
        if (n == 0) {
            throw new ImportException("Khách này không ở trạng thái lỗi cần thử lại.");
        }
        cfg.log(ZaloAutoSettings.A_APPROVE, invitationId, null, "THU_LAI", null, by);
    }

    /** Thôi không để bộ nối thêm khách này nữa (người sẽ thêm tay). */
    public void release(long invitationId, String by) {
        jdbc.sql("""
                        UPDATE invitation SET zalo_add_state = NULL, zalo_add_code = NULL
                        WHERE id = :id AND zalo_add_state IN ('WAITING_APPROVAL', 'QUEUED', 'FAILED')""")
                .param("id", invitationId).update();
        cfg.log(ZaloAutoSettings.A_APPROVE, invitationId, null, "TU_XU_LY", null, by);
    }

    private record Task(long invitationId, long customerId, String uid, String provinceCode) {
    }

    /**
     * Thêm TỐI ĐA MỘT người nếu được phép lúc này: nấc có thêm, cầu dao không nhảy, trong khung giờ, còn hạn mức ngày
     * và giờ, đủ khoảng cách. Mọi lần gọi Zalo (kể cả lỗi) tính vào hạn mức.
     */
    public Step addNext() {
        ZaloMode mode = cfg.effectiveMode();
        if (!mode.adds()) {
            return Step.NOT_ACTIVE;
        }
        if (!addLock.tryLock()) {
            return Step.NOTHING;
        }
        try {
            if (!cfg.mayAddNow()) {
                return Step.LIMIT;
            }
            ZaloGateway.Status st = gateway.status();
            if (st.state() == ZaloGateway.State.TRIPPED) {
                tripAndStop("BRIDGE_" + (st.tripReason() == null ? "TRIPPED" : st.tripReason()), false);
                return Step.TRIPPED;
            }
            if (!st.listening()) {
                return Step.UNREACHABLE;
            }
            List<GroupService.Group> usable = groups.list().stream().filter(GroupService.Group::hasZaloId).toList();
            Step best = Step.NOTHING;
            for (Task t : queued(mode)) {
                if (!contacts.stillEligible(t.customerId())) {
                    fail(t.invitationId(), "KHONG_DU_DIEU_KIEN");
                    cfg.log(ZaloAutoSettings.A_ADD, t.invitationId(), null, "BO_QUA", "KHONG_DU_DIEU_KIEN", "system");
                    continue;
                }
                Optional<GroupService.Group> group = groups.pickGroup(usable, VnGeo.byCode(t.provinceCode()).orElse(null));
                if (group.isEmpty()) {
                    best = Step.NO_GROUP; // chưa có nhóm hợp khu vực (R7): giữ hàng đợi, không đẩy sang miền khác
                    continue;
                }
                return addOne(t, group.get());
            }
            return best;
        } finally {
            addLock.unlock();
        }
    }

    private List<Task> queued(ZaloMode mode) {
        // AUTO: cả người còn "chờ duyệt" cũng đủ điều kiện (đã chọn tự thêm). CONFIRM: chỉ người đã được duyệt.
        String states = mode == ZaloMode.AUTO ? "('QUEUED', 'WAITING_APPROVAL')" : "('QUEUED')";
        return jdbc.sql("""
                        SELECT i.id, i.customer_id, i.zalo_uid, c.province
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.zalo_add_state IN %s AND i.zalo_uid IS NOT NULL AND i.status IN ('SENT', 'CLICKED', 'FRIENDED')
                        ORDER BY i.friended_at NULLS LAST, i.id LIMIT 20""".formatted(states))
                .query((rs, n) -> new Task(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("zalo_uid"),
                        rs.getString("province"))).list();
    }

    private Step addOne(Task t, GroupService.Group group) {
        // ghi trước khi gọi để một lần chết giữa chừng vẫn được tính vào hạn mức
        long logId = jdbc.sql("""
                        INSERT INTO zalo_auto_log (at, action, invitation_id, group_id, result, actor)
                        VALUES (:at, :a, :i, :g, 'DANG_GOI', 'system') RETURNING id""")
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("a", ZaloAutoSettings.A_ADD)
                .param("i", t.invitationId()).param("g", group.id()).query(Long.class).single();
        ZaloGateway.AddResult r = gateway.addToGroup(t.uid(), group.zaloGroupId());
        if (r.outcome() == ZaloGateway.Outcome.UNREACHABLE) {
            jdbc.sql("DELETE FROM zalo_auto_log WHERE id = :id").param("id", logId).update(); // chưa chạm Zalo: không tính
            return Step.UNREACHABLE;
        }
        jdbc.sql("UPDATE zalo_auto_log SET result = :r, code = :c WHERE id = :id")
                .param("r", r.outcome().name()).param("c", r.code(), java.sql.Types.VARCHAR).param("id", logId).update();
        switch (r.outcome()) {
            case OK -> {
                tx.executeWithoutResult(status -> {
                    invites.markJoinedExact(t.invitationId(), group.id());
                    jdbc.sql("UPDATE invitation SET zalo_add_state = 'ADDED', zalo_add_code = NULL, zalo_add_at = :at WHERE id = :id")
                            .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                            .param("id", t.invitationId()).update();
                });
                return Step.ADDED;
            }
            case PERSON_ERROR -> {
                fail(t.invitationId(), r.code() == null ? "LOI_NGUOI" : r.code());
                return Step.PERSON_FAILED;
            }
            default -> {
                // phiên mất, Zalo cảnh báo, lỗi lạ hoặc không rõ kết quả: dừng ngay, đánh dấu để người kiểm tra tay
                fail(t.invitationId(), r.outcome().name() + (r.code() == null ? "" : "_" + r.code()));
                tripAndStop(r.outcome().name() + (r.code() == null ? "" : ":" + r.code()), true);
                return Step.TRIPPED;
            }
        }
    }

    private void fail(long invitationId, String code) {
        jdbc.sql("UPDATE invitation SET zalo_add_state = 'FAILED', zalo_add_code = :c, zalo_add_at = :at WHERE id = :id")
                .param("c", code).param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", invitationId).update();
    }

    // ---------- cầu dao và phiên (hành động của người ở trang quản trị) ----------

    private void tripAndStop(String reason, boolean stopBridge) {
        cfg.trip(reason, "system");
        if (stopBridge) {
            session.stop(); // đóng kết nối nghe, ngừng mọi thao tác ở phía cầu nối
        }
    }

    /** Người bấm dừng ngay. */
    public void stopNow(String by) {
        cfg.trip("DUNG_TAY", by);
        session.stop();
    }

    /** Người đã đọc nhật ký và bấm "Mở lại": nghe lại bằng phiên đã lưu. Không đăng nhập lại tự động. */
    public boolean reopen(String by) {
        cfg.reopen(by);
        return session.resume();
    }

    public ZaloSession.Login startLogin(String by) {
        cfg.log(ZaloAutoSettings.A_LOGIN, null, null, "BAT_DAU", null, by);
        return session.startLogin();
    }

    /** Hỏi tiến độ quét QR; quét xong đúng tài khoản thì ghi nhật ký và mở lại nếu cầu dao nhảy vì mất phiên. */
    public ZaloSession.Login loginStatus(String by) {
        ZaloSession.Login l = session.loginStatus();
        if (l.state() == ZaloSession.LoginState.DONE) {
            cfg.log(ZaloAutoSettings.A_LOGIN, null, null, "XONG", null, by);
            String reason = cfg.trippedReason();
            // người vừa quét QR đúng tài khoản: mở lại nếu cầu dao nhảy vì mất phiên hoặc vì chính người đó đăng xuất
            if (cfg.tripped() && (reason.startsWith("SESSION_LOST") || reason.equals("DANG_XUAT"))) {
                cfg.reopen(by);
            }
        } else if (l.state() == ZaloSession.LoginState.ACCOUNT_MISMATCH) {
            cfg.log(ZaloAutoSettings.A_LOGIN, null, null, "SAI_TAI_KHOAN", null, by);
        }
        return l;
    }

    public boolean logout(String by) {
        cfg.log(ZaloAutoSettings.A_LOGOUT, null, null, "YEU_CAU", null, by);
        boolean ok = session.logout();
        cfg.trip("DANG_XUAT", by);
        return ok;
    }

    // ---------- để hiển thị ----------

    public ZaloGateway.Status bridgeStatus() {
        ZaloGateway.Status st = gateway.status();
        lastStatus = st;
        return st;
    }

    /** Một dòng cảnh báo cho trang Tổng quan và Lô nhắn hôm nay. */
    public record Alert(String level, String text) {
    }

    /**
     * Cảnh báo cần người xử lý, hoặc rỗng nếu bộ nối tắt/đang ổn. KHÔNG gọi cầu nối: dùng trạng thái lần hỏi gần nhất,
     * nên mở trang không làm tăng số lần gọi Zalo.
     */
    public java.util.Optional<Alert> alert() {
        if (!cfg.props().enabled() || cfg.chosenMode() == ZaloMode.OFF) {
            return java.util.Optional.empty();
        }
        if (cfg.tripped()) {
            return java.util.Optional.of(new Alert("bad", "Bộ nối Zalo đã DỪNG"));
        }
        ZaloGateway.Status st = lastStatus;
        if (st != null && st.state() == ZaloGateway.State.LOGIN_REQUIRED) {
            return java.util.Optional.of(new Alert("bad", "Phiên Zalo của tài khoản phụ hết hạn, cần quét mã QR lại"));
        }
        if (st != null && st.state() == ZaloGateway.State.UNREACHABLE) {
            return java.util.Optional.of(new Alert("warn", "Không gọi được tới cầu nối Zalo"));
        }
        long n = attentionCount();
        if (n > 0) {
            return java.util.Optional.of(new Alert("info", n + " việc Zalo đang chờ mẹ xử lý"));
        }
        return java.util.Optional.empty();
    }

    public List<WaitingRow> waiting() {
        List<GroupService.Group> usable = groups.list().stream().filter(GroupService.Group::hasZaloId).toList();
        ZaloMode mode = cfg.effectiveMode();
        return jdbc.sql("""
                        SELECT i.id, c.name, c.province, i.zalo_add_state, i.zalo_add_code, i.friended_at
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.zalo_add_state IN ('WAITING_APPROVAL', 'QUEUED', 'FAILED')
                          AND i.status IN ('SENT', 'CLICKED', 'FRIENDED')
                        ORDER BY i.friended_at NULLS LAST, i.id""")
                .query((rs, n) -> {
                    var province = VnGeo.byCode(rs.getString("province")).orElse(null);
                    var g = groups.pickGroup(usable, province).orElse(null);
                    String state = rs.getString("zalo_add_state");
                    return new WaitingRow(rs.getLong("id"), rs.getString("name"),
                            province == null ? "Chưa rõ khu vực" : province.name(), state, rs.getString("zalo_add_code"),
                            local(rs.getObject("friended_at", OffsetDateTime.class)), g == null ? null : g.name(), g != null,
                            mode.adds() && "WAITING_APPROVAL".equals(state));
                }).list();
    }

    /** Số việc cần người xử lý (chờ duyệt + lỗi + hàng đợi tay) cho nhãn trên menu. */
    public long attentionCount() {
        long inv = jdbc.sql("""
                        SELECT count(*) FROM invitation WHERE zalo_add_state IN ('WAITING_APPROVAL', 'FAILED')
                          AND status IN ('SENT', 'CLICKED', 'FRIENDED')""").query(Long.class).single();
        return inv + manualCount();
    }

    private LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    /** Giờ hiện tại theo đồng hồ của bộ nối (dùng cho test và trang quản trị). */
    public Instant now() {
        return clock.instant();
    }
}
