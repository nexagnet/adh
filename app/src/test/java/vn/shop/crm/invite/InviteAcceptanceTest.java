package vn.shop.crm.invite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.AppSettings;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.contact.PrivacyService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.shipping.Carrier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mốc 1C: nhóm Zalo, chiến dịch mời, link riêng, lượt bấm. Postgres thật, không gửi tin cho ai. */
class InviteAcceptanceTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";
    private static final String LINK_1 = "https://zalo.me/g/aaa111";
    private static final String LINK_2 = "https://zalo.me/g/bbb222";

    @Autowired
    ImportService imports;
    @Autowired
    ContactService contacts;
    @Autowired
    PrivacyService privacy;
    @Autowired
    GroupService groups;
    @Autowired
    InviteService invites;
    @Autowired
    AppSettings settings;

    private void importRows(String... rows) {
        String csv = HEADER + String.join("\n", rows) + "\n";
        long id = imports.createBatch(Carrier.VIETTEL_POST, "test.csv", csv.getBytes(StandardCharsets.UTF_8));
        ImportService.FileView view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
    }

    /** n khách mua đúng 1 lần, số 0900000001.. */
    private void seedOneTimers(int n) {
        String[] rows = new String[n];
        for (int i = 0; i < n; i++) {
            String phone = "09000000%02d".formatted(i + 1);
            rows[i] = "T%d,DH%d,05/01/2026 09:00:00,Khach %d,%s,%d Duong A,Giao thành công"
                    .formatted(i + 1, i + 1, i + 1, phone, i + 1);
        }
        importRows(rows);
    }

    private long customerId(String phone) {
        return jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", "+84" + phone.substring(1))
                .query(Long.class).single();
    }

    private long openGroup(String name, String link) {
        long id = groups.create(name, link, 5);
        groups.setStatus(id, GroupStatus.OPEN);
        return id;
    }

    private String tokenOf(long customerId) {
        return jdbc.sql("SELECT token FROM invitation WHERE customer_id = :c").param("c", customerId)
                .query(String.class).single();
    }

    private int clicks(long customerId) {
        return jdbc.sql("SELECT click_count FROM invitation WHERE customer_id = :c").param("c", customerId)
                .query(Integer.class).single();
    }

    // ---------- chỉ khách đủ điều kiện, mỗi khách một lần ----------

    @Test
    void chiKhachDaDuyetDuocMoiVaMoiKhachChiMotLan() {
        seedOneTimers(3);

        assertThatThrownBy(() -> invites.createCampaign("Thử", null, 14, 5))
                .isInstanceOf(ImportException.class).hasMessageContaining("Chưa có khách nào đủ điều kiện");

        contacts.approve(customerId("0900000001"));
        contacts.approve(customerId("0900000002"));
        long first = invites.createCampaign("Đợt 1", null, 14, 10);

        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE campaign_id = :c").param("c", first)
                .query(Long.class).single()).isEqualTo(2);
        // khách thứ 3 chưa duyệt nên không có lời mời; hai khách đầu đã được mời nên không mời lại
        assertThatThrownBy(() -> invites.createCampaign("Đợt 2", null, 14, 10)).isInstanceOf(ImportException.class);
        contacts.approve(customerId("0900000003"));
        long second = invites.createCampaign("Đợt 2", null, 14, 10);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE campaign_id = :c").param("c", second)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void khachMuaTuHaiLanKhongBaoGioDuocMoiKeCaKhiBiEpDongDuyet() {
        importRows(
                "T1,DH1,05/01/2026 09:00:00,Khach Lap,0366111222,1 Duong A,Giao thành công",
                "T2,DH2,06/01/2026 09:00:00,Khach Lap,0366111222,1 Duong A,Giao thành công",
                "T3,DH3,07/01/2026 09:00:00,Khach Mot,0912345678,2 Duong B,Giao thành công");
        // ép dòng ELIGIBLE cho khách REPEAT, như một lỗi/thao tác sai trực tiếp trên cơ sở dữ liệu
        jdbc.sql("""
                INSERT INTO contact_basis (customer_id, purpose, basis, status, recorded_by)
                SELECT id, 'GROUP_INVITE_POSTSALE', 'TRANSACTION_POSTSALE', 'ELIGIBLE', 'test'
                FROM customer WHERE segment = 'REPEAT'""").update();
        contacts.approve(customerId("0912345678"));

        invites.createCampaign("Đợt 1", null, 14, 10);

        assertThat(jdbc.sql("SELECT count(*) FROM invitation i JOIN customer c ON c.id = i.customer_id "
                + "WHERE c.segment = 'REPEAT'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void khachBiTuChoiHoacBiChanKhongCoLoiMoi() {
        seedOneTimers(3);
        contacts.approveAllCandidates();
        contacts.suppress(customerId("0900000001"), "khách nhắn");
        contacts.block(customerId("0900000002"), "phàn nàn");

        invites.createCampaign("Đợt 1", null, 14, 10);

        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT customer_id FROM invitation").query(Long.class).single())
                .isEqualTo(customerId("0900000003"));
    }

    @Test
    void loMoiToiDaGioiHanVaTamDungTatCa() {
        seedOneTimers(1);
        contacts.approveAllCandidates();

        assertThatThrownBy(() -> invites.createCampaign("Thử", null, 14, invites.maxCampaign() + 1))
                .hasMessageContaining("1 đến " + invites.maxCampaign());
        assertThatThrownBy(() -> invites.createCampaign("Thử", "x".repeat(801), 14, 5)).hasMessageContaining("quá dài");

        settings.pauseAll("kiểm tra", "test");
        assertThatThrownBy(() -> invites.createCampaign("Thử", null, 14, 5)).hasMessageContaining("tạm dừng");
        settings.resumeAll("test");
        // tin nhắn bằng tài khoản Zalo phụ không cần {link}
        long camp = invites.createCampaign("Thử", "Chào {ten}, {cua_hang} tư vấn cách dùng sản phẩm.", 14, 5);
        assertThat(invites.invitations(camp, invites.campaign(camp).orElseThrow().template()).getFirst().message())
                .startsWith("Chào Khach 1");
    }

    @Test
    void tokenNgauNhienKhongChuaSoDienThoai() {
        seedOneTimers(2);
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", null, 14, 10);

        List<String> tokens = jdbc.sql("SELECT token FROM invitation").query(String.class).list();

        assertThat(tokens).hasSize(2).doesNotHaveDuplicates();
        assertThat(tokens).allSatisfy(t -> {
            assertThat(t).hasSizeGreaterThanOrEqualTo(22).matches("[A-Za-z0-9_-]+");
            assertThat(t).doesNotContain("900000");
        });
    }

    // ---------- bấm link ----------

    @Test
    void bamLinkChiLaLuotBamKhongPhaiDaVaoNhomVaChuyenDenLinkZalo() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        long group = openGroup("Nhóm 1", LINK_1);
        invites.createCampaign("Đợt 1", null, 14, 10);
        long cust = customerId("0900000001");
        String token = tokenOf(cust);

        assertThat(invites.usable(token)).isTrue(); // mở trang không tính là lượt bấm
        assertThat(clicks(cust)).isZero();

        InviteService.GoResult result = invites.go(token);

        assertThat(result.outcome()).isEqualTo(InviteService.Outcome.REDIRECT);
        assertThat(result.url()).isEqualTo(LINK_1);
        assertThat(clicks(cust)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM invitation WHERE customer_id = :c").param("c", cust)
                .query(String.class).single()).isEqualTo("CLICKED");
        // số khách thật của nhóm KHÔNG tự tăng: click ≠ join
        assertThat(groups.find(group).orElseThrow().actual()).isZero();
        assertThat(groups.find(group).orElseThrow().pendingClicks()).isEqualTo(1);
    }

    @Test
    void bamLaiVanVaoCungNhomVaTangSoLuotBam() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        openGroup("Nhóm 1", LINK_1);
        openGroup("Nhóm 2", LINK_2);
        invites.createCampaign("Đợt 1", null, 14, 10);
        String token = tokenOf(customerId("0900000001"));

        String first = invites.go(token).url();
        String second = invites.go(token).url();

        assertThat(second).isEqualTo(first);
        assertThat(clicks(customerId("0900000001"))).isEqualTo(2);
    }

    @Test
    void khongCoNhomPhuHopThiKhongChuyenHuongVaKhongGhiLuotBam() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        groups.create("Nhóm chuẩn bị", LINK_1, 5); // còn PREPARING
        invites.createCampaign("Đợt 1", null, 14, 10);
        long cust = customerId("0900000001");

        InviteService.GoResult result = invites.go(tokenOf(cust));

        assertThat(result.outcome()).isEqualTo(InviteService.Outcome.NO_GROUP);
        assertThat(result.url()).isNull();
        assertThat(clicks(cust)).isZero();
    }

    @Test
    void chiaDeuGiuaCacNhomKhongDonHetVaoMotNhom() {
        seedOneTimers(6);
        contacts.approveAllCandidates();
        long g1 = openGroup("Nhóm 1", LINK_1);
        long g2 = openGroup("Nhóm 2", LINK_2);
        invites.createCampaign("Đợt 1", null, 14, 10);

        for (int i = 1; i <= 6; i++) {
            invites.go(tokenOf(customerId("09000000%02d".formatted(i))));
        }

        long inG1 = jdbc.sql("SELECT count(*) FROM invitation WHERE group_id = :g").param("g", g1)
                .query(Long.class).single();
        long inG2 = jdbc.sql("SELECT count(*) FROM invitation WHERE group_id = :g").param("g", g2)
                .query(Long.class).single();
        assertThat(inG1).isEqualTo(3);
        assertThat(inG2).isEqualTo(3);
    }

    @Test
    void nhomGanDayKhongCapLinkMoiVaNhomDaDem() {
        seedOneTimers(8);
        contacts.approveAllCandidates();
        long g1 = openGroup("Nhóm 1", LINK_1);
        groups.recount(g1, 5); // mẹ đếm: đã đủ 5 khách thật
        invites.createCampaign("Đợt 1", null, 14, 10);

        InviteService.GoResult full = invites.go(tokenOf(customerId("0900000001")));
        assertThat(full.outcome()).isEqualTo(InviteService.Outcome.NO_GROUP);

        groups.recount(g1, 2);
        InviteService.GoResult open = invites.go(tokenOf(customerId("0900000001")));
        assertThat(open.outcome()).isEqualTo(InviteService.Outcome.REDIRECT);
    }

    @Test
    void nhomTamDungKhongNhanKhachMoi() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        long g1 = openGroup("Nhóm 1", LINK_1);
        groups.setStatus(g1, GroupStatus.PAUSED);
        invites.createCampaign("Đợt 1", null, 14, 10);

        assertThat(invites.go(tokenOf(customerId("0900000001"))).outcome())
                .isEqualTo(InviteService.Outcome.NO_GROUP);
    }

    // ---------- điều kiện kiểm lại khi bấm ----------

    @Test
    void khachTuChoiSauKhiDuocMoiThiLinkKhongConDungDuoc() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        openGroup("Nhóm 1", LINK_1);
        invites.createCampaign("Đợt 1", null, 14, 10);
        long cust = customerId("0900000001");
        String token = tokenOf(cust);
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now() WHERE customer_id = :c")
                .param("c", cust).update();

        privacy.stopContact(cust, null, "khách nhắn không muốn");

        assertThat(invites.usable(token)).isFalse();
        assertThat(invites.go(token).outcome()).isEqualTo(InviteService.Outcome.UNAVAILABLE);
        assertThat(jdbc.sql("SELECT status FROM invitation WHERE customer_id = :c").param("c", cust)
                .query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    void chanKhachXoaLoiMoiChuaGuiVaBoChanPhaiDuyetLai() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", null, 14, 10);
        long cust = customerId("0900000001");

        contacts.block(cust, "phàn nàn");

        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
        contacts.unblock(cust);
        assertThat(contacts.eligibleNotInvited(10)).isEmpty(); // chưa duyệt lại thì chưa được mời
        contacts.approve(cust);
        assertThat(contacts.eligibleNotInvited(10)).hasSize(1);
    }

    @Test
    void khachBamNgungNhanTinTrenTrangMoiThiBiGhiVaoDanhSachTuChoi() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", null, 14, 10);
        long cust = customerId("0900000001");
        String token = tokenOf(cust);

        assertThat(invites.optOut(token)).isTrue();

        assertThat(contacts.isSuppressed("+84900000001")).isTrue();
        assertThat(contacts.inviteList(1, 10).items()).isEmpty();
        assertThat(contacts.events(cust)).extracting(ContactService.Event::recordedBy).contains("khach-tu-bam");
        assertThat(invites.optOut("khong-ton-tai")).isFalse();
    }

    @Test
    void linkHetHanKhongDungDuocVaTokenLaKhongTimThay() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        openGroup("Nhóm 1", LINK_1);
        invites.createCampaign("Đợt 1", null, 14, 10);
        String token = tokenOf(customerId("0900000001"));
        jdbc.sql("UPDATE invitation SET expires_at = now() - interval '1 minute'").update();

        assertThat(invites.go(token).outcome()).isEqualTo(InviteService.Outcome.UNAVAILABLE);
        assertThat(invites.go("khong-co-token-nay").outcome()).isEqualTo(InviteService.Outcome.UNAVAILABLE);
        assertThat(invites.go("x".repeat(200)).outcome()).isEqualTo(InviteService.Outcome.UNAVAILABLE);
    }

    @Test
    void xoaDuLieuKhachXoaCaLoiMoi() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", null, 14, 10);
        long cust = customerId("0900000001");

        privacy.erase(cust, null, "khách yêu cầu");

        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
    }

    @Test
    void huyLoiMoiChuaGuiTraKhachVeHangCho() {
        seedOneTimers(2);
        contacts.approveAllCandidates();
        long camp = invites.createCampaign("Đợt 1", null, 14, 10);

        assertThat(invites.cancelUnsent(camp)).isEqualTo(2);

        assertThat(contacts.eligibleNotInvited(10)).hasSize(2);
    }

    // ---------- nhóm ----------

    @Test
    void chiNhanLinkHttpsToiZaloMe() {
        for (String bad : List.of("http://zalo.me/g/abc", "https://evil.example.com/g/abc", "javascript:alert(1)",
                "https://zalo.me.evil.com/g/abc", "https://user@zalo.me/g/abc", "ftp://zalo.me/g/abc")) {
            assertThatThrownBy(() -> groups.create("Nhóm", bad, 5))
                    .as(bad).isInstanceOf(ImportException.class);
        }
        assertThat(groups.create("Nhóm", "https://zalo.me/g/abc123", 5)).isPositive();
        assertThat(groups.create("Nhóm không link", "", 5)).isPositive();
    }

    @Test
    void khongMoNhomKhiChuaCoLinkVaKhongXoaLinkCuaNhomDangMo() {
        long noLink = groups.create("Nhóm 1", null, 5);
        assertThatThrownBy(() -> groups.setStatus(noLink, GroupStatus.OPEN)).hasMessageContaining("link");

        long open = openGroup("Nhóm 2", LINK_1);
        assertThatThrownBy(() -> groups.update(open, "Nhóm 2", "", 5, null)).hasMessageContaining("link");
    }

    @Test
    void soKhachNhomKhongTinhTaiKhoanMeVaBot() {
        long g = openGroup("Nhóm 1", LINK_1);
        groups.recount(g, 5);

        GroupService.Group group = groups.find(g).orElseThrow();

        assertThat(group.actual()).isEqualTo(5);
        assertThat(group.expectedMembers()).isEqualTo(7); // 5 khách + mẹ + bot
        assertThatThrownBy(() -> groups.recount(g, -1)).isInstanceOf(ImportException.class);
    }

    @Test
    void renderTinKhongChuaSoDienThoaiVaDiaChi() {
        seedOneTimers(1);
        contacts.approveAllCandidates();
        long camp = invites.createCampaign("Đợt 1", null, 14, 10);

        var row = invites.invitations(camp, InviteService.DEFAULT_TEMPLATE).getFirst();

        assertThat(row.message()).contains("Khach 1").contains("/i/" + tokenOf(customerId("0900000001")));
        assertThat(row.message()).doesNotContain("0900000001").doesNotContain("Duong");
    }
}
