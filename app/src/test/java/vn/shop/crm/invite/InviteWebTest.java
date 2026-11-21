package vn.shop.crm.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;

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

/** Trang quản trị cần đăng nhập + CSRF; trang mời công khai chỉ cần token, không lộ SĐT/tên. */
class InviteWebTest extends AbstractPostgresTest {

    private static final String CSV = """
            Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái
            T1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công
            """;

    @Autowired
    WebApplicationContext context;
    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
    @Autowired
    GroupService groups;
    @Autowired
    InviteService invites;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String seedAndGetToken() {
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", CSV.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        long group = groups.create("Nhóm 1", "https://zalo.me/g/abc123", 5);
        groups.setStatus(group, GroupStatus.OPEN);
        invites.createCampaign("Đợt 1", null, 14, 5);
        return jdbc.sql("SELECT token FROM invitation").query(String.class).single();
    }

    @Test
    void trangQuanTriBatBuocDangNhapCsrfVaHienThiDuoc() throws Exception {
        for (String path : new String[]{"/groups", "/campaigns"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        }
        var owner = user("admin").roles("OWNER");
        mvc.perform(post("/groups").with(owner).param("name", "Nhóm X")).andExpect(status().isForbidden());
        mvc.perform(post("/campaigns/pause-all").with(owner)).andExpect(status().isForbidden());

        mvc.perform(post("/groups").with(owner).with(csrf()).param("name", "Nhóm X")
                        .param("link", "https://zalo.me/g/xyz").param("target", "5"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/groups"));
        mvc.perform(get("/groups").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Nhóm X")))
                .andExpect(content().string(containsString("không tính tài khoản mẹ và tài khoản bot")));
        mvc.perform(get("/campaigns").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("chưa gửi tin cho ai")));
    }

    @Test
    void linkKhongPhaiZaloBiTuChoiBangThongBaoTiengViet() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(post("/groups").with(owner).with(csrf()).param("name", "Nhóm X")
                        .param("link", "https://evil.example.com/x"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("zalo.me")));
    }

    @Test
    void chiTietChiendichHienLinkRiengVaXemTruocTin() throws Exception {
        String token = seedAndGetToken();
        long campaign = jdbc.sql("SELECT id FROM invitation_campaign").query(Long.class).single();

        mvc.perform(get("/campaigns/" + campaign).with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/i/" + token)))
                .andExpect(content().string(containsString("không tự gửi tin")));
    }

    @Test
    void trangMoiCongKhaiKhongCanDangNhapKhongLoThongTinKhach() throws Exception {
        String token = seedAndGetToken();

        mvc.perform(get("/i/" + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(containsString("Vào nhóm Zalo")))
                .andExpect(content().string(containsString("không bắt buộc")))
                .andExpect(content().string(not(containsString("0912345678"))))
                .andExpect(content().string(not(containsString("Nguyen"))))
                .andExpect(content().string(not(containsString("Duong A"))));
        // mở trang không tính là lượt bấm
        assertThat(jdbc.sql("SELECT click_count FROM invitation").query(Integer.class).single()).isZero();
    }

    @Test
    void nutVaoNhomDungPostKhongCanCsrfChuyenHuongDenZaloVaGhiLuotBam() throws Exception {
        String token = seedAndGetToken();

        mvc.perform(post("/i/" + token + "/go"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://zalo.me/g/abc123"));
        assertThat(jdbc.sql("SELECT click_count FROM invitation").query(Integer.class).single()).isEqualTo(1);
        // GET /go không phải đường vào nhóm (trình xem trước link không được tính lượt bấm)
        mvc.perform(get("/i/" + token + "/go")).andExpect(status().is4xxClientError());
        assertThat(jdbc.sql("SELECT click_count FROM invitation").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void linkSaiHoacHetHanChoTrang404ChungKhongNoiLyDo() throws Exception {
        String token = seedAndGetToken();
        jdbc.sql("UPDATE invitation SET expires_at = now() - interval '1 day'").update();

        for (String t : new String[]{token, "khong-co-token"}) {
            mvc.perform(get("/i/" + t)).andExpect(status().isNotFound())
                    .andExpect(content().string(containsString("không còn dùng được")));
            mvc.perform(post("/i/" + t + "/go")).andExpect(status().isNotFound());
        }
    }

    @Test
    void chuaCoNhomThiBaoLienHeCuaHangKhongChuyenHuongSai() throws Exception {
        String token = seedAndGetToken();
        jdbc.sql("UPDATE zalo_group SET status = 'PAUSED'").update();

        mvc.perform(post("/i/" + token + "/go"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(containsString("liên hệ cửa hàng")));
    }

    @Test
    void nutNgungNhanTinGhiVaoDanhSachTuChoi() throws Exception {
        String token = seedAndGetToken();

        mvc.perform(post("/i/" + token + "/stop"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sẽ không nhắn mời bạn nữa")));

        assertThat(contacts.isSuppressed("+84912345678")).isTrue();
    }
}
