package vn.shop.crm.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.ai.AiClient;
import vn.shop.crm.ai.AiGate;
import vn.shop.crm.ai.AiProperties;
import vn.shop.crm.ai.AiUsage;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI đề xuất chiến dịch, đầu cuối với AI giả: chỉ gửi số liệu ẩn danh, đầu ra qua kiểm cứng, nhật ký đầy đủ, tắt mặc định, trần số lần mỗi ngày,
 * lỗi AI không làm hỏng gì, và KHÔNG tạo chiến dịch hay gửi tin nào.
 */
class AdvisorServiceTest extends AbstractPostgresTest {

    @Autowired AdvisorSnapshotService snapshots;
    @Autowired AdvisorStore store;
    @Autowired AiUsage usage;
    @Autowired ContactService contacts;
    @Autowired Clock clock;
    @Autowired ProposalService proposals;

    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    final List<String> prompts = new ArrayList<>();
    final AtomicReference<AiClient.Options> options = new AtomicReference<>();
    boolean aiDown;

    @BeforeEach
    void seed() {
        reply.set(null);
        calls.set(0);
        prompts.clear();
        aiDown = false;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(jdbc.sql("""
                    INSERT INTO customer (phone, name, address, segment, successful_purchases, province, last_order_on)
                    VALUES (:p, :n, :a, 'ONE_TIME', 1, 'tphcm', now() - interval '20 days') RETURNING id""")
                    .param("p", String.format("+84900001%03d", i)).param("n", "Tran Thi BiMat" + i)
                    .param("a", "So " + i + " Duong BiMat").query(Long.class).single());
        }
        for (int i = 0; i < 8; i++) {
            contacts.approve(ids.get(i)); // 8 khách đủ điều kiện mời
        }
    }

    private AdvisorService service(boolean advisorOn, boolean aiOn, int maxRuns) {
        AiClient client = new AiClient() {
            @Override
            public String complete(String system, String user, boolean json) {
                return complete(system, user, json, null);
            }

            @Override
            public String complete(String system, String user, boolean json, Options o) {
                calls.incrementAndGet();
                prompts.add(user);
                options.set(o);
                if (aiDown) {
                    throw new AiUnavailableException("giả: AI hỏng");
                }
                return reply.get();
            }
        };
        AiGate gate = new AiGate(client, new AiProperties(aiOn, "khoa", "m", "https://api.deepseek.com", 100, 10, 300), usage, Clock.systemUTC());
        return new AdvisorService(snapshots, new CampaignAdvisor(gate, "", true), store, clock, contacts, advisorOn, maxRuns);
    }

    private String proposal(String extra) {
        String start = java.time.LocalDate.now(AdvisorSnapshotService.ZONE).plusDays(2).toString();
        String end = java.time.LocalDate.now(AdvisorSnapshotService.ZONE).plusDays(8).toString();
        return "{\"proposals\":[{\"title\":\"Đợt đầu, TP.HCM\",\"area\":\"S:DONG_NAM_BO\",\"batch_size\":8,\"start_date\":\"" + start
                + "\",\"end_date\":\"" + end + "\",\"window_from\":\"09:00\",\"window_to\":\"11:00\",\"daily_cap\":5,"
                + "\"message_angle\":\"Hướng dẫn sử dụng\",\"reasons\":[\"Có 8 khách đủ điều kiện mời\"],\"risks\":[\"Chưa có lịch sử\"],"
                + "\"confidence\":0.9" + extra + "}],\"skip_reason\":null}";
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void deXuatDuocLuuVaKhongTaoChienDichHayGuiTin() {
        reply.set(proposal(""));
        var view = service(true, true, 5).run("admin");

        assertThat(view.result().status()).isEqualTo(CampaignAdvisor.Status.OK);
        assertThat(view.result().proposals()).hasSize(1);
        assertThat(count("advisor_run")).isEqualTo(1);
        var row = jdbc.sql("SELECT state, confidence, confidence_capped, batch_size, reasons::text AS r FROM campaign_proposal")
                .query((rs, i) -> new Object[]{rs.getString("state"), rs.getDouble("confidence"), rs.getBoolean("confidence_capped"),
                        rs.getInt("batch_size"), rs.getString("r")}).single();
        assertThat(row[0]).isEqualTo("PROPOSED");
        assertThat(row[1]).isEqualTo(0.6); // chưa có lịch sử nên bị chặn trần dù AI nói 0,9
        assertThat(row[2]).isEqualTo(true);
        assertThat(row[3]).isEqualTo(8);
        assertThat((String) row[4]).contains("8 khách đủ điều kiện");
        // AI chỉ đề xuất: không có chiến dịch, lời mời hay tin nào được tạo
        assertThat(count("invitation_campaign")).isZero();
        assertThat(count("invitation")).isZero();
        assertThat(count("zalo_msg_log")).isZero();
        // AI nhận lượt dài hơn thường với suy nghĩ sâu, trong trần cứng
        assertThat(options.get()).isEqualTo(new AiClient.Options(CampaignAdvisor.TOKENS_THINKING, 120, null, true));
        assertThat(options.get().maxOutputTokens()).isLessThanOrEqualTo(AiClient.Options.HARD_MAX_TOKENS_THINKING);
    }

    @Test
    void chiGuiSoLieuAnDanhChoAiVaLuuDungBanDaGui() {
        reply.set(proposal(""));
        service(true, true, 5).run("admin");

        assertThat(prompts).hasSize(1);
        String prompt = prompts.getFirst();
        assertThat(prompt).contains("S:DONG_NAM_BO", "max_campaign_size");
        assertThat(prompt).doesNotContain("BiMat", "Tran", "Duong", "+84", "0900001", "@", "http");
        assertThat(prompt).doesNotContainPattern("\\d{8,}");
        String saved = jdbc.sql("SELECT snapshot_json FROM advisor_run").query(String.class).single();
        assertThat(prompt).contains(saved);
    }

    @Test
    void tatMacDinhVaKhongGoiAiKhiTat() {
        reply.set(proposal(""));
        assertThatThrownBy(() -> service(false, true, 5).run("admin")).isInstanceOf(ImportException.class).hasMessageContaining("đang tắt");
        assertThatThrownBy(() -> service(true, false, 5).run("admin")).isInstanceOf(ImportException.class).hasMessageContaining("AI đang tắt");
        assertThat(calls).hasValue(0);
        assertThat(count("advisor_run")).isZero();
        assertThat(service(true, true, 5).enabled()).isTrue();
        assertThat(service(false, true, 5).enabled()).isFalse();
        assertThat(service(true, false, 5).enabled()).isFalse();
    }

    @Test
    void tranSoLanMoiNgay() {
        reply.set(proposal(""));
        AdvisorService s = service(true, true, 2);
        s.run("admin");
        s.run("admin");
        assertThat(s.runsToday()).isEqualTo(2);
        assertThatThrownBy(() -> s.run("admin")).isInstanceOf(ImportException.class).hasMessageContaining("2 lần");
        assertThat(calls).hasValue(2);
    }

    @Test
    void aiHongThiGhiNhatKyVaKhongCoDeXuat() {
        aiDown = true;
        var view = service(true, true, 5).run("admin");
        assertThat(view.result().status()).isEqualTo(CampaignAdvisor.Status.UNAVAILABLE);
        assertThat(count("campaign_proposal")).isZero();
        assertThat(jdbc.sql("SELECT status FROM advisor_run").query(String.class).single()).isEqualTo("UNAVAILABLE");
        assertThat(count("invitation_campaign")).isZero();
    }

    @Test
    void dauRaSaiHoacCoLinkThiBoVaGhiLyDo() {
        reply.set("không phải JSON");
        assertThat(service(true, true, 5).run("admin").result().status()).isEqualTo(CampaignAdvisor.Status.INVALID);

        reply.set(proposal("").replace("Hướng dẫn sử dụng", "Vào http://khuyen-mai.example.com ngay"));
        var view = service(true, true, 5).run("admin");
        assertThat(view.result().status()).isEqualTo(CampaignAdvisor.Status.INVALID);
        assertThat(view.result().rejected()).singleElement().asString().contains("đường dẫn");
        assertThat(count("campaign_proposal")).isZero();
        assertThat(jdbc.sql("SELECT rejected::text FROM advisor_run ORDER BY id DESC LIMIT 1").query(String.class).single()).contains("đường dẫn");
    }

    @Test
    void khongDeXuatGiKemLyDoThiTrangThaiBoQua() {
        reply.set("{\"proposals\":[],\"skip_reason\":\"Chưa đủ dữ liệu để đề xuất\"}");
        var view = service(true, true, 5).run("admin");
        assertThat(view.result().status()).isEqualTo(CampaignAdvisor.Status.SKIPPED);
        assertThat(jdbc.sql("SELECT skip_reason FROM advisor_run").query(String.class).single()).contains("Chưa đủ dữ liệu");
    }

    @Test
    void deXuatVuotGioiHanBiBoDuAiNoiGi() {
        // AI "bị lừa" đề xuất vượt hạn mức và khung giờ: mã bỏ hết
        reply.set(proposal("").replace("\"daily_cap\":5", "\"daily_cap\":500").replace("\"window_to\":\"11:00\"", "\"window_to\":\"23:00\""));
        var view = service(true, true, 5).run("admin");
        assertThat(view.result().proposals()).isEmpty();
        assertThat(view.result().rejected()).singleElement().asString().contains("daily_cap");
    }

    /** Biến 3 khách chưa duyệt thành khách mua 2 lần (chưa có cơ sở liên hệ): chỉ chiến dịch cho phép mua nhiều lần mới mời được. */
    private void makeRepeatBuyers() {
        jdbc.sql("""
                UPDATE customer SET segment = 'REPEAT', successful_purchases = 2
                WHERE phone IN ('+84900001008', '+84900001009', '+84900001010')""").update();
    }

    @Test
    void boLocKhopThiLuuNguyenVanVaDuyetTaoChienDichDungBoLoc() {
        makeRepeatBuyers();
        reply.set(proposal(",\"filter\":{\"purchases_min\":2,\"purchases_max\":3}").replace("\"batch_size\":8", "\"batch_size\":3"));
        var view = service(true, true, 5).run("admin");

        assertThat(view.result().proposals()).hasSize(1);
        assertThat(view.result().proposals().getFirst().filter().multi()).isTrue();
        String saved = jdbc.sql("SELECT filter_json::text FROM campaign_proposal").query(String.class).single();
        assertThat(saved).contains("\"purchasesMin\"", "\"purchasesMax\"");
        // duyệt: chiến dịch mang đúng bộ lọc và số lần mua; khách mua 2 lần được duyệt riêng cho chiến dịch này
        long pid = proposals.open().getFirst().id();
        assertThat(proposals.open().getFirst().multi()).isTrue();
        long campaignId = proposals.approve(pid, "admin");
        assertThat(jdbc.sql("SELECT max_purchases FROM invitation_campaign WHERE id = :id").param("id", campaignId).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT customer_filter::text FROM invitation_campaign WHERE id = :id").param("id", campaignId).query(String.class).single())
                .contains("\"purchasesMax\"");
        assertThat(count("invitation")).isEqualTo(3);
        // vẫn chưa gửi gì cho ai
        assertThat(count("zalo_msg_log")).isZero();
    }

    @Test
    void boLocKhongKhopAiThiBoDeXuatVaGhiLyDo() {
        makeRepeatBuyers();
        reply.set(proposal(",\"filter\":{\"purchases_min\":5,\"purchases_max\":6}"));
        var view = service(true, true, 5).run("admin");

        assertThat(view.result().proposals()).isEmpty();
        assertThat(view.result().rejected()).singleElement().asString().contains("không khớp khách nào");
        assertThat(count("campaign_proposal")).isZero();
    }

    @Test
    void boLocKhopItHonLoThiKepLoVaGhiRuiRo() {
        makeRepeatBuyers();
        reply.set(proposal(",\"filter\":{\"purchases_min\":2,\"purchases_max\":3}")); // lô 8, chỉ 3 khách khớp
        var view = service(true, true, 5).run("admin");

        var p = view.result().proposals().getFirst();
        assertThat(p.batchSize()).isEqualTo(3);
        assertThat(p.risks()).anyMatch(r -> r.contains("chỉ khớp 3 khách"));
        assertThat(jdbc.sql("SELECT batch_size FROM campaign_proposal").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void boLocSaiKhuonCuaAiBiBoVaKhongDungDenKhach() {
        reply.set(proposal(",\"filter\":{\"so_dien_thoai\":\"0900001001\"}"));
        var view = service(true, true, 5).run("admin");

        assertThat(view.result().proposals()).isEmpty();
        assertThat(view.result().rejected()).singleElement().asString().contains("filter");
        assertThat(count("invitation_campaign")).isZero();
    }

    @Test
    void soLieuBoLocChoAiChiLaSoDemAnDanh() {
        makeRepeatBuyers();
        // thêm khách mua 2 lần cho đủ ngưỡng ẩn (10): 3 + 8 = 11
        for (int i = 0; i < 8; i++) {
            jdbc.sql("""
                    INSERT INTO customer (phone, name, address, segment, successful_purchases, province, last_order_on)
                    VALUES (:p, 'Rieng Tu', 'So 1 Duong Kin', 'REPEAT', 2, 'tphcm', now() - interval '20 days')""")
                    .param("p", String.format("+84900002%03d", i)).update();
        }
        var audience = snapshots.build().insights().audience();
        assertThat(audience.multiPurchase2()).isEqualTo(11L);
        assertThat(audience.multiPurchase3()).isNull(); // dưới 10 khách thì ẩn
        String json = snapshots.build().toJson();
        assertThat(json).contains("\"multi_purchase2\":11", "\"exchange_ready\":false");
        assertThat(json).doesNotContain("Rieng", "Kin", "+84", "0900002", "BiMat", "Tran");
        assertThat(json).doesNotContainPattern("\\d{8,}");
    }

    @Test
    void loiNhacAiVeBoLoc() {
        assertThat(CampaignAdvisor.SYSTEM).contains("filter", "audience");
    }
}
