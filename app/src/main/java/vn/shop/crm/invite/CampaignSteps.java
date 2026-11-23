package vn.shop.crm.invite;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.zalo.ZaloMessageService;

import java.util.ArrayList;
import java.util.List;

/**
 * Chuỗi tin mời của chiến dịch (D21, thiết kế mục 5.3): Tin 1 và tối đa hai tin nhắc. PR 5a: chỉ LƯU và KIỂM cấu hình; chưa có gì gửi tin nhắc
 * (bộ gửi và bộ nối làm ở PR 5b). Mặc định 1 lần, đúng như D18. Trần cứng {@link #MAX_TOUCHES} lần mỗi khách ghi cả trong mã lẫn trong cơ sở dữ liệu;
 * khoảng cách tối thiểu {@link #MIN_GAP_DAYS} ngày; nội dung tin nhắc qua {@link FollowupValidator}.
 */
@Service
public class CampaignSteps {

    /** Trần cứng số lần nhắn chủ động tới một khách (Tin 1 và các tin nhắc). Không chỉnh được ở giao diện hay biến môi trường. */
    public static final int MAX_TOUCHES = 3;
    public static final int MIN_GAP_DAYS = 3;
    public static final int MAX_GAP_DAYS = 30;
    /** Câu xin ngừng nhận tin do MÃ tự thêm vào cuối mỗi lần gửi của chiến dịch nhiều lần (PR 5b), không phụ thuộc người soạn có nhớ hay không. */
    public static final String STOP_FOOTER = "Anh/chị không muốn nhận tin nữa cứ nhắn \"dừng\" ạ.";

    /** Một tin nhắc: {@code stepNo} là 2 hoặc 3; {@code gapDays} là số ngày tối thiểu kể từ lần gửi thành công liền trước. */
    public record Step(int stepNo, String template, int gapDays) {
    }

    public record Plan(int maxTouches, List<Step> followups) {
        public boolean multiple() {
            return maxTouches > 1;
        }

        public Step step(int no) {
            return followups.stream().filter(s -> s.stepNo() == no).findFirst().orElse(null);
        }
    }

    private final JdbcClient jdbc;
    private final ZaloMessageService messages;

    public CampaignSteps(JdbcClient jdbc, ZaloMessageService messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public Plan plan(long campaignId) {
        int max = jdbc.sql("SELECT max_touches FROM invitation_campaign WHERE id = :id").param("id", campaignId)
                .query(Integer.class).optional().orElse(1);
        List<Step> steps = jdbc.sql("SELECT step_no, template, gap_days FROM campaign_step WHERE campaign_id = :id ORDER BY step_no")
                .param("id", campaignId).query((rs, n) -> new Step(rs.getInt(1), rs.getString(2), rs.getInt(3))).list();
        return new Plan(max, steps);
    }

    /**
     * Đặt các tin nhắc của chiến dịch. {@code followups} theo thứ tự lần gửi 2, 3 (tối đa {@code MAX_TOUCHES - 1}); danh sách rỗng = chỉ gửi 1 lần.
     * Số lần gửi = 1 + số tin nhắc. Lỗi ở bất kỳ tin nào thì không lưu gì. Chiến dịch từ 2 lần trở lên cần Tin 1 không còn câu "sẽ không nhắn lại".
     */
    @Transactional
    public void saveFollowups(long campaignId, List<Step> followups, String by) {
        String first = lockFirstMessage(campaignId);
        if (followups.size() > MAX_TOUCHES - 1) {
            throw new ImportException("Mỗi khách nhận tối đa " + MAX_TOUCHES + " lần nhắn (Tin 1 và " + (MAX_TOUCHES - 1) + " tin nhắc).");
        }
        List<Step> clean = new ArrayList<>();
        String previous = first;
        int no = 2;
        for (Step s : followups) {
            if (s.gapDays() < MIN_GAP_DAYS || s.gapDays() > MAX_GAP_DAYS) {
                throw new ImportException("Lần gửi " + no + ": khoảng cách với lần trước phải từ " + MIN_GAP_DAYS + " đến " + MAX_GAP_DAYS + " ngày.");
            }
            String tpl = s.template() == null ? "" : s.template().strip();
            var problem = FollowupValidator.checkFollowup(tpl, previous);
            if (problem.isPresent()) {
                throw new ImportException("Lần gửi " + no + ": " + problem.get());
            }
            clean.add(new Step(no, tpl, s.gapDays()));
            previous = tpl;
            no++;
        }
        if (!clean.isEmpty()) {
            FollowupValidator.checkFirstForMultiple(first).ifPresent(m -> {
                throw new ImportException(m);
            });
        }
        jdbc.sql("DELETE FROM campaign_step WHERE campaign_id = :id").param("id", campaignId).update();
        for (Step s : clean) {
            jdbc.sql("INSERT INTO campaign_step (campaign_id, step_no, template, gap_days) VALUES (:id, :no, :t, :g)")
                    .param("id", campaignId).param("no", s.stepNo()).param("t", s.template()).param("g", s.gapDays()).update();
        }
        jdbc.sql("UPDATE invitation_campaign SET max_touches = :n WHERE id = :id").param("n", 1 + clean.size()).param("id", campaignId).update();
        messages.audit("SO_LAN_GUI_" + (1 + clean.size()), campaignId, by);
    }

    /**
     * Sửa nội dung Tin 1 của chiến dịch (cần khi chuyển sang gửi nhiều lần). Chỉ được khi CHƯA gửi Tin 1 cho khách nào của chiến dịch này,
     * để tin đã gửi và tin sẽ gửi không lệch nhau. Nếu chiến dịch đang gửi nhiều lần thì Tin 1 mới phải không còn câu "sẽ không nhắn lại"
     * và tin nhắc đầu tiên phải khác Tin 1 mới.
     */
    @Transactional
    public void saveFirstMessage(long campaignId, String template, String by) {
        lockFirstMessage(campaignId);
        String tpl = template == null ? "" : template.strip();
        if (tpl.isEmpty()) {
            throw new ImportException("Nội dung Tin 1 không được để trống.");
        }
        if (tpl.length() > InviteService.MAX_TEMPLATE) {
            throw new ImportException("Nội dung tin quá dài (tối đa " + InviteService.MAX_TEMPLATE + " ký tự).");
        }
        long sent = jdbc.sql("SELECT count(*) FROM invitation WHERE campaign_id = :id AND sent_at IS NOT NULL").param("id", campaignId)
                .query(Long.class).single();
        if (sent > 0) {
            throw new ImportException("Tin 1 đã gửi cho " + sent + " khách của chiến dịch này nên không đổi nội dung được nữa.");
        }
        Plan plan = plan(campaignId);
        if (plan.multiple()) {
            FollowupValidator.checkFirstForMultiple(tpl).ifPresent(m -> {
                throw new ImportException(m);
            });
            Step second = plan.step(2);
            if (second != null && FollowupValidator.fold(second.template()).equals(FollowupValidator.fold(tpl))) {
                throw new ImportException("Tin 1 không được trùng nội dung tin nhắc lần 2.");
            }
        }
        jdbc.sql("UPDATE invitation_campaign SET message_template = :t WHERE id = :id").param("t", tpl).param("id", campaignId).update();
        messages.audit("SUA_TIN_1", campaignId, by);
    }

    /** Khóa dòng chiến dịch (hai lần lưu cùng lúc không đè nhau) và trả nội dung Tin 1 hiện tại. */
    private String lockFirstMessage(long campaignId) {
        return jdbc.sql("SELECT message_template FROM invitation_campaign WHERE id = :id FOR UPDATE").param("id", campaignId)
                .query(String.class).optional().orElseThrow(() -> new ImportException("Không tìm thấy chiến dịch."));
    }
}
