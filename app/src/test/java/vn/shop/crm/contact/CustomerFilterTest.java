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
import java.time.Instant;
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

/** Bộ lọc khách của chiến dịch (PR 1): lọc đúng, chỉ làm hẹp, lưu vào chiến dịch, form web. Chạy trên Postgres thật. */
class CustomerFilterTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái,Nội dung hàng hóa\n";

    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
    @Autowired
    InviteService invites;
    @Autowired
    ExchangeMarkers markers;
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

    /**
     * A: áo, Hà Nội, một đơn. B: pin, Cần Thơ, thêm một đơn hủy. C: áo, Bình Dương, thêm đơn hủy mã DOI-9 (đơn đổi).
     * D: áo, Đà Nẵng, thêm một đơn đang hoàn. E: áo, Hà Nội, thêm một đơn đang vận chuyển. F: mua 2 lần (REPEAT, không bao giờ đủ điều kiện).
     */
    @BeforeEach
    void setUp() {
        importRows(
                "T1,DH1,05/01/2026 09:00:00,A,0900000001,1 Duong A Xa B Hà Nội,Giao thành công,Áo điều hòa",
                "T2,DH2,05/01/2026 09:00:00,B,0900000002,2 Duong A Xa B Cần Thơ,Giao thành công,Pin 12V",
                "T2b,DH2b,06/01/2026 09:00:00,B,0900000002,2 Duong A Xa B Cần Thơ,Đã hủy,Pin 12V",
                "T3,DH3,05/01/2026 09:00:00,C,0900000003,3 Duong A Xa B Bình Dương,Giao thành công,Áo điều hòa",
                "T3b,DOI-9,06/01/2026 09:00:00,C,0900000003,3 Duong A Xa B Bình Dương,Đã hủy,Áo điều hòa",
                "T4,DH4,05/01/2026 09:00:00,D,0900000004,4 Duong A Xa B Đà Nẵng,Giao thành công,Áo điều hòa",
                "T4b,DH4b,06/01/2026 09:00:00,D,0900000004,4 Duong A Xa B Đà Nẵng,Đang chuyển hoàn,Áo điều hòa",
                "T5,DH5,05/01/2026 09:00:00,E,0900000005,5 Duong A Xa B Hà Nội,Giao thành công,Áo điều hòa",
                "T5b,DH5b," + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " 09:00:00,E,0900000005,5 Duong A Xa B Hà Nội,Đang vận chuyển,Áo điều hòa",
                "T6,DH6,05/01/2026 09:00:00,F,0900000006,6 Duong A Xa B Hà Nội,Giao thành công,Áo điều hòa",
                "T7,DH7,06/01/2026 09:00:00,F,0900000006,6 Duong A Xa B Hà Nội,Giao thành công,Áo điều hòa");
        contacts.approveAllCandidates();
    }

    private static CustomerFilter f(java.util.function.UnaryOperator<Builder> fn) {
        return fn.apply(new Builder()).build();
    }

    /** Dựng bộ lọc từng trường cho dễ đọc. */
    private static final class Builder {
        CustomerFilter.Tri exchange, cancelled, returned;
        boolean noOpen;
        Integer maxShipments;
        List<ProductGroup> bought = List.of(), notBought = List.of();
        List<String> excludeAreas = List.of(), carriers = List.of();
        boolean skipOpenCases;

        Builder exchange(CustomerFilter.Tri t) { exchange = t; return this; }
        Builder cancelled(CustomerFilter.Tri t) { cancelled = t; return this; }
        Builder returned(CustomerFilter.Tri t) { returned = t; return this; }
        Builder noOpen() { noOpen = true; return this; }
        Builder maxShipments(int n) { maxShipments = n; return this; }
        Builder bought(ProductGroup... g) { bought = List.of(g); return this; }
        Builder notBought(ProductGroup... g) { notBought = List.of(g); return this; }
        Builder excludeAreas(String... a) { excludeAreas = List.of(a); return this; }
        Builder carriers(String... c) { carriers = List.of(c); return this; }
        Builder skipOpenCases() { skipOpenCases = true; return this; }

        CustomerFilter build() {
            return new CustomerFilter(null, null, excludeAreas, carriers, exchange, cancelled, returned, noOpen, maxShipments, null, null,
                    bought, null, notBought, null, null, skipOpenCases, null);
        }
    }

    private List<String> names(CustomerFilter filter) {
        return contacts.eligibleNotInvited(50, null, filter).stream().map(ContactService.Invitee::name).sorted().toList();
    }

    @Test
    void khongLocThiGiongHetHomNayVaKhachMuaNhieuLanKhongBaoGioLot() {
        assertThat(names(CustomerFilter.NONE)).containsExactly("A", "B", "C", "D", "E");
        assertThat(contacts.countEligibleNotInvited(null, CustomerFilter.NONE)).isEqualTo(5);
    }

    @Test
    void locTheoDonHuyDonHoanVaDonDangGiao() {
        assertThat(names(f(b -> b.cancelled(CustomerFilter.Tri.NO)))).containsExactly("A", "D", "E");
        assertThat(names(f(b -> b.cancelled(CustomerFilter.Tri.YES)))).containsExactly("B", "C");
        assertThat(names(f(b -> b.returned(CustomerFilter.Tri.NO)))).containsExactly("A", "B", "C", "E");
        assertThat(names(f(b -> b.returned(CustomerFilter.Tri.YES)))).containsExactly("D");
        assertThat(names(f(Builder::noOpen))).containsExactly("A", "B", "C");
        assertThat(names(f(b -> b.maxShipments(1)))).containsExactly("A");
    }

    @Test
    void ketHopBangVaLaSoDonKhongQuaNhieuVaKhongCoDonHuy() {
        assertThat(names(f(b -> b.cancelled(CustomerFilter.Tri.NO).returned(CustomerFilter.Tri.NO).noOpen()))).containsExactly("A");
        assertThat(names(f(b -> b.cancelled(CustomerFilter.Tri.NO).maxShipments(1)))).containsExactly("A");
    }

    @Test
    void donDoiChiLocDuocKhiDaCaiDauHieu() {
        assertThatThrownBy(() -> names(f(b -> b.exchange(CustomerFilter.Tri.NO))))
                .isInstanceOf(ImportException.class).hasMessageContaining("Chưa cài cách nhận biết đơn đổi");
        markers.save("DOI", "", "", "admin");
        assertThat(names(f(b -> b.exchange(CustomerFilter.Tri.YES)))).containsExactly("C");
        assertThat(names(f(b -> b.exchange(CustomerFilter.Tri.NO)))).containsExactly("A", "B", "D", "E");
        // ví dụ của chủ dự án: không có đơn đổi và không có đơn hủy
        assertThat(names(f(b -> b.exchange(CustomerFilter.Tri.NO).cancelled(CustomerFilter.Tri.NO)))).containsExactly("A", "D", "E");
    }

    @Test
    void locTheoNhomHangDaMuaVaChuaMua() {
        assertThat(names(f(b -> b.bought(ProductGroup.PIN)))).containsExactly("B");
        assertThat(names(f(b -> b.bought(ProductGroup.AO_DIEU_HOA)))).containsExactly("A", "C", "D", "E");
        assertThat(names(f(b -> b.notBought(ProductGroup.PIN)))).containsExactly("A", "C", "D", "E");
        assertThat(names(f(b -> b.notBought(ProductGroup.AO_DIEU_HOA)))).containsExactly("B");
        // mua áo nhưng chưa mua pin (bán thêm)
        assertThat(names(f(b -> b.bought(ProductGroup.AO_DIEU_HOA).notBought(ProductGroup.PIN)))).containsExactly("A", "C", "D", "E");
    }

    @Test
    void chuaMuaLoaiKhachCoDonGiaoThanhCongChuaRoTenHang() {
        importRows("T8,DH8,05/01/2026 09:00:00,G,0900000008,8 Duong A Xa B Hà Nội,Giao thành công,");
        contacts.approveAllCandidates();
        assertThat(names(f(b -> b.notBought(ProductGroup.PIN)))).containsExactly("A", "C", "D", "E");
        assertThat(names(CustomerFilter.NONE)).contains("G");
    }

    @Test
    void locTheoKhuVucLoaiTruVaHang() {
        assertThat(names(f(b -> b.excludeAreas("R:NAM")))).containsExactly("A", "D", "E");
        assertThat(names(f(b -> b.excludeAreas("R:NAM", "R:BAC")))).containsExactly("D");
        assertThat(names(f(b -> b.carriers("VIETTEL_POST")))).hasSize(5);
        assertThat(names(f(b -> b.carriers("JT_EXPRESS")))).isEmpty();
    }

    @Test
    void locTheoTienDoiSoatChiTinhKhachCoDoiSoat() {
        jdbc.sql("INSERT INTO settlement_period (id, carrier, period_code, last_batch_id) VALUES (1, 'JT_EXPRESS', 'k1', (SELECT min(id) FROM import_batch))")
                .update();
        jdbc.sql("""
                INSERT INTO settlement_line (period_id, tracking_no, cod_collected, freight_fee, cod_fee, return_fee, partial_fee, adjustment, net_payable)
                VALUES (1, 'T1', 1500000, 0, 0, 0, 0, 0, 1500000), (1, 'T2', 300000, 0, 0, 0, 0, 0, 300000)""").update();
        var high = new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, null, null, null, 1000000L, null, false, null);
        var low = new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 500000L, false, null);
        assertThat(names(high)).containsExactly("A");
        assertThat(names(low)).containsExactly("B"); // khách chưa có đối soát (C, D, E) không được chọn
    }

    @Test
    void boQuaKhachDangCoViecCanNguoiThat() {
        long b = jdbc.sql("SELECT id FROM customer WHERE name = 'B'").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO warranty_case (customer_id, zalo_uid, state, category, urgency, inbound_at)
                VALUES (:c, 'u1', 'OPEN', 'KHAC', 'THUONG', now())""").param("c", b).update();
        assertThat(names(CustomerFilter.NONE)).contains("B");
        assertThat(names(f(Builder::skipOpenCases))).containsExactly("A", "C", "D", "E");
    }

    @Test
    void locChiLamHepKhachTuChoiVaKhachDaMoiVanBiLoai() {
        long a = jdbc.sql("SELECT id FROM customer WHERE name = 'A'").query(Long.class).single();
        jdbc.sql("INSERT INTO suppression (phone_hash, reason, recorded_by) SELECT encode(sha256(convert_to(phone, 'UTF8')), 'hex'), 'OPT_OUT', 'test' FROM customer WHERE id = :a")
                .param("a", a).update();
        assertThat(names(CustomerFilter.NONE)).containsExactly("B", "C", "D", "E");
        invites.createCampaign("Đợt 1", null, 14, 1, null);
        assertThat(names(CustomerFilter.NONE)).hasSize(3);
    }

    @Test
    void tuNhanKhachMoiCungDungBoLoc() {
        importRows(
                "T9,DH9,05/01/2026 09:00:00,G,0900000009,9 Duong A Xa B Hà Nội,Giao thành công,Áo điều hòa",
                "T9b,DH9b,06/01/2026 09:00:00,G,0900000009,9 Duong A Xa B Hà Nội,Đã hủy,Áo điều hòa",
                "T10,DH10,05/01/2026 09:00:00,H,0900000010,10 Duong A Xa B Hà Nội,Giao thành công,Áo điều hòa");
        var filter = f(b -> b.cancelled(CustomerFilter.Tri.NO));
        assertThat(contacts.newCandidates(Instant.EPOCH, null, 10, filter)).extracting(ContactService.Invitee::name).containsExactly("H");
        assertThat(contacts.newCandidates(Instant.EPOCH, null, 10)).extracting(ContactService.Invitee::name).containsExactlyInAnyOrder("G", "H");
    }

    @Test
    void boLocDuocLuuVaoChienDichVaDocLaiDuoc() {
        var filter = f(b -> b.cancelled(CustomerFilter.Tri.NO).noOpen().notBought(ProductGroup.PIN));
        long id = invites.createCampaign("Khách ổn định", null, 14, 50, null, filter);
        assertThat(invites.campaignFilter(id)).isEqualTo(filter);
        assertThat(jdbc.sql("SELECT customer_filter IS NOT NULL FROM invitation_campaign WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(invites.campaignFilter(id).describe()).contains("Không có đơn hủy", "Bỏ khách đang có đơn chưa giao xong", "Chưa mua: pin");
        // chiến dịch tạo như cũ (không lọc) thì không lưu bộ lọc
        long plain = invites.createCampaign("Không lọc", null, 14, 50, null);
        assertThat(invites.campaignFilter(plain)).isEqualTo(CustomerFilter.NONE);
        assertThat(jdbc.sql("SELECT customer_filter IS NULL FROM invitation_campaign WHERE id = :id").param("id", plain).query(Boolean.class).single()).isTrue();
    }

    @Test
    void giaTriSaiBiTuChoiBangChuTiengViet() {
        assertThatThrownBy(() -> new CustomerFilter(null, null, null, null, null, null, null, false, null, 100, 50, null, null, null, null, null, false, null))
                .isInstanceOf(ImportException.class).hasMessageContaining("Độ mới");
        assertThatThrownBy(() -> new CustomerFilter(null, null, List.of("R:ABC"), null, null, null, null, false, null, null, null, null, null, null, null, null, false, null))
                .isInstanceOf(ImportException.class).hasMessageContaining("Khu vực");
        assertThatThrownBy(() -> new CustomerFilter(null, null, null, List.of("GHN"), null, null, null, false, null, null, null, null, null, null, null, null, false, null))
                .isInstanceOf(ImportException.class).hasMessageContaining("Hãng");
        assertThatThrownBy(() -> new CustomerFilter(null, null, null, null, null, null, null, false, 0, null, null, null, null, null, null, null, false, null))
                .isInstanceOf(ImportException.class).hasMessageContaining("Tổng số đơn");
        assertThatThrownBy(() -> f(b -> b.bought(ProductGroup.PIN).notBought(ProductGroup.PIN)))
                .isInstanceOf(ImportException.class).hasMessageContaining("vừa");
        assertThatThrownBy(() -> new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, List.of(ProductGroup.CHUA_RO), null, null, null, null, false, null))
                .isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> new CustomerFilter(null, null, null, null, null, null, null, false, null, null, null, null, null, null, null, null, false, "RANDOM"))
                .isInstanceOf(ImportException.class).hasMessageContaining("Thứ tự");
    }

    @Test
    void jsonHongHoacRongThiKhongLocGiVaKhongNoiRong() {
        assertThat(CustomerFilter.fromJson(null)).isEqualTo(CustomerFilter.NONE);
        assertThat(CustomerFilter.fromJson("{không phải json")).isEqualTo(CustomerFilter.NONE);
        assertThat(CustomerFilter.fromJson("{\"exchange\":\"CO_LA\"}")).isEqualTo(CustomerFilter.NONE);
        assertThat(CustomerFilter.NONE.describe()).isEmpty();
        var json = f(b -> b.cancelled(CustomerFilter.Tri.NO).bought(ProductGroup.AO_DIEU_HOA)).toJson();
        assertThat(CustomerFilter.fromJson(json).cancelled()).isEqualTo(CustomerFilter.Tri.NO);
    }

    @Test
    void formTaoChienDichCoBoLocVaTrangChiTietHienLaiBangChu() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        mvc.perform(get("/campaigns").param("tab", "moi").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Lọc khách kỹ hơn")))
                .andExpect(content().string(containsString("Cách nhận biết đơn đổi")))
                .andExpect(content().string(containsString("Chưa cài cách nhận biết đơn đổi")));
        mvc.perform(post("/campaigns/exchange-markers").with(owner).with(csrf()).param("orderRef", "DOI, đổi")
                        .param("goods", "").param("status", "")).andExpect(status().is3xxRedirection());
        assertThat(markers.get().orderRef()).containsExactly("doi", "đổi");
        mvc.perform(post("/campaigns").with(owner).with(csrf()).param("name", "Ổn định").param("count", "50").param("expiresDays", "14")
                        .param("cancelled", "NO").param("exchange", "NO").param("notBought", "PIN").param("pickOrder", "NEWEST_PURCHASE"))
                .andExpect(status().is3xxRedirection());
        var c = invites.campaigns().get(0);
        assertThat(c.total()).isEqualTo(3); // A, D, E: không đơn hủy, không đơn đổi, chưa mua pin
        mvc.perform(get("/campaigns/" + c.id()).with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chiến dịch này mời ai")))
                .andExpect(content().string(containsString("Không có đơn hủy")))
                .andExpect(content().string(containsString("Không có đơn đổi")))
                .andExpect(content().string(containsString("Khách mua gần đây nhất trước")));
        mvc.perform(post("/campaigns").with(owner).with(csrf()).param("name", "Lỗi").param("count", "5").param("minDays", "abc"))
                .andExpect(status().is4xxClientError());
    }

    // ---------- xem trước (PR 2) ----------

    @Test
    void xemTruocDemDungVaChiRaTungDieuKienLoaiBaoNhieu() {
        markers.save("DOI", "", "", "admin");
        var filter = f(b -> b.exchange(CustomerFilter.Tri.NO).cancelled(CustomerFilter.Tri.NO).noOpen());
        var r = preview.preview(null, 30, filter);
        assertThat(r.ok()).isTrue();
        assertThat(r.base()).isEqualTo(5);
        assertThat(r.steps()).extracting(CampaignPreview.Step::remaining).containsExactly(4L, 3L, 1L); // bỏ C (đổi), rồi B (hủy), rồi D và E (đơn chưa xong)
        assertThat(r.steps()).extracting(CampaignPreview.Step::removed).containsExactly(1L, 1L, 2L);
        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.willInvite()).isEqualTo(1);
        assertThat(r.byArea()).extracting(CampaignPreview.AreaCount::count).containsExactly(1L, 0L, 0L, 0L);
        // số xem trước đúng bằng số khách chiến dịch thật lấy được
        assertThat(contacts.eligibleNotInvited(30, null, filter)).hasSize((int) r.matched());
    }

    @Test
    void xemTruocBoLocTrongThiBangSoKhachDuDieuKienVaKhongTaoGi() {
        var r = preview.preview(null, 3, CustomerFilter.NONE);
        assertThat(r.matched()).isEqualTo(5);
        assertThat(r.willInvite()).isEqualTo(3);
        assertThat(r.steps()).isEmpty();
        assertThat(invites.campaigns()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
        assertThat(preview.preview("R:NAM", 30, CustomerFilter.NONE).matched()).isEqualTo(2);
        assertThat(preview.preview("R:NAM", 30, CustomerFilter.NONE).byArea()).isEmpty();
    }

    @Test
    void xemTruocBaoLoiBangChuKhiChuaCaiDonDoiHoacKhuVucSai() {
        var r = preview.preview(null, 30, f(b -> b.exchange(CustomerFilter.Tri.NO)));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("Chưa cài cách nhận biết đơn đổi");
        assertThat(preview.preview("R:ABC", 30, CustomerFilter.NONE).error()).contains("Khu vực không hợp lệ");
    }

    @Test
    void trangXemTruocTraDoanTrangVaKhongTaoChienDich() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        mvc.perform(post("/campaigns/preview").with(owner).with(csrf()).param("count", "30").param("cancelled", "NO"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("khách khớp")))
                .andExpect(content().string(containsString("Không có đơn hủy")))
                .andExpect(content().string(containsString("data-matched=\"3\"")));
        mvc.perform(post("/campaigns/preview").with(owner).with(csrf()).param("exchange", "NO"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa xem trước được")))
                .andExpect(content().string(containsString("data-matched=\"0\"")));
        mvc.perform(post("/campaigns/preview").with(owner).with(csrf()).param("minDays", "abc"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("phải là số nguyên")));
        mvc.perform(get("/campaigns").param("tab", "moi").with(owner)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Xem trước số khách khớp")))
                .andExpect(content().string(containsString("campaign-preview.js")));
        assertThat(invites.campaigns()).isEmpty();
    }
}
