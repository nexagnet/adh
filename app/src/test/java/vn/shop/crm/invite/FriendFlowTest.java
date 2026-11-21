package vn.shop.crm.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.shipping.Carrier;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Đường nhắn bằng tài khoản Zalo phụ (D13): nhắn tay MỘT lần, khách đồng ý kết bạn thì được thêm vào nhóm cùng khu vực.
 * Hệ thống không tự gửi, chỉ ghi lại các bước người gửi đánh dấu.
 */
class FriendFlowTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
    @Autowired
    GroupService groups;
    @Autowired
    InviteService invites;
    @Autowired
    AppSettings settings;
    @Autowired
    WebApplicationContext context;

    long north;
    long south;
    long campaign;

    @BeforeEach
    void setUp() {
        String csv = HEADER
                + "T1,DH1,05/01/2026 09:00:00,Khach Bac,0900000001,1 Duong A Xa B Hà Nội,Giao thành công\n"
                + "T2,DH2,05/01/2026 09:00:00,Khach Nam,0900000002,2 Duong A Xa B Cần Thơ,Giao thành công\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        north = groups.create("Nhóm miền Bắc", "https://zalo.me/g/bac", 5, "R:BAC");
        groups.setStatus(north, GroupStatus.OPEN);
        south = groups.create("Nhóm miền Nam", "https://zalo.me/g/nam", 5, "R:NAM");
        groups.setStatus(south, GroupStatus.OPEN);
        contacts.approveAllCandidates();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn cách dùng sản phẩm.", 14, 10);
    }

    private long invitationOf(String phone) {
        return jdbc.sql("SELECT i.id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p")
                .param("p", "+84" + phone.substring(1)).query(Long.class).single();
    }

    private String statusOf(long invitation) {
        return jdbc.sql("SELECT status FROM invitation WHERE id = :id").param("id", invitation).query(String.class).single();
    }

    private void markSent(long invitation) {
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), sent_channel = 'MANUAL_EXPORT' WHERE id = :id")
                .param("id", invitation).update();
    }

    @Test
    void goiYNhomCungKhuVucChiDocChuaGiuCho() {
        var rows = invites.invitations(campaign, invites.campaign(campaign).orElseThrow().template());
        var bac = rows.stream().filter(r -> "Khach Bac".equals(r.name())).findFirst().orElseThrow();
        var nam = rows.stream().filter(r -> "Khach Nam".equals(r.name())).findFirst().orElseThrow();
        assertThat(bac.groupName()).isEqualTo("Nhóm miền Bắc");
        assertThat(bac.suggested()).isTrue();
        assertThat(nam.groupName()).isEqualTo("Nhóm miền Nam");
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE group_id IS NOT NULL").query(Long.class).single()).isZero();
        assertThat(groups.find(north).orElseThrow().actual()).isZero();
    }

    @Test
    void nhanTayRoiKetBanRoiVaoNhomThiTangSoKhachThatCuaNhom() {
        long inv = invitationOf("0900000001");
        // chưa nhắn thì chưa đánh dấu được kết bạn hay vào nhóm
        assertThatThrownBy(() -> invites.markFriended(inv)).isInstanceOf(ImportException.class).hasMessageContaining("đã được nhắn");
        assertThatThrownBy(() -> invites.markJoined(inv)).isInstanceOf(ImportException.class);

        markSent(inv);
        invites.markFriended(inv);
        assertThat(statusOf(inv)).isEqualTo("FRIENDED");
        invites.markJoined(inv);
        assertThat(statusOf(inv)).isEqualTo("JOINED");

        assertThat(jdbc.sql("SELECT group_id FROM invitation WHERE id = :id").param("id", inv).query(Long.class).single())
                .isEqualTo(north);
        assertThat(groups.find(north).orElseThrow().actual()).isEqualTo(1);
        assertThat(groups.find(south).orElseThrow().actual()).isZero();
        var camp = invites.campaign(campaign).orElseThrow();
        assertThat(camp.friended()).isEqualTo(1);
        assertThat(camp.joined()).isEqualTo(1);
        // đã vào nhóm rồi thì không đánh dấu lại để khỏi tính số khách hai lần
        assertThatThrownBy(() -> invites.markJoined(inv)).isInstanceOf(ImportException.class);
        assertThat(groups.find(north).orElseThrow().actual()).isEqualTo(1);
    }

    @Test
    void vaoNhomThangKhongCanKetBanDanhDauTruocVaBaoLoiKhiKhongConNhom() {
        long inv = invitationOf("0900000002");
        markSent(inv);
        groups.setStatus(south, GroupStatus.PAUSED);
        assertThatThrownBy(() -> invites.markJoined(inv)).isInstanceOf(ImportException.class)
                .hasMessageContaining("Chưa có nhóm Zalo nào còn chỗ");
        assertThat(statusOf(inv)).isEqualTo("SENT");

        groups.setStatus(south, GroupStatus.OPEN);
        invites.markJoined(inv);
        assertThat(statusOf(inv)).isEqualTo("JOINED");
        assertThat(jdbc.sql("SELECT friended_at IS NOT NULL FROM invitation WHERE id = :id").param("id", inv)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void khachTuChoiVaoDanhSachTuChoiVaKhongBaoGioDuocNhanLai() {
        long inv = invitationOf("0900000001");
        markSent(inv);
        invites.markFriended(inv);
        invites.markDeclined(inv);

        assertThat(statusOf(inv)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT count(*) FROM suppression").query(Long.class).single()).isEqualTo(1);
        // đã hủy thì không đánh dấu tiếp, và khách không lọt vào chiến dịch mới
        assertThatThrownBy(() -> invites.markJoined(inv)).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> invites.createCampaign("Đợt 2", null, 14, 5)).isInstanceOf(ImportException.class);
    }

    @Test
    void moiKhachChiDuocNhanMotLanDuyNhat() {
        // khách đã có lời mời (kể cả đã gửi, đã kết bạn, đã vào nhóm) không bao giờ vào chiến dịch mới
        long inv = invitationOf("0900000001");
        markSent(inv);
        invites.markJoined(inv);
        assertThatThrownBy(() -> invites.createCampaign("Đợt 2", null, 14, 5)).isInstanceOf(ImportException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE customer_id = (SELECT customer_id FROM invitation WHERE id = :id)")
                .param("id", inv).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void soanNoiDungMacDinhTrenTrangQuanTri() {
        assertThat(invites.defaultTemplate()).isEqualTo(InviteService.DEFAULT_TEMPLATE);
        invites.saveDefaultTemplate("Chào {ten}, {cua_hang} tư vấn sản phẩm cho bạn.");
        assertThat(invites.defaultTemplate()).isEqualTo("Chào {ten}, {cua_hang} tư vấn sản phẩm cho bạn.");
        assertThatThrownBy(() -> invites.saveDefaultTemplate("x".repeat(801))).hasMessageContaining("quá dài");
        invites.saveDefaultTemplate("  ");
        assertThat(invites.defaultTemplate()).isEqualTo(InviteService.DEFAULT_TEMPLATE);
    }

    @Test
    void trangChienDichHienNhomGoiYCacNutBuocVaGhiNhanQuaWeb() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        long inv = invitationOf("0900000001");
        mvc.perform(get("/campaigns/" + campaign).with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tài khoản Zalo phụ")))
                .andExpect(content().string(containsString("Nhóm miền Bắc")))
                .andExpect(content().string(containsString("nên thêm vào")));
        markSent(inv);
        mvc.perform(get("/campaigns/" + campaign).with(owner))
                .andExpect(content().string(containsString("Đã kết bạn")))
                .andExpect(content().string(containsString("Khách từ chối")));

        mvc.perform(post("/campaigns/" + campaign + "/invitations/" + inv + "/friended").with(csrf()).with(owner))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/campaigns/" + campaign));
        mvc.perform(post("/campaigns/" + campaign + "/invitations/" + inv + "/joined").with(csrf()).with(owner))
                .andExpect(status().is3xxRedirection());
        assertThat(statusOf(inv)).isEqualTo("JOINED");
        mvc.perform(post("/campaigns/" + campaign + "/invitations/" + inv + "/joined").with(csrf()).with(owner))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/campaigns/default-template").with(csrf()).with(owner).param("template", "Chào {ten}"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/campaigns"));
        mvc.perform(get("/campaigns").with(owner)).andExpect(content().string(containsString("Chào {ten}")))
                .andExpect(content().string(containsString("Đã vào nhóm")));
    }
}
