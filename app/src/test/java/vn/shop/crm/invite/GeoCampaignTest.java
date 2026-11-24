package vn.shop.crm.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chiến dịch theo khu vực: ví dụ trời rét thì chỉ nhắn khách một miền. Chưa gửi gì cho ai. */
class GeoCampaignTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
    @Autowired
    InviteService invites;
    @Autowired
    WebApplicationContext context;

    @BeforeEach
    void setUp() {
        String csv = HEADER
                + "T1,DH1,05/01/2026 09:00:00,Khach Bac,0900000001,1 Duong A Xa B Hà Nội,Giao thành công\n"
                + "T2,DH2,05/01/2026 09:00:00,Khach Nam 1,0900000002,2 Duong A Xa B Cần Thơ,Giao thành công\n"
                + "T3,DH3,05/01/2026 09:00:00,Khach Nam 2,0900000003,3 Duong A Xa B Bình Dương,Giao thành công\n"
                + "T4,DH4,05/01/2026 09:00:00,Khach Trung,0900000004,4 Duong A Xa B Đà Nẵng,Giao thành công\n"
                + "T5,DH5,05/01/2026 09:00:00,Khach Chua Ro,0900000005,5 Duong Bi Mat,Giao thành công\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
    }

    private List<String> namesOf(long campaign) {
        return jdbc.sql("""
                        SELECT c.name FROM invitation i JOIN customer c ON c.id = i.customer_id
                        WHERE i.campaign_id = :id ORDER BY c.name""").param("id", campaign).query(String.class).list();
    }

    @Test
    void chienDichMienNamChiLayKhachMienNam() {
        long id = invites.createCampaign("Rét, miền Nam", null, 14, 50, "R:NAM");
        assertThat(namesOf(id)).containsExactly("Khach Nam 1", "Khach Nam 2");
        var campaign = invites.campaign(id).orElseThrow();
        assertThat(campaign.geoScope()).isEqualTo("R:NAM");
        assertThat(campaign.scopeLabel()).isEqualTo("Miền Nam");
    }

    @Test
    void locTheoVungTinhVaChuaRoKhuVuc() {
        assertThat(namesOf(invites.createCampaign("Một tỉnh", null, 14, 50, "P:can-tho"))).containsExactly("Khach Nam 1");
        assertThat(namesOf(invites.createCampaign("Chưa rõ", null, 14, 50, "U"))).containsExactly("Khach Chua Ro");
        assertThat(invites.campaigns()).extracting(InviteService.Campaign::scopeLabel)
                .containsExactlyInAnyOrder("Cần Thơ", "Chưa rõ khu vực");
    }

    @Test
    void khachDaDuocMoiRoiKhongLotVaoChienDichKhacDuLaCungKhuVuc() {
        invites.createCampaign("Đợt 1", null, 14, 1, "R:NAM");
        long second = invites.createCampaign("Đợt 2", null, 14, 50, "R:NAM");
        assertThat(namesOf(second)).hasSize(1);
        assertThatThrownBy(() -> invites.createCampaign("Đợt 3", null, 14, 50, "R:NAM"))
                .isInstanceOf(ImportException.class).hasMessageContaining("khu vực này");
    }

    @Test
    void khongChonKhuVucThiLayMoiNoi() {
        assertThat(namesOf(invites.createCampaign("Tất cả", null, 14, 50))).hasSize(5);
        assertThat(invites.campaigns().get(0).scopeLabel()).isEqualTo("Mọi khu vực");
    }

    @Test
    void maKhuVucLaVaSoLuongQuaLonBiTuChoi() {
        assertThatThrownBy(() -> invites.createCampaign("X", null, 14, 10, "R:ABC"))
                .isInstanceOf(ImportException.class).hasMessageContaining("Khu vực không hợp lệ");
        assertThatThrownBy(() -> invites.createCampaign("X", null, 14, invites.maxCampaign() + 1, "R:NAM"))
                .isInstanceOf(ImportException.class).hasMessageContaining("từ 1 đến");
        assertThat(invites.campaigns()).isEmpty();
    }

    @Test
    void demKhachDuDieuKienTheoKhuVuc() {
        assertThat(contacts.countEligibleNotInvited("R:NAM")).isEqualTo(2);
        assertThat(contacts.countEligibleNotInvited("R:BAC")).isEqualTo(1);
        assertThat(contacts.countEligibleNotInvited("U")).isEqualTo(1);
        assertThat(contacts.countEligibleNotInvited(null)).isEqualTo(5);
    }

    @Test
    void trangChienDichCoOChonKhuVucVaTaoDuocChienDichTheoMien() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/campaigns").param("tab", "moi").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Khu vực khách")))
                .andExpect(content().string(containsString("Miền Nam")))
                .andExpect(content().string(containsString("theo hạn mức và khung giờ")));
        mvc.perform(post("/campaigns").with(owner).with(csrf()).param("name", "Rét miền Nam").param("count", "50")
                        .param("expiresDays", "14").param("area", "R:NAM"))
                .andExpect(status().is3xxRedirection());
        assertThat(invites.campaigns()).hasSize(1);
        assertThat(invites.campaigns().get(0).total()).isEqualTo(2);
        mvc.perform(get("/campaigns/" + invites.campaigns().get(0).id()).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("khu vực:")))
                .andExpect(content().string(containsString("Miền Nam")));
    }
}
