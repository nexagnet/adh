package vn.shop.crm.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.contact.ContactService;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trang "Đề xuất của AI": cần đăng nhập; duyệt chỉ tạo chiến dịch CHƯA duyệt tự gửi (không tin nào đi), số khách không vượt số còn đủ điều kiện,
 * chữ của AI không vào mẫu tin gửi khách, bấm hai lần không tạo hai chiến dịch, đề xuất quá hạn không duyệt được.
 */
class AdvisorWebTest extends AbstractPostgresTest {

    static final String ANGLE = "Nhan manh troi ret hom nay";

    @Autowired WebApplicationContext context;
    @Autowired ContactService contacts;
    @Autowired Clock clock;
    MockMvc mvc;
    long runId;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(jdbc.sql("""
                    INSERT INTO customer (phone, name, address, segment, successful_purchases, province, last_order_on)
                    VALUES (:p, :n, :a, 'ONE_TIME', 1, 'tphcm', now() - interval '20 days') RETURNING id""")
                    .param("p", String.format("+84900002%03d", i)).param("n", "Khach " + i).param("a", "So " + i + " Duong Thu")
                    .query(Long.class).single());
        }
        for (int i = 0; i < 8; i++) {
            contacts.approve(ids.get(i)); // 8 khách đủ điều kiện ở miền Nam
        }
        runId = jdbc.sql("""
                INSERT INTO advisor_run (created_by, status, snapshot_json, ai_output)
                VALUES ('admin', 'OK', '{"generated_on":"2026-11-23","overall":{"customers":10}}', '{"proposals":[]}') RETURNING id""")
                .query(Long.class).single();
    }

    private LocalDate today() {
        return clock.instant().atZone(AdvisorSnapshotService.ZONE).toLocalDate();
    }

    private long proposal(String title, String area, int batch, LocalDate start) {
        return jdbc.sql("""
                INSERT INTO campaign_proposal (run_id, title, area, batch_size, start_on, end_on, window_from, window_to, daily_cap,
                    message_angle, reasons, risks, confidence, confidence_capped)
                VALUES (:run, :title, :area, :batch, :start, :end, '09:00', '11:00', 10, :angle,
                    '["Dự báo lạnh ở miền Nam"]'::jsonb, '["Chưa có lịch sử phản hồi"]'::jsonb, 0.55, TRUE) RETURNING id""")
                .param("run", runId).param("title", title).param("area", area).param("batch", batch)
                .param("start", start).param("end", start.plusDays(5)).param("angle", ANGLE).query(Long.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String state(long id) {
        return jdbc.sql("SELECT state FROM campaign_proposal WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void canDangNhap() throws Exception {
        long id = proposal("Đợt 1", "R:NAM", 5, today());
        mvc.perform(get("/advisor")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/advisor/runs/" + runId)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/advisor/run").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(count("invitation_campaign")).isZero();
        assertThat(state(id)).isEqualTo("PROPOSED");
    }

    @Test
    void trangHienLyDoRuiRoDoTinCayVaNutTatKhiTinhNangTat() throws Exception {
        proposal("Rét đợt 1, miền Nam", "R:NAM", 5, today());
        mvc.perform(get("/advisor").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rét đợt 1, miền Nam")))
                .andExpect(content().string(containsString("Dự báo lạnh ở miền Nam")))
                .andExpect(content().string(containsString("Chưa có lịch sử phản hồi")))
                .andExpect(content().string(containsString("55%")))
                .andExpect(content().string(containsString("Duyệt và tạo chiến dịch")))
                .andExpect(content().string(containsString("Tính năng đang tắt")))
                .andExpect(content().string(not(containsString("Nhờ AI đề xuất</button>"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void bamNhoAiKhiTatThiBaoLoiVaKhongGoiGi() throws Exception {
        mvc.perform(post("/advisor/run").with(user("admin")).with(csrf()))
                .andExpect(redirectedUrl("/advisor"))
                .andExpect(flash().attribute("flashBad", containsString("đang tắt")));
        assertThat(count("advisor_run")).isEqualTo(1); // chỉ lượt dựng sẵn của test, không thêm lượt nào
    }

    @Test
    void duyetTaoChienDichChuaDuyetTuGuiVaKhongCoTinNaoDi() throws Exception {
        long id = proposal("Rét đợt 1, miền Nam", "R:NAM", 5, today());
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(user("admin")).with(csrf()))
                .andExpect(redirectedUrlPattern("/campaigns/*"));
        assertThat(count("invitation_campaign")).isEqualTo(1);
        assertThat(count("invitation")).isEqualTo(5);
        assertThat(count("zalo_msg_log")).isZero();
        var row = jdbc.sql("SELECT name, message_template, geo_scope, auto_send_approved_at, auto_intake_since FROM invitation_campaign")
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4), rs.getObject(5)}).single();
        assertThat(row[0]).isEqualTo("Rét đợt 1, miền Nam");
        assertThat(row[2]).isEqualTo("R:NAM");
        assertThat(row[3]).as("chưa duyệt cho tự gửi").isNull();
        assertThat(row[4]).as("chưa bật tự nhận khách mới").isNull();
        assertThat((String) row[1]).as("chữ của AI không vào tin gửi khách").doesNotContain(ANGLE);
        var p = jdbc.sql("SELECT state, decided_by, campaign_id FROM campaign_proposal WHERE id = :id").param("id", id)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getObject(3)}).single();
        assertThat(p[0]).isEqualTo("APPROVED");
        assertThat(p[1]).isEqualTo("admin");
        assertThat(p[2]).isNotNull();
    }

    @Test
    void bamHaiLanKhongTaoHaiChienDich() throws Exception {
        long id = proposal("Đợt 1", "R:NAM", 3, today());
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(user("admin")).with(csrf()));
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(user("admin")).with(csrf()))
                .andExpect(redirectedUrl("/advisor"))
                .andExpect(flash().attribute("flashBad", containsString("đã được xử lý")));
        assertThat(count("invitation_campaign")).isEqualTo(1);
        assertThat(count("invitation")).isEqualTo(3);
    }

    @Test
    void soKhachKhongVuotSoConDuDieuKien() throws Exception {
        long id = proposal("Đợt lớn", "R:NAM", 40, today());
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(user("admin")).with(csrf()));
        assertThat(count("invitation")).isEqualTo(8);
    }

    @Test
    void khongConKhachThiKhongTaoChienDichVaDeXuatVanChoQuyetDinh() throws Exception {
        long id = proposal("Đợt miền Bắc", "R:BAC", 5, today());
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(user("admin")).with(csrf()))
                .andExpect(redirectedUrl("/advisor"))
                .andExpect(flash().attribute("flashBad", containsString("không còn khách nào")));
        assertThat(count("invitation_campaign")).isZero();
        assertThat(state(id)).isEqualTo("PROPOSED");
    }

    @Test
    void tamDungTatCaThiKhongDuyetDuoc() throws Exception {
        long id = proposal("Đợt 1", "R:NAM", 5, today());
        jdbc.sql("INSERT INTO app_setting (key, value) VALUES ('campaigns.paused', 'true') ON CONFLICT (key) DO UPDATE SET value = 'true'").update();
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(user("admin")).with(csrf()))
                .andExpect(redirectedUrl("/advisor"))
                .andExpect(flash().attribute("flashBad", containsString("tạm dừng")));
        assertThat(count("invitation_campaign")).isZero();
        assertThat(state(id)).isEqualTo("PROPOSED");
    }

    @Test
    void boQuaGhiLyDoVaKhongDuyetDuocNuaDeXuatDaXuLy() throws Exception {
        long id = proposal("Đợt 1", "R:NAM", 5, today());
        mvc.perform(post("/advisor/proposals/" + id + "/reject").with(user("admin")).with(csrf()).param("note", "  Đang bận đơn  "))
                .andExpect(redirectedUrl("/advisor"));
        var p = jdbc.sql("SELECT state, decision_note, decided_by FROM campaign_proposal WHERE id = :id").param("id", id)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}).single();
        assertThat(p).containsExactly("REJECTED", "Đang bận đơn", "admin");
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(user("admin")).with(csrf()))
                .andExpect(flash().attribute("flashBad", containsString("đã được xử lý")));
        assertThat(count("invitation_campaign")).isZero();
        mvc.perform(get("/advisor").with(user("admin")))
                .andExpect(content().string(containsString("Đang bận đơn")))
                .andExpect(content().string(containsString("Đã bỏ qua")));
    }

    @Test
    void lyDoBoQuaQuaDaiBiTuChoi() throws Exception {
        long id = proposal("Đợt 1", "R:NAM", 5, today());
        mvc.perform(post("/advisor/proposals/" + id + "/reject").with(user("admin")).with(csrf()).param("note", "x".repeat(301)))
                .andExpect(flash().attribute("flashBad", containsString("tối đa 300")));
        assertThat(state(id)).isEqualTo("PROPOSED");
    }

    @Test
    void deXuatQuaHanThanhExpiredVaKhongDuyetDuoc() throws Exception {
        long id = proposal("Đợt cũ", "R:NAM", 5, today().minusDays(1));
        mvc.perform(get("/advisor").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quá hạn")));
        assertThat(state(id)).isEqualTo("EXPIRED");
        mvc.perform(post("/advisor/proposals/" + id + "/approve").with(user("admin")).with(csrf()))
                .andExpect(flash().attribute("flashBad", containsString("đã được xử lý")));
        assertThat(count("invitation_campaign")).isZero();
    }

    @Test
    void xemDuocSoLieuDaGuiChoAiVaChuAiTraVe() throws Exception {
        mvc.perform(get("/advisor/runs/" + runId).with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("generated_on")))
                .andExpect(content().string(containsString("2026-11-23")))
                .andExpect(content().string(containsString("Số liệu đã gửi cho AI")))
                .andExpect(content().string(containsString("Chữ AI trả về")));
        mvc.perform(get("/advisor/runs/999999").with(user("admin"))).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Không tìm thấy lượt nhờ AI")));
    }

    @Test
    void menuCoMucDeXuatCuaAi() throws Exception {
        mvc.perform(get("/advisor").with(user("admin")))
                .andExpect(content().string(containsString("href=\"/advisor\"")));
    }
}
