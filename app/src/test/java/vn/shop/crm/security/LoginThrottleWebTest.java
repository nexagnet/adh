package vn.shop.crm.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Đăng nhập sai nhiều lần thì bị khóa tạm, kể cả khi sau đó nhập đúng mật khẩu. */
class LoginThrottleWebTest extends AbstractPostgresTest {

    @Autowired
    WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private void login(String ip, String password) throws Exception {
        mvc.perform(post("/login").with(from(ip)).with(csrf()).param("username", "admin").param("password", password));
    }

    @Test
    void saiNamLanThiKhoaKeCaKhiSauDoNhapDung() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").with(from("10.0.0.1")).with(csrf())
                            .param("username", "admin").param("password", "sai"))
                    .andExpect(redirectedUrl("/login?error"));
        }

        mvc.perform(post("/login").with(from("10.0.0.1")).with(csrf())
                        .param("username", "admin").param("password", "test-password-only"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?locked"));
        mvc.perform(get("/login").param("locked", ""))
                .andExpect(status().isOk()).andExpect(content().string(containsString("đợi 15 phút")));
    }

    @Test
    void mayKhacVanDangNhapDuocKhiMotMayBiKhoa() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("10.0.0.2", "sai");
        }

        mvc.perform(post("/login").with(from("10.0.0.3")).with(csrf())
                        .param("username", "admin").param("password", "test-password-only"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
    }

    @Test
    void dangNhapDungXoaDemSaiTruocDo() throws Exception {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                login("10.0.0.4", "sai");
            }
            mvc.perform(post("/login").with(from("10.0.0.4")).with(csrf())
                            .param("username", "admin").param("password", "test-password-only"))
                    .andExpect(redirectedUrl("/"));
        }
    }
}
