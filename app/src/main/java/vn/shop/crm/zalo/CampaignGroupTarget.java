package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;

/**
 * Nhóm Zalo đích RIÊNG của chiến dịch (D21 PR 6). Chỉ làm CHẶT hơn hệ thống, để trống là theo hệ thống: tắt "cho vào nhóm mới tự tạo"
 * thì khách vẫn được tự thêm vào nhóm hiện có hợp khu vực, nhưng không bị gom vào nhóm hệ thống tự tạo.
 * (Tin 2 gửi link nhóm và chế độ "chỉ gửi link nhóm" đã bỏ 24/11/2026, V47.)
 * Chưa làm: ghim một nhóm cố định và mẫu tên nhóm riêng của chiến dịch (đề xuất tạo nhóm hiện gom khách của mọi chiến dịch theo khu vực).
 */
@Service
public class CampaignGroupTarget {

    public record Target(boolean allowCreation) {
        public static final Target SYSTEM = new Target(true);

        public boolean any() {
            return !allowCreation;
        }
    }

    private final JdbcClient jdbc;
    private final ZaloMessageService messages;

    public CampaignGroupTarget(JdbcClient jdbc, ZaloMessageService messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public Target load(long campaignId) {
        return jdbc.sql("SELECT allow_group_creation FROM invitation_campaign WHERE id = :id")
                .param("id", campaignId)
                .query((rs, n) -> new Target(rs.getBoolean("allow_group_creation")))
                .optional().orElse(Target.SYSTEM);
    }

    public void save(long campaignId, Target target, String by) {
        int n = jdbc.sql("""
                        UPDATE invitation_campaign SET allow_group_creation = :c WHERE id = :id""")
                .param("c", target.allowCreation()).param("id", campaignId).update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
        messages.audit("NHOM_DICH_CHIEN_DICH", campaignId, by);
    }
}
