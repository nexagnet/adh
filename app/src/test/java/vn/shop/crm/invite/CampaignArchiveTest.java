package vn.shop.crm.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.zalo.ZaloInviteSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lưu trữ chiến dịch cũ (V53): bỏ duyệt tự gửi, tắt tự nhận, giữ kết quả, không xóa khách, khôi phục không tự duyệt lại. */
class CampaignArchiveTest extends AbstractPostgresTest {

    @Autowired CampaignArchive archive;
    @Autowired InviteService invites;
    @Autowired ContactService contacts;
    @Autowired ZaloInviteSender sender;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    long id;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (int i = 0; i < 4; i++) {
            long c = jdbc.sql("""
                    INSERT INTO customer (phone, name, address, segment, successful_purchases, province, last_order_on)
                    VALUES (:p, :n, 'So 1 Duong', 'ONE_TIME', 1, 'tphcm', now() - interval '20 days') RETURNING id""")
                    .param("p", String.format("+84900003%03d", i)).param("n", "Khach " + i).query(Long.class).single();
            contacts.approve(c);
        }
        id = invites.createCampaign("Chiến dịch cũ", null, 14, 4, "P:tphcm");
        // 1 khách đã nhắn và kết bạn, 3 chưa gửi
        jdbc.sql("""
                UPDATE invitation SET status = 'SENT', sent_at = now(), friended_at = now()
                WHERE id = (SELECT min(id) FROM invitation WHERE campaign_id = :id)""").param("id", id).update();
        sender.approveCampaign(id, "admin");
        jdbc.sql("UPDATE invitation_campaign SET auto_intake_since = now(), auto_intake_by = 'admin' WHERE id = :id").param("id", id).update();
    }

    private long invitations() {
        return jdbc.sql("SELECT count(*) FROM invitation WHERE campaign_id = :id").param("id", id).query(Long.class).single();
    }

    @Test
    void luuTruBoDuyetVaTatTuNhanNhungGiuNguyenLoiMoiVaKhach() {
        int cancelled = archive.archive(id, false, "admin");
        assertThat(cancelled).isZero();
        assertThat(archive.archived(id)).isTrue();
        assertThat(sender.approved(id)).isFalse();
        assertThat(jdbc.sql("SELECT auto_intake_since IS NULL FROM invitation_campaign WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();
        assertThat(invitations()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(4);
        // lưu trữ lần hai không làm gì thêm
        assertThat(archive.archive(id, true, "admin")).isZero();
        assertThat(invitations()).isEqualTo(4);
    }

    @Test
    void coTheHuyLuonLoiMoiChuaGuiNhungGiuLoiMoiDaGui() {
        assertThat(archive.archive(id, true, "admin")).isEqualTo(3);
        assertThat(invitations()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE campaign_id = :id AND friended_at IS NOT NULL").param("id", id)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void khongDuyetTuGuiDuocChoChienDichDaLuuTruVaKhoiPhucKhongTuDuyetLai() {
        archive.archive(id, false, "admin");
        assertThatThrownBy(() -> sender.approveCampaign(id, "admin")).isInstanceOf(ImportException.class)
                .hasMessageContaining("lưu trữ");
        assertThat(sender.approved(id)).isFalse();

        archive.restore(id, "admin");
        assertThat(archive.archived(id)).isFalse();
        assertThat(sender.approved(id)).isFalse();
        sender.approveCampaign(id, "admin");
        assertThat(sender.approved(id)).isTrue();
    }

    @Test
    void khongTimThayChienDichThiBaoLoi() {
        assertThatThrownBy(() -> archive.archive(9999, false, "admin")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> archive.restore(9999, "admin")).isInstanceOf(ImportException.class);
    }

    @Test
    void trangDanhSachAnChienDichLuuTruMacDinhVaChoXemKhiBam() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(post("/campaigns/{id}/archive", id).with(owner).with(csrf()).param("cancelUnsent", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/campaigns/" + id));
        assertThat(archive.archived(id)).isTrue();
        assertThat(invitations()).isEqualTo(1);

        mvc.perform(get("/campaigns").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Chiến dịch cũ"))))
                .andExpect(content().string(containsString("Xem cả chiến dịch đã lưu trữ")));
        mvc.perform(get("/campaigns").param("luutru", "true").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chiến dịch cũ")))
                .andExpect(content().string(containsString("Đã lưu trữ")));
        mvc.perform(get("/campaigns/{id}", id).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Khôi phục chiến dịch")))
                .andExpect(content().string(not(containsString("Duyệt cho hệ thống tự gửi"))));

        mvc.perform(post("/campaigns/{id}/restore", id).with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(archive.archived(id)).isFalse();
        mvc.perform(get("/campaigns/{id}", id).with(owner)).andExpect(content().string(containsString("Lưu trữ chiến dịch")));
    }

    @Test
    void luuTruCanDangNhapVaCsrf() throws Exception {
        mvc.perform(post("/campaigns/{id}/archive", id).with(csrf())).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(post("/campaigns/{id}/archive", id).with(user("admin").roles("OWNER"))).andExpect(status().isForbidden());
        assertThat(archive.archived(id)).isFalse();
    }
}
