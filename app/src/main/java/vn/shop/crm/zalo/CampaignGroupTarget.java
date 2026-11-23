package vn.shop.crm.zalo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.invite.InviteService;

/**
 * Nhóm Zalo đích RIÊNG của chiến dịch (D21 PR 6). Chỉ làm CHẶT hơn hệ thống, để trống là theo hệ thống:
 * <ul>
 *   <li>{@code LINK_ONLY}: khách của chiến dịch KHÔNG bị bộ nối tự thêm vào nhóm và KHÔNG được đưa vào nhóm mới do hệ thống tự tạo;
 *       chỉ nhận link nhóm (Tin 2, qua đường gửi chung: giờ gửi, nghỉ cố định, cầu dao) để tự bấm vào;</li>
 *   <li>tắt "cho vào nhóm mới tự tạo": khách vẫn được tự thêm vào nhóm hiện có hợp khu vực, nhưng không bị gom vào nhóm hệ thống tự tạo;</li>
 *   <li>nội dung Tin 2 riêng (phải có link nhóm).</li>
 * </ul>
 * Chưa làm: ghim một nhóm cố định và mẫu tên nhóm riêng của chiến dịch (đề xuất tạo nhóm hiện gom khách của mọi chiến dịch theo khu vực).
 */
@Service
public class CampaignGroupTarget {

    public record Target(boolean linkOnly, boolean allowCreation, String linkTemplate) {
        public static final Target SYSTEM = new Target(false, true, null);

        public boolean any() {
            return linkOnly || !allowCreation || (linkTemplate != null && !linkTemplate.isBlank());
        }
    }

    private final JdbcClient jdbc;
    private final ZaloMessageService messages;

    public CampaignGroupTarget(JdbcClient jdbc, ZaloMessageService messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public Target load(long campaignId) {
        return jdbc.sql("SELECT group_delivery, allow_group_creation, group_link_template FROM invitation_campaign WHERE id = :id")
                .param("id", campaignId)
                .query((rs, n) -> new Target("LINK_ONLY".equals(rs.getString("group_delivery")), rs.getBoolean("allow_group_creation"),
                        rs.getString("group_link_template")))
                .optional().orElse(Target.SYSTEM);
    }

    public void save(long campaignId, Target target, String by) {
        String template = InviteService.checkedGroupLinkTemplate(target.linkTemplate());
        int n = jdbc.sql("""
                        UPDATE invitation_campaign SET group_delivery = :d, allow_group_creation = :c, group_link_template = :t WHERE id = :id""")
                .param("d", target.linkOnly() ? "LINK_ONLY" : null, java.sql.Types.VARCHAR).param("c", target.allowCreation())
                .param("t", template.isEmpty() ? null : template, java.sql.Types.VARCHAR).param("id", campaignId).update();
        if (n == 0) {
            throw new ImportException("Không tìm thấy chiến dịch.");
        }
        messages.audit("NHOM_DICH_CHIEN_DICH", campaignId, by);
    }
}
