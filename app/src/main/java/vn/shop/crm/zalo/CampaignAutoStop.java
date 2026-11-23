package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.shop.crm.contact.ContactService;

import java.util.List;

/**
 * Tự dừng một chiến dịch khi khách từ chối nhiều (D21 PR 4 phần còn lại, đề xuất ở docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md mục 5.3a).
 * Mỗi nhịp tính, cho từng chiến dịch đang được duyệt tự gửi: trong số Tin 1 đã tự gửi (từ lần duyệt gần nhất sau một lần tự dừng), bao nhiêu
 * khách nay đang ở trạng thái "đã từ chối" (khách bấm ngừng, nhắn xin ngừng, hay AI ghi từ chối; lời mời đang tạm dừng chờ mẹ xem chưa tính).
 * Đủ tối thiểu {@link #MIN_SENT} tin và tỷ lệ từ ngưỡng thì BỎ DUYỆT chiến dịch (đây là hướng an toàn: dừng tự gửi và tự nhận khách mới ngay),
 * ghi lý do và nhật ký; không tự tiếp tục: người xem lý do rồi duyệt lại bằng tay thì bộ đếm bắt đầu lại từ lúc đó.
 * Ngưỡng chung là {@link #SYSTEM_PCT}; chiến dịch chỉ được đặt thấp hơn. Không xóa gì (khách từ chối vẫn giữ cờ và số điện thoại).
 */
@Service
public class CampaignAutoStop {

    private static final Logger log = LoggerFactory.getLogger(CampaignAutoStop.class);

    /** Ngưỡng chung (phần trăm). Đề xuất của thiết kế; chủ shop chưa chốt nên để hằng số, sửa ở đây khi chốt. */
    public static final int SYSTEM_PCT = 5;
    /** Số Tin 1 tối thiểu đã gửi trước khi xét (ít hơn thì một người từ chối cũng làm tỷ lệ vọt lên vô nghĩa). */
    public static final int MIN_SENT = 20;

    private final JdbcClient jdbc;
    private final ZaloMessageService messages;

    public CampaignAutoStop(JdbcClient jdbc, ZaloMessageService messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    record Stat(long campaignId, Integer ownPct, int sent, int refused) {
        int threshold() {
            return ownPct == null ? SYSTEM_PCT : Math.min(SYSTEM_PCT, ownPct);
        }

        boolean tooHigh() {
            return sent >= MIN_SENT && refused * 100 >= threshold() * sent;
        }
    }

    /** Một nhịp: dừng các chiến dịch vượt ngưỡng. Trả số chiến dịch vừa bị dừng. Lỗi không làm hỏng nhịp gửi. */
    public int evaluate() {
        int stopped = 0;
        try {
            for (Stat s : stats()) {
                if (s.tooHigh() && stop(s)) {
                    stopped++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Kiểm tỷ lệ từ chối gặp lỗi: {}", e.getClass().getSimpleName());
        }
        return stopped;
    }

    List<Stat> stats() {
        return jdbc.sql("""
                        SELECT k.id, k.stop_refusal_pct, count(i.id) AS sent,
                               count(i.id) FILTER (WHERE b.status = 'OPTED_OUT') AS refused
                        FROM invitation_campaign k
                        JOIN invitation i ON i.campaign_id = k.id AND i.sent_channel = :ch AND i.sent_at IS NOT NULL
                             AND i.sent_at >= COALESCE(k.auto_stop_since, '-infinity'::timestamptz)
                        LEFT JOIN contact_basis b ON b.customer_id = i.customer_id AND b.purpose = :p
                        WHERE k.auto_send_approved_at IS NOT NULL
                        GROUP BY k.id, k.stop_refusal_pct""")
                .param("ch", ZaloInviteSender.CHANNEL).param("p", ContactService.PURPOSE)
                .query((rs, n) -> new Stat(rs.getLong("id"), (Integer) rs.getObject("stop_refusal_pct"), rs.getInt("sent"), rs.getInt("refused")))
                .list();
    }

    /** Lý do chiến dịch bị hệ thống tự dừng (chưa ai duyệt lại), để hiện ở trang chiến dịch. */
    public java.util.Optional<String> stoppedReason(long campaignId) {
        return jdbc.sql("SELECT auto_stopped_reason FROM invitation_campaign WHERE id = :id AND auto_stopped_at IS NOT NULL")
                .param("id", campaignId).query(String.class).optional();
    }

    private boolean stop(Stat s) {
        String reason = "Khách từ chối " + s.refused() + "/" + s.sent() + " Tin 1 đã gửi (từ ngưỡng " + s.threshold() + "%)";
        int n = jdbc.sql("""
                        UPDATE invitation_campaign SET auto_send_approved_at = NULL, auto_send_approved_by = NULL,
                               auto_stopped_at = now(), auto_stopped_reason = :r
                        WHERE id = :id AND auto_send_approved_at IS NOT NULL""")
                .param("r", reason).param("id", s.campaignId()).update();
        if (n > 0) {
            messages.audit("TU_DUNG_TU_CHOI_CAO", s.campaignId(), "he-thong");
        }
        return n > 0;
    }
}
