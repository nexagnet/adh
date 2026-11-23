package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ô "Lịch và nhịp riêng" ở trang chiến dịch: cần đăng nhập, lưu giá trị chặt hơn, từ chối giá trị nới hơn hệ thống, xóa về theo hệ thống. */
class CampaignScheduleWebTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired CampaignPolicy policy;
    MockMvc mvc;
    long id;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        id = jdbc.sql("INSERT INTO invitation_campaign (name, message_template, expires_days, created_by) VALUES ('Đợt 1', 't', 14, 'test') RETURNING id")
                .query(Long.class).single();
    }

    @Test
    void moiTabChiHienPhanCuaMinhVaTabLaThiVeTongQuan() throws Exception {
        // Mặc định và tab lạ: Tổng quan (không có ô cấu hình riêng), bốn tab còn lại mỗi tab một phần.
        mvc.perform(get("/campaigns/" + id).with(user("admin"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tổng quan")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Lịch và nhịp riêng của chiến dịch"))));
        mvc.perform(get("/campaigns/" + id + "?tab=khong-co").with(user("admin"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Lịch và nhịp riêng của chiến dịch"))));
        mvc.perform(get("/campaigns/" + id + "?tab=lich").with(user("admin"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Lịch và nhịp riêng của chiến dịch")))
                .andExpect(content().string(containsString("Điều kiện gửi riêng")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Nhóm Zalo của chiến dịch"))));
        mvc.perform(get("/campaigns/" + id + "?tab=nhom").with(user("admin"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Nhóm Zalo của chiến dịch")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Điều kiện gửi riêng"))));
        mvc.perform(get("/campaigns/" + id + "?tab=tin").with(user("admin"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Số lần gửi mời")));
    }

    @Test
    void canDangNhap() throws Exception {
        mvc.perform(post("/campaigns/" + id + "/schedule").with(csrf()).param("dailyCap", "1")).andExpect(status().is3xxRedirection());
        assertThat(policy.load(id)).isEqualTo(CampaignPolicy.Raw.NONE);
    }

    @Test
    void luuGiaTriChatHonRoiTrangHienLaiDungGiaTri() throws Exception {
        mvc.perform(post("/campaigns/" + id + "/schedule").with(user("admin")).with(csrf())
                        .param("startOn", "2099-01-02").param("endOn", "2099-01-09").param("windowFrom", "09:00").param("windowTo", "11:30")
                        .param("dailyCap", "3").param("gapSeconds", "300"))
                .andExpect(redirectedUrl("/campaigns/" + id));
        var raw = policy.load(id);
        assertThat(raw.windowFrom()).isEqualTo(LocalTime.of(9, 0));
        assertThat(raw.windowTo()).isEqualTo(LocalTime.of(11, 30));
        assertThat(raw.dailyCap()).isEqualTo(3);
        assertThat(raw.gapSeconds()).isEqualTo(300);
        mvc.perform(get("/campaigns/" + id + "?tab=lich").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lịch và nhịp riêng của chiến dịch")))
                .andExpect(content().string(containsString("value=\"2099-01-02\"")))
                .andExpect(content().string(containsString("value=\"09:00\"")))
                .andExpect(content().string(containsString("Chưa tới ngày bắt đầu")));
    }

    @Test
    void giaTriNoiHonHeThongBiTuChoiBangTiengVietVaKhongLuu() throws Exception {
        mvc.perform(post("/campaigns/" + id + "/schedule").with(user("admin")).with(csrf()).param("windowFrom", "05:00").param("windowTo", "10:00"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("không được nới")));
        mvc.perform(post("/campaigns/" + id + "/schedule").with(user("admin")).with(csrf()).param("dailyCap", "999"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("không được cao hơn")));
        mvc.perform(post("/campaigns/" + id + "/schedule").with(user("admin")).with(csrf()).param("startOn", "khong-phai-ngay"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Ngày bắt đầu không đúng")));
        mvc.perform(post("/campaigns/" + id + "/schedule").with(user("admin")).with(csrf()).param("dailyCap", "abc"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("phải là một số nguyên")));
        assertThat(policy.load(id)).isEqualTo(CampaignPolicy.Raw.NONE);
    }

    @Test
    void nutTheoHeThongXoaGiaTriRieng() throws Exception {
        mvc.perform(post("/campaigns/" + id + "/schedule").with(user("admin")).with(csrf()).param("dailyCap", "2"));
        assertThat(policy.load(id).dailyCap()).isEqualTo(2);
        mvc.perform(post("/campaigns/" + id + "/schedule").with(user("admin")).with(csrf()).param("reset", "true").param("dailyCap", "2"))
                .andExpect(redirectedUrl("/campaigns/" + id));
        assertThat(policy.load(id)).isEqualTo(CampaignPolicy.Raw.NONE);
    }

    @Test
    void chienDichKhongCoThiBaoLoi() throws Exception {
        mvc.perform(post("/campaigns/999999/schedule").with(user("admin")).with(csrf()).param("dailyCap", "1"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Không tìm thấy chiến dịch")));
    }
}
