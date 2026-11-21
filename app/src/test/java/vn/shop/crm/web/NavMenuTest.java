package vn.shop.crm.web;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Thanh bên trái chia nhóm và khối "Việc cần làm hôm nay" (thiết kế Penpot đã duyệt). */
class NavMenuTest extends AbstractPostgresTest {

    private static final String CSV = """
            Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái
            T1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A,Giao thành công
            """;

    @Autowired
    WebApplicationContext context;
    @Autowired
    ImportService imports;
    @Autowired
    NavMenu menu;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void khoaMucDangMoPhanBietKhachMuaMotLanVaTuHaiLan() {
        assertThat(NavMenu.keyOf("/customers", "ONE_TIME")).isEqualTo("/customers/one-time");
        assertThat(NavMenu.keyOf("/customers", "REPEAT")).isEqualTo("/customers/repeat");
        assertThat(NavMenu.keyOf("/customers/12", null)).isEqualTo("/customers/one-time");
        assertThat(NavMenu.keyOf("/groups", null)).isEqualTo("/groups");
        // xuất file và lọc "cần kiểm tra" không tô sai mục
        assertThat(NavMenu.keyOf("/customers/export.csv", "ONE_TIME")).isEqualTo("/customers/export.csv");
        assertThat(NavMenu.keyOf("/customers", null)).isEqualTo("/customers");
    }

    @Test
    void chiMotMucDuocToSangVaCoDuBonNhom() {
        var groups = menu.groups("/groups");
        assertThat(groups).extracting(NavMenu.Group::title)
                .containsExactly("Khách hàng", "Mời vào nhóm", "Trợ lý AI", "Khác");
        var active = groups.stream().flatMap(g -> g.items().stream()).filter(NavMenu.Item::active).toList();
        assertThat(active).extracting(NavMenu.Item::label).containsExactly("Nhóm Zalo");
        assertThat(menu.groups("/").stream().flatMap(g -> g.items().stream()).filter(NavMenu.Item::active)
                .map(NavMenu.Item::label)).containsExactly("Tổng quan");
    }

    @Test
    void thanhBenHienTrenTrangDaDangNhapVaToSangMucDangMo() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/groups").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mời vào nhóm")))
                .andExpect(content().string(containsString("Tài khoản trợ lý AI")))
                .andExpect(content().string(containsString("class=\"active\"")))
                .andExpect(content().string(not(containsString(">Bot<"))));
        mvc.perform(get("/customers").param("segment", "REPEAT").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Khách mua từ 2 lần")));
    }

    @Test
    void tongQuanCoViecCanLamHomNayKhiDaCoDon() throws Exception {
        var owner = user("admin").roles("OWNER");
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", CSV.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        mvc.perform(get("/").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Việc cần làm hôm nay")))
                .andExpect(content().string(containsString("đang chờ duyệt mời")));
    }
}
