package vn.shop.crm.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.inbox.WarrantyService;
import vn.shop.crm.shipping.Carrier;

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

/** Công tắc phân loại bảo hành ở trang trợ lý (chỉ chủ) và việc bảo hành ở trang Việc cần người thật xử lý. */
class WarrantyPageTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired ImportService imports;
    @Autowired WarrantyService warranty;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String csv = "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n"
                + "T9,DH9,05/01/2026 09:00:00,Chi Mai,0900000009,1 Duong A Xa B Cần Thơ,Giao thành công\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        jdbc.sql("UPDATE shipment SET goods = 'Pin áo điều hòa 48V' WHERE tracking_no = 'T9'").update();
    }

    private long customer() {
        return jdbc.sql("SELECT id FROM customer WHERE phone = '+84900000009'").query(Long.class).single();
    }

    private void openCase(String urgency, String summary) {
        jdbc.sql("""
                INSERT INTO warranty_case (customer_id, zalo_uid, state, category, urgency, needs_photo, summary, draft_text, inbound_text, inbound_at)
                VALUES (:c, :u, 'OPEN', 'LOI_SAN_PHAM', :urg, TRUE, :s, 'Dạ em xin lỗi anh/chị ạ.', 'Pin sạc không vào điện', now())""")
                .param("c", customer()).param("u", "9001" + urgency).param("urg", urgency).param("s", summary).update();
    }

    @Test
    void congTacTatMacDinhVaChiChuBatTatDuoc() throws Exception {
        assertThat(warranty.enabled()).isFalse();
        mvc.perform(get("/zalo/reply").param("tab", "bat-tat").with(user("admin").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Phân loại bảo hành và khiếu nại")))
                .andExpect(content().string(containsString("không bao giờ tự gửi gì cho khách")));
        mvc.perform(post("/zalo/reply/warranty").with(user("me").roles("STAFF")).with(csrf()).param("enabled", "true"))
                .andExpect(status().isForbidden());
        assertThat(warranty.enabled()).isFalse();
        mvc.perform(post("/zalo/reply/warranty").with(user("admin").roles("OWNER")).with(csrf()).param("enabled", "true"))
                .andExpect(redirectedUrl("/zalo/reply?tab=bat-tat#bao-hanh"));
        assertThat(warranty.enabled()).isTrue();
        mvc.perform(post("/zalo/reply/warranty").with(user("admin").roles("OWNER")).with(csrf()).param("enabled", "false"))
                .andExpect(redirectedUrl("/zalo/reply?tab=bat-tat#bao-hanh"));
        assertThat(warranty.enabled()).isFalse();
    }

    @Test
    void viecBaoHanhHienKemDonVaDuocXuLyXong() throws Exception {
        openCase("CAO", "Khách báo pin sạc không vào điện.");
        mvc.perform(get("/handoffs").with(user("me").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bảo hành và khiếu nại")))
                .andExpect(content().string(containsString("Gấp")))
                .andExpect(content().string(containsString("Sản phẩm lỗi hoặc hỏng")))
                .andExpect(content().string(containsString("Khách báo pin sạc không vào điện.")))
                .andExpect(content().string(containsString("Nháp (chưa gửi): Dạ em xin lỗi anh/chị ạ.")))
                .andExpect(content().string(containsString("Pin áo điều hòa 48V")))
                .andExpect(content().string(containsString("T9")))
                .andExpect(content().string(containsString("không đọc ảnh hay video")))
                .andExpect(content().string(containsString("Nên xin khách ảnh hoặc video")))
                .andExpect(content().string(not(containsString("Không có việc nào đang chờ"))));
        mvc.perform(get("/").with(user("admin").roles("OWNER")))
                .andExpect(content().string(containsString("việc trợ lý AI chuyển cho người thật")));
        long id = warranty.open().get(0).id();
        mvc.perform(post("/handoffs/warranty/{id}/done", id).with(user("me").roles("STAFF")).with(csrf()))
                .andExpect(redirectedUrl("/handoffs"));
        assertThat(warranty.openCount()).isZero();
        mvc.perform(get("/handoffs").with(user("me").roles("STAFF")))
                .andExpect(content().string(not(containsString("Nháp (chưa gửi)"))));
    }
}
