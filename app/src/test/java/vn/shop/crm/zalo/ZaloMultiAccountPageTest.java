package vn.shop.crm.zalo;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;

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

/** D23, PR 7: trang Zalo chọn tài khoản để xem và chỉnh; nút bấm áp dụng cho tài khoản đang chọn; trần tổng; tạm ngưng tài khoản thêm. */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.multi.enabled=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2"})
class ZaloMultiAccountPageTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired FakeZaloGateway fake;
    @Autowired AppSettings settings;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAccountRegistry registry;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fake.reset();
        registry.ensureRows();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor owner() {
        return user("admin").roles("OWNER");
    }

    @Test
    void coHaiTaiKhoanThiHienThanhChonVaNhoLuaChonTrongPhien() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/zalo").with(owner()).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tài khoản phụ 2")))
                .andExpect(content().string(not(containsString("Tạm ngưng tài khoản này"))));
        mvc.perform(get("/zalo?account=2").with(owner()).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đang xem và chỉnh")))
                .andExpect(content().string(containsString("Tạm ngưng tài khoản này")));
        // lần sau không kèm ?account= vẫn là tài khoản 2 (nhớ trong phiên)
        mvc.perform(get("/zalo?tab=gioi-han").with(owner()).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tạm ngưng tài khoản này")))
                .andExpect(content().string(containsString("Trần tổng của mọi tài khoản")));
    }

    @Test
    void nutBamApDungChoTaiKhoanDangChon() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/zalo?account=2").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/zalo/messages/limits").with(owner()).with(csrf()).session(session).param("daily", "4").param("gap", "150"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.sql("SELECT value FROM app_setting WHERE key = 'acct2.zalo.msg.daily'").query(String.class).single()).isEqualTo("4");
        assertThat(jdbc.sql("SELECT count(*) FROM app_setting WHERE key = 'zalo.msg.daily'").query(Long.class).single()).isZero();
        // trở về tài khoản 1: cùng nút, khóa cũ
        mvc.perform(get("/zalo?account=1").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/zalo/messages/limits").with(owner()).with(csrf()).session(session).param("daily", "9").param("gap", "150"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.sql("SELECT value FROM app_setting WHERE key = 'zalo.msg.daily'").query(String.class).single()).isEqualTo("9");
    }

    @Test
    void maTaiKhoanLaKhongLamHongVaKhongDoiSangTaiKhoanChuaKhaiBao() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/zalo?account=7").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(get("/zalo?account=abc").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/zalo/messages/limits").with(owner()).with(csrf()).session(session).param("daily", "5").param("gap", "150"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.sql("SELECT value FROM app_setting WHERE key = 'zalo.msg.daily'").query(String.class).single()).isEqualTo("5");
    }

    @Test
    void tranTongVaTamNgungTaiKhoanThem() throws Exception {
        mvc.perform(post("/zalo/messages/total-limit").with(owner()).with(csrf()).param("daily", "80")).andExpect(status().is3xxRedirection());
        assertThat(messages.totalDaily()).isEqualTo(80);
        assertThat(registry.active()).containsExactly(1L, 2L);
        mvc.perform(post("/zalo/accounts/2/enabled").with(owner()).with(csrf()).param("on", "false")).andExpect(status().is3xxRedirection());
        assertThat(registry.active()).containsExactly(1L); // tài khoản 2 tạm ngưng: bộ hẹn giờ bỏ qua
        mvc.perform(post("/zalo/accounts/2/enabled").with(owner()).with(csrf()).param("on", "true")).andExpect(status().is3xxRedirection());
        assertThat(registry.active()).containsExactly(1L, 2L);
        // tài khoản 1 không tạm ngưng được
        mvc.perform(post("/zalo/accounts/1/enabled").with(owner()).with(csrf()).param("on", "false")).andExpect(status().is4xxClientError());
        assertThat(registry.active()).contains(1L);
    }

    @Test
    void nhanVienKhongDoiDuocTaiKhoanHayTran() throws Exception {
        var staff = user("nhanvien").roles("STAFF");
        mvc.perform(post("/zalo/messages/total-limit").with(staff).with(csrf()).param("daily", "80")).andExpect(status().isForbidden());
        mvc.perform(post("/zalo/accounts/2/enabled").with(staff).with(csrf()).param("on", "false")).andExpect(status().isForbidden());
        assertThat(registry.active()).containsExactly(1L, 2L);
    }
}
