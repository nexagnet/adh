package vn.shop.crm.send;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trang Gửi tin, chạy thử và xuất danh sách: đăng nhập + CSRF, không gửi thật, tôn trọng tạm dừng. */
class SendWebTest extends AbstractPostgresTest {

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
    InviteService invites;
    @Autowired
    AppSettings settings;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private long seedCampaign() {
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", CSV.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        return invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn. Muốn ngừng nhận tin thì bấm: {link}", 14, 5);
    }

    @Test
    void batBuocDangNhapVaCsrf() throws Exception {
        long camp = seedCampaign();
        mvc.perform(get("/sending")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        var owner = user("admin").roles("OWNER");
        mvc.perform(post("/campaigns/" + camp + "/dry-run").with(owner)).andExpect(status().isForbidden());
        mvc.perform(post("/campaigns/" + camp + "/export.csv").with(owner)).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM send_log").query(Long.class).single()).isZero();
    }

    @Test
    void trangGuiTinHienGuiThatDangTatVaNhatKy() throws Exception {
        long camp = seedCampaign();
        var owner = user("admin").roles("OWNER");
        mvc.perform(post("/campaigns/" + camp + "/dry-run").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/campaigns/" + camp));

        mvc.perform(get("/zalo").with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tạm dừng và nhật ký gửi")))
                .andExpect(content().string(containsString("Chạy thử")))
                .andExpect(content().string(containsString("***678")));
    }

    @Test
    void trangSendingCuChuyenSangTrangZalo() throws Exception {
        mvc.perform(get("/sending").with(user("admin").roles("OWNER")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/zalo#gui-tay"));
    }

    @Test
    void xuatDanhSachTraCsvCoLinkRiengVaDanhDauDaGui() throws Exception {
        long camp = seedCampaign();
        var owner = user("admin").roles("OWNER");

        var body = mvc.perform(post("/campaigns/" + camp + "/export.csv").with(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("loi_moi_chien_dich_" + camp)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("0912345678").contains("/i/").doesNotContain("Duong A");
        assertThat(jdbc.sql("SELECT status FROM invitation").query(String.class).single()).isEqualTo("SENT");
        // xuất lần hai: không còn tin nào
        mvc.perform(post("/campaigns/" + camp + "/export.csv").with(owner).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Không có tin nào để xuất")));
    }

    @Test
    void dangTamDungThiKhongXuatDuoc() throws Exception {
        long camp = seedCampaign();
        settings.pauseAll("kiểm tra", "test");

        mvc.perform(post("/campaigns/" + camp + "/export.csv").with(user("admin").roles("OWNER")).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("tạm dừng")));
        assertThat(jdbc.sql("SELECT status FROM invitation").query(String.class).single()).isEqualTo("READY");
    }
}
