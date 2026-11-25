package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mặc định (bộ nối TẮT): không ai thấy mục Zalo ở menu, trang chỉ báo "đang tắt", mọi thao tác bị từ chối. */
class ZaloAdminPageOffTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired ZaloAutoSettings cfg;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void menuVanCoMucZaloVaTrangBaoDangTat() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/groups").with(owner)).andExpect(content().string(containsString("Zalo tài khoản phụ")));
        mvc.perform(get("/zalo").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Bộ nối Zalo đang tắt")))
                .andExpect(content().string(not(containsString("Quét mã QR"))));
        mvc.perform(get("/zalo?tab=cau-hinh").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"zalo-account-config\"")))
                .andExpect(content().string(containsString("Đang tắt trong cấu hình máy chủ")))
                .andExpect(content().string(not(containsString("/zalo/login/start"))));
        mvc.perform(get("/").with(owner)).andExpect(content().string(not(containsString("Bộ nối Zalo đã DỪNG"))));
    }

    @Test
    void moiThaoTacBiTuChoiKhiChuaBat() throws Exception {
        var owner = user("admin").roles("OWNER");
        for (String path : new String[]{"/zalo/stop", "/zalo/reopen", "/zalo/logout", "/zalo/login/start"}) {
            mvc.perform(post(path).with(owner).with(csrf())).andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("APP_ZALO_AUTO_ENABLED")));
        }
        mvc.perform(get("/zalo/login").with(owner)).andExpect(status().isBadRequest());
        assertThat(cfg.tripped()).isFalse();
    }
}
