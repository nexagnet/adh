package vn.shop.crm.zalo;

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

import static org.assertj.core.api.Assertions.assertThat;
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

/** D23, PR 7: trang Zalo chọn tài khoản để xem và chỉnh; nút bấm áp dụng cho tài khoản đang chọn; trần tổng; tạm ngưng tài khoản thêm. */
@Import(ZaloTwoBridgeSchedulerTest.Bridges.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.multi.enabled=true",
        "app.zalo.safety.live=true",
        "app.zalo.accounts[0].id=2",
        "app.zalo.accounts[0].bridge-url=http://127.0.0.1:1",
        "app.zalo.accounts[0].bridge-key=k2"})
class ZaloMultiAccountPageTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired ZaloTwoBridgeSchedulerTest.BridgePair bridges;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings auto;
    @Autowired ZaloAccountRegistry registry;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        bridges.reset();
        registry.setEnabled(2, true);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor owner() {
        return user("admin").roles("OWNER");
    }

    @Test
    void cauHinhHienTrangThaiVaGiuTungTaiKhoanKhiThaoTac() throws Exception {
        mvc.perform(get("/zalo?tab=cau-hinh&account=2").with(owner())).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"zalo-account-config\"")))
                .andExpect(content().string(containsString("Cấu hình tài khoản Zalo")))
                .andExpect(content().string(containsString("Chỉ dùng số thử")))
                .andExpect(content().string(containsString("/zalo/login/start?account=2")))
                .andExpect(content().string(containsString("/zalo/accounts/2/enabled?account=2")));

        mvc.perform(post("/zalo/login/start?account=2").param("tab", "cau-hinh").with(owner()).with(csrf()))
                .andExpect(redirectedUrl("/zalo/login?tab=cau-hinh&account=2"));
        bridges.second().login = ZaloSession.Login.of(ZaloSession.LoginState.DONE);
        mvc.perform(get("/zalo/login?tab=cau-hinh&account=2").with(owner()))
                .andExpect(redirectedUrl("/zalo?tab=cau-hinh&account=2"));

        mvc.perform(post("/zalo/accounts/2/enabled?account=2").param("on", "false").param("tab", "cau-hinh")
                        .with(owner()).with(csrf()))
                .andExpect(redirectedUrl("/zalo?tab=cau-hinh&account=2"));

        mvc.perform(get("/zalo?tab=cau-hinh&account=1").with(owner())).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"zalo-account-config\"")))
                .andExpect(content().string(containsString("Có thể gửi cho khách thật")))
                .andExpect(content().string(not(containsString("/zalo/accounts/1/enabled"))));
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
    void haiTabDungTaiKhoanTrongBieuMauVaTrongTrangChuyenHuong() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/zalo?account=2&tab=gioi-han").with(owner()).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/zalo/messages/limits?account=2\"")));
        mvc.perform(get("/zalo?account=1").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/zalo/messages/limits?account=2").with(owner()).with(csrf()).session(session).param("daily", "4").param("gap", "150"))
                .andExpect(redirectedUrl("/zalo?tab=gioi-han&account=2"));
        assertThat(jdbc.sql("SELECT value FROM app_setting WHERE key = 'acct2.zalo.msg.daily'").query(String.class).single()).isEqualTo("4");
        assertThat(jdbc.sql("SELECT count(*) FROM app_setting WHERE key = 'zalo.msg.daily'").query(Long.class).single()).isZero();
        mvc.perform(get("/zalo?account=2").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/zalo/messages/limits?account=1").with(owner()).with(csrf()).session(session).param("daily", "9").param("gap", "150"))
                .andExpect(redirectedUrl("/zalo?tab=gioi-han&account=1"));
        assertThat(jdbc.sql("SELECT value FROM app_setting WHERE key = 'zalo.msg.daily'").query(String.class).single()).isEqualTo("9");
    }

    @Test
    void tuChoiBieuMauThieuSaiHoacCoHaiMaTaiKhoan() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/zalo?account=7").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(get("/zalo?account=abc").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(get("/zalo?account=2").with(owner()).session(session)).andExpect(status().isOk());
        for (String query : new String[]{"", "?account=7", "?account=abc", "?account=1&account=2"}) {
            mvc.perform(post("/zalo/messages/limits" + query).with(owner()).with(csrf()).session(session)
                            .param("daily", "5").param("gap", "150"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_setting WHERE key IN ('zalo.msg.daily', 'acct2.zalo.msg.daily')")
                .query(Long.class).single()).isZero();
    }

    @Test
    void qrGiuTaiKhoanKhiTabKhacDoiLuaChon() throws Exception {
        MockHttpSession session = new MockHttpSession();
        bridges.first().login = new ZaloSession.Login(ZaloSession.LoginState.WAITING_SCAN, new byte[]{1, 2, 3});
        bridges.second().login = new ZaloSession.Login(ZaloSession.LoginState.WAITING_SCAN, new byte[]{4, 5, 6});
        mvc.perform(post("/zalo/login/start?account=2").with(owner()).with(csrf()).session(session))
                .andExpect(redirectedUrl("/zalo/login?account=2"));
        mvc.perform(get("/zalo?account=1").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(get("/zalo/login?account=2").with(owner()).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/zalo?tab=cau-hinh&amp;account=2\"")))
                .andExpect(content().string(containsString("data:image/png;base64,BAUG")))
                .andExpect(content().string(not(containsString("data:image/png;base64,AQID"))));
        mvc.perform(get("/zalo/login").with(owner()).session(session)).andExpect(status().isBadRequest());
        bridges.second().login = ZaloSession.Login.of(ZaloSession.LoginState.DONE);
        mvc.perform(get("/zalo/login?account=2").with(owner()).session(session))
                .andExpect(redirectedUrl("/zalo?account=2"));
    }

    @Test
    void dangXuatChiGoiBridgeCuaTabDaGui() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/zalo?account=2").with(owner()).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/zalo/logout?account=2\"")));
        mvc.perform(get("/zalo?account=1").with(owner()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/zalo/logout?account=2").with(owner()).with(csrf()).session(session))
                .andExpect(redirectedUrl("/zalo?tab=tinh-trang&account=2"));
        assertThat(bridges.second().logouts).isEqualTo(1);
        assertThat(bridges.first().logouts).isZero();
    }

    @Test
    void cauDaoChungChiHienNutMoLaiOTaiKhoanBaoLoi() throws Exception {
        ZaloAccountContext.run(2, () -> auto.trip("RESTRICTED:VERIFY", "test"));
        mvc.perform(get("/zalo?account=1").with(owner())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tất cả tài khoản đang dừng")))
                .andExpect(content().string(containsString("href=\"/zalo?account=2\"")))
                .andExpect(content().string(not(containsString("action=\"/zalo/reopen?account=1\""))));
        mvc.perform(get("/zalo?account=2").with(owner())).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/zalo/reopen?account=2\"")));
    }

    @Test
    void taiKhoanThuKhongTaoDonGiaVaoDuLieuDungChungDangChayThat() throws Exception {
        mvc.perform(get("/zalo?account=2&tab=thu").with(owner())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đang ở chế độ thử")))
                .andExpect(content().string(not(containsString("/zalo/test-numbers/seed"))));
        mvc.perform(post("/zalo/test-numbers/seed?account=2").with(owner()).with(csrf()))
                .andExpect(status().isBadRequest());
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
