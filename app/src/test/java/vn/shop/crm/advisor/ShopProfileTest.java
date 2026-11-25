package vn.shop.crm.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hồ sơ cửa hàng cho AI đề xuất: lưu, kiểm chữ, vào đúng số liệu gửi AI; kèm kết quả theo ngày trong tuần và so sánh mua lại. */
class ShopProfileTest extends AbstractPostgresTest {

    @Autowired ShopProfile shop;
    @Autowired AdvisorSnapshotService snapshots;
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void chuaKhaiThiKhongCoGiGuiAi() {
        assertThat(shop.load().empty()).isTrue();
        assertThat(shop.forAi()).isNull();
        assertThat(snapshots.build().insights()).isNull();
        assertThat(snapshots.build().toJson()).doesNotContain("insights");
    }

    @Test
    void luuHoSoVaVaoSoLieuChiCacOChuaKhaiLaBoQua() {
        shop.save("Áo điều hòa và phụ kiện, pin", "", "Giảm 10% so với TikTok, miễn ship", "VAO_NHOM", "Không nhắn vào Chủ nhật", "admin");
        var s = snapshots.build();
        assertThat(s.insights().shop().products()).isEqualTo("Áo điều hòa và phụ kiện, pin");
        assertThat(s.insights().shop().season()).isNull();
        assertThat(s.insights().shop().goal()).isEqualTo("VAO_NHOM");
        String json = s.toJson();
        assertThat(json).contains("\"insights\"", "\"shop\"", "Giảm 10% so với TikTok", "\"goal\":\"VAO_NHOM\"");
        assertThat(json).doesNotContain("\"season\"");
        // ô để trống là xóa ô đó
        shop.save("", "", "", "", "", "admin");
        assertThat(shop.load().empty()).isTrue();
        assertThat(snapshots.build().insights()).isNull();
    }

    @Test
    void tuChoiChuCoDuLieuCaNhanVaMucTieuLa() {
        assertThatThrownBy(() -> shop.save("Gọi 0912345678", "", "", "", "", "admin")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> shop.save("", "", "Xem shop.vn", "", "", "admin")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> shop.save("", "x".repeat(ShopProfile.MAX_LENGTH + 1), "", "", "", "admin")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> shop.save("", "", "", "BAT_KY", "", "admin")).isInstanceOf(ImportException.class)
                .hasMessageContaining("Mục tiêu");
        // lỗi một ô thì không lưu ô nào
        shop.save("Áo điều hòa", "", "", "", "", "admin");
        assertThatThrownBy(() -> shop.save("Pin", "", "email@a.com", "", "", "admin")).isInstanceOf(ImportException.class);
        assertThat(shop.load().products()).isEqualTo("Áo điều hòa");
    }

    @Test
    void ketQuaTheoNgayTrongTuanChiCoKhiDaNhanTin() {
        assertThat(snapshots.build().insights()).isNull();
        long camp = jdbc.sql("INSERT INTO invitation_campaign (name, message_template, expires_days, created_by) VALUES ('c', 't', 14, 'admin') RETURNING id")
                .query(Long.class).single();
        for (int i = 0; i < 3; i++) {
            long cust = jdbc.sql("""
                    INSERT INTO customer (phone, name, address, segment, successful_purchases, province)
                    VALUES (:p, 'A', 'B', 'ONE_TIME', 1, 'tphcm') RETURNING id""").param("p", "+8490000555" + i).query(Long.class).single();
            jdbc.sql("""
                    INSERT INTO invitation (campaign_id, customer_id, token, status, expires_at, sent_at, friended_at)
                    VALUES (:c, :u, :t, 'SENT', now() + interval '10 days', TIMESTAMPTZ '2026-11-23 10:00:00+07', CASE WHEN :f THEN now() END)""")
                    .param("c", camp).param("u", cust).param("t", "tok" + i).param("f", i == 0)
                    .update();
        }
        var days = snapshots.build().insights().sendWeekdays();
        assertThat(days).hasSize(7);
        var monday = days.get(0); // 23/11/2026 là thứ Hai
        assertThat(monday.label()).isEqualTo("T2");
        assertThat(monday.sent()).isEqualTo(3);
        assertThat(monday.friended()).isEqualTo(1);
        assertThat(days.get(1).sent()).isZero();
        assertThat(snapshots.build().toJson()).contains("\"send_weekdays\"");
    }

    @Test
    void loiNhacChoAiNoiRoHoSoLaDuLieuVaMuaLaiChiLaSoSanhQuanSat() {
        assertThat(CampaignAdvisor.SYSTEM).contains("insights", "KHÔNG nới được quy tắc nào", "KHÔNG kết luận nhóm làm họ mua nhiều hơn");
    }

    @Test
    void trangLuuHoSoCanDangNhapVaHienLaiGiaTri() throws Exception {
        mvc.perform(post("/advisor/shop").with(csrf()).param("products", "Áo điều hòa")).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/advisor/shop").with(user("admin")).param("products", "Áo điều hòa")).andExpect(status().isForbidden());
        assertThat(shop.load().empty()).isTrue();

        var admin = user("admin");
        mvc.perform(post("/advisor/shop").with(admin).with(csrf()).param("products", "Áo điều hòa và phụ kiện")
                        .param("offer", "Miễn ship khi mua trong nhóm").param("goal", "MUA_LAI"))
                .andExpect(redirectedUrl("/advisor")).andExpect(flash().attribute("flash", containsString("Đã lưu hồ sơ cửa hàng")));
        mvc.perform(get("/advisor").with(admin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Hồ sơ cửa hàng")))
                .andExpect(content().string(containsString("Áo điều hòa và phụ kiện")))
                .andExpect(content().string(containsString("Miễn ship khi mua trong nhóm")))
                .andExpect(content().string(containsString("Tăng khách mua lại")));
        mvc.perform(post("/advisor/shop").with(admin).with(csrf()).param("products", "Gọi 0912345678"))
                .andExpect(flash().attribute("flashBad", containsString("số điện thoại")));
        assertThat(shop.load().products()).isEqualTo("Áo điều hòa và phụ kiện");
    }
}
