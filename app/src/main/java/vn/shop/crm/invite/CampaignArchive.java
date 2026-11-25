package vn.shop.crm.invite;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.zalo.ZaloMessageService;

/**
 * Lưu trữ chiến dịch cũ (25/11/2026, V53). Lưu trữ = "chiến dịch này đã xong hoặc không còn hiệu lực": hệ thống bỏ duyệt tự gửi, tắt tự nhận khách mới
 * và AI đề xuất chiến dịch không còn coi nó là đang chạy (xem {@code AdvisorSnapshotService}). Không xóa chiến dịch, lời mời, khách hay số điện thoại.
 * Tùy chọn hủy các lời mời CHƯA gửi để khách quay về danh sách "chưa được mời" (giống nút "Hủy các lời mời chưa gửi").
 * Lớp này không gọi Zalo và không gửi tin nào.
 */
@Service
public class CampaignArchive {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ZaloMessageService messages;

    public CampaignArchive(JdbcClient jdbc, TransactionTemplate tx, ZaloMessageService messages) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.messages = messages;
    }

    /** @return số lời mời chưa gửi đã hủy (0 nếu không chọn hủy hoặc chiến dịch đã lưu trữ từ trước) */
    public int archive(long campaignId, boolean cancelUnsent, String by) {
        if (!exists(campaignId)) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
        Integer cancelled = tx.execute(status -> {
            int n = jdbc.sql("""
                            UPDATE invitation_campaign SET archived_at = now(), archived_by = :by,
                                   auto_send_approved_at = NULL, auto_send_approved_by = NULL,
                                   auto_intake_since = NULL, auto_intake_by = NULL
                            WHERE id = :id AND archived_at IS NULL""").param("by", by).param("id", campaignId).update();
            if (n == 0) {
                return 0;
            }
            return cancelUnsent
                    ? jdbc.sql("DELETE FROM invitation WHERE campaign_id = :id AND status = 'READY'").param("id", campaignId).update()
                    : 0;
        });
        messages.audit("LUU_TRU_CHIEN_DICH", campaignId, by);
        return cancelled == null ? 0 : cancelled;
    }

    /** Khôi phục: chỉ bỏ dấu lưu trữ. Duyệt tự gửi và tự nhận khách mới KHÔNG tự bật lại: chủ shop duyệt lại nếu muốn. */
    public void restore(long campaignId, String by) {
        if (!exists(campaignId)) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
        int n = jdbc.sql("UPDATE invitation_campaign SET archived_at = NULL, archived_by = NULL WHERE id = :id AND archived_at IS NOT NULL")
                .param("id", campaignId).update();
        if (n > 0) {
            messages.audit("KHOI_PHUC_CHIEN_DICH", campaignId, by);
        }
    }

    public boolean archived(long campaignId) {
        return jdbc.sql("SELECT archived_at IS NOT NULL FROM invitation_campaign WHERE id = :id").param("id", campaignId)
                .query(Boolean.class).optional().orElse(false);
    }

    private boolean exists(long id) {
        return jdbc.sql("SELECT count(*) FROM invitation_campaign WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }
}
