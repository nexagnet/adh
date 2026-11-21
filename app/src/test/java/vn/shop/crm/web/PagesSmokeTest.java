package vn.shop.crm.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Các trang đã làm lại theo thiết kế Penpot phải render được, tiếng Việt thuần, không lộ mã nội bộ. */
class PagesSmokeTest extends AbstractPostgresTest {

    private static final String CSV = """
            Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái
            T1,DH1,05/01/2026 09:00:00,Nguyen Van A,0912345678,12 Duong A Ha Noi,Giao thành công
            T2,DH2,06/01/2026 09:00:00,Tran Thi B,0987654321,34 Duong B Can Tho,Giao thành công
            T3,DH3,07/01/2026 09:00:00,Tran Thi B,0987654321,34 Duong B Can Tho,Giao thành công
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
    long batch;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        batch = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", CSV.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(batch, null);
        imports.commit(batch, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    private long customerId(String phone) {
        return jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", phone).query(Long.class).single();
    }

    @Test
    void chiTietKhachHaiCotCoLoiMoiVaNhatKyBoMe() throws Exception {
        contacts.approveAllCandidates();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/customers/" + customerId("+84912345678")).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"cols2\"")))
                .andExpect(content().string(containsString("Lời mời vào nhóm")))
                .andExpect(content().string(containsString("Chưa mời")))
                .andExpect(content().string(containsString("Hệ thống")))
                .andExpect(content().string(containsString("panel danger")));
        mvc.perform(get("/customers/" + customerId("+84987654321")).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Khách này không nằm trong danh sách mời")));
    }

    @Test
    void danhSachKhachCoBoLocDemVaCotDuyetMoi() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/customers").param("segment", "ONE_TIME").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"chips\"")))
                .andExpect(content().string(containsString("Tất cả")))
                .andExpect(content().string(containsString("Cần kiểm tra")))
                .andExpect(content().string(containsString("Duyệt mời")))
                .andExpect(content().string(containsString("Lời mời")));
        mvc.perform(get("/customers").param("segment", "REPEAT").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Không có nút mời vào nhóm")));
    }

    @Test
    void trangTroLyAiVaViecCanMeKhongConChuBotVaNoiRoChuaChonCachKetNoi() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/bot").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa chọn cách nào")))
                .andExpect(content().string(containsString("Kết nối (chọn một cách trước)")))
                .andExpect(content().string(not(containsString("Bot trợ lý"))));
        mvc.perform(get("/handoffs").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa chọn cách báo cho mẹ")));
    }

    @Test
    void yeuCauCuaKhachHanTheoLoaiVaGhiNhanTheoSoDienThoai() throws Exception {
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/privacy").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("2")))
                .andExpect(content().string(containsString("ngày làm việc")))
                .andExpect(content().string(containsString("Ghi nhận yêu cầu mới")));
        mvc.perform(post("/privacy/new").with(owner).with(csrf()).param("phone", "0912345678")
                        .param("kind", "STOP_CONTACT"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/privacy/new").with(owner).with(csrf()).param("phone", "0900000000")
                        .param("kind", "ERASE"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Không tìm thấy khách")));
    }

    @Test
    void trangGuiTinVaChienDichCoNutKhoaVaNhaCungCapChuaChon() throws Exception {
        contacts.approveAllCandidates();
        long group = groups.create("Nhóm 1", "https://zalo.me/g/abc123", 5);
        groups.setStatus(group, GroupStatus.OPEN);
        invites.createCampaign("Đợt 1", null, 14, 5);
        long campaign = jdbc.sql("SELECT id FROM invitation_campaign").query(Long.class).single();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/sending").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa chọn")));
        mvc.perform(get("/campaigns/" + campaign).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Gửi tự động (đang khóa)")))
                .andExpect(content().string(containsString("Xuất danh sách để mẹ gửi tay")));
        mvc.perform(get("/groups").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chỗ trống trong các nhóm")));
    }

    @Test
    void cacBuocNhapFileCoThanhBuocVaCanhBaoThieuMaDon() throws Exception {
        var owner = user("admin").roles("OWNER");
        long fresh = imports.createBatch(Carrier.JT_EXPRESS, "j.csv", ("Mã vận đơn,Trạng thái vận đơn,SĐT người nhận\n"
                + "861000000001,Giao thành công,0912345678\n").getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/imports/new").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"steps\"")))
                .andExpect(content().string(containsString("Chọn file")));
        mvc.perform(get("/imports/" + fresh + "/mapping").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa chọn cột mã đơn hàng của shop")));
        mvc.perform(get("/imports/" + batch).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Xác nhận")));
    }

    @Test
    void trangKhachNoiRoCoTroLyAi() throws Exception {
        contacts.approveAllCandidates();
        long group = groups.create("Nhóm 1", "https://zalo.me/g/abc123", 5);
        groups.setStatus(group, GroupStatus.OPEN);
        invites.createCampaign("Đợt 1", null, 14, 5);
        String token = jdbc.sql("SELECT token FROM invitation").query(String.class).single();
        mvc.perform(get("/i/" + token)).andExpect(status().isOk())
                .andExpect(content().string(containsString("trợ lý AI")))
                .andExpect(content().string(containsString("Tôi không muốn nhận tin từ cửa hàng nữa")));
    }
}
