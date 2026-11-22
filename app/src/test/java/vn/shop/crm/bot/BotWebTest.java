package vn.shop.crm.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.invite.GroupService;

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

class BotWebTest extends AbstractPostgresTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    GroupService groups;
    @Autowired
    FaqService faqs;
    @Autowired
    HandoffService handoffs;
    @Autowired
    AppSettings settings;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void batBuocDangNhapVaCsrf() throws Exception {
        for (String path : new String[]{"/bot", "/handoffs"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        }
        var owner = user("admin").roles("OWNER");
        mvc.perform(post("/bot/enable").with(owner).param("on", "true")).andExpect(status().isForbidden());
        mvc.perform(post("/bot/simulate").with(owner).param("text", "mua")).andExpect(status().isForbidden());
        assertThat(settings.botEnabled()).isFalse();
    }

    @Test
    void trangBotMacDinhTatVaChayThu() throws Exception {
        mvc.perform(get("/bot").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TẮT")))
                .andExpect(content().string(containsString("chưa kết nối")))
                .andExpect(content().string(containsString("Chưa chọn cách nối cụ thể")));
    }

    @Test
    void congTacBotVaTungNhomVaFaq() throws Exception {
        var owner = user("admin").roles("OWNER");
        long group = groups.create("Nhóm 1", null, 5);

        mvc.perform(post("/bot/enable").with(owner).with(csrf()).param("on", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/bot"));
        mvc.perform(post("/bot/groups/" + group + "/toggle").with(owner).with(csrf()).param("on", "true"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/bot/faq").with(owner).with(csrf()).param("title", "Giặt")
                        .param("keywords", "giặt").param("answer", "Giặt tay."))
                .andExpect(status().is3xxRedirection());

        assertThat(settings.botEnabled()).isTrue();
        assertThat(jdbc.sql("SELECT bot_enabled FROM zalo_group").query(Boolean.class).single()).isTrue();
        assertThat(faqs.list()).hasSize(1);
        mvc.perform(post("/bot/faq").with(owner).with(csrf()).param("title", "").param("keywords", "x")
                        .param("answer", "y"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thuBotKhongGhiGiVaChoThayBotSeLamGi() throws Exception {
        var owner = user("admin").roles("OWNER");

        mvc.perform(post("/bot/simulate").with(owner).with(csrf()).param("text", "Mình muốn mua thêm"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("báo mẹ")))
                .andExpect(content().string(containsString("không ghi gì")));
        mvc.perform(post("/bot/simulate").with(owner).with(csrf()).param("text", "trời đẹp quá"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("không trả lời")));

        assertThat(jdbc.sql("SELECT count(*) FROM handoff").query(Long.class).single()).isZero();
    }

    @Test
    void viecCanMeXuLyNhanVaXong() throws Exception {
        var owner = user("admin").roles("OWNER");
        long group = groups.create("Nhóm 1", null, 5);
        handoffs.open(group, "ORDER", "muốn mua thêm");
        long id = handoffs.list(true).getFirst().id();

        mvc.perform(get("/handoffs").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("muốn mua thêm")));
        mvc.perform(post("/handoffs/" + id + "/ack").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/handoffs/" + id + "/ack").with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/handoffs/" + id + "/resolve").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(handoffs.list(true)).isEmpty();
        mvc.perform(get("/").with(owner)).andExpect(status().isOk());
    }
}
