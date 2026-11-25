package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.common.PhoneHash;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.inbox.ReplyService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.safety.TestMode;
import vn.shop.crm.safety.ZaloSafetyProperties;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloGateway.AddResult;
import vn.shop.crm.zalo.ZaloGateway.Outcome;
import vn.shop.crm.zalo.ZaloInviteSender.Step;
import vn.shop.crm.zalo.ZaloMessenger.Kind;
import vn.shop.crm.zalo.ZaloMessenger.Result;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CHẾ ĐỘ THỬ (mặc định): dù công tắc tự gửi và các nấc tự động đều BẬT, hệ thống chỉ được liên hệ những số trong danh sách thử. Dữ liệu giả hoàn toàn:
 * 0900000001..3 là "khách thật" (đã duyệt sẵn, đã có lời mời READY, y như tình trạng trên máy chủ), 0900000004..5 là số thử đã khai báo.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.safety.live=false",
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class TestModeTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloAutoService zalo;
    @Autowired ZaloInviteSender sender;
    @Autowired ZaloCampaignIntake intake;
    @Autowired ZaloGroupCreationService groupMaker;
    @Autowired ZaloMessageService messages;
    @Autowired ZaloAutoSettings cfg;
    @Autowired AppSettings appSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloLink link;
    @Autowired ZaloTestClock clock;
    @Autowired TestMode mode;
    @Autowired ReplyService replies;
    @Autowired ZaloAutoProperties autoProps;
    @Autowired WebApplicationContext context;

    long north;
    long campaign;

    private void importRows(String... rows) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String r : rows) {
            csv.append(r).append('\n');
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t" + System.nanoTime() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    private static String row(int n) {
        return "T" + n + ",DH" + n + ",05/01/2026 09:00:00,Khach " + n + ",09000000" + String.format("%02d", n)
                + ",1 Duong A Xa B Hà Nội,Giao thành công";
    }

    private static String phone(int n) {
        return "+84900000" + String.format("%03d", n);
    }

    private long cust(int n) {
        return jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", phone(n)).query(Long.class).single();
    }

    private long inv(int n) {
        return jdbc.sql("SELECT id FROM invitation WHERE customer_id = :c").param("c", cust(n)).query(Long.class).single();
    }

    private String invStatus(int n) {
        return jdbc.sql("SELECT status FROM invitation WHERE customer_id = :c").param("c", cust(n)).query(String.class).single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void after(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    @BeforeEach
    void setUp() {
        // Khai báo số thử TRƯỚC khi nhập đơn (số đã là khách thì bị từ chối).
        mode.add("0900000004", "Số của tôi", "test");
        mode.add("0900000005", "Bạn A", "test");
        importRows(row(1), row(2), row(3), row(4), row(5));
        north = groups.create("Nhóm miền Bắc", "https://zalo.me/g/bac", 50, "R:BAC");
        groups.setStatus(north, GroupStatus.OPEN);
        groups.setZaloGroupId(north, "1001");
        // Tình trạng thật trên máy chủ: khách thật ĐÃ được duyệt từ trước.
        jdbc.sql("""
                INSERT INTO contact_basis (customer_id, purpose, basis, status, statement, recorded_by)
                SELECT id, 'GROUP_INVITE_POSTSALE', 'TRANSACTION_POSTSALE', 'ELIGIBLE', 'test', 'test' FROM customer""").update();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        // ... và đã có lời mời READY cho khách thật trong chiến dịch (đã duyệt tự gửi).
        invites.addInvitations(campaign, 14, "Đợt 1", List.of(
                new ContactService.Invitee(cust(1), phone(1), "Khach 1"),
                new ContactService.Invitee(cust(2), phone(2), "Khach 2"),
                new ContactService.Invitee(cust(3), phone(3), "Khach 3")));
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
        appSettings.set("zalo.msg.enabled", "false", "test");
        appSettings.set("zalo.msg.daily", "10", "test");
        appSettings.set("zalo.msg.gap_seconds", "120", "test");
        appSettings.set("zalo.auto.mode", "OFF", "test");
        appSettings.set("campaigns.paused", "false", "test");
        cfg.reopen("test");
    }

    /** Bật mọi thứ như trên máy chủ: nấc tự động, công tắc tự gửi, chiến dịch đã duyệt. */
    private void everythingOn() {
        cfg.setMode(ZaloMode.AUTO, true, "mẹ");
        messages.setEnabled(true, "mẹ");
        sender.approveCampaign(campaign, "mẹ");
    }

    // ---------- mặc định an toàn ----------

    @Test
    void macDinhLaCheDoThuKhiKhongDatGi() {
        assertThat(mode.testOnly()).isTrue();
        assertThat(mode.live()).isFalse();
        // thuộc tính không đặt thì mặc định là chế độ THỬ (thất bại kín)
        ZaloSafetyProperties unset = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("app.zalo.safety", ZaloSafetyProperties.class);
        assertThat(unset.live()).isFalse();
        assertThat(mode.protectedCustomers()).isEqualTo(3);
    }

    @Test
    void duongNoiTuiZaloDuocBocBangLuoiAnToanCaKhiBatBoNoi() {
        assertThat(link).isInstanceOf(SafeZaloLink.class);
        assertThat(new ZaloAutoConfig().zaloAccountRoutes(autoProps, new ZaloAccountProperties(null), mode, new ZaloBridgeIdentity(jdbc))
                .target(1L)).isInstanceOf(SafeZaloLink.class);
    }

    // ---------- danh sách mời và chiến dịch chỉ có khách thử ----------

    @Test
    void danhSachMoiVaChienDichChiCoKhachThuDuKhachThatDaDuocDuyetSan() {
        assertThat(contacts.inviteList(1, 50).total()).isEqualTo(2);
        assertThat(contacts.inviteList(1, 50).items()).extracting(ContactService.InviteRow::phone).containsExactlyInAnyOrder(phone(4), phone(5));
        assertThat(contacts.stats().eligible()).isEqualTo(2);
        // chiến dịch tạo ở setUp (khi 5 khách đều đã duyệt) chỉ lấy khách thử
        assertThat(count("SELECT count(*) FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone IN ('" + phone(4) + "','" + phone(5) + "')"))
                .isEqualTo(2);
        // khách thử đã được mời ở đợt 1, khách thật bị loại khỏi nguồn: không còn ai để tạo đợt 2 (không lấy khách thật cho đủ số)
        assertThatThrownBy(() -> invites.createCampaign("Đợt 2", "Chào {ten}", 14, 20)).isInstanceOf(ImportException.class);
        // nghiệp vụ không đổi, nhưng được phép liên hệ thì chỉ khách thử
        assertThat(contacts.stillEligible(cust(1))).isTrue();
        assertThat(contacts.mayContact(cust(1))).isFalse();
        assertThat(contacts.mayContact(cust(4))).isTrue();
    }

    @Test
    void duyetTatCaChiDuyetKhachThu() {
        jdbc.sql("DELETE FROM contact_basis").update();
        assertThat(contacts.stats().candidates()).isEqualTo(2);
        assertThat(contacts.approveAllCandidates()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM contact_basis")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM contact_basis WHERE customer_id = " + cust(1))).isZero();
        assertThatThrownBy(() -> contacts.approve(cust(1))).isInstanceOf(ImportException.class);
    }

    // ---------- tự gửi ----------

    @Test
    void tuGuiChiNhacSoThuDuCongTacBatVaKhachThatDaCoLoiMoiSan() {
        everythingOn();
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        after(120);
        assertThat(sender.step()).isEqualTo(Step.INVITE_SENT);
        after(120);
        assertThat(sender.step()).isEqualTo(Step.NOTHING); // hết khách thử; khách thật vẫn nằm đó
        assertThat(fake.messages).hasSize(2);
        assertThat(fake.messages).extracting(m -> m.phone()).containsExactlyInAnyOrder("84900000004", "84900000005");
        // khách thật không bị đụng tới: vẫn READY, không bị đánh dấu thất bại hay bị hủy
        for (int n = 1; n <= 3; n++) {
            assertThat(invStatus(n)).isEqualTo("READY");
        }
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'FAILED'")).isZero();
    }

    @Test
    void khongCoSoThuNaoThiKhongGuiAiKeCaKhiMoiCongTacBat() {
        jdbc.sql("DELETE FROM zalo_test_number").update();
        everythingOn();
        assertThat(sender.step()).isEqualTo(Step.NOTHING);
        assertThat(fake.messages).isEmpty();
        assertThat(count("SELECT count(*) FROM invitation WHERE status = 'READY'")).isEqualTo(5);
    }

    // ---------- tự thêm nhóm ----------

    @Test
    void tuThemNhomKhongThemKhachThat() {
        cfg.setMode(ZaloMode.AUTO, true, "mẹ");
        for (int n : new int[]{1, 4}) {
            jdbc.sql("""
                    UPDATE invitation SET status = 'FRIENDED', zalo_uid = :u, zalo_add_state = 'QUEUED', friended_at = now() WHERE id = :id""")
                    .param("u", "U" + n).param("id", inv(n)).update();
        }
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds).singleElement().satisfies(a -> assertThat(a[0]).isEqualTo("U4"));
        after(3600);
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOTHING);
        assertThat(fake.adds).hasSize(1);
        assertThat(jdbc.sql("SELECT zalo_add_state FROM invitation WHERE id = :id").param("id", inv(1)).query(String.class).single()).isEqualTo("QUEUED");
    }

    // ---------- tự tạo nhóm ----------

    @Test
    void tuTaoNhomChiXetKhachThu() {
        jdbc.sql("DELETE FROM zalo_group").update(); // không có nhóm hợp khu vực: khách đã kết bạn vào hàng chờ tạo nhóm
        for (int n = 1; n <= 5; n++) {
            jdbc.sql("""
                    UPDATE invitation SET status = 'FRIENDED', zalo_uid = :u, friended_at = now() WHERE id = :id""")
                    .param("u", "U" + n).param("id", inv(n)).update();
        }
        assertThat(groupMaker.waitingCustomers()).hasSize(2);
    }

    // ---------- tự nhận khách mới ----------

    @Test
    void tuNhanKhachMoiChiNhanKhachThu() {
        mode.add("0900000007", "Bạn B", "test");
        everythingOn();
        intake.enable(campaign, "mẹ");
        importRows(row(6), row(7)); // 6 là khách thật mới, 7 là số thử
        assertThat(intake.intake()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM contact_basis WHERE customer_id = " + cust(7) + " AND recorded_by = '" + ZaloCampaignIntake.ACTOR + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM contact_basis WHERE customer_id = " + cust(6))).isZero();
        assertThat(count("SELECT count(*) FROM invitation WHERE customer_id = " + cust(6))).isZero();
    }

    // ---------- AI trả lời, tin riêng ----------

    @Test
    void viecTraLoiChoKhachThatKhongBaoGioDenLuotGui() {
        jdbc.sql("""
                INSERT INTO reply_draft (customer_id, zalo_uid, state, reply_text, inbound_at)
                VALUES (:c, 'UREAL', 'READY', 'Chào anh/chị', now())""").param("c", cust(1)).update();
        assertThat(replies.nextReady()).isEmpty();
        jdbc.sql("""
                INSERT INTO reply_draft (customer_id, zalo_uid, state, reply_text, inbound_at)
                VALUES (:c, 'UTEST', 'READY', 'Chào anh/chị', now())""").param("c", cust(4)).update();
        assertThat(replies.nextReady()).isPresent().get().extracting(ReplyService.Ready::uid).isEqualTo("UTEST");
    }

    @Test
    void tinRiengCuaKhachThatBiBoNgayKhongToiAI() {
        jdbc.sql("UPDATE invitation SET zalo_uid = 'UREAL' WHERE id = :id").param("id", inv(1)).update();
        jdbc.sql("UPDATE invitation SET zalo_uid = 'UTEST' WHERE id = :id").param("id", inv(4)).update();
        fake.pushInbound("UREAL", "mình không cần nữa");
        fake.pushInbound("UNKNOWN", "alo");
        fake.pushInbound("UTEST", "vào nhóm có ưu đãi gì vậy");
        long before = mode.blockedCount();
        assertThat(link.inbound(0)).extracting(ZaloInboxReader.Inbound::uid).containsExactly("UTEST");
        assertThat(mode.blockedCount()).isEqualTo(before + 2);
        // hỏi lại ở nhịp sau: vẫn bỏ nhưng không đếm lại cùng những tin đó
        assertThat(link.inbound(0)).extracting(ZaloInboxReader.Inbound::uid).containsExactly("UTEST");
        assertThat(mode.blockedCount()).isEqualTo(before + 2);
    }

    // ---------- lưới an toàn cuối cùng ----------

    @Test
    void luoiCuoiChanMoiViecChamToiNguoiKhongPhaiSoThu() {
        jdbc.sql("UPDATE invitation SET zalo_target_uid = 'UREAL' WHERE id = :id").param("id", inv(1)).update();
        jdbc.sql("UPDATE invitation SET zalo_target_uid = 'UT4' WHERE id = :id").param("id", inv(4)).update();
        jdbc.sql("UPDATE invitation SET zalo_uid = 'UT5' WHERE id = :id").param("id", inv(5)).update();

        // gửi theo số điện thoại
        var blocked = link.sendByPhone(Kind.INVITE, "84900000001", "x");
        assertThat(blocked.result()).isEqualTo(Result.UNREACHABLE);
        assertThat(blocked.notSent()).isTrue();
        assertThat(link.sendByPhone(Kind.INVITE, "0900000001", "x").result()).isEqualTo(Result.UNREACHABLE);
        assertThat(link.sendByPhone(Kind.INVITE, "không phải số", "x").result()).isEqualTo(Result.UNREACHABLE);
        assertThat(fake.messages).isEmpty();
        assertThat(link.sendByPhone(Kind.INVITE, "84900000004", "x").result()).isEqualTo(Result.OK);
        assertThat(fake.messages).hasSize(1);

        // gửi theo mã Zalo (mã của khách thật, mã lạ, mã của số thử)
        assertThat(link.sendByUid(Kind.LINK, "UREAL", "x").result()).isEqualTo(Result.UNREACHABLE);
        assertThat(link.sendByUid(Kind.REPLY, "UNKNOWN", "x").result()).isEqualTo(Result.UNREACHABLE);
        assertThat(link.sendByUid(Kind.WELCOME, null, "x").result()).isEqualTo(Result.UNREACHABLE);
        assertThat(fake.messages).hasSize(1);
        assertThat(link.sendByUid(Kind.LINK, "UT4", "x").result()).isEqualTo(Result.OK);
        assertThat(fake.messages).hasSize(2);

        // lời mời kết bạn: cũng chỉ cho số thử
        assertThat(link.sendFriendRequest("UREAL", "x").result()).isEqualTo(Result.UNREACHABLE);
        assertThat(link.sendFriendRequest("UREAL", "x").notSent()).isTrue();
        assertThat(link.sendFriendRequest(null, "x").result()).isEqualTo(Result.UNREACHABLE);
        assertThat(fake.friendRequests).isEmpty();
        assertThat(link.sendFriendRequest("UT4", "x").result()).isEqualTo(Result.OK);
        assertThat(fake.friendRequests).hasSize(1);

        // đặt lại phó nhóm: chỉ cho nhóm Adh đã ghi
        assertThat(link.setDeputy("777000", "4242").outcome()).isEqualTo(ZaloGroupMaker.GroupOutcome.UNREACHABLE);
        assertThat(fake.deputyCalls).isEmpty();

        // thêm vào nhóm
        assertThat(link.addToGroup("UREAL", "1001").outcome()).isEqualTo(Outcome.UNREACHABLE);
        assertThat(fake.adds).isEmpty();
        assertThat(link.addToGroup("UT5", "1001")).isEqualTo(AddResult.ok());
        assertThat(fake.adds).hasSize(1);

        // tạo nhóm: chỉ cần MỘT thành viên không phải số thử là không tạo
        assertThat(link.createGroup("Nhóm", List.of("UT4", "UREAL"), null, null).outcome()).isEqualTo(ZaloGroupMaker.GroupOutcome.UNREACHABLE);
        assertThat(link.createGroup("Nhóm", List.of("UT4", "UREAL"), null, null).notCreated()).isTrue();
        assertThat(fake.creates).isEmpty();
        assertThat(link.createGroup("Nhóm", List.of("UT4", "UT5"), null, null).outcome()).isEqualTo(ZaloGroupMaker.GroupOutcome.OK);
        assertThat(fake.creates).hasSize(1);

        assertThat(mode.blockedCount()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void traNguoiDeXacNhanPhoNhomVanDuocChoMeVaChiDoc() {
        link.findByPhone("84912345678");
        assertThat(fake.finds).containsExactly("84912345678");
    }

    // ---------- danh sách số thử ----------

    @Test
    void khaiBaoSoThuTuChoiSoSaiSoTrungSoLaKhachVaSoTuChoi() {
        assertThatThrownBy(() -> mode.add("12345", null, "t")).isInstanceOf(ImportException.class).hasMessageContaining("không hợp lệ");
        assertThatThrownBy(() -> mode.add("0900 000 004", null, "t")).isInstanceOf(ImportException.class).hasMessageContaining("đã có");
        assertThatThrownBy(() -> mode.add("0900000001", null, "t")).isInstanceOf(ImportException.class).hasMessageContaining("đã là một khách");
        jdbc.sql("INSERT INTO suppression (phone_hash, reason, recorded_by) VALUES (:h, 'ERASED', 'test')").param("h", PhoneHash.of(phone(50))).update();
        assertThatThrownBy(() -> mode.add("+84 900 000 050", null, "t")).isInstanceOf(ImportException.class).hasMessageContaining("danh sách từ chối");
        assertThatThrownBy(() -> mode.add("0900000060", "x".repeat(101), "t")).isInstanceOf(ImportException.class).hasMessageContaining("quá dài");
        mode.add("84900000061", "dạng 84...", "t");
        assertThat(mode.phoneAllowed("0900000061")).isTrue();
        assertThat(mode.phoneAllowed("+84900000061")).isTrue();
        assertThat(mode.phoneAllowed("0900000001")).isFalse();
        mode.remove("0900000061", "t");
        assertThat(mode.phoneAllowed("0900000061")).isFalse();
        assertThatThrownBy(() -> mode.remove("0900000061", "t")).isInstanceOf(ImportException.class);
    }

    @Test
    void danhSachThuCoTran() {
        for (int i = 0; mode.numbers().size() < 30; i++) {
            mode.add("09111100" + String.format("%02d", i), null, "t");
        }
        assertThatThrownBy(() -> mode.add("0911119999", null, "t")).isInstanceOf(ImportException.class).hasMessageContaining("tối đa");
    }

    // ---------- giao diện ----------

    @Test
    void trangZaloHienCheDoThuVaChiAdminKhaiBaoSo() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/zalo").param("tab", "thu").with(user("admin").roles("OWNER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chế độ thử")))
                .andExpect(content().string(containsString("+84900000004")))
                .andExpect(content().string(containsString("Đang ở chế độ thử")))
                .andExpect(content().string(not(containsString("+84900000001"))));
        mvc.perform(get("/").with(user("admin").roles("OWNER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đang ở chế độ thử")));
        mvc.perform(post("/zalo/test-numbers").with(user("admin").roles("OWNER")).with(csrf()).param("phone", "0900000020").param("note", "Bạn C"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/zalo?tab=thu#che-do-thu"));
        assertThat(mode.phoneAllowed("0900000020")).isTrue();
        // số đã là khách: lỗi tiếng Việt, không thêm
        mvc.perform(post("/zalo/test-numbers").with(user("admin").roles("OWNER")).with(csrf()).param("phone", "0900000001"))
                .andExpect(status().isBadRequest());
        assertThat(mode.phoneAllowed("0900000001")).isFalse();
        mvc.perform(post("/zalo/test-numbers/remove").with(user("admin").roles("OWNER")).with(csrf()).param("phone", "0900000020"))
                .andExpect(status().is3xxRedirection());
        assertThat(mode.phoneAllowed("0900000020")).isFalse();
        // chưa đăng nhập hoặc không phải admin thì không khai báo được
        mvc.perform(post("/zalo/test-numbers").with(csrf()).param("phone", "0900000021")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/zalo/test-numbers").with(user("staff").roles("STAFF")).with(csrf()).param("phone", "0900000021"))
                .andExpect(status().isForbidden());
        assertThat(mode.phoneAllowed("0900000021")).isFalse();
    }
}
