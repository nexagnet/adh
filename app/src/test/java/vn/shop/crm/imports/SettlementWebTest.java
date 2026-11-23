package vn.shop.crm.imports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Các trang nhập đối soát J&T: bắt buộc đăng nhập và CSRF, upload, xem trước, xác nhận, danh sách kỳ. */
class SettlementWebTest extends AbstractPostgresTest {

    private static final String FILE = "Mã khách hàng,KH0001\nMã kỳ đối soát,KH0001-20261021-1022\n"
            + "Mã vận đơn,Mã đơn KH,Tên người nhận,Số điện thoại người nhận,Địa chỉ người nhận,Ngày Gửi Hàng,"
            + "Thời gian Ký Nhận,Tiền COD đã ký nhận,Tiền cước PP_PM,Phí thu hộ COD,Phí chuyển hoàn,Phí giao một phần,"
            + "Điều chỉnh,Số tiền phải trả sau cấn trừ,Thời gian điều chỉnh,Ghi chú\n"
            + "861000000001,,Khach A,0912345601,\"12 Duong A\",2026-10-21 08:00:00,2026-10-22 10:00:00,300000,20000,0,0,0,0,280000,,\n"
            + "Tổng,,,,,,,300000,,,,,,280000,,\n";

    @Autowired
    WebApplicationContext context;
    @Autowired
    ImportService imports;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static MockMultipartFile upload(String content) {
        return new MockMultipartFile("file", "KH0001-20261021-1022.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void chuaDangNhapKhongVaoDuoc() throws Exception {
        mvc.perform(get("/imports/settlement")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void taiLenKhongCoCsrfBiTuChoi() throws Exception {
        mvc.perform(multipart("/imports/settlement").file(upload(FILE)).with(user("admin").roles("OWNER")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM import_batch").query(Long.class).single()).isZero();
    }

    @Test
    void luongTaiLenXemTruocXacNhanVaDanhSachKy() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/imports/settlement").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Nhập file đối soát J&amp;T")))
                .andExpect(content().string(containsString("Chưa có kỳ đối soát nào")));

        MvcResult uploaded = mvc.perform(multipart("/imports/settlement").file(upload(FILE)).with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        String preview = uploaded.getResponse().getRedirectedUrl();
        assertThat(preview).startsWith("/imports/settlement/");

        mvc.perform(get(preview).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("KH0001-20261021-1022")))
                .andExpect(content().string(containsString("Xác nhận nhập")))
                .andExpect(content().string(containsString("Đơn chưa có trong Adh")));
        assertThat(jdbc.sql("SELECT count(*) FROM settlement_period").query(Long.class).single()).isZero();

        mvc.perform(post(preview + "/commit").with(owner)).andExpect(status().isForbidden());
        MvcResult committed = mvc.perform(post(preview + "/commit").with(owner).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/imports/settlement")).andReturn();
        // trang danh sách hiện kết quả vừa nhập (thông báo truyền qua flash)
        mvc.perform(get("/imports/settlement").with(owner).flashAttrs(committed.getFlashMap()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Đã nhập xong")));
        // bấm lần hai (tải lại trang): báo lỗi rõ ràng, không nhập đôi
        mvc.perform(post(preview + "/commit").with(owner).with(csrf())).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("đã được xác nhận")));

        mvc.perform(get("/imports/settlement").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("KH0001-20261021-1022")))
                .andExpect(content().string(containsString("280.000")));
        mvc.perform(get(preview).with(owner)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/imports/settlement"));
    }

    @Test
    void xemTruocBaoDonKyNhanMaAdhChuaTinhGiaoThanhCong() throws Exception {
        String orders = "Mã vận đơn,Trạng thái vận đơn,SĐT người nhận,Địa chỉ người nhận,Tên người nhận,Địa chỉ hành chính\n"
                + "861000000001,Kí nhận CPN,0912345601,\"12 Duong A\",Khach A,\"Phường 1, Quận 5, Hồ Chí Minh\"\n";
        long batch = imports.createBatch(vn.shop.crm.shipping.Carrier.JT_EXPRESS, "orders.csv",
                orders.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));

        var owner = user("admin").roles("OWNER");
        String preview = mvc.perform(multipart("/imports/settlement").file(upload(FILE)).with(owner).with(csrf()))
                .andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(preview).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("chưa tính là Giao thành công")))
                .andExpect(content().string(containsString("«Kí nhận CPN»")))
                .andExpect(content().string(containsString("Trạng thái đơn")));
    }

    @Test
    void fileKhongPhaiDoiSoatBaoLoiTiengViet() throws Exception {
        mvc.perform(multipart("/imports/settlement").file(upload("Mã vận đơn,Trạng thái\n1,Giao thành công\n"))
                        .with(user("admin").roles("OWNER")).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Mã kỳ đối soát")));
    }

    @Test
    void trangNhapDonVaDanhSachLoNhapCoLienKetSangDoiSoat() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/imports").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("/imports/settlement")));
        mvc.perform(get("/imports/new").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("/imports/settlement")));
    }

    @Test
    void loDoiSoatKhongMoDuocBangTrangNhapDon() throws Exception {
        var owner = user("admin").roles("OWNER");
        String preview = mvc.perform(multipart("/imports/settlement").file(upload(FILE)).with(owner).with(csrf()))
                .andReturn().getResponse().getRedirectedUrl();
        long id = Long.parseLong(preview.substring(preview.lastIndexOf('/') + 1));
        // lô đối soát không được hiện ở các trang của luồng nhập đơn
        mvc.perform(get("/imports/" + id + "/mapping").with(owner)).andExpect(status().isBadRequest());
        mvc.perform(get("/imports/" + id).with(owner)).andExpect(status().isBadRequest());
    }
}
