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
import vn.shop.crm.inbox.RefusalReviewService;
import vn.shop.crm.invite.InviteService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trang "Việc cần mẹ xử lý": khách nhắn riêng trợ lý AI nghi từ chối (tạm dừng) và những lần AI đã tự ghi (Hoàn tác). */
class RefusalReviewPageTest extends AbstractPostgresTest {

    @Autowired WebApplicationContext context;
    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired InviteService invites;
    @Autowired RefusalReviewService reviews;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String csv = "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n"
                + "T1,DH1,05/01/2026 09:00:00,Chi Lan,0900000001,1 Duong A Xa B Cần Thơ,Giao thành công\n"
                + "T2,DH2,05/01/2026 09:00:00,Anh Hung,0900000002,1 Duong A Xa B Cần Thơ,Giao thành công\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", "Chào {ten}", 14, 20);
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), zalo_target_uid = '7001' WHERE customer_id = (SELECT id FROM customer WHERE phone = '+84900000001')").update();
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), zalo_target_uid = '7002' WHERE customer_id = (SELECT id FROM customer WHERE phone = '+84900000002')").update();
    }

    private RefusalReviewService.Target target(String uid) {
        return reviews.targetFor(uid).orElseThrow();
    }

    @Test
    void hienViecTamDungVaViecAiDaGhiVoiNutDungVaDemTrenMenu() throws Exception {
        reviews.hold(target("7001"), RefusalReviewService.Reason.AI_KHONG_CHAC, "TU_CHOI", 0.6, "thôi phiền quá");
        reviews.autoRecord(target("7002"), "TU_CHOI", 0.95);
        mvc.perform(get("/handoffs").with(user("me").roles("STAFF"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Khách nhắn riêng, cần mẹ xem")))
                .andExpect(content().string(containsString("thôi phiền quá")))
                .andExpect(content().string(containsString("Chi Lan")))
                .andExpect(content().string(containsString("Khách từ chối thật, đừng nhắn lại")))
                .andExpect(content().string(containsString("Khách không từ chối, cho tiếp tục")))
                .andExpect(content().string(containsString("Trợ lý AI đã ghi khách từ chối")))
                .andExpect(content().string(containsString("Anh Hung")))
                .andExpect(content().string(containsString("Hoàn tác")));
        mvc.perform(get("/").with(user("me").roles("STAFF"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("khách nhắn riêng có thể là từ chối")));
    }

    @Test
    void nutHoanTacVaChoTiepTucChayDuoc() throws Exception {
        reviews.hold(target("7001"), RefusalReviewService.Reason.AI_LOI, null, null, "thôi");
        reviews.autoRecord(target("7002"), "TU_CHOI", 0.95);
        long held = reviews.held().get(0).id();
        long auto = reviews.recentAuto(14).get(0).id();
        mvc.perform(post("/handoffs/refusals/" + held + "/release").with(user("me").roles("STAFF")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/handoffs"));
        mvc.perform(post("/handoffs/refusals/" + auto + "/undo").with(user("me").roles("STAFF")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.sql("SELECT string_agg(status, ',' ORDER BY id) FROM invitation").query(String.class).single()).isEqualTo("SENT,SENT");
        assertThat(contacts.isSuppressed("+84900000002")).isFalse();
        mvc.perform(get("/handoffs").with(user("me").roles("STAFF")))
                .andExpect(content().string(not(containsString("Khách nhắn riêng, cần mẹ xem"))))
                .andExpect(content().string(not(containsString("Trợ lý AI đã ghi khách từ chối"))));
    }

    @Test
    void chuaDangNhapKhongBamDuoc() throws Exception {
        mvc.perform(post("/handoffs/refusals/1/confirm").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/handoffs/refusals/1/undo")).andExpect(status().isForbidden());
    }

    @Test
    void viecDaXuLyThiBaoLoiTiengViet() throws Exception {
        mvc.perform(post("/handoffs/refusals/999/confirm").with(user("me").roles("STAFF")).with(csrf()))
                .andExpect(status().is4xxClientError())
                .andExpect(content().string(containsString("Việc này đã được xử lý")));
    }
}
