package vn.shop.crm.contact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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

/** Các trang cơ sở liên hệ và quyền dữ liệu: bắt buộc đăng nhập + CSRF, render được, xác nhận trước khi xóa. */
class ContactWebTest extends AbstractPostgresTest {

    private static final String CSV = """
            Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái
            T1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công
            T2,DH2,06/01/2026 09:00:00,Tran Thi B,0987654321,34 Duong B,Giao thành công
            """;

    @Autowired
    WebApplicationContext context;
    @Autowired
    ImportService imports;
    @Autowired
    PrivacyService privacy;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private long seedAndGetFirstCustomerId() {
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", CSV.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        return jdbc.sql("SELECT id FROM customer WHERE phone = '+84912345678'").query(Long.class).single();
    }

    @Test
    void chuaDangNhapKhongVaoDuocCacTrangMoi() throws Exception {
        mvc.perform(get("/contacts")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/privacy")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void postKhongCoCsrfBiTuChoi() throws Exception {
        long id = seedAndGetFirstCustomerId();
        var owner = user("admin").roles("OWNER");
        mvc.perform(post("/contacts/approve-all").with(owner)).andExpect(status().isForbidden());
        mvc.perform(post("/customers/" + id + "/privacy/erase").with(owner).param("confirm", "XOA"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void duyetHangLoatPhaiTichONhanTrachNhiemVaKhongGuiGiHet() throws Exception {
        seedAndGetFirstCustomerId();
        var owner = user("admin").roles("OWNER");

        mvc.perform(post("/contacts/approve-all").with(owner).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("xác nhận trách nhiệm")));
        assertThat(jdbc.sql("SELECT count(*) FROM contact_basis").query(Long.class).single()).isZero();

        mvc.perform(post("/contacts/approve-all").with(owner).with(csrf()).param("confirm", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/contacts"));
        assertThat(jdbc.sql("SELECT count(*) FROM contact_basis WHERE status = 'ELIGIBLE'").query(Long.class).single())
                .isEqualTo(2);

        mvc.perform(get("/contacts").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đủ điều kiện mời")))
                .andExpect(content().string(containsString("0912 345 678")));
    }

    @Test
    void trangChiTietKhachHienCoSoLienHeVaVungXoa() throws Exception {
        long id = seedAndGetFirstCustomerId();
        var owner = user("admin").roles("OWNER");

        mvc.perform(get("/customers/" + id).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa duyệt")))
                .andExpect(content().string(containsString("Duyệt: được mời vào nhóm")))
                .andExpect(content().string(containsString("Xóa dữ liệu của khách")));

        mvc.perform(post("/customers/" + id + "/approve").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/customers/" + id).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã duyệt, được mời")))
                .andExpect(content().string(containsString("Chủ cửa hàng chịu trách nhiệm")));
    }

    @Test
    void xoaKhachPhaiGoDungChuXoa() throws Exception {
        long id = seedAndGetFirstCustomerId();
        var owner = user("admin").roles("OWNER");

        mvc.perform(post("/customers/" + id + "/privacy/erase").with(owner).with(csrf()).param("confirm", "khong"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(2);

        mvc.perform(post("/customers/" + id + "/privacy/erase").with(owner).with(csrf()).param("confirm", "xoa"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/customers"));
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void xuatDuLieuKhachTraVeFileCsv() throws Exception {
        long id = seedAndGetFirstCustomerId();
        var owner = user("admin").roles("OWNER");

        mvc.perform(post("/customers/" + id + "/privacy/export.csv").with(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("T1")));
    }

    @Test
    void trangYeuCauVaTongQuanRenderDuoc() throws Exception {
        long id = seedAndGetFirstCustomerId();
        var owner = user("admin").roles("OWNER");

        mvc.perform(post("/customers/" + id + "/privacy/open").with(owner).with(csrf())
                .param("kind", "ERASE").param("receivedOn", java.time.LocalDate.now().toString()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/privacy").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Xóa dữ liệu")))
                .andExpect(content().string(containsString("***678")));
        mvc.perform(get("/").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("yêu cầu của khách đang chờ xử lý")))
                .andExpect(content().string(containsString("đang chờ bạn duyệt để được mời vào nhóm")));
    }

    @Test
    void hoanTatDungMotYeuCauXemVaChiGhiNhatKyMotLan() throws Exception {
        long customer = seedAndGetFirstCustomerId();
        var overdue = LocalDate.now().minusWeeks(1);
        long access = privacy.openRequest(customer, PrivacyService.Kind.ACCESS, overdue, null);
        long otherAccess = privacy.openRequest(customer, PrivacyService.Kind.ACCESS, LocalDate.now(), null);
        long export = privacy.openRequest(customer, PrivacyService.Kind.EXPORT, LocalDate.now(), null);
        String path = "/privacy/" + access + "/complete-access";
        var owner = user("chu-shop").roles("OWNER");

        mvc.perform(get("/privacy").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString(path)))
                .andExpect(content().string(not(containsString("/privacy/" + export + "/complete-access"))));
        mvc.perform(post(path).with(owner)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("nhan-vien").roles("STAFF")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/privacy/" + export + "/complete-access").with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/privacy/99999/complete-access").with(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(privacy.openCount()).isEqualTo(3);
        assertThat(privacy.overdueCount()).isEqualTo(1);

        mvc.perform(post(path).with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/privacy"));
        var timestamp = accessCompletionTime();
        mvc.perform(post(path).with(user("chu-shop-khac").roles("OWNER")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/privacy"));

        assertThat(privacy.requests(true)).extracting(PrivacyService.RequestRow::id)
                .containsExactlyInAnyOrder(otherAccess, export);
        assertThat(privacy.overdueCount()).isZero();
        assertThat(accessCompletionTime()).isEqualTo(timestamp);
        assertThat(jdbc.sql("SELECT recorded_by FROM contact_event WHERE event = 'ACCESS_COMPLETED'")
                .query(String.class).single()).isEqualTo("chu-shop");
        mvc.perform(get("/privacy?tab=done").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString(path))));
    }

    @Test
    void ghiNhanDaPhanHoiYeuCauXemSauKhiHoSoDaXoa() throws Exception {
        long customer = seedAndGetFirstCustomerId();
        long request = privacy.openRequest(customer, PrivacyService.Kind.ACCESS, LocalDate.now(), null);
        privacy.erase(customer, LocalDate.now(), null);
        var owner = user("chu-shop").roles("OWNER");

        mvc.perform(get("/privacy").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Hồ sơ khách không còn")))
                .andExpect(content().string(not(containsString("href=\"/customers/" + customer + "\""))));
        mvc.perform(post("/privacy/" + request + "/complete-access").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(privacy.openCount()).isZero();
        assertThat(jdbc.sql("SELECT phone_tail FROM contact_event WHERE event = 'ACCESS_COMPLETED'")
                .query(String.class).single()).isEqualTo("***678");
    }

    private OffsetDateTime accessCompletionTime() {
        return jdbc.sql("SELECT recorded_at FROM contact_event WHERE event = 'ACCESS_COMPLETED'")
                .query(OffsetDateTime.class).single();
    }
}
