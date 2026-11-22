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
 * Luồng kết bạn -> vào nhóm (D15, R3 đến R8, R10): gợi ý nhóm ngay, nút gộp, giữ chỗ, chờ nhóm, nhắc quá hạn,
 * không phản hồi, khách rời nhóm. Hệ thống chỉ ghi và nhắc trên web, không gửi gì cho khách.
 */
class FriendQueueTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired WebApplicationContext context;

    long bacOne;   // nhóm miền Bắc chỉ còn đúng 1 chỗ
    long bacTwo;   // nhóm miền Bắc dự phòng
    long campaign;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 3; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",090000000").append(i).append(",1 Duong A Xa B Hà Nội,Giao thành công\n");
        }
        csv.append("T4,DH4,05/01/2026 09:00:00,Khach Nam,0900000004,2 Duong A Xa B Cần Thơ,Giao thành công\n");
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        bacOne = groups.create("Nhóm Bắc 1", "https://zalo.me/g/bac1", 1, "R:BAC");
        groups.setStatus(bacOne, GroupStatus.OPEN);
        bacTwo = groups.create("Nhóm Bắc 2", "https://zalo.me/g/bac2", 5, "R:BAC");
        groups.setStatus(bacTwo, GroupStatus.OPEN);
        contacts.approveAllCandidates();
        campaign = invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 10);
    }

    private long inv(int n) {
        return jdbc.sql("SELECT i.id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p")
                .param("p", "+8490000000" + n).query(Long.class).single();
    }

    private String statusOf(int n) {
        return jdbc.sql("SELECT status FROM invitation WHERE id = :id").param("id", inv(n)).query(String.class).single();
    }

    private void sent(int n) {
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), sent_channel = 'MANUAL_EXPORT' WHERE id = :id")
                .param("id", inv(n)).update();
    }

    private InviteService.InvitationRow row(int n) {
        return invites.invitations(campaign, invites.campaign(campaign).orElseThrow().template()).stream()
                .filter(r -> r.id() == inv(n)).findFirst().orElseThrow();
    }

    private long actual(long group) {
        return groups.find(group).orElseThrow().actual();
    }

    // ---------- R3: gợi ý nhóm ngay khi bấm Đã kết bạn ----------

    @Test
    void baoKetBanThiGoiYNgayNhomCungKhuVucVaGiuCho() {
        sent(1);
        var r = invites.markFriended(inv(1));
        assertThat(r.reserved()).isTrue();
        assertThat(r.groupName()).isEqualTo("Nhóm Bắc 1"); // cùng điểm, nhóm có id nhỏ hơn
        assertThat(r.groupLink()).isEqualTo("https://zalo.me/g/bac1");
        assertThat(row(1).reservedUntil()).isNotNull();
        assertThat(groups.find(bacOne).orElseThrow().reserved()).isEqualTo(1);
        assertThat(actual(bacOne)).isZero(); // giữ chỗ không phải là đã vào nhóm
    }

    // ---------- R4: giữ chỗ ----------

    @Test
    void haiKhachKetBanLienNhauKhongBiDonVaoMotNhomChiConMotCho() {
        sent(1);
        sent(2);
        assertThat(invites.markFriended(inv(1)).groupName()).isEqualTo("Nhóm Bắc 1");
        var second = invites.markFriended(inv(2));
        assertThat(second.groupName()).isEqualTo("Nhóm Bắc 2"); // nhóm 1 đã được giữ chỗ cho khách trước
    }

    @Test
    void hetHanGiuChoThiNhomLaiDuocGoiYChoNguoiKhacVaChoCuaKhachNayGoiYLai() {
        sent(1);
        sent(2);
        invites.markFriended(inv(1));
        jdbc.sql("UPDATE invitation SET reserved_until = now() - interval '1 minute' WHERE id = :id").param("id", inv(1)).update();
        assertThat(groups.find(bacOne).orElseThrow().reserved()).isZero();
        assertThat(row(1).reservedUntil()).isNull(); // hết hạn: không còn hiện là đã giữ chỗ
        assertThat(invites.markFriended(inv(2)).groupName()).isEqualTo("Nhóm Bắc 1");
    }

    @Test
    void daVaoNhomThiChoGiuDuocTinhLaChoThatKhongTinhHaiLan() {
        sent(1);
        invites.markFriended(inv(1));
        invites.markJoined(inv(1));
        assertThat(statusOf(1)).isEqualTo("JOINED");
        assertThat(actual(bacOne)).isEqualTo(1);
        assertThat(groups.find(bacOne).orElseThrow().reserved()).isZero();
        assertThat(groups.find(bacOne).orElseThrow().score()).isEqualTo(1.0);
    }

    @Test
    void vaoNhomDungNhomDaGiuChoDuNhomDaTinhDayViChinhChoGiuDo() {
        sent(1);
        invites.markFriended(inv(1)); // nhóm Bắc 1 (mục tiêu 1) giờ tính đầy vì chỗ giữ
        assertThat(groups.find(bacOne).orElseThrow().full()).isTrue();
        invites.markJoined(inv(1));
        assertThat(jdbc.sql("SELECT group_id FROM invitation WHERE id = :id").param("id", inv(1)).query(Long.class).single())
                .isEqualTo(bacOne);
        assertThat(actual(bacTwo)).isZero();
    }

    @Test
    void khachDaTuBamLinkThiKhongTinhChoGiuLanNuaVaoLuotBamCho() {
        sent(1);
        jdbc.sql("UPDATE invitation SET status = 'CLICKED', first_clicked_at = now(), click_count = 1, group_id = :g WHERE id = :id")
                .param("g", bacOne).param("id", inv(1)).update();
        invites.markFriended(inv(1));
        assertThat(groups.find(bacOne).orElseThrow().reserved()).isZero();
        assertThat(groups.find(bacOne).orElseThrow().pendingClicks()).isEqualTo(1);
    }

    @Test
    void boNoiD16KhongGiuChoViTuChonNhomKhiThem() {
        sent(1);
        var r = invites.markFriended(inv(1), "bộ nối Zalo", false);
        assertThat(r.reserved()).isFalse();
        assertThat(groups.find(bacOne).orElseThrow().reserved()).isZero();
        assertThat(statusOf(1)).isEqualTo("FRIENDED");
    }

    // ---------- R5: nút gộp ----------

    @Test
    void nutGopGhiKetBanRoiVaoNhomMotLanVaKhongTangHaiLan() {
        sent(1);
        invites.markFriendedAndJoined(inv(1));
        assertThat(statusOf(1)).isEqualTo("JOINED");
        assertThat(actual(bacOne)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM contact_event WHERE event = 'FRIENDED'").query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> invites.markFriendedAndJoined(inv(1))).isInstanceOf(ImportException.class);
        assertThat(actual(bacOne)).isEqualTo(1);
    }

    @Test
    void nutGopKhachDaKetBanChiGhiVaoNhom() {
        sent(1);
        invites.markFriended(inv(1));
        invites.markFriendedAndJoined(inv(1));
        assertThat(statusOf(1)).isEqualTo("JOINED");
        assertThat(jdbc.sql("SELECT count(*) FROM contact_event WHERE event = 'FRIENDED'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void nutGopKhongCoNhomHopThiBaoLoiVaKhongGhiGi() {
        sent(4); // khách miền Nam, chỉ có nhóm miền Bắc
        assertThatThrownBy(() -> invites.markFriendedAndJoined(inv(4))).isInstanceOf(ImportException.class)
                .hasMessageContaining("Chưa có nhóm");
        assertThat(statusOf(4)).isEqualTo("SENT");
        assertThat(jdbc.sql("SELECT count(*) FROM contact_event WHERE event = 'FRIENDED'").query(Long.class).single()).isZero();
    }

    // ---------- chọn nhóm khác nhóm gợi ý, cột gợi ý trong file xuất ----------

    private long groupOf(int n) {
        return jdbc.sql("SELECT group_id FROM invitation WHERE id = :id").param("id", inv(n)).query(Long.class).single();
    }

    @Test
    void daVaoNhomKhacNhomGoiYThiGhiDungNhomMeChonVaNhomGoiYKhongBiTinh() {
        sent(1);
        invites.markFriended(inv(1)); // gợi ý và giữ chỗ ở Nhóm Bắc 1
        invites.markJoined(inv(1), bacTwo);
        assertThat(groupOf(1)).isEqualTo(bacTwo);
        assertThat(actual(bacTwo)).isEqualTo(1);
        assertThat(actual(bacOne)).isZero();
        assertThat(groups.find(bacOne).orElseThrow().reserved()).isZero(); // chỗ giữ được trả lại
    }

    @Test
    void nutGopCungChonDuocNhomKhacVaNhomKhongDungDuocThiBaoLoiKhongGhiGi() {
        sent(1);
        sent(2);
        long closed = groups.create("Nhóm đóng", "https://zalo.me/g/dong", 5, "R:BAC");
        groups.setStatus(closed, GroupStatus.CLOSED);
        assertThatThrownBy(() -> invites.markFriendedAndJoined(inv(1), closed)).isInstanceOf(ImportException.class);
        assertThat(statusOf(1)).isEqualTo("SENT");
        assertThatThrownBy(() -> invites.markJoined(inv(2), 999_999L)).isInstanceOf(ImportException.class);

        invites.markFriendedAndJoined(inv(1), bacTwo);
        assertThat(statusOf(1)).isEqualTo("JOINED");
        assertThat(groupOf(1)).isEqualTo(bacTwo);
    }

    @Test
    void trangChienDichCoONhonChonNhomVaNhanDuocNhomChon() throws Exception {
        sent(1);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/campaigns/" + campaign).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"groupId\"")))
                .andExpect(content().string(containsString("Nhóm Bắc 2")));
        mvc.perform(post("/campaigns/" + campaign + "/invitations/" + inv(1) + "/joined").param("groupId", String.valueOf(bacTwo))
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(groupOf(1)).isEqualTo(bacTwo);
    }

    @Test
    void fileXuatGuiTayCoCotKhuVucVaNhomGoiY() {
        String province = jdbc.sql("SELECT province FROM customer WHERE phone = '+84900000001'").query(String.class).single();
        assertThat(invites.areaLabel(province)).isEqualTo("Hà Nội");
        assertThat(invites.suggestGroupName(province)).contains("Nhóm Bắc 1");
        assertThat(invites.areaLabel(null)).isEmpty();
        // chưa rõ khu vực: gợi ý đúng như nút "Đã kết bạn" sẽ gợi ý, không gợi ý khác đi
        assertThat(invites.suggestGroupName(null)).isEqualTo(groups.pickGroup(null).map(GroupService.Group::name));

        var csv = new String(vn.shop.crm.send.SendService.exportCsv(java.util.List.of(
                new vn.shop.crm.send.InvitationSender.OutboundInvite(1, 1, "+84900000001", "Khach 1", "Chào",
                        "Hà Nội", "Nhóm Bắc 1"),
                new vn.shop.crm.send.InvitationSender.OutboundInvite(2, 2, "+84900000002", "Khach 2", "Chào", "", ""))),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("khu_vuc").contains("nhom_goi_y").contains("Hà Nội").contains("Nhóm Bắc 1");
        assertThat(csv).doesNotContain("zalo.me"); // file xuất không lộ link nhóm
    }

    // ---------- R7: chờ nhóm ----------

    @Test
    void chuaCoNhomHopThiKetBanDuocGhiChoNhomKhongDayNhomMienKhacVaHetChoKhiMoNhom() {
        sent(4);
        var r = invites.markFriended(inv(4));
        assertThat(r.waiting()).isTrue();
        assertThat(r.reserved()).isFalse();
        assertThat(statusOf(4)).isEqualTo("FRIENDED");
        assertThat(row(4).groupName()).isNull();
        assertThat(row(4).waitingGroup()).isTrue();
        assertThat(groups.waitingByProvince()).anyMatch(w -> w.provinceName().equals("Cần Thơ") && w.count() == 1);
        assertThat(invites.friendStats().waitingGroup()).isEqualTo(1);

        long nam = groups.create("Nhóm Nam", "https://zalo.me/g/nam", 5, "R:NAM");
        groups.setStatus(nam, GroupStatus.OPEN);

        assertThat(row(4).waitingGroup()).isFalse();
        assertThat(row(4).groupName()).isEqualTo("Nhóm Nam");
        assertThat(groups.waitingByProvince()).noneMatch(w -> w.provinceName().equals("Cần Thơ"));
        assertThat(invites.friendStats().readyToAdd()).isEqualTo(1);
        invites.markJoined(inv(4));
        assertThat(actual(nam)).isEqualTo(1);
    }

    // ---------- R6, R10: nhắc việc ----------

    @Test
    void ketBanQuaHaiNgayChuaVaoNhomThanhQuaHanVaQuaMuoiBonNgayChuaKetBanLaKhongPhanHoi() {
        sent(1);
        sent(2);
        sent(3);
        invites.markFriended(inv(1));
        invites.markFriended(inv(2));
        jdbc.sql("UPDATE invitation SET friended_at = now() - interval '3 days' WHERE id = :id").param("id", inv(1)).update();
        jdbc.sql("UPDATE invitation SET sent_at = now() - interval '15 days' WHERE id = :id").param("id", inv(3)).update();

        assertThat(row(1).overdue()).isTrue();
        assertThat(row(2).overdue()).isFalse();
        assertThat(row(3).noReply()).isTrue();
        assertThat(row(1).noReply()).isFalse();
        var stats = invites.friendStats();
        assertThat(stats.friended()).isEqualTo(2);
        assertThat(stats.overdue()).isEqualTo(1);
        assertThat(stats.noReply()).isEqualTo(1);
        assertThat(invites.friendedQueue()).extracting(InviteService.InvitationRow::id).containsExactly(inv(1), inv(2));
        assertThat(invites.noReplyQueue()).extracting(InviteService.InvitationRow::id).containsExactly(inv(3));
        // không có đường nào tự nhắn khách: chỉ nhãn, trạng thái vẫn là đã nhắn
        assertThat(statusOf(3)).isEqualTo("SENT");
        assertThat(jdbc.sql("SELECT count(*) FROM send_log").query(Long.class).single()).isZero();
    }

    // ---------- R8: khách rời nhóm ----------

    @Test
    void khachRoiNhomThiSoKhachCuaNhomGiamMotVaChiMotLan() {
        sent(1);
        sent(2);
        invites.markFriendedAndJoined(inv(1));
        invites.markFriendedAndJoined(inv(2));
        long group = jdbc.sql("SELECT group_id FROM invitation WHERE id = :id").param("id", inv(1)).query(Long.class).single();
        long before = actual(group);

        invites.markLeft(inv(1));

        assertThat(actual(group)).isEqualTo(before - 1);
        assertThat(row(1).leftAt()).isNotNull();
        assertThat(statusOf(1)).isEqualTo("JOINED");
        assertThatThrownBy(() -> invites.markLeft(inv(1))).isInstanceOf(ImportException.class).hasMessageContaining("rồi");
        assertThat(actual(group)).isEqualTo(before - 1);
        assertThat(jdbc.sql("SELECT count(*) FROM contact_event WHERE event = 'LEFT_GROUP'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void chiBaoRoiNhomChoKhachDaVaoNhom() {
        sent(1);
        assertThatThrownBy(() -> invites.markLeft(inv(1))).isInstanceOf(ImportException.class);
        invites.markFriended(inv(1));
        assertThatThrownBy(() -> invites.markLeft(inv(1))).isInstanceOf(ImportException.class);
    }

    @Test
    void khachVaoNhomBangLuotBamLinkRoiRoiNhomKhongTruSoKhachThat() {
        sent(1);
        jdbc.sql("UPDATE invitation SET status = 'CLICKED', first_clicked_at = now(), click_count = 1, group_id = :g WHERE id = :id")
                .param("g", bacTwo).param("id", inv(1)).update();
        invites.markJoined(inv(1));
        assertThat(actual(bacTwo)).isZero(); // đã nằm ở lượt bấm chờ, không cộng vào số thật
        invites.markLeft(inv(1));
        assertThat(actual(bacTwo)).isZero(); // không bị trừ xuống âm hay trừ nhầm
    }

    @Test
    void khachTuChoiKhiDangONhomThiNhomGiamMotVaVaoDanhSachTuChoi() {
        sent(1);
        invites.markFriendedAndJoined(inv(1));
        long group = jdbc.sql("SELECT group_id FROM invitation WHERE id = :id").param("id", inv(1)).query(Long.class).single();
        invites.markDeclined(inv(1));
        assertThat(contacts.isSuppressed("+84900000001")).isTrue();
        assertThat(actual(group)).isZero();
        assertThat(row(1).leftAt()).isNotNull();
    }

    @Test
    void soKhachKhongBaoGioAm() {
        sent(1);
        invites.markFriendedAndJoined(inv(1));
        long group = jdbc.sql("SELECT group_id FROM invitation WHERE id = :id").param("id", inv(1)).query(Long.class).single();
        groups.recount(group, 0); // mẹ đếm lại thấy 0 trước khi báo rời
        invites.markLeft(inv(1));
        assertThat(actual(group)).isZero();
    }

    // ---------- giao diện ----------

    @Test
    void trangChoVaoNhomTongQuanVaThanhBenHienViecVaGhiNhanQuaWeb() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        sent(1);
        sent(4);
        invites.markFriended(inv(1));
        invites.markFriended(inv(4));
        jdbc.sql("UPDATE invitation SET friended_at = now() - interval '3 days' WHERE id = :id").param("id", inv(1)).update();

        mvc.perform(get("/friends").with(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chờ vào nhóm")))
                .andExpect(content().string(containsString("Quá hạn thêm vào nhóm")))
                .andExpect(content().string(containsString("Chờ nhóm")))
                .andExpect(content().string(containsString("Đã giữ chỗ đến")));
        mvc.perform(get("/").with(owner))
                .andExpect(content().string(containsString("khách đã kết bạn đang chờ thêm vào nhóm")))
                .andExpect(content().string(containsString("chưa có nhóm hợp khu vực")));

        mvc.perform(post("/campaigns/" + campaign + "/invitations/" + inv(1) + "/joined").param("back", "friends")
                        .with(csrf()).with(owner))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/friends"));
        mvc.perform(post("/campaigns/" + campaign + "/invitations/" + inv(1) + "/left").with(csrf()).with(owner))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/campaigns/" + campaign));
        assertThat(row(1).leftAt()).isNotNull();
        mvc.perform(get("/campaigns/" + campaign).with(owner))
                .andExpect(content().string(containsString("Đã rời nhóm")));
    }

    @Test
    void nutGopVaFlashGoiYNhomTrenTrangChienDich() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("admin").roles("OWNER");
        sent(1);
        sent(2);
        mvc.perform(get("/campaigns/" + campaign).with(owner))
                .andExpect(content().string(containsString("Đã kết bạn và đã vào nhóm")));
        mvc.perform(post("/campaigns/" + campaign + "/invitations/" + inv(1) + "/friended").with(csrf()).with(owner))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("flash", containsString("Nhóm Bắc 1")));
        mvc.perform(post("/campaigns/" + campaign + "/invitations/" + inv(2) + "/friended-joined").with(csrf()).with(owner))
                .andExpect(status().is3xxRedirection());
        assertThat(statusOf(2)).isEqualTo("JOINED");
    }
}
