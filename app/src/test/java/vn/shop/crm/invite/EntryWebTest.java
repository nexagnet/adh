package vn.shop.crm.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trang công khai /moi (không đăng nhập, không CSRF) và trang in giấy /print (cần đăng nhập). */
class EntryWebTest extends AbstractPostgresTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    GroupService groups;
    @Autowired
    JdbcClient jdbc;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private void openGroup(String link) {
        long id = groups.create("Nhóm 1", link, 5);
        groups.setStatus(id, GroupStatus.OPEN);
    }

    @Test
    void trangMoiCongKhaiCoNutVaKhongCanDangNhap() throws Exception {
        mvc.perform(get("/moi"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cảm ơn bạn đã mua hàng!")))
                .andExpect(content().string(containsString("Vào nhóm Zalo")))
                .andExpect(content().string(containsString("không bắt buộc")));
    }

    @Test
    void bamVaoNhomChuyenSangZaloVaDatCookieQuetLaiKhongDemThem() throws Exception {
        openGroup("https://zalo.me/g/abc123");

        MockHttpServletResponse first = mvc.perform(post("/moi/vao"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://zalo.me/g/abc123"))
                .andExpect(header().string("Set-Cookie", containsString("adh_moi=")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andReturn().getResponse();
        String setCookie = first.getHeader(HttpHeaders.SET_COOKIE);
        String value = setCookie.substring("adh_moi=".length(), setCookie.indexOf(';'));

        mvc.perform(post("/moi/vao").cookie(new Cookie("adh_moi", value)))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://zalo.me/g/abc123"));

        assertThat(jdbc.sql("SELECT count(*) FROM open_entry").query(Long.class).single()).isEqualTo(1);
        assertThat(groups.list().get(0).pendingClicks()).isEqualTo(1);
    }

    @Test
    void khongConChoThiBaoChoKhach() throws Exception {
        mvc.perform(post("/moi/vao"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("chưa có chỗ")));
    }

    @Test
    void xemTruocBangGetKhongTinhLuot() throws Exception {
        openGroup("https://zalo.me/g/abc123");
        mvc.perform(get("/moi")).andExpect(status().isOk());
        mvc.perform(get("/moi/vao")).andExpect(status().is4xxClientError());
        assertThat(jdbc.sql("SELECT count(*) FROM open_entry").query(Long.class).single()).isZero();
    }

    @Test
    void khachXinNgungNhanTinKhongCanDangNhap() throws Exception {
        mvc.perform(post("/moi/ngung").param("phone", "0912345678"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã ghi nhận")));
        assertThat(jdbc.sql("SELECT count(*) FROM suppression").query(Long.class).single()).isEqualTo(1);

        mvc.perform(post("/moi/ngung").param("phone", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Số điện thoại chưa đúng")))
                .andExpect(content().string(containsString("Vào nhóm Zalo")));
        assertThat(jdbc.sql("SELECT count(*) FROM suppression").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void trangInCanDangNhap() throws Exception {
        for (String path : new String[]{"/print", "/print/qr.png", "/print/qr.svg"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        }
        mvc.perform(post("/print/texts").with(csrf()).param("title", "x")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/print/texts").param("title", "x").with(user("admin").roles("OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void trangInHienMauQrChuSuaDuocVaCanhBaoDiaChiChuaThat() throws Exception {
        openGroup("https://zalo.me/g/abc123");
        mvc.perform(get("/print").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("In giấy giới thiệu")))
                .andExpect(content().string(containsString("/print/qr.png")))
                .andExpect(content().string(containsString("localhost:8080/moi")))
                .andExpect(content().string(containsString("Chưa nên in")))
                .andExpect(content().string(containsString("Khách quét lúc này sẽ vào")))
                .andExpect(content().string(containsString("/print-a6.css")))
                .andExpect(content().string(containsString("Cảm ơn bạn đã mua hàng!")));

        mvc.perform(get("/print").param("kho", "a4x4").with(user("admin").roles("OWNER")))
                .andExpect(content().string(containsString("/print-a4x4.css")));
        // khổ lạ thì dùng A6, không lộ lỗi
        mvc.perform(get("/print").param("kho", "../../etc").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/print-a6.css")));
    }

    @Test
    void suaChuTrenGiayRoiXemLaiVaChuBiMaHoa() throws Exception {
        var admin = user("admin").roles("OWNER");
        mvc.perform(post("/print/texts").with(csrf()).with(admin)
                        .param("kho", "A5").param("title", "Cảm ơn quý khách <script>alert(1)</script>")
                        .param("intro", "Nhóm nhỏ của {cua_hang}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/print?kho=A5"));

        mvc.perform(get("/print").param("kho", "A5").with(admin))
                .andExpect(content().string(containsString("Cảm ơn quý khách &lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))))
                .andExpect(content().string(containsString("/print-a5.css")));

        // trang khách thấy sau khi quét dùng cùng chữ
        mvc.perform(get("/moi")).andExpect(content().string(containsString("Cảm ơn quý khách &lt;script&gt;")));

        mvc.perform(post("/print/texts/reset").with(csrf()).with(admin).param("kho", "A6"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/moi")).andExpect(content().string(containsString("Cảm ơn bạn đã mua hàng!")));
    }

    @Test
    void chuBoTrongHoacQuaDaiBaoLoiTiengViet() throws Exception {
        var admin = user("admin").roles("OWNER");
        mvc.perform(post("/print/texts").with(csrf()).with(admin).param("title", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("không được để trống")));
    }

    @Test
    void hinhQrTaiVeDuoc() throws Exception {
        var admin = user("admin").roles("OWNER");
        byte[] png = mvc.perform(get("/print/qr.png").with(admin)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andReturn().getResponse().getContentAsByteArray();
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        mvc.perform(get("/print/qr.png").param("tai", "true").with(admin))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
        mvc.perform(get("/print/qr.svg").with(admin)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("image/svg+xml")))
                .andExpect(content().string(containsString("<svg")));
    }
}
