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
    void khoaMucDangMoGomMotKhachVaTrangDanhSachVaoMucKhachHang() {
        assertThat(NavMenu.keyOf("/customers")).isEqualTo("/customers");
        assertThat(NavMenu.keyOf("/customers/12")).isEqualTo("/customers");
        assertThat(NavMenu.keyOf("/groups")).isEqualTo("/groups");
        assertThat(NavMenu.keyOf("/customers/export.csv")).isEqualTo("/customers/export.csv");
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
                .andExpect(content().string(containsString("Trợ lý trong nhóm")))
                .andExpect(content().string(containsString("class=\"active\"")))
                .andExpect(content().string(not(containsString(">Bot<"))));
        mvc.perform(get("/customers").param("segment", "REPEAT").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mua từ 2 lần")));
    }

    @Test
    void menuCoTrangTraLoiKhachVaDeXuatAiVaToSangDungMuc() {
        var all = menu.groups("/zalo/reply").stream().flatMap(g -> g.items().stream()).toList();
        assertThat(all).extracting(NavMenu.Item::label).contains("Trợ lý AI trả lời khách", "Đề xuất của AI", "Hiệu quả chiến dịch");
        // /zalo/reply có mục riêng nên không được tô sáng thêm "Zalo tài khoản phụ".
        assertThat(all.stream().filter(NavMenu.Item::active).map(NavMenu.Item::label)).containsExactly("Trợ lý AI trả lời khách");
        assertThat(menu.groups("/zalo").stream().flatMap(g -> g.items().stream()).filter(NavMenu.Item::active)
                .map(NavMenu.Item::label)).containsExactly("Zalo tài khoản phụ");
        // Ba mục nhắn tay và chạy thử (sẽ bỏ) nằm cuối nhóm, sau các mục của luồng tự động.
        var invite = menu.groups("/").get(1).items().stream().map(NavMenu.Item::label).toList();
        assertThat(invite.indexOf("Zalo tài khoản phụ")).isLessThan(invite.indexOf("Chờ vào nhóm"));
        assertThat(invite.indexOf("Đề xuất của AI")).isLessThan(invite.indexOf("Lô nhắn hôm nay"));
    }

    @Test
    void tongQuanHienKhoiTuGuiVaDeXuatAi() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tự gửi tin hôm nay")))
                .andExpect(content().string(containsString("Đề xuất chiến dịch của AI đang chờ")))
                .andExpect(content().string(containsString("Trợ lý AI trả lời khách")));
    }

    @Test
    void moiTrangQuanTriCoDaiCheDoThuHoacChayThat() throws Exception {
        // Test chạy với app.zalo.safety.live=true nên dải đỏ "CHẠY THẬT" phải hiện ở mọi trang điều khiển, kể cả các trang trước đây thiếu dải.
        var owner = user("admin").roles("OWNER");
        for (String path : new String[]{"/handoffs", "/performance", "/zalo/reply", "/friends", "/groups", "/map", "/bot", "/campaigns", "/advisor"}) {
            mvc.perform(get(path).with(owner)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Đang CHẠY THẬT")));
        }
    }

    @Test
    void tongQuanCoViecCanLamHomNayKhiDaCoDon() throws Exception {
        var owner = user("admin").roles("OWNER");
        long batch = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", CSV.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
        mvc.perform(get("/").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Việc cần làm hôm nay")))
                .andExpect(content().string(containsString("đang chờ bạn duyệt để được mời vào nhóm")));
    }
}
