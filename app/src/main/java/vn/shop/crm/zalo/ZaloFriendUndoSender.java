package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.zalo.ZaloMessageService.Delivery;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Tự THU HỒI lời mời kết bạn đã gửi mà quá N ngày chưa ai đồng ý (25/11/2026, chủ dự án duyệt; mặc định 7 ngày, chỉnh ở {@code /zalo}, 0 là tắt) để danh sách
 * lời mời đang chờ của Zalo (có hạn, báo Z223 khi đầy) luôn còn chỗ. NƠI DUY NHẤT dùng {@code Kind.UNDO_FRIEND}. Không nhắn gì cho khách, không đọc gì.
 *
 * <p>Mỗi nhịp tối đa MỘT lần thu hồi, qua {@link ZaloMessageService} (công tắc, khung giờ, khoảng nghỉ cố định chung, cầu dao dùng chung, trần thu hồi mỗi ngày
 * riêng) rồi bộ nối (chỉ lời mời do CHÍNH bộ nối đã gửi, mỗi người một lần). Chỉ lời mời có kết quả "đã gửi" (SENT), khách chưa kết bạn, chưa vào nhóm; chế độ thử
 * chỉ chạm số thử. Ghi dấu {@code friend_undo_at} TRƯỚC khi gọi (UNKNOWN): không rõ kết quả thì KHÔNG bao giờ tự thu hồi lại. Thu hồi xong đặt kết quả lời mời là
 * WITHDRAWN (không xóa khách hay số) và bỏ tạm dừng lời mời kết bạn nếu có (vì đã có chỗ). Ba lần Zalo từ chối liên tiếp trong một ngày thì dừng đến mai.
 * Không có số ngẫu nhiên.
 */
@Service
public class ZaloFriendUndoSender {

    private static final Logger log = LoggerFactory.getLogger(ZaloFriendUndoSender.class);
    /** Số lần Zalo từ chối thu hồi trong một ngày thì dừng đến ngày mai (nhiều khả năng cách làm này không hợp, để người xem). */
    static final int MAX_FAILED_PER_DAY = 3;

    public enum Step { NOT_ACTIVE, BLOCKED, NOTHING, DONE, FAILED_ONE, RETRY, TRIPPED }

    private record Task(long invitationId, String uid) {
    }

    private final JdbcClient jdbc;
    private final AppSettings settings;
    private final ZaloMessageService messages;
    private final Clock clock;
    private final TestMode testMode;

    public ZaloFriendUndoSender(JdbcClient jdbc, AppSettings settings, ZaloMessageService messages, Clock clock, TestMode testMode) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.messages = messages;
        this.clock = clock;
        this.testMode = testMode;
    }

    /** Một nhịp: thu hồi tối đa MỘT lời mời đã quá hạn chờ. */
    public Step step() {
        if (!messages.enabled() || settings.paused()) {
            return Step.NOT_ACTIVE;
        }
        int days = messages.friendWithdrawDays();
        if (days <= 0 || failedToday() >= MAX_FAILED_PER_DAY) {
            return Step.NOTHING;
        }
        if (messages.blocker(ZaloMessageService.SCOPE_REAL, ZaloMessenger.Kind.UNDO_FRIEND, null, null).isPresent()) {
            return Step.BLOCKED;
        }
        List<Task> due = due(days);
        return due.isEmpty() ? Step.NOTHING : send(due.get(0), days);
    }

    private long failedToday() {
        OffsetDateTime start = clock.instant().atZone(clock.getZone()).toLocalDate().atStartOfDay(clock.getZone()).toOffsetDateTime();
        return jdbc.sql("SELECT count(*) FROM invitation WHERE " + ZaloAccountContext.sql("account_id") + " AND friend_undo_outcome = 'FAILED' AND friend_undo_at >= :s")
                .param("s", start).query(Long.class).single();
    }

    private List<Task> due(int days) {
        return jdbc.sql("""
                        SELECT i.id, i.zalo_target_uid
                        FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.friend_request_outcome = 'SENT' AND i.friended_at IS NULL AND i.joined_at IS NULL AND i.friend_undo_at IS NULL
                          AND i.zalo_target_uid IS NOT NULL AND i.friend_request_at < :before
                          AND
                        """ + ZaloFriendReconciliation.STILL_MAY_BE_PENDING + testMode.onlyTestSql("c.phone") + " AND "
                + ZaloAccountContext.sql("i.account_id") + " ORDER BY i.friend_request_at, i.id LIMIT 1")
                .param("before", OffsetDateTime.now(clock).minusDays(days))
                .query((rs, n) -> new Task(rs.getLong("id"), rs.getString("zalo_target_uid"))).list();
    }

    private Step send(Task t, int days) {
        int claimed = jdbc.sql("""
                        UPDATE invitation i SET friend_undo_at = :at, friend_undo_outcome = 'UNKNOWN'
                        WHERE i.id = :id AND i.friend_undo_at IS NULL AND i.friend_request_outcome = 'SENT'
                          AND i.friended_at IS NULL AND i.joined_at IS NULL AND
                        """ + ZaloFriendReconciliation.STILL_MAY_BE_PENDING + " AND " + ZaloAccountContext.sql("i.account_id"))
                .param("id", t.invitationId()).param("at", OffsetDateTime.now(clock)).update();
        if (claimed == 0) {
            return Step.NOTHING;
        }
        Delivery d;
        try {
            d = messages.undoFriendRequest(ZaloMessageService.SCOPE_REAL, t.invitationId(), t.uid());
        } catch (RuntimeException e) {
            log.warn("Thu hồi lời mời kết bạn báo lỗi: {}", e.getClass().getSimpleName());
            return Step.TRIPPED; // dòng ở lại UNKNOWN, không thu hồi lại
        }
        switch (d.status()) {
            case SENT -> {
                jdbc.sql("UPDATE invitation SET friend_undo_outcome = 'DONE', friend_request_outcome = 'WITHDRAWN' WHERE id = :id")
                        .param("id", t.invitationId()).update();
                messages.audit("THU_HOI_LOI_MOI_QUA_" + days + "_NGAY", t.invitationId(), "system");
                // đã có chỗ trong danh sách lời mời: bỏ tạm dừng gửi lời mời (nếu Zalo từng báo đầy)
                messages.resumeFriendRequests("system");
                return Step.DONE;
            }
            case PERSON_ERROR -> {
                jdbc.sql("UPDATE invitation SET friend_undo_outcome = 'FAILED' WHERE id = :id").param("id", t.invitationId()).update();
                messages.audit("THU_HOI_LOI_MOI_LOI_" + (d.code() == null ? "LOI" : d.code()), t.invitationId(), "system");
                return Step.FAILED_ONE;
            }
            case RETRY, BLOCKED -> {
                release(t);
                return Step.RETRY;
            }
            default -> {
                if (d.certainlyNotSent()) {
                    release(t);
                }
                return Step.TRIPPED;
            }
        }
    }

    /** Chỉ dùng khi CHẮC CHẮN chưa chạm Zalo: trả chỗ để thử lại. */
    private void release(Task t) {
        jdbc.sql("UPDATE invitation SET friend_undo_at = NULL, friend_undo_outcome = NULL WHERE id = :id AND friend_undo_outcome = 'UNKNOWN'")
                .param("id", t.invitationId()).update();
    }
}
