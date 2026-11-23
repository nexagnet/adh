package vn.shop.crm.testrun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.shop.crm.geo.Province;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.zalo.ZaloAutoService;
import vn.shop.crm.zalo.ZaloFriendTap;
import vn.shop.crm.zalo.ZaloGateway;
import vn.shop.crm.zalo.ZaloMessageService;
import vn.shop.crm.zalo.ZaloMessageService.Delivery;
import vn.shop.crm.zalo.ZaloMessenger.Kind;
import vn.shop.crm.zalo.ZaloTickHook;

import java.util.List;
import java.util.Optional;

/**
 * Chạy đợt thử qua ĐÚNG bộ nối thật (D18): Tin 1 → khách đồng ý kết bạn → tự thêm vào nhóm đích → Tin 2. Màn hình tạm thời:
 * xóa gói này (và bảng thử) là xong, phần chiến dịch thật không biết gì về nó (chỉ có hai chỗ cắm {@link ZaloFriendTap} và
 * {@link ZaloTickHook} ở gói zalo).
 *
 * <p>Mọi lần gửi đi qua {@link ZaloMessageService} (công tắc, giờ, hạn mức ngày, khoảng nghỉ cố định, cầu dao dùng CHUNG với chiến
 * dịch thật; hạn mức riêng của đợt chỉ làm chặt hơn), mọi lần thêm nhóm đi qua {@link ZaloAutoService#addExternal}. Mỗi nhịp tối đa
 * MỘT tin và MỘT lần thêm. Số thử không bao giờ ghép với khách thật và không ghi send_log, contact_event hay danh sách từ chối.
 */
@Component
public class TestRunAutomation implements ZaloFriendTap, ZaloTickHook {

    private static final Logger log = LoggerFactory.getLogger(TestRunAutomation.class);

    private record RunRow(long id, String template, String linkTemplate, int dailyLimit, int gapSeconds, Long groupId, boolean sendLink) {
    }

    private record Target(long id, String phone, String name, String province, String uid) {
    }

    private final JdbcClient jdbc;
    private final ZaloMessageService messages;
    private final ZaloAutoService zalo;
    private final GroupService groups;
    private final InviteService invites;

    public TestRunAutomation(JdbcClient jdbc, ZaloMessageService messages, ZaloAutoService zalo, GroupService groups, InviteService invites) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.zalo = zalo;
        this.groups = groups;
        this.invites = invites;
    }

    /** Tin thử không có link riêng (không có lời mời thật): bỏ chỗ {link} thay vì gửi một link không dùng được. */
    static String renderInvite(InviteService invites, String template, String name, Province province) {
        return invites.render(template, name, "", province).replaceAll("[ \\t]{2,}", " ").strip();
    }

    // ---------- khách đồng ý kết bạn ----------

    @Override
    public boolean accept(ZaloGateway.FriendEvent e) {
        Long n = jdbc.sql("SELECT count(*) FROM test_target WHERE zalo_uid = :u").param("u", e.uid()).query(Long.class).single();
        if (n == 0) {
            return false;
        }
        jdbc.sql("UPDATE test_target SET status = 'FRIENDED', friended_at = now() WHERE zalo_uid = :u AND status = 'SENT'")
                .param("u", e.uid()).update();
        return true;
    }

    // ---------- mỗi nhịp ----------

    @Override
    public void tick() {
        List<RunRow> runs = jdbc.sql("""
                        SELECT id, message_template, link_template, daily_limit, gap_seconds, zalo_group_id, send_link
                        FROM test_run WHERE running ORDER BY id""")
                .query((rs, n) -> new RunRow(rs.getLong("id"), rs.getString("message_template"), rs.getString("link_template"),
                        rs.getInt("daily_limit"), rs.getInt("gap_seconds"), (Long) rs.getObject("zalo_group_id"), rs.getBoolean("send_link")))
                .list();
        boolean added = false;
        boolean messaged = false;
        for (RunRow run : runs) {
            if (!added) {
                added = tryAdd(run);
            }
            if (!messaged) {
                messaged = tryMessage(run);
            }
        }
    }

    private static String scope(RunRow run) {
        return "TEST:" + run.id();
    }

    /** Thêm một số thử đã kết bạn vào nhóm đích. Trả true nếu đã dùng một lượt thêm (kể cả lỗi). */
    private boolean tryAdd(RunRow run) {
        if (run.groupId() == null) {
            return false;
        }
        Optional<Target> t = target(run.id(), "status = 'FRIENDED' AND zalo_uid IS NOT NULL AND add_state IS NULL");
        Optional<GroupService.Group> group = groups.find(run.groupId());
        if (t.isEmpty() || group.isEmpty()) {
            return false;
        }
        ZaloAutoService.ExternalAdd r = zalo.addExternal(t.get().uid(), group.get());
        switch (r.step()) {
            case ADDED -> {
                // KHÔNG gọi markJoined/addMember: số khách thật của nhóm không tăng
                jdbc.sql("""
                                UPDATE test_target SET status = 'JOINED', add_state = 'ADDED', group_id = :g, joined_at = now(),
                                                       last_code = NULL WHERE id = :id""")
                        .param("g", group.get().id()).param("id", t.get().id()).update();
                return true;
            }
            case PERSON_FAILED, TRIPPED -> {
                jdbc.sql("UPDATE test_target SET add_state = 'FAILED', last_code = :c WHERE id = :id")
                        .param("c", r.code() == null ? "KHONG_RO" : r.code()).param("id", t.get().id()).update();
                return true;
            }
            default -> {
                return false; // chưa tới lượt (hạn mức, giờ, bộ nối chưa sẵn sàng): thử lại nhịp sau
            }
        }
    }

    /** Gửi một tin (Tin 2 trước, rồi Tin 1). Trả true nếu đã dùng một lượt gửi. */
    private boolean tryMessage(RunRow run) {
        if (messages.blocker(scope(run), vn.shop.crm.zalo.ZaloMessenger.Kind.INVITE, run.dailyLimit(), run.gapSeconds()).isPresent()) {
            return false;
        }
        if (run.sendLink() && run.groupId() != null) {
            Optional<Target> t = target(run.id(), "status IN ('FRIENDED', 'JOINED') AND zalo_uid IS NOT NULL AND add_state IS NOT NULL AND link_state IS NULL");
            if (t.isPresent()) {
                return sendLink(run, t.get());
            }
        }
        Optional<Target> t = target(run.id(), "status = 'READY' AND last_code IS NULL");
        return t.isPresent() && sendInvite(run, t.get());
    }

    private Optional<Target> target(long runId, String cond) {
        return jdbc.sql("SELECT id, phone, name, province, zalo_uid FROM test_target WHERE run_id = :r AND " + cond + " ORDER BY id LIMIT 1")
                .param("r", runId)
                .query((rs, n) -> new Target(rs.getLong("id"), rs.getString("phone"), rs.getString("name"), rs.getString("province"),
                        rs.getString("zalo_uid")))
                .optional();
    }

    private boolean sendInvite(RunRow run, Target t) {
        // khóa số này trước khi gọi: sập giữa chừng hay hai nhịp chồng nhau không gửi hai lần
        int claimed = jdbc.sql("UPDATE test_target SET last_code = 'DANG_GUI' WHERE id = :id AND status = 'READY' AND last_code IS NULL")
                .param("id", t.id()).update();
        if (claimed == 0) {
            return false;
        }
        Province province = VnGeo.byCode(t.province()).orElse(null);
        String text = renderInvite(invites, run.template(), t.name(), province);
        Delivery d = messages.sendByPhone(scope(run), Kind.INVITE, t.id(), t.phone().replaceAll("\\D", ""), text, run.dailyLimit(), run.gapSeconds());
        switch (d.status()) {
            case SENT -> {
                markSent(t.id(), d.uid(), null);
                return true;
            }
            case PERSON_ERROR -> {
                if ("DUPLICATE".equals(d.code())) {
                    // bộ nối nhớ đã gửi Tin 1 cho người này (đợt thử trước): không gửi lại, đi tiếp các bước sau
                    markSent(t.id(), d.uid(), "DA_GUI_TRUOC");
                } else {
                    jdbc.sql("UPDATE test_target SET status = 'FAILED', last_code = :c WHERE id = :id")
                            .param("c", d.code() == null ? "LOI_NGUOI" : d.code()).param("id", t.id()).update();
                }
                return true;
            }
            default -> {
                if (d.certainlyNotSent()) {
                    jdbc.sql("UPDATE test_target SET last_code = NULL WHERE id = :id AND last_code = 'DANG_GUI'").param("id", t.id()).update();
                } else {
                    jdbc.sql("UPDATE test_target SET status = 'FAILED', last_code = 'KHONG_RO' WHERE id = :id").param("id", t.id()).update();
                }
                return d.status() == ZaloMessageService.Status.TRIPPED && !d.certainlyNotSent();
            }
        }
    }

    private void markSent(long id, String uid, String code) {
        jdbc.sql("UPDATE test_target SET status = 'SENT', sent_at = now(), zalo_uid = :u, last_code = :c WHERE id = :id")
                .param("u", uid, java.sql.Types.VARCHAR).param("c", code, java.sql.Types.VARCHAR).param("id", id).update();
    }

    private boolean sendLink(RunRow run, Target t) {
        GroupService.Group group = groups.find(run.groupId()).orElse(null);
        if (group == null || !group.hasLink()) {
            jdbc.sql("UPDATE test_target SET link_state = 'FAILED', last_code = 'KHONG_CO_LINK' WHERE id = :id").param("id", t.id()).update();
            return false;
        }
        int claimed = jdbc.sql("UPDATE test_target SET link_state = 'FAILED', last_code = 'DANG_GUI' WHERE id = :id AND link_state IS NULL")
                .param("id", t.id()).update();
        if (claimed == 0) {
            return false;
        }
        String text = invites.renderGroupLink(run.linkTemplate(), t.name(), group.name(), group.inviteLink(), "")
                .replaceAll("[ \\t]{2,}", " ").strip();
        Delivery d = messages.sendByUid(scope(run), Kind.LINK, t.id(), t.uid(), text, run.dailyLimit(), run.gapSeconds());
        switch (d.status()) {
            case SENT -> {
                jdbc.sql("UPDATE test_target SET link_state = 'SENT', last_code = NULL WHERE id = :id").param("id", t.id()).update();
                return true;
            }
            case PERSON_ERROR -> {
                boolean dup = "DUPLICATE".equals(d.code());
                jdbc.sql("UPDATE test_target SET link_state = :s, last_code = :c WHERE id = :id").param("s", dup ? "SENT" : "FAILED")
                        .param("c", dup ? "DA_GUI_TRUOC" : d.code() == null ? "LOI_NGUOI" : d.code()).param("id", t.id()).update();
                return true;
            }
            default -> {
                if (d.certainlyNotSent()) {
                    jdbc.sql("UPDATE test_target SET link_state = NULL, last_code = NULL WHERE id = :id AND last_code = 'DANG_GUI'")
                            .param("id", t.id()).update();
                    return false;
                }
                jdbc.sql("UPDATE test_target SET link_state = 'FAILED', last_code = 'KHONG_RO' WHERE id = :id").param("id", t.id()).update();
                return true;
            }
        }
    }
}
