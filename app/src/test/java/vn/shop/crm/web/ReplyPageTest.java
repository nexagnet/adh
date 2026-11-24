package vn.shop.crm.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.inbox.ReplyInfo;
import vn.shop.crm.inbox.ReplyService;
import vn.shop.crm.shipping.Carrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trang "Trợ lý AI trả lời khách" (D20) và việc chuyển người thật ở trang Việc cần người thật xử lý. */
class ReplyPageTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired ImportService imports;
    @Autowired ReplyService replies;
    @Autowired ReplyInfo info;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String csv = "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n"
                + "T9,DH9,05/01/2026 09:00:00,Chi Mai,0900000009,1 Duong A Xa B Cần Thơ,Giao thành công\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    private long customer() {
        return jdbc.sql("SELECT id FROM customer WHERE phone = '+84900000009'").query(Long.class).single();
    }

    @Test
    void chiChuVaoDuocTrangVaThayBangThongTinMacDinh() throws Exception {
        mvc.perform(get("/zalo/reply")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/zalo/reply").param("tab", "thong-tin").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Trợ lý AI trả lời khách")))
                .andExpect(content().string(containsString("Bảng thông tin cửa hàng")))
                .andExpect(content().string(containsString("giảm 10%")))
                .andExpect(content().string(containsString("áo điều hòa")))
                .andExpect(content().string(containsString("Thử một câu")));
    }

    @Test
    void luuNacVaBangThongTin() throws Exception {
        mvc.perform(post("/zalo/reply/mode").with(user("admin").roles("OWNER")).with(csrf()).param("mode", "DRAFT"))
                .andExpect(redirectedUrl("/zalo/reply?tab=bat-tat"));
        assertThat(replies.mode()).isEqualTo(ReplyService.Mode.DRAFT);
        mvc.perform(post("/zalo/reply/mode").with(user("admin").roles("OWNER")).with(csrf()).param("mode", "AUTO"))
                .andExpect(status().isBadRequest()); // chưa tick xác nhận
        mvc.perform(post("/zalo/reply/info").with(user("admin").roles("OWNER")).with(csrf()).param("warranty", "Bảo hành 6 tháng"))
                .andExpect(redirectedUrl("/zalo/reply?tab=thong-tin"));
        assertThat(info.value("warranty")).isEqualTo("Bảo hành 6 tháng");
        assertThat(info.value("offer")).contains("10%"); // ô không gửi lên thì giữ nguyên
    }

    @Test
    void thuMotCauKhiAiTatThiBaoRoKhongLuuGi() throws Exception {
        mvc.perform(post("/zalo/reply/preview").with(user("admin").roles("OWNER")).with(csrf()).param("text", "vào nhóm mất phí không"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AI đang tắt")));
        assertThat(jdbc.sql("SELECT count(*) FROM reply_draft").query(Long.class).single()).isZero();
    }

    @Test
    void viecChuyenNguoiThatHienOTrangViecVaDuocXuLyXong() throws Exception {
        long c = customer();
        jdbc.sql("""
                INSERT INTO reply_draft (customer_id, zalo_uid, state, reason, inbound_text, inbound_at)
                VALUES (:c, '8001', 'HANDOFF', 'NGOAI_PHAM_VI', 'Pin 48V giá bao nhiêu', now())""").param("c", c).update();
        mvc.perform(get("/handoffs").with(user("me").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Khách nhắn riêng, cần người thật trả lời")))
                .andExpect(content().string(containsString("Pin 48V giá bao nhiêu")))
                .andExpect(content().string(containsString("Khách hỏi ngoài việc mời vào nhóm")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Không có việc nào đang chờ"))));
        mvc.perform(get("/").with(user("admin").roles("OWNER")))
                .andExpect(content().string(containsString("việc trợ lý AI chuyển cho người thật")));
        long id = replies.handoffs().get(0).id();
        mvc.perform(post("/handoffs/replies/{id}/done", id).with(user("me").roles("STAFF")).with(csrf()))
                .andExpect(redirectedUrl("/handoffs"));
        assertThat(replies.openCount()).isZero();
    }

    @Test
    void nhapHienOTrangTroLyKemNutDaXuLy() throws Exception {
        long c = customer();
        jdbc.sql("""
                INSERT INTO reply_draft (customer_id, zalo_uid, state, inbound_text, reply_text, inbound_at)
                VALUES (:c, '8002', 'DRAFT', 'Vào nhóm mất phí không', 'Dạ không mất phí ạ.', now())""").param("c", c).update();
        mvc.perform(get("/zalo/reply").with(user("admin").roles("OWNER")))
                .andExpect(content().string(containsString("Nháp trợ lý soạn")))
                .andExpect(content().string(containsString("Dạ không mất phí ạ.")));
    }

    @Test
    void trangCoKhoiHoSoKhachVaLuuDanhSachTruongDuocGui() throws Exception {
        mvc.perform(get("/zalo/reply").param("tab", "nang-cao").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hồ sơ khách gửi cho AI")))
                .andExpect(content().string(containsString("Khu vực của khách")))
                .andExpect(content().string(containsString("mẫu giả")));
        mvc.perform(post("/zalo/reply/context").with(user("admin").roles("OWNER")).with(csrf()).param("field", "khu_vuc").param("field", "nhom"))
                .andExpect(redirectedUrl("/zalo/reply?tab=nang-cao"));
        assertThat(replies.context().enabled()).extracting(vn.shop.crm.inbox.ReplyContext.Field::key).containsExactly("khu_vuc", "nhom");
        mvc.perform(post("/zalo/reply/context").with(user("admin").roles("OWNER")).with(csrf())).andExpect(redirectedUrl("/zalo/reply?tab=nang-cao"));
        assertThat(replies.context().enabled()).isEmpty();
        mvc.perform(post("/zalo/reply/context").with(csrf())).andExpect(status().is3xxRedirection()); // chưa đăng nhập
    }
}
