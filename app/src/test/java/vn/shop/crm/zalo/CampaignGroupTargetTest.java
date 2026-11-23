package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.CampaignGroupTarget.Target;
import vn.shop.crm.zalo.ZaloInviteSender.Step;
import vn.shop.crm.zalo.ZaloMessenger.Kind;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D21 PR 6: nhóm Zalo đích riêng của chiến dịch chỉ làm CHẶT hơn hệ thống. "Chỉ gửi link nhóm" thì khách kết bạn KHÔNG bị bộ nối tự thêm vào nhóm
 * mà nhận Tin 2 (link nhóm) để tự bấm; không cho gom vào nhóm mới tự tạo; Tin 2 riêng của chiến dịch. Để trống thì chạy y như trước.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class CampaignGroupTargetTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloAutoService zalo;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;
    @Autowired CampaignGroupTarget target;
    @Autowired ZaloGroupCreationService creation;
    @Autowired WebApplicationContext context;

    long campaign;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 5; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",090000000").append(i).append(",1 Duong A Xa B Hà Nội,Giao thành công\n");
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        long north = groups.create("Nhóm miền Bắc", "https://zalo.me/g/bac", 50, "R:BAC");
        groups.setStatus(north, GroupStatus.OPEN);
        groups.setZaloGroupId(north, "1001");
        contacts.approveAllCandidates();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
        appSettings.set("zalo.msg.enabled", "false", "test");
        appSettings.set("zalo.msg.daily", "10", "test");
        appSettings.set("zalo.msg.gap_seconds", "120", "test");
        appSettings.set("zalo.auto.mode", "OFF", "test");
        appSettings.set("campaigns.paused", "false", "test");
        cfg.reopen("test");
        cfg.setMode(ZaloMode.AUTO, true, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
    }

    private String col(String column, long invitationId) {
        return jdbc.sql("SELECT " + column + " FROM invitation WHERE id = :id").param("id", invitationId).query(String.class).optional().orElse(null);
    }

    /** Gửi Tin 1 cho khách đầu tiên rồi cho khách đó kết bạn. Trả mã lời mời và mã Zalo. */
    private long friend() {
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        long inv = jdbc.sql("SELECT id FROM invitation WHERE status = 'SENT'").query(Long.class).single();
        String uid = col("zalo_target_uid", inv);
        fake.pushAdd(uid, null, 1);
        assertThat(zalo.poll()).isEqualTo(1);
        assertThat(col("status", inv)).isEqualTo("FRIENDED");
        return inv;
    }

    private void after(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    @Test
    void chuaDatGiThiTuThemVaoNhomNhuCu() {
        long inv = friend();
        assertThat(target.load(campaign)).isEqualTo(Target.SYSTEM);
        assertThat(col("zalo_add_state", inv)).isEqualTo("QUEUED");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds).hasSize(1);
    }

    @Test
    void chiGuiLinkNhomThiKhongTuThemMaGuiTin2ChoKhachTuBam() {
        target.save(campaign, new Target(true, true, null), "mẹ");
        long inv = friend();
        assertThat(col("zalo_add_state", inv)).isNull();
        assertThat(col("zalo_link_state", inv)).isEqualTo("PENDING");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOTHING);
        assertThat(fake.adds).isEmpty(); // bộ nối không thêm ai vào nhóm
        after(120);
        assertThat(sender.step()).isEqualTo(Step.LINK_SENT);
        var link = fake.messages.get(fake.messages.size() - 1);
        assertThat(link.kind()).isEqualTo(Kind.LINK);
        assertThat(link.text()).contains("https://zalo.me/g/bac");
        assertThat(col("zalo_link_state", inv)).isEqualTo("SENT");
        assertThat(fake.adds).isEmpty();
    }

    @Test
    void chiGuiLinkNhomNhungChuaBatTuGuiThiKhongXepTinNao() {
        target.save(campaign, new Target(true, true, null), "mẹ");
        messages.setEnabled(false, "mẹ");
        assertThat(sender.step()).isEqualTo(Step.NOT_ACTIVE);
        // khách kết bạn khi công tắc tắt: không xếp tin, không thêm nhóm
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), zalo_target_uid = '777' WHERE id = (SELECT min(id) FROM invitation)").update();
        fake.pushAdd("777", null, 1);
        zalo.poll();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE zalo_link_state IS NOT NULL OR zalo_add_state IS NOT NULL")
                .query(Long.class).single()).isZero();
        assertThat(fake.adds).isEmpty();
    }

    @Test
    void tin2RiengCuaChienDichDuocDungThayMauChung() {
        target.save(campaign, new Target(true, true, "Link nhóm riêng của đợt này: {link_nhom}"), "mẹ");
        friend();
        after(120);
        assertThat(sender.step()).isEqualTo(Step.LINK_SENT);
        assertThat(fake.messages.get(fake.messages.size() - 1).text()).isEqualTo("Link nhóm riêng của đợt này: https://zalo.me/g/bac");
    }

    @Test
    void khachCuaChienDichChiLinkVaChienDichTatTaoNhomKhongBiGomVaoNhomMoi() {
        // khách ở miền Nam, chưa có nhóm hợp khu vực: bình thường sẽ nằm trong danh sách chờ tạo nhóm
        jdbc.sql("UPDATE customer SET province = 'tphcm'").update();
        long inv = friend();
        jdbc.sql("UPDATE invitation SET zalo_add_state = 'QUEUED'").update();
        var all = creation.waitingCustomers();
        long before = all.stream().filter(w -> w.invitationId() == inv).count();
        assertThat(before).isEqualTo(1); // trước khi đặt gì, khách này đang chờ nhóm mới
        target.save(campaign, new Target(false, false, null), "mẹ"); // tắt "cho gom vào nhóm mới tự tạo"
        assertThat(creation.waitingCustomers().stream().filter(w -> w.invitationId() == inv)).isEmpty();
        target.save(campaign, new Target(true, true, null), "mẹ"); // chỉ gửi link: cũng không gom
        assertThat(creation.waitingCustomers().stream().filter(w -> w.invitationId() == inv)).isEmpty();
        target.save(campaign, Target.SYSTEM, "mẹ");
        assertThat(creation.waitingCustomers().stream().filter(w -> w.invitationId() == inv).count()).isEqualTo(before);
    }

    @Test
    void mauKhongCoLinkBiTuChoiVaKhongLuu() {
        assertThatThrownBy(() -> target.save(campaign, new Target(false, true, "Mời mình vào nhóm nhé"), "mẹ"))
                .isInstanceOf(ImportException.class).hasMessageContaining("link_nhom");
        assertThat(target.load(campaign)).isEqualTo(Target.SYSTEM);
    }

    @Test
    void trangLuuRoiHienLaiVaNutTheoHeThongXoa() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/campaigns/" + campaign + "/group-target").with(csrf()).param("linkOnly", "true")).andExpect(status().is3xxRedirection());
        assertThat(target.load(campaign)).isEqualTo(Target.SYSTEM); // chưa đăng nhập thì không lưu
        mvc.perform(post("/campaigns/" + campaign + "/group-target").with(user("admin")).with(csrf())
                        .param("linkOnly", "true").param("allowCreation", "true").param("linkTemplate", "Vào nhóm: {link_nhom}"))
                .andExpect(redirectedUrl("/campaigns/" + campaign));
        assertThat(target.load(campaign)).isEqualTo(new Target(true, true, "Vào nhóm: {link_nhom}"));
        mvc.perform(get("/campaigns/" + campaign).with(user("admin")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Nhóm Zalo của chiến dịch")));
        mvc.perform(post("/campaigns/" + campaign + "/group-target").with(user("admin")).with(csrf()).param("linkTemplate", "thiếu link"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("link_nhom")));
        mvc.perform(post("/campaigns/" + campaign + "/group-target").with(user("admin")).with(csrf()).param("reset", "true"))
                .andExpect(redirectedUrl("/campaigns/" + campaign));
        assertThat(target.load(campaign)).isEqualTo(Target.SYSTEM);
    }

    @Autowired vn.shop.crm.inbox.ComebackService comebacks;

    @Test
    void trangLuuNacKhachQuayLaiRiengCuaChienDichVaTuChoiNacLa() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/campaigns/" + campaign + "/comeback").with(csrf()).param("mode", "TASKS")).andExpect(status().is3xxRedirection());
        assertThat(comebacks.campaignMode(campaign)).isNull(); // chưa đăng nhập thì không lưu
        mvc.perform(post("/campaigns/" + campaign + "/comeback").with(user("admin")).with(csrf()).param("mode", "TASKS"))
                .andExpect(redirectedUrl("/campaigns/" + campaign));
        assertThat(comebacks.campaignMode(campaign)).isEqualTo(vn.shop.crm.inbox.ComebackService.Mode.TASKS);
        mvc.perform(get("/campaigns/" + campaign).with(user("admin")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Khách từng từ chối nhưng nhắn lại muốn mua")));
        mvc.perform(post("/campaigns/" + campaign + "/comeback").with(user("admin")).with(csrf()).param("mode", "TUY_Y"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("không đúng")));
        mvc.perform(post("/campaigns/" + campaign + "/comeback").with(user("admin")).with(csrf()).param("mode", ""))
                .andExpect(redirectedUrl("/campaigns/" + campaign));
        assertThat(comebacks.campaignMode(campaign)).isNull();
    }
}
