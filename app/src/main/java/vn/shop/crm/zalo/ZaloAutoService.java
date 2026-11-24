package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneNormalizer;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

    public enum Step { NOT_ACTIVE, LIMIT, NOTHING, NO_GROUP, ADDED, PERSON_FAILED, GROUP_FAILED, TRIPPED, UNREACHABLE }

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
    private final org.springframework.beans.factory.ObjectProvider<ZaloFriendTap> taps;
    private final ZaloMessageService messages;
    private final TestMode testMode;
    private final ZaloGroupSettings groupSettings;
    /** Trạng thái cầu nối lần hỏi gần nhất (do bộ hẹn giờ cập nhật) để các trang không gọi cầu nối mỗi lần mở. */
    private volatile ZaloGateway.Status lastStatus;

    public ZaloAutoService(JdbcClient jdbc, ZaloGateway gateway, ZaloSession session, ZaloAutoSettings cfg,
                           InviteService invites, GroupService groups, ContactService contacts, TransactionTemplate tx,
                           Clock clock, org.springframework.beans.factory.ObjectProvider<ZaloFriendTap> taps,
                           ZaloMessageService messages, TestMode testMode, ZaloGroupSettings groupSettings) {
        this.groupSettings = groupSettings;
        this.taps = taps;
        this.testMode = testMode;
        this.messages = messages;
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

    /** Trạng thái cầu nối lần hỏi gần nhất (null nếu chưa hỏi lần nào). Không gọi cầu nối. */
    public ZaloGateway.Status lastBridgeStatus() {
        return lastStatus;
    }

    /** Một nhịp của bộ hẹn giờ: nghe sự kiện rồi thêm tối đa một người. */
    public void tick() {
        try {
            poll();
        } catch (RuntimeException e) {
            log.warn("Bộ nối Zalo gặp lỗi khi nghe sự kiện: {}", e.getClass().getSimpleName());
        }
        try {
            syncFriends();
        } catch (RuntimeException e) {
            log.warn("Bộ nối Zalo gặp lỗi khi đối chiếu bạn bè: {}", e.getClass().getSimpleName());
        }
        try {
            addNext();
        } catch (RuntimeException e) {
            log.warn("Bộ nối Zalo gặp lỗi khi thêm người: {}", e.getClass().getSimpleName());
        }
    }

    // ---------- đối chiếu danh sách bạn (24/11/2026, chủ dự án duyệt: CHỈ ĐỌC, chỉ đối chiếu, không lưu bạn ngoài hệ thống) ----------

    private Instant lastFriendSync;
    private Instant friendSyncSession;

    /** Cho test (bean dùng chung giữa các test, đồng hồ giả bị đặt lại): quên lần đối chiếu trước. */
    void resetFriendSync() {
        lastFriendSync = null;
        friendSyncSession = null;
    }

    /**
     * Bắt những khách ĐÃ nhận Tin 1 và đã thành bạn nhưng hệ thống lỡ sự kiện (cầu nối khởi động lại làm mất bộ nhớ sự kiện, Adh tắt lúc đó...).
     * Hỏi cầu nối đúng những mã Zalo (tối đa 500) của khách đã nhận Tin 1 mà chưa được ghi "đã kết bạn"; cầu nối chỉ trả lại mã nào đang là bạn;
     * rồi xử lý y hệt một sự kiện kết bạn (cùng điều kiện đủ điều kiện liên hệ, cùng đường xếp vào hàng thêm nhóm). Người ngoài hệ thống không
     * bao giờ được hỏi hay lưu. Chạy mỗi {@code friendSyncMinutes} phút và ngay khi cầu nối nối lại phiên; ở chế độ thử chỉ xét số thử.
     * Trả số khách vừa được ghi nhận.
     */
    public int syncFriends() {
        if (!cfg.effectiveMode().listens()) {
            return 0;
        }
        ZaloGateway.Status st = lastStatus;
        if (st == null || st.state() != ZaloGateway.State.LISTENING || !Boolean.TRUE.equals(st.accountOk())) {
            return 0;
        }
        Instant now = clock.instant();
        boolean reconnected = !java.util.Objects.equals(st.sessionSince(), friendSyncSession);
        if (!reconnected && lastFriendSync != null
                && now.isBefore(lastFriendSync.plus(java.time.Duration.ofMinutes(cfg.props().friendSyncMinutes())))) {
            return 0;
        }
        List<Map<String, Object>> pending = jdbc.sql("""
                        SELECT i.id, i.zalo_target_uid FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.status IN ('SENT', 'CLICKED') AND i.zalo_uid IS NULL AND i.zalo_target_uid IS NOT NULL
                        """ + testMode.onlyTestSql("c.phone") + " ORDER BY i.sent_at NULLS LAST, i.id LIMIT 500").query().listOfRows();
        lastFriendSync = now;
        friendSyncSession = st.sessionSince();
        List<String> uids = pending.stream().map(r -> String.valueOf(r.get("zalo_target_uid"))).filter(u -> u.matches("\\d{1,32}")).distinct().toList();
        if (uids.isEmpty()) {
            return 0;
        }
        ZaloGateway.FriendCheck r = gateway.friendsAmong(uids);
        if (!r.ok()) {
            if (List.of("WINDOW", "BUSY", "NOT_ACTIVE", "BRIDGE_DOWN").contains(r.code())) {
                lastFriendSync = null; // chưa hỏi được vì lý do tạm thời: thử lại ở nhịp sau
                friendSyncSession = null;
            }
            return 0;
        }
        int done = 0;
        for (String uid : r.friends()) {
            if (!uids.contains(uid)) {
                continue; // cầu nối trả mã không hỏi: bỏ, không tin
            }
            Boolean ok = tx.execute(status -> reconcile(uid));
            if (Boolean.TRUE.equals(ok)) {
                done++;
            }
        }
        return done;
    }

    /** Một mã đã là bạn mà hệ thống chưa biết: ghi nhận như sự kiện kết bạn. Gọi trong giao dịch. */
    private boolean reconcile(String uid) {
        Optional<Match> m = findMatchByTargetUid(uid);
        if (m.isEmpty() || !m.get().matchable()) {
            return false;
        }
        Match match = m.get();
        if (!contacts.stillEligible(match.customerId())) {
            cfg.log(ZaloAutoSettings.A_MATCH, match.invitationId(), null, "KHONG_DU_DIEU_KIEN", null, "system");
            return false;
        }
        if (!attach(match.invitationId(), uid, "đối chiếu danh sách bạn")) {
            return false;
        }
        cfg.log(ZaloAutoSettings.A_MATCH, match.invitationId(), null, "DOI_CHIEU", null, "system");
        return true;
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
        if (st.state() != ZaloGateway.State.LISTENING || !Boolean.TRUE.equals(st.accountOk())) {
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
        // D18: Tin 1 do hệ thống tự gửi đã lưu mã Zalo của khách, nên khớp theo mã, không cần số điện thoại.
        Optional<Match> m = findMatchByTargetUid(e.uid());
        if (m.isEmpty() && taps.orderedStream().anyMatch(t -> t.accept(e))) {
            // thuộc về một số của chiến dịch thử: không ghép với khách thật
            record(e, "STRANGER", null, null);
            cfg.log(ZaloAutoSettings.A_MATCH, null, null, "DOI_TUONG_THU", null, "system");
            return;
        }
        if (m.isEmpty()) {
            Optional<String> phone = normalizePhone(e.phone());
            if (phone.isEmpty()) {
                // đa số khách ẩn số (đo thật: khoảng 5/32): không ghép được thì để người xử lý tay, không dò thêm
                record(e, "NO_PHONE", e.uid(), null);
                cfg.log(ZaloAutoSettings.A_MATCH, null, null, "KHONG_CO_SO", null, "system");
                return;
            }
            m = findMatch(phone.get());
        }
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

    /** Khách đã được hệ thống tự gửi Tin 1: cầu nối đã tra và lưu mã Zalo của họ (D18). */
    private Optional<Match> findMatchByTargetUid(String uid) {
        return jdbc.sql("""
                        SELECT i.id, i.customer_id, i.status, i.zalo_uid IS NOT NULL AS has_uid
                        FROM invitation i WHERE i.zalo_target_uid = :u""")
                .param("u", uid)
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
     * Trả false nếu uid này đã gắn vào lời mời khác. Khách đã được gửi link nhóm tay (group_link_sent_at) thì chỉ gắn uid,
     * không xếp vào hàng thêm: khách sẽ tự vào bằng link, bộ nối không thêm lần nữa.
     */
    private boolean attach(long invitationId, String uid, String via) {
        ZaloMode mode = cfg.effectiveMode();
        String state = mode == ZaloMode.AUTO ? "QUEUED" : "WAITING_APPROVAL";
        // Kiểm trùng ngay trong câu lệnh: một lỗi vi phạm unique trong giao dịch làm Postgres hủy cả giao dịch.
        int n = jdbc.sql("""
                        UPDATE invitation i SET zalo_uid = :u, zalo_add_code = NULL, zalo_add_at = NULL,
                               zalo_add_state = CASE WHEN i.group_link_sent_at IS NULL THEN CAST(:s AS TEXT) END
                        FROM invitation_campaign k
                        WHERE k.id = i.campaign_id AND i.id = :id AND i.zalo_uid IS NULL
                          AND NOT EXISTS (SELECT 1 FROM invitation o WHERE o.zalo_uid = :u)""")
                .param("u", uid).param("s", state).param("id", invitationId).update();
        if (n == 0) {
            return false;
        }
        String status = jdbc.sql("SELECT status FROM invitation WHERE id = :id").param("id", invitationId)
                .query(String.class).single();
        if (List.of("SENT", "CLICKED").contains(status)) {
            invites.markFriended(invitationId, via, false);
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

    private record Task(long invitationId, long customerId, String uid, String provinceCode, String prevState, Long heldGroupId, Instant friendedAt) {
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
            if (inFlightLeftover()) {
                // Một lần gọi Zalo trước đó không có kết quả (sập giữa chừng): không biết đã thêm hay chưa, dừng, không tự thử lại.
                tripAndStop("INTERRUPTED", true);
                return Step.TRIPPED;
            }
            if (!cfg.mayAddNow()) {
                return Step.LIMIT;
            }
            ZaloGateway.Status st = gateway.status();
            if (st.state() == ZaloGateway.State.TRIPPED) {
                tripAndStop("BRIDGE_" + (st.tripReason() == null ? "TRIPPED" : st.tripReason()), false);
                return Step.TRIPPED;
            }
            if (Boolean.FALSE.equals(st.accountOk())) {
                tripAndStop("ACCOUNT_MISMATCH", true);
                return Step.TRIPPED;
            }
            if (!st.listening() || !Boolean.TRUE.equals(st.accountOk())) {
                return Step.UNREACHABLE; // thất bại kín: chưa chắc đúng tài khoản phụ thì không thêm ai
            }
            // MỘT nhóm cho cả hai đường (tự thêm và tin gửi link): nhóm phải có cả mã nhóm Zalo lẫn link mời, và đang nhận khách.
            List<GroupService.Group> usable = groups.list().stream()
                    .filter(g -> g.hasZaloId() && g.hasLink() && g.status() == vn.shop.crm.invite.GroupStatus.OPEN).toList();
            Step best = Step.NOTHING;
            List<Task> tasks = queued(mode);
            // Khách dư lẻ: nhóm cùng khu vực đã đủ mục tiêu mà khu vực (miền) còn quá ít khách chờ để lập nhóm mới thì được thêm vào nhóm còn
            // dưới TRẦN MỀM (mục tiêu + vài khách) thay vì chờ mãi. Đếm khách chờ theo miền (khách chưa có nhóm nào dưới mục tiêu).
            java.util.Map<vn.shop.crm.geo.Region, Integer> waitingByRegion = new java.util.HashMap<>();
            for (Task t : tasks) {
                var p = VnGeo.byCode(t.provinceCode()).orElse(null);
                if (p != null && t.heldGroupId() == null && groups.pickGroup(usable, p).isEmpty()) {
                    waitingByRegion.merge(p.region(), 1, Integer::sum);
                }
            }
            int minToCreate = groupSettings.minCustomers();
            for (Task t : tasks) {
                if (!contacts.stillEligible(t.customerId())) {
                    fail(t.invitationId(), "KHONG_DU_DIEU_KIEN");
                    cfg.log(ZaloAutoSettings.A_MATCH, t.invitationId(), null, "BO_QUA", "KHONG_DU_DIEU_KIEN", "system"); // không phải lần gọi Zalo: không tính hạn mức
                    continue;
                }
                Optional<GroupService.Group> group;
                if (t.heldGroupId() != null) {
                    // khách đã được giữ chỗ/gán một nhóm (nhóm gợi ý ở tin link): dùng đúng nhóm đó, không đổi sang nhóm khác
                    group = usable.stream().filter(g -> g.id() == t.heldGroupId()).findFirst();
                } else {
                    var province = VnGeo.byCode(t.provinceCode()).orElse(null);
                    // Khách chưa rõ tỉnh chỉ được thêm vào nhóm CHUNG (không gắn khu vực); thêm vào nhóm của một miền bất kỳ có thể sai miền.
                    List<GroupService.Group> pool = province == null ? usable.stream().filter(g -> g.scope() == null).toList() : usable;
                    group = groups.pickGroup(pool, province);
                    if (group.isEmpty() && province != null && waitingByRegion.getOrDefault(province.region(), 0) < minToCreate) {
                        // khách dư lẻ: mới đồng ý chỉ vào nhóm CÙNG TỈNH; chờ quá số ngày đã chỉnh thì được gộp lên nhóm cùng vùng hoặc miền
                        boolean waitedLong = t.friendedAt() != null
                                && !t.friendedAt().plus(java.time.Duration.ofDays(groupSettings.mergeUpDays())).isAfter(clock.instant());
                        group = groups.pickGroup(pool, province, true, waitedLong ? Integer.MAX_VALUE : 0);
                    }
                }
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
                        SELECT i.id, i.customer_id, i.zalo_uid, c.province, i.zalo_add_state,
                               coalesce(i.group_id, CASE WHEN i.reserved_until > now() THEN i.reserved_group_id END) AS held_group, i.friended_at
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.zalo_add_state IN %s AND i.zalo_uid IS NOT NULL AND i.status IN ('SENT', 'CLICKED', 'FRIENDED')
                          AND (i.tin1_blocked_at IS NULL OR i.tin1_resent_at IS NOT NULL)
                        """.formatted(states) + testMode.onlyTestSql("c.phone") + " ORDER BY i.friended_at NULLS LAST, i.id LIMIT 500")
                .query((rs, n) -> new Task(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("zalo_uid"),
                        rs.getString("province"), rs.getString("zalo_add_state"), (Long) rs.getObject("held_group"),
                        rs.getTimestamp("friended_at") == null ? null : rs.getTimestamp("friended_at").toInstant())).list();
    }

    /** Có lần gọi Zalo nào còn ghi "đang gọi" không? Đang giữ khóa thêm người nên mọi dòng như vậy là của lần đã chết giữa chừng. */
    private boolean inFlightLeftover() {
        return jdbc.sql("SELECT count(*) FROM zalo_auto_log WHERE action = :a AND result = 'DANG_GOI'")
                .param("a", ZaloAutoSettings.A_ADD).query(Long.class).single() > 0;
    }

    private Step addOne(Task t, GroupService.Group group) {
        // Trước khi gọi: đánh dấu khách này "đang gọi" (FAILED/DANG_GOI) rồi ghi nhật ký. Sập giữa chừng thì khách ở lại trạng thái
        // cần người xem, KHÔNG quay về hàng đợi để lần sau thêm lại; nhật ký vẫn tính vào hạn mức.
        fail(t.invitationId(), "DANG_GOI");
        long logId = jdbc.sql("""
                        INSERT INTO zalo_auto_log (at, action, invitation_id, group_id, result, actor)
                        VALUES (:at, :a, :i, :g, 'DANG_GOI', 'system') RETURNING id""")
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("a", ZaloAutoSettings.A_ADD)
                .param("i", t.invitationId()).param("g", group.id()).query(Long.class).single();
        ZaloGateway.AddResult r = gateway.addToGroup(t.uid(), group.zaloGroupId());
        if (r.outcome() == ZaloGateway.Outcome.UNREACHABLE) {
            // chưa chạm Zalo (không nối được): không tính hạn mức, trả khách về trạng thái cũ
            jdbc.sql("DELETE FROM zalo_auto_log WHERE id = :id").param("id", logId).update();
            jdbc.sql("UPDATE invitation SET zalo_add_state = :s, zalo_add_code = NULL WHERE id = :id")
                    .param("s", t.prevState()).param("id", t.invitationId()).update();
            return Step.UNREACHABLE;
        }
        try {
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
                    return afterPersonError(group);
                }
                case GROUP_ERROR -> {
                    // Zalo báo lỗi của CẢ NHÓM (không còn nhóm, không đủ quyền...): không phải lỗi của khách này và không phải dấu hiệu tài khoản
                    // bị hạn chế. Tạm dừng đúng nhóm đó, trả khách về hàng đợi (khách sẽ được xếp nhóm khác), mẹ thấy cảnh báo.
                    jdbc.sql("UPDATE invitation SET zalo_add_state = :s, zalo_add_code = NULL WHERE id = :id")
                            .param("s", t.prevState()).param("id", t.invitationId()).update();
                    groups.setStatus(group.id(), GroupStatus.PAUSED);
                    return afterGroupError();
                }
                default -> {
                    // phiên mất, Zalo cảnh báo, lỗi lạ hoặc không rõ kết quả: dừng ngay, đánh dấu để người kiểm tra tay
                    fail(t.invitationId(), r.outcome().name() + (r.code() == null ? "" : "_" + r.code()));
                    tripAndStop(r.outcome().name() + (r.code() == null ? "" : ":" + r.code()), true);
                    return Step.TRIPPED;
                }
            }
        } catch (RuntimeException e) {
            // Đã gọi Zalo nhưng ghi kết quả lỗi (ví dụ cơ sở dữ liệu chớp): không biết trạng thái thật, dừng. Khách ở lại FAILED/DANG_GOI.
            log.warn("Lỗi sau khi gọi Zalo thêm người: {}", e.getClass().getSimpleName());
            tripAndStop("INTERRUPTED", true);
            return Step.TRIPPED;
        }
    }

    /** Bao nhiêu lần thêm liền lỗi riêng người, cùng MỘT nhóm, thì tạm dừng nhóm đó (nghi nhóm cài sai: chỉ quản trị viên được thêm...). */
    static final int GROUP_STREAK = 4;
    /** Trong cửa sổ này (các lần thêm gần nhất, mọi nhóm), từng này lần lỗi riêng người thì dừng cầu dao (nghi Zalo hạn chế). */
    static final int TRIP_WINDOW = 8;
    static final int TRIP_PERSON_ERRORS = 6;
    /** Bao nhiêu lần liền lỗi cả nhóm (nhóm khác nhau) thì dừng cầu dao: nhiều nhóm cùng hỏng cùng lúc không phải chuyện ngẫu nhiên. */
    static final int GROUP_ERROR_TRIP = 3;

    /**
     * Sau một lần lỗi riêng người. Không dừng cầu dao chỉ vì vài khách xui liền nhau (nhiều người cài "không cho thêm vào nhóm"):
     * (1) {@value #GROUP_STREAK} lần liền cùng một nhóm: tạm dừng nhóm đó, mẹ xem; (2) {@value #TRIP_PERSON_ERRORS} trong {@value #TRIP_WINDOW}
     * lần gần nhất (lẫn nhiều nhóm): dừng cầu dao, đừng đốt hạn mức.
     */
    private Step afterPersonError(GroupService.Group group) {
        List<Map<String, Object>> last = jdbc.sql("SELECT result, group_id FROM zalo_auto_log WHERE action = :a AND result <> 'DANG_GOI' ORDER BY id DESC LIMIT :n")
                .param("a", ZaloAutoSettings.A_ADD).param("n", TRIP_WINDOW).query().listOfRows();
        long persons = last.stream().filter(m -> "PERSON_ERROR".equals(m.get("result"))).count();
        if (last.size() == TRIP_WINDOW && persons >= TRIP_PERSON_ERRORS) {
            tripAndStop("NHIEU_LOI_NGUOI", true);
            return Step.TRIPPED;
        }
        boolean streak = last.size() >= GROUP_STREAK && last.stream().limit(GROUP_STREAK)
                .allMatch(m -> "PERSON_ERROR".equals(m.get("result")) && m.get("group_id") != null && ((Number) m.get("group_id")).longValue() == group.id());
        if (streak) {
            groups.setStatus(group.id(), GroupStatus.PAUSED);
            return Step.GROUP_FAILED;
        }
        return Step.PERSON_FAILED;
    }

    private Step afterGroupError() {
        List<String> last = jdbc.sql("SELECT result FROM zalo_auto_log WHERE action = :a AND result <> 'DANG_GOI' ORDER BY id DESC LIMIT :n")
                .param("a", ZaloAutoSettings.A_ADD).param("n", GROUP_ERROR_TRIP).query(String.class).list();
        if (last.size() == GROUP_ERROR_TRIP && last.stream().allMatch("GROUP_ERROR"::equals)) {
            tripAndStop("NHIEU_LOI_NHOM", true);
            return Step.TRIPPED;
        }
        return Step.GROUP_FAILED;
    }

    private void fail(long invitationId, String code) {
        jdbc.sql("UPDATE invitation SET zalo_add_state = 'FAILED', zalo_add_code = :c, zalo_add_at = :at WHERE id = :id")
                .param("c", code).param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", invitationId).update();
    }

    // ---------- cầu dao và phiên (hành động của người ở trang quản trị) ----------

    private void tripAndStop(String reason, boolean stopBridge) {
        try {
            cfg.trip(reason, "system");
        } finally {
            if (stopBridge) {
                session.stop(); // đóng kết nối nghe, ngừng mọi thao tác ở phía cầu nối, kể cả khi ghi cầu dao lỗi
            }
        }
    }

    /** Người bấm dừng ngay. */
    public boolean stopNow(String by) {
        cfg.trip("DUNG_TAY", by);
        return session.stop();
    }

    /** Lý do dừng mà người bấm "Mở lại" không cần xác nhận thêm: chính họ vừa dừng, đăng xuất, hoặc chỉ mất phiên (quét QR là xong). */
    private static boolean needsCheck(String reason) {
        return !(reason == null || reason.isBlank() || reason.equals("DUNG_TAY") || reason.equals("DANG_XUAT")
                || reason.startsWith("SESSION_LOST"));
    }

    /**
     * Người đã đọc nhật ký và bấm "Mở lại": nghe lại bằng phiên đã lưu. Với các lý do nghiêm trọng (Zalo cảnh báo, lỗi lạ, không
     * biết kết quả...) bắt buộc tick xác nhận đã xem nhật ký và kiểm tra nhóm bằng mắt. Chỉ bỏ cầu dao khi cầu nối nghe lại được.
     */
    public boolean reopen(String by, boolean checked) {
        if (cfg.tripped() && !checked && needsCheck(cfg.trippedReason())) {
            throw new ImportException("Bộ nối dừng vì: " + new ZaloLabels().reason(cfg.trippedReason())
                    + ". Hãy đọc nhật ký, kiểm tra nhóm trên Zalo bằng mắt rồi tick ô xác nhận trước khi mở lại.");
        }
        boolean ok = session.resume();
        if (ok) {
            cfg.reopen(by);
        }
        return ok;
    }

    /** Nhóm Zalo tài khoản phụ đang tham gia (chỉ đọc). Kết quả chỉ hiện trên trang admin, không ghi nhật ký tên nhóm. */
    public ZaloSession.GroupList zaloGroups() {
        return session.listGroups();
    }

    /** Gán mã nhóm Zalo (chọn từ danh sách) cho một nhóm của Adh; bộ nối chỉ thêm khách vào nhóm có mã. */
    public void assignZaloGroup(long adhGroupId, String zaloGroupId) {
        groups.setZaloGroupId(adhGroupId, zaloGroupId);
    }

    /** Các nhóm của Adh, để chọn gán mã. */
    public List<GroupService.Group> adhGroups() {
        return groups.list();
    }

    public ZaloSession.Login startLogin(String by) {
        cfg.log(ZaloAutoSettings.A_LOGIN, null, null, "BAT_DAU", null, by);
        return session.startLogin();
    }

    /** Hỏi tiến độ quét QR; quét xong đúng tài khoản thì ghi nhật ký và mở lại nếu cầu dao nhảy vì mất phiên. */
    public ZaloSession.Login loginStatus(String by) {
        ZaloSession.Login l = session.loginStatus();
        if (l.state() == ZaloSession.LoginState.DONE) {
            if (!cfg.lastLoginDone()) {
                cfg.log(ZaloAutoSettings.A_LOGIN, null, null, "XONG", null, by);
            }
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

    /**
     * Khách đã kết bạn mà chờ nhóm quá lâu (khu vực quá ít khách nên không tạo được nhóm) hoặc chưa rõ khu vực: cần mẹ quyết, ví dụ tạo nhóm
     * tay hoặc chọn tỉnh. Không tính khách đã có nhóm.
     */
    public long longWaitingCount(int days) {
        return jdbc.sql("""
                        SELECT count(*) FROM invitation i JOIN customer c ON c.id = i.customer_id
                        JOIN invitation_campaign k ON k.id = i.campaign_id
                        WHERE i.status = 'FRIENDED' AND i.zalo_uid IS NOT NULL AND i.group_id IS NULL AND i.group_link_sent_at IS NULL
                          AND (i.zalo_add_state IS NULL OR i.zalo_add_state IN ('WAITING_APPROVAL', 'QUEUED'))
                          AND (i.friended_at < :before OR c.province IS NULL OR c.province = '')
                          AND (i.friended_at IS NULL OR i.friended_at < :unknownAfter)""")
                .param("before", OffsetDateTime.ofInstant(clock.instant().minusSeconds(days * 86400L), ZoneOffset.UTC))
                .param("unknownAfter", OffsetDateTime.ofInstant(clock.instant().minusSeconds(2 * 86400L), ZoneOffset.UTC))
                .query(Long.class).single();
    }

    /** Số việc cần người xử lý (chờ duyệt + lỗi + hàng đợi tay) cho nhãn trên menu. */
    public long attentionCount() {
        long inv = jdbc.sql("""
                        SELECT count(*) FROM invitation WHERE zalo_add_state IN ('WAITING_APPROVAL', 'FAILED')
                          AND status IN ('SENT', 'CLICKED', 'FRIENDED')""").query(Long.class).single();
        return inv + manualCount() + longWaitingCount(groupSettings.alertDays());
    }

    private LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    /** Giờ hiện tại theo đồng hồ của bộ nối (dùng cho test và trang quản trị). */
    public Instant now() {
        return clock.instant();
    }
}
