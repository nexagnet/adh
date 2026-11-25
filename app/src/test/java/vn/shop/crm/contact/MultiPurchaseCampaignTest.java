package vn.shop.crm.contact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.InviteService;
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

/**
 * Chiến dịch mời cả khách mua nhiều lần (PR 3, quyết định 25/11/2026). Quy tắc cũ vẫn đúng với mọi chiến dịch mặc định: khách mua từ 2 lần
 * không bao giờ được liên hệ; chỉ chiến dịch cho phép đúng số lần mua của họ mới mở cửa, và mua thêm vượt mức thì dừng.
 */
class MultiPurchaseCampaignTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái,Nội dung hàng hóa\n";

    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
    @Autowired
    InviteService invites;
    @Autowired
    CampaignPreview preview;
    @Autowired
    WebApplicationContext context;

    private void importRows(String... rows) {
        String csv = HEADER + String.join("\n", rows) + "\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    private static String row(String t, String ref, String name, String phone, String status) {
        return t + "," + ref + ",05/01/2026 09:00:00," + name + "," + phone + ",1 Duong A Xa B Hà Nội," + status + ",Áo điều hòa";
    }

    /** P: 1 lần (đã duyệt). R2, R3, R4: 2, 3, 4 lần (không được duyệt trước). X: 2 lần nhưng có đơn không mã đơn (cần kiểm tra). */
    @BeforeEach
    void setUp() {
        importRows(
                row("P1", "DH1", "P", "0900000001", "Giao thành công"),
                row("A1", "DHA1", "R2", "0900000002", "Giao thành công"), row("A2", "DHA2", "R2", "0900000002", "Giao thành công"),
                row("B1", "DHB1", "R3", "0900000003", "Giao thành công"), row("B2", "DHB2", "R3", "0900000003", "Giao thành công"),
                row("B3", "DHB3", "R3", "0900000003", "Giao thành công"),
                row("C1", "DHC1", "R4", "0900000004", "Giao thành công"), row("C2", "DHC2", "R4", "0900000004", "Giao thành công"),
                row("C3", "DHC3", "R4", "0900000004", "Giao thành công"), row("C4", "DHC4", "R4", "0900000004", "Giao thành công"),
                row("X1", "DHX1", "X", "0900000005", "Giao thành công"), row("X2", "", "X", "0900000005", "Giao thành công"));
        contacts.approveAllCandidates();
    }

    private static CustomerFilter purchases(Integer min, Integer max) {
        return new CustomerFilter(min, max, null, null, null, null, null, false, null, null, null, null, null, null, null, null, false, null);
    }

    private long id(String name) {
        return jdbc.sql("SELECT id FROM customer WHERE name = :n").param("n", name).query(Long.class).single();
    }

    private List<String> names(CustomerFilter f) {
        return contacts.eligibleNotInvited(50, null, f).stream().map(ContactService.Invitee::name).sorted().toList();
    }

    @Test
    void macDinhVanChiMoiKhachMuaMotLanKhachMuaNhieuLanKhongBaoGioLot() {
        assertThat(names(CustomerFilter.NONE)).containsExactly("P");
        assertThat(contacts.stillEligible(id("R2"))).isFalse();
        long campaign = invites.createCampaign("Mặc định", null, 14, 50, null);
        assertThat(jdbc.sql("SELECT max_purchases FROM invitation_campaign WHERE id = :id").param("id", campaign).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM contact_basis").query(Long.class).single()).isEqualTo(1); // chỉ P; không ai được duyệt thêm
    }

    @Test
    void khoangSoLanMuaDuocChuanHoaVaKiemTra() {
        assertThat(purchases(1, 1)).isEqualTo(CustomerFilter.NONE);
        assertThat(purchases(null, null)).isEqualTo(CustomerFilter.NONE);
        assertThat(purchases(2, null).maxPurchases()).isEqualTo(CustomerFilter.MAX_PURCHASES);
        assertThat(purchases(null, 3).minPurchases()).isEqualTo(1);
        assertThat(purchases(1, 3).multi()).isTrue();
        assertThatThrownBy(() -> purchases(3, 2)).isInstanceOf(ImportException.class).hasMessageContaining("Số lần mua");
        assertThatThrownBy(() -> purchases(0, 2)).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> purchases(1, 21)).isInstanceOf(ImportException.class);
        assertThat(purchases(1, 3).describe()).containsExactly("Mua từ 1 đến 3 lần");
        assertThat(purchases(2, null).describe()).containsExactly("Mua từ 2 lần trở lên");
    }

    @Test
    void chonMuaTuMotDenBaLanLayDungKhachVaBoKhachCanKiemTra() {
        assertThat(names(purchases(1, 3))).containsExactly("P", "R2", "R3"); // R4 mua 4 lần; X cần kiểm tra
        assertThat(names(purchases(2, 3))).containsExactly("R2", "R3");
        assertThat(contacts.countEligibleNotInvited(null, purchases(1, 3))).isEqualTo(3);
        // ví dụ của chủ dự án: tới 3 lần và không có đơn hủy
        var f = new CustomerFilter(1, 3, null, null, null, CustomerFilter.Tri.NO, null, false, null, null, null, null, null, null, null, null, false, null);
        assertThat(names(f)).containsExactly("P", "R2", "R3");
    }

    @Test
    void taoChienDichChoPhepMuaNhieuLanDuyetKhachRiengVaMoCuaDungMucChoPhep() {
        long campaign = invites.createCampaign("Tới 3 lần", null, 14, 50, null, purchases(1, 3));
        assertThat(jdbc.sql("SELECT max_purchases FROM invitation_campaign WHERE id = :id").param("id", campaign).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE campaign_id = :id").param("id", campaign).query(Long.class).single()).isEqualTo(3);
        // khách mua nhiều lần được duyệt liên hệ riêng cho chiến dịch này, ghi rõ chiến dịch
        var basis = jdbc.sql("SELECT evidence_ref, status FROM contact_basis WHERE customer_id = :c").param("c", id("R2"))
                .query((rs, i) -> rs.getString(1) + "/" + rs.getString(2)).single();
        assertThat(basis).isEqualTo("CHIEN-DICH-" + campaign + "/ELIGIBLE");
        assertThat(jdbc.sql("SELECT count(*) FROM contact_event WHERE event = 'BASIS_APPROVED' AND detail LIKE '%chiến dịch #" + campaign + "%'")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(contacts.stillEligible(id("P"))).isTrue();
        assertThat(contacts.stillEligible(id("R2"))).isTrue();
        assertThat(contacts.stillEligible(id("R3"))).isTrue();
        assertThat(contacts.stillEligible(id("R4"))).isFalse(); // không có lời mời nào
        // mua thêm vượt mức chiến dịch cho phép thì mọi bước gửi dừng
        jdbc.sql("UPDATE customer SET successful_purchases = 4 WHERE id = :c").param("c", id("R3")).update();
        assertThat(contacts.stillEligible(id("R3"))).isFalse();
    }

    @Test
    void khachMuaNhieuLanKhongQuaCuaChienDichMacDinhDuCoLoiMoi() {
        // lời mời của R2 nằm trong chiến dịch cho phép 1 lần: không được gửi
        long plain = jdbc.sql("INSERT INTO invitation_campaign (name, message_template, expires_days, created_by) VALUES ('mặc định', 't', 14, 'x') RETURNING id")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO contact_basis (customer_id, purpose, basis, status, recorded_by) VALUES (:c, 'GROUP_INVITE_POSTSALE', 'TRANSACTION_POSTSALE', 'ELIGIBLE', 'x')")
                .param("c", id("R2")).update();
        jdbc.sql("INSERT INTO invitation (campaign_id, customer_id, token, expires_at) VALUES (:k, :c, 'tok', now() + interval '5 days')")
                .param("k", plain).param("c", id("R2")).update();
        assertThat(contacts.stillEligible(id("R2"))).isFalse();
    }

    @Test
    void khachBiChanTuChoiHoacDaDuocMoiKhongBaoGioLotVaoChienDichNhieuLan() {
        contacts.block(id("R2"), "phàn nàn");
        assertThat(names(purchases(1, 3))).containsExactly("P", "R3");
        jdbc.sql("INSERT INTO suppression (phone_hash, reason, recorded_by) SELECT encode(sha256(convert_to(phone, 'UTF8')), 'hex'), 'OPT_OUT', 'test' FROM customer WHERE id = :c")
                .param("c", id("R3")).update();
        assertThat(names(purchases(1, 3))).containsExactly("P");
        invites.createCampaign("Đợt 1", null, 14, 50, null, purchases(1, 3));
        assertThat(names(purchases(1, 3))).isEmpty(); // mỗi khách chỉ được mời một lần trong đời
        assertThat(jdbc.sql("SELECT count(*) FROM contact_basis WHERE customer_id = :c AND status = 'BLOCKED'").param("c", id("R2")).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void khachMotLanChuaDuyetVanPhaiDuyetTruocKhongTuDuyetQuaChienDichNhieuLan() {
        importRows(row("N1", "DHN1", "N", "0900000009", "Giao thành công"));
        assertThat(names(purchases(1, 3))).containsExactly("P", "R2", "R3");
    }

    @Test
    void xemTruocChienDichNhieuLanBatDauTuMoiSoLanMua() {
        var r = preview.preview(null, 30, purchases(1, 3));
        assertThat(r.base()).isEqualTo(4); // P, R2, R3, R4
        assertThat(r.steps()).extracting(CampaignPreview.Step::label).containsExactly("Mua từ 1 đến 3 lần");
        assertThat(r.steps().get(0).removed()).isEqualTo(1);
        assertThat(r.matched()).isEqualTo(3);
        assertThat(preview.preview(null, 30, CustomerFilter.NONE).base()).isEqualTo(1);
    }

    @Test
    void formVaTrangChiTietChoChonSoLanMua() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/campaigns").param("tab", "moi").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Số lần mua")))
                .andExpect(content().string(containsString("đúng 1 lần")));
        mvc.perform(post("/campaigns").with(owner).with(csrf()).param("name", "Tới 3 lần").param("count", "50").param("expiresDays", "14")
                        .param("purchasesMin", "1").param("purchasesMax", "3"))
                .andExpect(status().is3xxRedirection());
        var c = invites.campaigns().get(0);
        assertThat(c.total()).isEqualTo(3);
        mvc.perform(get("/campaigns/" + c.id()).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mua từ 1 đến 3 lần")));
        mvc.perform(post("/campaigns").with(owner).with(csrf()).param("name", "Sai").param("count", "5")
                        .param("purchasesMin", "3").param("purchasesMax", "2")).andExpect(status().is4xxClientError());
    }
}
