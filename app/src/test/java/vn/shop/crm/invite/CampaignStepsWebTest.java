package vn.shop.crm.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

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

/** Ô "Số lần gửi mời" ở trang chiến dịch: cần đăng nhập, lưu cấu hình hợp lệ, từ chối cấu hình sai bằng tiếng Việt, nói rõ tin nhắc chưa được gửi. */
class CampaignStepsWebTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired CampaignSteps steps;
    MockMvc mvc;
    long id;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        id = jdbc.sql("INSERT INTO invitation_campaign (name, message_template, expires_days, created_by) VALUES ('Đợt 1', 'Chào {ten} ạ.', 14, 'test') RETURNING id")
                .query(Long.class).single();
    }

    @Test
    void canDangNhap() throws Exception {
        mvc.perform(post("/campaigns/" + id + "/touches").with(csrf()).param("touches", "2").param("text2", "Nhắn lại ạ").param("gap2", "3"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/campaigns/" + id + "/first-message").with(csrf()).param("template", "x")).andExpect(status().is3xxRedirection());
        assertThat(steps.plan(id).maxTouches()).isEqualTo(1);
    }

    @Test
    void trangHienOSoLanGuiVaDieuKienGuiTinNhac() throws Exception {
        mvc.perform(get("/campaigns/" + id + "?tab=tin").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Số lần gửi mời")))
                .andExpect(content().string(containsString("Tin nhắc chỉ tự gửi khi đủ điều kiện")))
                .andExpect(content().string(containsString("cứ nhắn")))
                .andExpect(content().string(containsString("Mẫu Tin 1 cho chiến dịch gửi nhiều lần")));
    }

    @Test
    void luuHaiLanRoiTrangHienDungNoiDung() throws Exception {
        mvc.perform(post("/campaigns/" + id + "/touches").with(user("admin")).with(csrf()).param("touches", "2")
                        .param("text2", "Em nhắn lại phòng khi mình chưa kịp xem ạ.").param("gap2", "4"))
                .andExpect(redirectedUrl("/campaigns/" + id));
        assertThat(steps.plan(id).maxTouches()).isEqualTo(2);
        mvc.perform(get("/campaigns/" + id + "?tab=tin").with(user("admin")))
                .andExpect(content().string(containsString("Em nhắn lại phòng khi mình chưa kịp xem ạ.")))
                .andExpect(content().string(containsString("value=\"4\"")));
    }

    @Test
    void cauHinhSaiBiTuChoiBangTiengViet() throws Exception {
        mvc.perform(post("/campaigns/" + id + "/touches").with(user("admin")).with(csrf()).param("touches", "2")
                        .param("text2", "Lần cuối cùng ạ").param("gap2", "3"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("cảm giác ép")));
        mvc.perform(post("/campaigns/" + id + "/touches").with(user("admin")).with(csrf()).param("touches", "2")
                        .param("text2", "Nhắn lại ạ").param("gap2", "1"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("từ 3 đến 30 ngày")));
        mvc.perform(post("/campaigns/" + id + "/touches").with(user("admin")).with(csrf()).param("touches", "4"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("từ 1 đến 3")));
        mvc.perform(post("/campaigns/" + id + "/touches").with(user("admin")).with(csrf()).param("touches", "2")
                        .param("text2", "Nhắn lại ạ").param("gap2", "abc"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("phải là một số nguyên")));
        assertThat(steps.plan(id).maxTouches()).isEqualTo(1);
    }

    @Test
    void ngayGapKhongBiChanNhamVaLuuTin1() throws Exception {
        mvc.perform(post("/campaigns/" + id + "/first-message").with(user("admin")).with(csrf()).param("template", "Tin 1 mới ạ"))
                .andExpect(redirectedUrl("/campaigns/" + id));
        assertThat(jdbc.sql("SELECT message_template FROM invitation_campaign WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("Tin 1 mới ạ");
    }
}
