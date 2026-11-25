package vn.shop.crm.invite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.CustomerFilter;
import vn.shop.crm.contact.ExchangeMarkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mẫu chiến dịch (PR 5): 8 mẫu dựng sẵn hợp lệ, mẫu tự lưu chỉ chứa bộ lọc, chọn mẫu chỉ điền form, không tạo gì. */
class CampaignTemplatesTest extends AbstractPostgresTest {

    @Autowired CampaignTemplates templates;
    @Autowired ExchangeMarkers markers;
    @Autowired WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void tamMauDungSanDeuHopLeVaCoDuNoiDung() {
        var list = templates.builtIn();
        assertThat(list).hasSize(8);
        assertThat(list).allSatisfy(t -> {
            assertThat(t.name()).isNotBlank();
            assertThat(t.description()).isNotBlank();
            assertThat(t.valuesJson()).contains("\"pickOrder\"", "\"purchasesMin\"", "\"skipOpenCases\"");
        });
        // mẫu "mua 1 đến 3 lần" mở cửa cho khách mua nhiều lần, còn "khách mới" vẫn đúng 1 lần
        assertThat(list.stream().filter(t -> t.key().equals("on-dinh")).findFirst().orElseThrow().filter().multi()).isTrue();
        assertThat(list.stream().filter(t -> t.key().equals("khach-moi")).findFirst().orElseThrow().filter().multi()).isFalse();
        assertThat(list.stream().filter(t -> t.key().equals("chi-theo-khu-vuc")).findFirst().orElseThrow().filter().any()).isFalse();
    }

    @Test
    void mauCanDonDoiHayDoiSoatBiKhoaChoDenKhiCoDuLieu() {
        var before = templates.builtIn();
        assertThat(before.stream().filter(t -> t.key().equals("on-dinh")).findFirst().orElseThrow().blocked()).contains("đơn đổi");
        assertThat(before.stream().filter(t -> t.key().equals("mua-nhieu")).findFirst().orElseThrow().blocked()).contains("đơn đổi");
        assertThat(before.stream().filter(t -> t.key().equals("tung-doi-hang")).findFirst().orElseThrow().blocked()).isNotNull();
        assertThat(before.stream().filter(t -> t.key().equals("gia-tri-cao")).findFirst().orElseThrow().blocked()).contains("đối soát");
        assertThat(before.stream().filter(t -> t.key().equals("khach-moi")).findFirst().orElseThrow().blocked()).isNull();

        markers.save("DOI", "", "", "admin");
        assertThat(templates.builtIn().stream().filter(t -> t.key().equals("on-dinh")).findFirst().orElseThrow().blocked()).isNull();
    }

    @Test
    void luuDocVaXoaMauTuLuu() {
        var filter = CustomerFilter.builder().purchases(2, 3).cancelled(CustomerFilter.Tri.NO).build();
        long id = templates.save("  Mẫu   của  tôi ", filter, "admin");
        var saved = templates.saved();
        assertThat(saved).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(id);
            assertThat(t.name()).isEqualTo("Mẫu của tôi"); // gọn khoảng trắng
            assertThat(t.filter().maxPurchases()).isEqualTo(3);
            assertThat(t.filter().cancelled()).isEqualTo(CustomerFilter.Tri.NO);
        });
        templates.delete(id);
        assertThat(templates.saved()).isEmpty();
    }

    @Test
    void tenMauSaiBiTuChoi() {
        var f = CustomerFilter.NONE;
        assertThatThrownBy(() -> templates.save("  ", f, "admin")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> templates.save(null, f, "admin")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> templates.save("x".repeat(61), f, "admin")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> templates.save("khách mới mua", f, "admin")).hasMessageContaining("mẫu có sẵn");
        templates.save("Mẫu A", f, "admin");
        assertThatThrownBy(() -> templates.save("mẫu a", f, "admin")).hasMessageContaining("Đã có mẫu");
    }

    @Test
    void toiDaBaMuoiMau() {
        for (int i = 0; i < CampaignTemplates.MAX_SAVED; i++) {
            templates.save("Mẫu " + i, CustomerFilter.NONE, "admin");
        }
        assertThatThrownBy(() -> templates.save("Thêm một mẫu", CustomerFilter.NONE, "admin")).hasMessageContaining("xóa bớt");
    }

    @Test
    void mauKhongChuaKhachHayDuLieuCaNhan() {
        templates.save("Mẫu sạch", CustomerFilter.builder().purchases(1, 3).build(), "admin");
        String stored = jdbc.sql("SELECT filter::text FROM campaign_template").query(String.class).single();
        assertThat(stored).doesNotContain("phone", "+84", "name", "address");
        assertThat(jdbc.sql("SELECT count(*) FROM campaign_template").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void trangTaoChienDichCoMauVaLuuXoaQuaForm() throws Exception {
        MockMvc mvc = mvc();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/campaigns").param("tab", "moi").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Bắt đầu từ mẫu")))
                .andExpect(content().string(containsString("Khách mới mua")))
                .andExpect(content().string(containsString("campaign-templates.js")))
                .andExpect(content().string(not(containsString("Mẫu của bạn"))));

        // lưu từ form: chỉ các ô lọc được lưu
        mvc.perform(post("/campaigns/templates").with(owner).with(csrf()).param("templateName", "Ổn định miền Bắc")
                        .param("purchasesMin", "1").param("purchasesMax", "3").param("cancelled", "NO").param("notBought", "PIN")
                        .param("excludeAreas", "R:NAM")).andExpect(status().is3xxRedirection());
        var saved = templates.saved().getFirst();
        assertThat(saved.filter().maxPurchases()).isEqualTo(3);
        assertThat(saved.filter().excludeAreas()).containsExactly("R:NAM");
        // chưa tạo chiến dịch nào
        assertThat(jdbc.sql("SELECT count(*) FROM invitation_campaign").query(Long.class).single()).isZero();

        mvc.perform(get("/campaigns").param("tab", "moi").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mẫu của bạn")))
                .andExpect(content().string(containsString("Ổn định miền Bắc")));

        // tên trống: lỗi người dùng, không lưu
        mvc.perform(post("/campaigns/templates").with(owner).with(csrf()).param("templateName", "")).andExpect(status().is4xxClientError());
        assertThat(templates.saved()).hasSize(1);

        mvc.perform(post("/campaigns/templates/" + saved.id() + "/delete").with(owner).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(templates.saved()).isEmpty();
    }

    @Test
    void phaiDangNhapVaCoCsrf() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(post("/campaigns/templates").with(csrf()).param("templateName", "A")).andExpect(status().is3xxRedirection());
        assertThat(templates.saved()).isEmpty();
        mvc.perform(post("/campaigns/templates").with(user("admin").roles("OWNER")).param("templateName", "A")).andExpect(status().isForbidden());
        assertThat(templates.saved()).isEmpty();
    }
}
