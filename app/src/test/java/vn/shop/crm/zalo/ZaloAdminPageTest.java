package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.invite.GroupService;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trang quản trị "Zalo tài khoản phụ" (D16, bộ nối GIẢ): chỉ admin, QR không lọt ra ngoài, nấc/hạn mức/dừng/mở lại. */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class ZaloAdminPageTest extends AbstractPostgresTest {

    private static final byte[] PNG = "qr-demo-bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired WebApplicationContext context;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloAutoSettings cfg;
    @Autowired GroupService groups;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fake.reset();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor owner() {
        return user("admin").roles("OWNER");
    }

    @Test
    void chuaDangNhapThiBiChuyenVeTrangDangNhapVaKhongThayQR() throws Exception {
        fake.login = new ZaloSession.Login(ZaloSession.LoginState.WAITING_SCAN, PNG);
        mvc.perform(get("/zalo")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/zalo/login")).andExpect(status().is3xxRedirection())
                .andExpect(content().string(not(containsString("data:image"))));
        mvc.perform(post("/zalo/login/start").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(fake.logouts + fake.stops + fake.resumes).isZero();
    }

    @Test
    void nguoiKhongPhaiAdminBiChanTrenMoiDuongCuaTrangZalo() throws Exception {
        fake.login = new ZaloSession.Login(ZaloSession.LoginState.WAITING_SCAN, PNG);
        var staff = user("nhanvien").roles("STAFF");
        for (String path : new String[]{"/zalo", "/zalo/login"}) {
            mvc.perform(get(path).with(staff)).andExpect(status().isForbidden())
                    .andExpect(content().string(not(containsString("data:image"))));
        }
        mvc.perform(post("/zalo/stop").with(staff).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/zalo/mode").with(staff).with(csrf()).param("mode", "CONFIRM")).andExpect(status().isForbidden());
        mvc.perform(post("/zalo/logout").with(staff).with(csrf())).andExpect(status().isForbidden());
        assertThat(fake.stops + fake.logouts).isZero();
        assertThat(cfg.chosenMode()).isEqualTo(ZaloMode.OFF);
    }

    @Test
    void thieuCsrfThiTuChoi() throws Exception {
        mvc.perform(post("/zalo/stop").with(owner())).andExpect(status().isForbidden());
        assertThat(fake.stops).isZero();
    }

    @Test
    void trangHienTiengVietKhongLoMaNoiBo() throws Exception {
        mvc.perform(get("/zalo").with(owner())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Zalo tài khoản phụ")))
                .andExpect(content().string(containsString("Đang nghe")))
                .andExpect(content().string(not(containsString("LISTENING"))))
                .andExpect(content().string(not(containsString(">LISTEN_ONLY<"))))
                .andExpect(content().string(not(containsString("WAITING_APPROVAL"))));
    }

    @Test
    void qrChiHienTrenTrangDangNhapVaKhongDuocLuuDem() throws Exception {
        fake.login = new ZaloSession.Login(ZaloSession.LoginState.WAITING_SCAN, PNG);
        mvc.perform(get("/zalo/login").with(owner())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("data:image/png;base64,")))
                .andExpect(content().string(containsString("http-equiv=\"refresh\"")))
                .andExpect(content().string(not(containsString("WAITING_SCAN"))));
        // trang chính không bao giờ chứa ảnh QR
        mvc.perform(get("/zalo").with(owner())).andExpect(content().string(not(containsString("data:image"))));
    }

    @Test
    void daQuetXongThiQuayVeTrangChinh() throws Exception {
        fake.login = ZaloSession.Login.of(ZaloSession.LoginState.DONE);
        mvc.perform(get("/zalo/login").with(owner())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/zalo"));
    }

    @Test
    void saiTaiKhoanHienThongBaoVaKhongCoQR() throws Exception {
        fake.login = ZaloSession.Login.of(ZaloSession.LoginState.ACCOUNT_MISMATCH);
        mvc.perform(get("/zalo/login").with(owner())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sai tài khoản")))
                .andExpect(content().string(not(containsString("data:image"))));
    }

    @Test
    void saiTaiKhoanHienMaDeDienZaloAccountIdChiChoAdminVaKhongLuuKhongGhiLog() throws Exception {
        fake.login = new ZaloSession.Login(ZaloSession.LoginState.ACCOUNT_MISMATCH, null, "555123456");
        mvc.perform(get("/zalo/login").with(owner())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("555123456")))
                .andExpect(content().string(containsString("ZALO_ACCOUNT_ID")));
        // người không phải admin hoặc chưa đăng nhập không thấy
        mvc.perform(get("/zalo/login").with(user("nhanvien").roles("STAFF"))).andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("555123456"))));
        mvc.perform(get("/zalo/login")).andExpect(status().is3xxRedirection());
        // trang chính, nhật ký và cơ sở dữ liệu không có mã
        mvc.perform(get("/zalo").with(owner())).andExpect(content().string(not(containsString("555123456"))));
        assertThat(cfg.recentLog(50)).noneMatch(r -> String.valueOf(r.code()).contains("555123456")
                || String.valueOf(r.result()).contains("555123456"));
        assertThat(new ZaloSession.Login(ZaloSession.LoginState.ACCOUNT_MISMATCH, null, "555123456").toString())
                .doesNotContain("555123456");
    }

    @Test
    void maTaiKhoanKhongHienKhiKhongPhaiSaiTaiKhoan() throws Exception {
        fake.login = new ZaloSession.Login(ZaloSession.LoginState.WAITING_SCAN, PNG, "555123456");
        mvc.perform(get("/zalo/login").with(owner())).andExpect(content().string(not(containsString("555123456"))));
    }

    @Test
    void chonNacVaNacTuThemCanTickXacNhan() throws Exception {
        mvc.perform(post("/zalo/mode").with(owner()).with(csrf()).param("mode", "CONFIRM"))
                .andExpect(status().is3xxRedirection());
        assertThat(cfg.chosenMode()).isEqualTo(ZaloMode.CONFIRM);
        mvc.perform(post("/zalo/mode").with(owner()).with(csrf()).param("mode", "AUTO"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("tick xác nhận")));
        assertThat(cfg.chosenMode()).isEqualTo(ZaloMode.CONFIRM);
        mvc.perform(post("/zalo/mode").with(owner()).with(csrf()).param("mode", "AUTO").param("confirmedTested", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(cfg.chosenMode()).isEqualTo(ZaloMode.AUTO);
    }

    @Test
    void hanMucKhongVuotTranCung() throws Exception {
        mvc.perform(post("/zalo/limits").with(owner()).with(csrf()).param("daily", "101").param("hourly", "5").param("gap", "2"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("từ 1 đến 100")));
        mvc.perform(post("/zalo/limits").with(owner()).with(csrf()).param("daily", "40").param("hourly", "4").param("gap", "3"))
                .andExpect(status().is3xxRedirection());
        assertThat(cfg.limits()).isEqualTo(new ZaloAutoSettings.Limits(40, 4, 3));
    }

    @Test
    void dungNgayRoiMoLaiChiKhiNguoiBam() throws Exception {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        mvc.perform(post("/zalo/stop").with(owner()).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(cfg.tripped()).isTrue();
        assertThat(fake.stops).isEqualTo(1);
        mvc.perform(get("/zalo").with(owner())).andExpect(content().string(containsString("Bộ nối Zalo đang dừng")))
                .andExpect(content().string(containsString("Có người bấm dừng")))
                .andExpect(content().string(not(containsString("DUNG_TAY"))));
        assertThat(fake.resumes).isZero();
        mvc.perform(post("/zalo/reopen").with(owner()).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(cfg.tripped()).isFalse();
        assertThat(fake.resumes).isEqualTo(1);
    }

    @Test
    void moLaiSauLyDoNghiemTrongPhaiTickXacNhan() throws Exception {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        cfg.trip("RESTRICTED", "system");
        mvc.perform(post("/zalo/reopen").with(owner()).with(csrf())).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("tick")));
        assertThat(cfg.tripped()).isTrue();
        assertThat(fake.resumes).isZero();
        mvc.perform(post("/zalo/reopen").with(owner()).with(csrf()).param("checked", "true")).andExpect(status().is3xxRedirection());
        assertThat(cfg.tripped()).isFalse();
    }

    @Test
    void canhBaoDungHienTrenTongQuanVaLoHomNay() throws Exception {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        mvc.perform(get("/").with(owner())).andExpect(content().string(not(containsString("Bộ nối Zalo đã DỪNG"))));
        cfg.trip("DUNG_TAY", "mẹ");
        mvc.perform(get("/").with(owner())).andExpect(content().string(containsString("Bộ nối Zalo đã DỪNG")));
        mvc.perform(get("/today").with(owner())).andExpect(content().string(containsString("Bộ nối Zalo đã DỪNG")));
    }

    @Test
    void thanhBenCoMucZaloKhiBatVaKhongGoiCauNoiMoiLanMoTrang() throws Exception {
        mvc.perform(get("/groups").with(owner())).andExpect(content().string(containsString("Zalo tài khoản phụ")));
        int before = fake.statusCalls;
        mvc.perform(get("/").with(owner())).andExpect(status().isOk());
        mvc.perform(get("/today").with(owner())).andExpect(status().isOk());
        assertThat(fake.statusCalls).isEqualTo(before);
    }

    @Test
    void maNhomZaloLuuDuocVaChiNhanChuSo() throws Exception {
        long id = groups.create("Nhóm thử", "https://zalo.me/g/x", 5, null);
        mvc.perform(post("/groups/{id}/zalo-id", id).with(owner()).with(csrf()).param("zaloGroupId", "12345"))
                .andExpect(status().is3xxRedirection());
        assertThat(groups.list().stream().filter(g -> g.id() == id).findFirst().orElseThrow().zaloGroupId()).isEqualTo("12345");
        mvc.perform(post("/groups/{id}/zalo-id", id).with(owner()).with(csrf()).param("zaloGroupId", "https://zalo.me/g/x"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/groups").with(owner())).andExpect(content().string(containsString("Mã nhóm trong Zalo")));
    }
}
