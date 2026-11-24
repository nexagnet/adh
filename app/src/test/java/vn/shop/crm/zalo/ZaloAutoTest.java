package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.common.ImportException;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.GroupStatus;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloGateway.AddResult;
import vn.shop.crm.zalo.ZaloGateway.Outcome;
import vn.shop.crm.zalo.ZaloGateway.State;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bộ nối Zalo D16 với bộ nối GIẢ: ghép khách, hàng đợi tay, nấc, hạn mức, cầu dao. Không có Zalo thật trong test.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000"})
class ZaloAutoTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloAutoService zalo;
    @Autowired ZaloAutoSettings cfg;
    @Autowired ZaloGroupSettings groupSettings;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    long north;
    long south;

    @BeforeEach
    void setUp() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 8; i++) {
            csv.append("T").append(i).append(",DH").append(i).append(",05/01/2026 09:00:00,Khach ").append(i)
                    .append(",090000000").append(i).append(",1 Duong A Xa B Hà Nội,Giao thành công\n");
        }
        csv.append("T9,DH9,05/01/2026 09:00:00,Khach Nam,0900000009,2 Duong A Xa B Cần Thơ,Giao thành công\n");
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        north = groups.create("Nhóm miền Bắc", "https://zalo.me/g/bac", 50, "R:BAC");
        groups.setStatus(north, GroupStatus.OPEN);
        groups.setZaloGroupId(north, "1001");
        south = groups.create("Nhóm miền Nam", "https://zalo.me/g/nam", 50, "R:NAM");
        groups.setStatus(south, GroupStatus.OPEN);
        groups.setZaloGroupId(south, "2002");
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 20);
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        fake.reset();
        zalo.resetFriendSync();
    }

    // ---------- tiện ích ----------

    private long inv(int n) {
        return jdbc.sql("SELECT i.id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p")
                .param("p", "+8490000000" + n).query(Long.class).single();
    }

    private void sent(int n) {
        jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), sent_channel = 'MANUAL_EXPORT' WHERE id = :id")
                .param("id", inv(n)).update();
    }

    private String status(int n) {
        return jdbc.sql("SELECT status FROM invitation WHERE id = :id").param("id", inv(n)).query(String.class).single();
    }

    private String addState(int n) {
        return jdbc.sql("SELECT zalo_add_state FROM invitation WHERE id = :id").param("id", inv(n)).query(String.class).optional().orElse(null);
    }

    private void friend(int n, long seq) {
        fake.pushAdd("70000000" + n, "8490000000" + n, seq);
    }

    private void mode(ZaloMode m) {
        cfg.setMode(m, m == ZaloMode.AUTO, "mẹ");
    }

    // ---------- một nhóm cho cả hai đường ----------

    private void hold(int n, long groupId) {
        jdbc.sql("UPDATE invitation SET reserved_group_id = :g, reserved_until = now() + interval '1 hour' WHERE id = :id")
                .param("g", groupId).param("id", inv(n)).update();
    }

    @Test
    void khachDaDuocGiuChoMotNhomThiTuThemVaoDungNhomDoKhongDoiNhom() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        hold(1, south); // nhóm gợi ý ở tin link là nhóm miền Nam, dù khách ở miền Bắc
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds).singleElement().satisfies(a -> assertThat(a[1]).isEqualTo("2002"));
        assertThat(jdbc.sql("SELECT group_id FROM invitation WHERE id = :id").param("id", inv(1)).query(Long.class).single())
                .isEqualTo(south);
    }

    @Test
    void nhomDuocGiuChoMaKhongCoMaZaloHoacKhongCoLinkThiKhongTuThemSangNhomKhac() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        long noId = groups.create("Nhóm chưa có mã", "https://zalo.me/g/chua", 50, "R:BAC");
        groups.setStatus(noId, GroupStatus.OPEN);
        hold(1, noId);
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NO_GROUP);
        assertThat(fake.adds).isEmpty();
    }

    // ---------- 24/11/2026: nhóm khoảng 5 khách, trần mềm 8 cho khách dư lẻ ----------

    private long smallGroupAt(int actual) {
        return smallGroupAt(actual, "P:ha-noi");
    }

    private long smallGroupAt(int actual, String scope) {
        groups.setStatus(north, GroupStatus.PAUSED);
        groups.setStatus(south, GroupStatus.PAUSED);
        long g = groups.create("Nhóm nhỏ", "https://zalo.me/g/nho", 5, scope);
        groups.setStatus(g, GroupStatus.OPEN);
        groups.setZaloGroupId(g, "4004");
        groups.recount(g, actual);
        return g;
    }

    @Test
    void khachDuLeVaoNhomDaDuMucTieuNhungConDuoiTranMem() {
        mode(ZaloMode.AUTO);
        long g = smallGroupAt(5); // đủ mục tiêu 5 khách, trần mềm 8
        sent(1);
        friend(1, 1);
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds).singleElement().satisfies(a -> assertThat(a[1]).isEqualTo("4004"));
        assertThat(groups.find(g).orElseThrow().actual()).isEqualTo(6);
    }

    @Test
    void khachDuLeMoiDongYChiVaoNhomCungTinhChoQuaSoNgayMoiGopLenVung() {
        mode(ZaloMode.AUTO);
        smallGroupAt(5, "R:BAC"); // nhóm cấp miền, không phải nhóm cùng tỉnh
        sent(1);
        friend(1, 1);
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NO_GROUP);
        assertThat(fake.adds).isEmpty();
        clock.set(LocalDateTime.of(2026, 11, 24, 10, 30)); // đã quá 3 ngày kể từ lúc kết bạn (21/11 10:00), vẫn trong khung giờ gửi
        jdbc.sql("UPDATE invitation SET friended_at = TIMESTAMPTZ '2026-11-21 10:00:00+07' WHERE id = :id").param("id", inv(1)).update();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds).singleElement().satisfies(a -> assertThat(a[1]).isEqualTo("4004"));
    }

    @Test
    void soNgayGopLenVungChinhDuocOTrangZalo() {
        mode(ZaloMode.AUTO);
        groupSettings.setDays(1, 2, 5, "mẹ");
        assertThat(groupSettings.mergeUpDays()).isEqualTo(1);
        assertThat(groupSettings.alertDays()).isEqualTo(5);
        assertThatThrownBy(() -> groupSettings.setDays(5, 3, 9, "mẹ")).isInstanceOf(ImportException.class);
        groups.setSoftCap(6, "mẹ");
        assertThat(groups.softCap()).isEqualTo(6);
        assertThatThrownBy(() -> groups.setSoftCap(4, "mẹ")).isInstanceOf(ImportException.class);
        smallGroupAt(6);
        sent(1);
        friend(1, 1);
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NO_GROUP); // đã chạm trần mềm 6
    }

    @Test
    void khachDuLeKhongVuotTranMem() {
        mode(ZaloMode.AUTO);
        smallGroupAt(8);
        sent(1);
        friend(1, 1);
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NO_GROUP);
        assertThat(fake.adds).isEmpty();
    }

    @Test
    void nhieuKhachChoCungMienDuNguongLapNhomThiKhongDoDonVaoNhomDay() {
        mode(ZaloMode.AUTO);
        smallGroupAt(5);
        for (int n = 1; n <= 3; n++) {
            sent(n);
            friend(n, n);
        }
        zalo.poll();
        // 3 khách cùng miền Bắc đủ ngưỡng lập nhóm mới: họ chờ nhóm mới, không nhồi quá mục tiêu
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NO_GROUP);
        assertThat(fake.adds).isEmpty();
    }

    @Test
    void khachChuaRoTinhChiVaoNhomChungKhongVaoNhomMotMien() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        jdbc.sql("UPDATE customer SET province = NULL WHERE id = (SELECT customer_id FROM invitation WHERE id = :id)").param("id", inv(1)).update();
        // chỉ có nhóm miền Bắc và miền Nam: khách chưa rõ tỉnh không được thêm vào nhóm miền nào
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NO_GROUP);
        assertThat(fake.adds).isEmpty();
        long general = groups.create("Nhóm chung", "https://zalo.me/g/chung", 50, null);
        groups.setStatus(general, GroupStatus.OPEN);
        groups.setZaloGroupId(general, "3003");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds).singleElement().satisfies(a -> assertThat(a[1]).isEqualTo("3003"));
    }

    @Test
    void khachChoNhomQuaLauHoacChuaRoKhuVucDuocBaoChoMe() {
        mode(ZaloMode.AUTO);
        sent(1);
        sent(2);
        friend(1, 1);
        friend(2, 2);
        zalo.poll();
        assertThat(zalo.longWaitingCount(14)).isZero();
        jdbc.sql("UPDATE invitation SET friended_at = TIMESTAMPTZ '2026-11-06 10:00:00+07' WHERE id = :id").param("id", inv(1)).update();
        assertThat(zalo.longWaitingCount(14)).isEqualTo(1);
        jdbc.sql("UPDATE customer SET province = NULL WHERE id = (SELECT customer_id FROM invitation WHERE id = :id)").param("id", inv(2)).update();
        assertThat(zalo.longWaitingCount(14)).isEqualTo(1); // khách chưa rõ tỉnh mới kết bạn chưa tới 2 ngày
        jdbc.sql("UPDATE invitation SET friended_at = TIMESTAMPTZ '2026-11-19 10:00:00+07' WHERE id = :id").param("id", inv(2)).update();
        assertThat(zalo.longWaitingCount(14)).isEqualTo(2);
        assertThat(zalo.attentionCount()).isGreaterThanOrEqualTo(2);
    }

    // ---------- 24/11/2026: đối chiếu danh sách bạn (chỉ đọc) để bắt khách lỡ sự kiện ----------

    private void target(int n) {
        jdbc.sql("UPDATE invitation SET zalo_target_uid = :u WHERE id = :id").param("u", "70000000" + n).param("id", inv(n)).update();
    }

    @Test
    void doiChieuBanBeBatKhachLoSuKienVaXepVaoHangDoi() {
        mode(ZaloMode.AUTO);
        sent(1);
        sent(2);
        target(1);
        target(2);
        fake.friends.add("700000001");
        fake.friends.add("999999"); // người ngoài hệ thống cũng là bạn của tài khoản phụ
        zalo.poll(); // không có sự kiện nào: bộ nối đã khởi động lại và mất sự kiện
        assertThat(zalo.syncFriends()).isEqualTo(1);
        assertThat(status(1)).isEqualTo("FRIENDED");
        assertThat(addState(1)).isEqualTo("QUEUED");
        assertThat(status(2)).isEqualTo("SENT"); // chưa là bạn: giữ nguyên
        // chỉ hỏi về khách đã nhận Tin 1, không bao giờ hỏi hay lưu người ngoài hệ thống
        assertThat(fake.lastAsked).containsExactlyInAnyOrder("700000001", "700000002");
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE zalo_uid = '999999'").query(Long.class).single()).isZero();
        // và khách này được thêm vào nhóm như khách có sự kiện
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
    }

    @Test
    void doiChieuBanBeCoKhoangNghiNhungNoiLaiPhienThiDoiChieuNgay() {
        mode(ZaloMode.AUTO);
        sent(1);
        target(1);
        zalo.poll();
        zalo.syncFriends();
        assertThat(fake.friendChecks).isEqualTo(1);
        clock.advance(Duration.ofMinutes(5));
        zalo.poll();
        zalo.syncFriends();
        assertThat(fake.friendChecks).isEqualTo(1); // chưa tới 20 phút
        clock.advance(Duration.ofMinutes(16));
        zalo.poll();
        zalo.syncFriends();
        assertThat(fake.friendChecks).isEqualTo(2);
        // cầu nối nối lại phiên (mốc phiên đổi): đối chiếu ngay, không đợi
        fake.status = new ZaloGateway.Status(ZaloGateway.State.LISTENING, null, true, null, Instant.parse("2026-11-22T04:00:00Z"), null);
        zalo.poll();
        zalo.syncFriends();
        assertThat(fake.friendChecks).isEqualTo(3);
    }

    @Test
    void doiChieuBanBeKhongLamGiKhiKhongCoAiCanHoiHoacKhachDaTuChoi() {
        mode(ZaloMode.AUTO);
        sent(1);
        zalo.poll();
        assertThat(zalo.syncFriends()).isZero();
        assertThat(fake.friendChecks).isZero(); // không có mã nào để hỏi thì không gọi cầu nối
        target(1);
        fake.friends.add("700000001");
        contacts.suppress(jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", inv(1)).query(Long.class).single(), "test");
        clock.advance(Duration.ofMinutes(30));
        zalo.poll();
        assertThat(zalo.syncFriends()).isZero();
        assertThat(status(1)).isEqualTo("CANCELLED"); // khách đã từ chối: lời mời đã hủy, không bị đưa vào hàng đợi
        assertThat(addState(1)).isNull();
    }

    @Test
    void doiChieuBanBeChuaHoiDuocViLyDoTamThoiThiThuLaiONhipSau() {
        mode(ZaloMode.AUTO);
        sent(1);
        target(1);
        fake.friends.add("700000001");
        fake.friendCheckError = "WINDOW";
        zalo.poll();
        assertThat(zalo.syncFriends()).isZero();
        fake.friendCheckError = null;
        zalo.poll();
        assertThat(zalo.syncFriends()).isEqualTo(1); // không phải đợi 20 phút
        assertThat(cfg.tripped()).isFalse();
    }

    @Test
    void suKienDenSauKhiDaDoiChieuKhongGhiHaiLan() {
        mode(ZaloMode.AUTO);
        sent(1);
        target(1);
        fake.friends.add("700000001");
        zalo.poll();
        assertThat(zalo.syncFriends()).isEqualTo(1);
        friend(1, 1); // sự kiện đến muộn
        zalo.poll();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE zalo_uid = '700000001'").query(Long.class).single()).isEqualTo(1);
        assertThat(addState(1)).isEqualTo("QUEUED");
    }

    // ---------- mặc định tắt ----------

    @Test
    void macDinhTatDuCoCoMoiTruongKhongNgheKhongGoiGi() {
        assertThat(cfg.chosenMode()).isEqualTo(ZaloMode.OFF);
        assertThat(cfg.effectiveMode()).isEqualTo(ZaloMode.OFF);
        friend(1, 1);
        assertThat(zalo.poll()).isZero();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOT_ACTIVE);
        assertThat(fake.totalCalls()).isZero();
    }

    @Test
    void nacTuDongPhaiTickXacNhanDaChayThu() {
        assertThatThrownBy(() -> cfg.setMode(ZaloMode.AUTO, false, "mẹ")).isInstanceOf(ImportException.class)
                .hasMessageContaining("chạy thử");
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        assertThat(cfg.chosenMode()).isEqualTo(ZaloMode.CONFIRM);
        cfg.setMode(ZaloMode.AUTO, true, "mẹ");
        assertThat(cfg.chosenMode()).isEqualTo(ZaloMode.AUTO);
        assertThat(cfg.recentLog(10)).anyMatch(l -> "CAU_HINH".equals(l.action()) && "mẹ".equals(l.actor()));
    }

    // ---------- ghép khách ----------

    @Test
    void ketBanKhopSoDaNhanThiDanhDauKetBanVaChoDuyetKhongThemNgay() {
        mode(ZaloMode.CONFIRM);
        sent(1);
        friend(1, 1);
        assertThat(zalo.poll()).isEqualTo(1);
        assertThat(status(1)).isEqualTo("FRIENDED");
        assertThat(addState(1)).isEqualTo("WAITING_APPROVAL");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOTHING);
        assertThat(fake.adds).isEmpty();
        assertThat(cfg.cursor()).isEqualTo(1);
        assertThat(zalo.waiting()).singleElement().satisfies(w -> {
            assertThat(w.mayApprove()).isTrue();
            assertThat(w.groupName()).isEqualTo("Nhóm miền Bắc");
        });
    }

    @Test
    void suKienLapKhongXuLyHaiLan() {
        mode(ZaloMode.CONFIRM);
        sent(1);
        friend(1, 1);
        zalo.poll();
        // cầu nối gửi lại cùng số thứ tự (nối lại sau khi đứt): con trỏ đã qua nên bị bỏ
        fake.push(new ZaloGateway.FriendEvent(1, "70000001", "84900000001", Instant.now()));
        assertThat(zalo.poll()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_friend_event").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void nguoiLaKhongCoLoiMoiThiChiGhiNhatKyKhongLuuUid() {
        mode(ZaloMode.CONFIRM);
        fake.pushAdd("70009999", "84912345670", 1); // số không thuộc khách nào
        zalo.poll();
        assertThat(jdbc.sql("SELECT outcome FROM zalo_friend_event").query(String.class).single()).isEqualTo("STRANGER");
        assertThat(jdbc.sql("SELECT uid FROM zalo_friend_event").query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE zalo_uid IS NOT NULL").query(Long.class).single()).isZero();
        assertThat(zalo.manualCount()).isZero();
    }

    @Test
    void khachCoSoNhungChuaDuocNhanHoacDaTuChoiThiKhongGhep() {
        mode(ZaloMode.AUTO);
        friend(1, 1); // lời mời còn READY, chưa nhắn
        sent(2);
        contacts.suppress(jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", inv(2)).query(Long.class).single(), "test");
        friend(2, 2);
        zalo.poll();
        assertThat(jdbc.sql("SELECT outcome FROM zalo_friend_event ORDER BY bridge_seq").query(String.class).list())
                .containsExactly("BLOCKED", "BLOCKED");
        assertThat(addState(1)).isNull();
        assertThat(addState(2)).isNull();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOTHING);
        assertThat(fake.adds).isEmpty();
    }

    @Test
    void khongLayDuocSoThiVaoHangDoiTayVaNguoiGhepTayQuaCungCong() {
        mode(ZaloMode.CONFIRM);
        sent(3);
        fake.pushAdd("70000042", null, 1);
        zalo.poll();
        assertThat(zalo.manualCount()).isEqualTo(1);
        assertThat(zalo.manualQueue()).singleElement().satisfies(m -> assertThat(m.uidTail()).isEqualTo("…0042"));
        assertThat(zalo.candidates()).extracting(ZaloAutoService.Candidate::invitationId).contains(inv(3));
        assertThat(status(3)).isEqualTo("SENT"); // chưa ghép thì chưa đổi gì

        var eventId = zalo.manualQueue().getFirst().id();
        // khách chưa nhắn (READY) hoặc đã từ chối không ghép tay được
        assertThatThrownBy(() -> zalo.resolve(eventId, inv(4), "mẹ")).isInstanceOf(ImportException.class);
        zalo.resolve(eventId, inv(3), "mẹ");
        assertThat(status(3)).isEqualTo("FRIENDED");
        assertThat(addState(3)).isEqualTo("WAITING_APPROVAL");
        assertThat(zalo.manualCount()).isZero();
        assertThat(jdbc.sql("SELECT uid FROM zalo_friend_event WHERE id = :id").param("id", eventId).query(String.class).optional()).isEmpty();
        assertThatThrownBy(() -> zalo.resolve(eventId, inv(3), "mẹ")).hasMessageContaining("đã được xử lý");
    }

    @Test
    void boQuaDongHangDoiTay() {
        mode(ZaloMode.CONFIRM);
        fake.pushAdd("70000042", "", 1);
        zalo.poll();
        zalo.dismiss(zalo.manualQueue().getFirst().id(), "mẹ");
        assertThat(zalo.manualCount()).isZero();
    }

    // ---------- duyệt và thêm vào nhóm ----------

    @Test
    void nacMeDuyetBamDuyetMoiThemVaVaoDungNhomKhuVuc() {
        mode(ZaloMode.CONFIRM);
        sent(1);
        friend(1, 1);
        zalo.poll();
        zalo.approve(inv(1), "mẹ");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds).singleElement().satisfies(a -> {
            assertThat(a[0]).isEqualTo("700000001");
            assertThat(a[1]).isEqualTo("1001"); // nhóm miền Bắc, không phải miền Nam
        });
        assertThat(status(1)).isEqualTo("JOINED");
        assertThat(addState(1)).isEqualTo("ADDED");
        assertThat(groups.find(north).orElseThrow().actual()).isEqualTo(1);
        assertThat(groups.find(south).orElseThrow().actual()).isZero();
        // duyệt rồi bấm lại không thêm lần hai
        assertThatThrownBy(() -> zalo.approve(inv(1), "mẹ")).isInstanceOf(ImportException.class);
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.LIMIT); // trong khoảng cách tối thiểu
        assertThat(fake.adds).hasSize(1);
    }

    @Test
    void nacChiNgheKhongChoDuyetKhongThemGi() {
        mode(ZaloMode.LISTEN_ONLY);
        sent(1);
        friend(1, 1);
        zalo.poll();
        assertThat(status(1)).isEqualTo("FRIENDED");
        assertThatThrownBy(() -> zalo.approve(inv(1), "mẹ")).hasMessageContaining("Nấc hiện tại");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOT_ACTIVE);
        assertThat(fake.adds).isEmpty();
        assertThat(zalo.waiting()).singleElement().satisfies(w -> assertThat(w.mayApprove()).isFalse());
    }

    @Test
    void nacTuDongThemKhongCanDuyetVaChiMotNguoiMoiNhip() {
        mode(ZaloMode.AUTO);
        sent(1);
        sent(2);
        friend(1, 1);
        friend(2, 2);
        zalo.poll();
        assertThat(addState(1)).isEqualTo("QUEUED");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.LIMIT);
        assertThat(fake.adds).hasSize(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(status(1)).isEqualTo("JOINED");
        assertThat(status(2)).isEqualTo("JOINED");
    }

    @Test
    void chuyenSangTuDongThiNguoiDangChoDuyetCungDuocThem() {
        mode(ZaloMode.CONFIRM);
        sent(1);
        friend(1, 1);
        zalo.poll();
        mode(ZaloMode.AUTO);
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
    }

    @Test
    void khachDaDuocNguoiThemTayThiBoNoiKhongThemNua() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        invites.markJoined(inv(1)); // mẹ nhanh tay hơn
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOTHING);
        assertThat(fake.adds).isEmpty();
        assertThat(groups.find(north).orElseThrow().actual()).isEqualTo(1);
    }

    @Test
    void chuaCoNhomHopKhuVucThiGiuHangDoiKhongDayQuaMienKhac() {
        mode(ZaloMode.AUTO);
        groups.setZaloGroupId(north, ""); // nhóm miền Bắc chưa có mã Zalo
        sent(1);
        friend(1, 1);
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NO_GROUP);
        assertThat(fake.adds).isEmpty(); // nhóm Nam có mã nhưng khác miền
        assertThat(addState(1)).isEqualTo("QUEUED");
        groups.setZaloGroupId(north, "1001");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds.getFirst()[1]).isEqualTo("1001");
    }

    @Test
    void maNhomPhaiLaChuSo() {
        assertThatThrownBy(() -> groups.setZaloGroupId(north, "https://zalo.me/g/abc")).isInstanceOf(ImportException.class);
    }

    // ---------- hạn mức ----------

    @Test
    void hanMucMacDinhLaChuShopChot() {
        var l = cfg.limits();
        assertThat(l.daily()).isEqualTo(150);
        assertThat(l.hourly()).isEqualTo(15);
        assertThat(l.gapMinutes()).isEqualTo(2);
    }

    @Test
    void chinhHanMucKhongVuotTranCungVaGhiNhatKy() {
        assertThatThrownBy(() -> cfg.setLimits(301, 5, 2, "mẹ")).hasMessageContaining("300");
        assertThatThrownBy(() -> cfg.setLimits(50, 31, 2, "mẹ")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> cfg.setLimits(50, 5, 0, "mẹ")).isInstanceOf(ImportException.class);
        cfg.setLimits(30, 4, 3, "mẹ");
        assertThat(cfg.limits()).isEqualTo(new ZaloAutoSettings.Limits(30, 4, 3));
        assertThat(cfg.recentLog(5)).anyMatch(l -> "CAU_HINH".equals(l.action()) && l.result().startsWith("HAN_MUC 30/4/3")
                && l.code().startsWith("CU 150/15/2"));
    }

    @Test
    void tranTheoGioVaKhoangCachVaTheoNgay() {
        mode(ZaloMode.AUTO);
        cfg.setLimits(3, 2, 2, "mẹ");
        for (int i = 1; i <= 4; i++) {
            sent(i);
            friend(i, i);
        }
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        clock.advance(Duration.ofMinutes(2));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        clock.advance(Duration.ofMinutes(2));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.LIMIT); // trần 2/giờ
        clock.advance(Duration.ofMinutes(60));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED); // giờ mới, người thứ 3 của ngày
        clock.advance(Duration.ofMinutes(61));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.LIMIT); // trần 3/ngày
        assertThat(fake.adds).hasSize(3);
        // sang ngày mới (trong khung giờ) thì được tiếp
        clock.set(LocalDateTime.of(2026, 11, 23, 7, 0));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds).hasSize(4);
    }

    @Test
    void ngoaiKhungGioKhongThem() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        clock.set(LocalDateTime.of(2026, 11, 22, 6, 59));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.LIMIT);
        clock.set(LocalDateTime.of(2026, 11, 22, 22, 0));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.LIMIT);
        clock.set(LocalDateTime.of(2026, 11, 22, 21, 59));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
    }

    // ---------- lỗi theo người và cầu dao ----------

    @Test
    void loiRiengMotNguoiKhongLamCauDaoNhayKhongTuThuLaiNhungTinhVaoHanMuc() {
        mode(ZaloMode.AUTO);
        sent(1);
        sent(2);
        friend(1, 1);
        friend(2, 2);
        zalo.poll();
        fake.willReturn(AddResult.of(Outcome.PERSON_ERROR, "ALREADY_MEMBER"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.PERSON_FAILED);
        assertThat(cfg.tripped()).isFalse();
        assertThat(addState(1)).isEqualTo("FAILED");
        assertThat(status(1)).isEqualTo("FRIENDED");
        assertThat(cfg.quota().usedToday()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED); // người kế tiếp, không thử lại người lỗi
        assertThat(fake.adds).extracting(a -> a[0]).containsExactly("700000001", "700000002");
        // người bấm thử lại một lần thì mới quay về hàng chờ
        zalo.retry(inv(1), "mẹ");
        assertThat(addState(1)).isEqualTo("WAITING_APPROVAL");
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"SYSTEM_ERROR", "RESTRICTED", "SESSION_LOST"})
    void loiLaHoacZaloCanhBaoLamCauDaoNhayVaMoiThaoTacDungLai(Outcome outcome) {
        mode(ZaloMode.AUTO);
        sent(1);
        sent(2);
        friend(1, 1);
        friend(2, 2);
        zalo.poll();
        fake.willReturn(AddResult.of(outcome, "X1"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
        assertThat(cfg.tripped()).isTrue();
        assertThat(cfg.trippedReason()).startsWith(outcome.name());
        assertThat(fake.stops).isEqualTo(1);
        assertThat(addState(1)).isEqualTo("FAILED"); // người kiểm tra tay, không tự thử lại
        // sau khi nhảy: không nghe, không thêm, không gọi gì nữa
        int calls = fake.totalCalls();
        clock.advance(Duration.ofHours(1));
        assertThat(zalo.poll()).isZero();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOT_ACTIVE);
        assertThat(fake.totalCalls()).isEqualTo(calls);
        assertThat(cfg.recentLog(20)).anyMatch(l -> "DUNG".equals(l.action()));
    }

    @Test
    void hetGioChoLucThemLaKhongBietKetQuaThiDung() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        fake.willReturn(AddResult.of(Outcome.SYSTEM_ERROR, "TIMEOUT"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
        assertThat(status(1)).isEqualTo("FRIENDED"); // không ghi "đã vào nhóm" khi không chắc
    }

    @Test
    void khongGoiDuocToiCauNoiThiKhongTinhHanMucVaKhongDung() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        fake.willReturn(AddResult.of(Outcome.UNREACHABLE, "BRIDGE_DOWN"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.UNREACHABLE);
        assertThat(cfg.tripped()).isFalse();
        assertThat(cfg.quota().usedToday()).isZero();
        assertThat(addState(1)).isEqualTo("QUEUED");
    }

    @Test
    void cauNoiBaoTuDungHoacSaiTaiKhoanThiAdhCungDung() {
        mode(ZaloMode.CONFIRM);
        fake.status = new ZaloGateway.Status(State.TRIPPED, "DUPLICATE_CONNECTION", true, null, null, null);
        zalo.poll();
        assertThat(cfg.tripped()).isTrue();
        assertThat(cfg.trippedReason()).isEqualTo("BRIDGE_DUPLICATE_CONNECTION");

        cfg.reopen("mẹ");
        fake.status = new ZaloGateway.Status(State.LISTENING, null, false, null, null, null);
        zalo.poll();
        assertThat(cfg.trippedReason()).isEqualTo("ACCOUNT_MISMATCH");
        assertThat(fake.stops).isEqualTo(1);
    }

    @Test
    void chuaDangNhapHoacMatKetNoiTamThoiThiChoKhongDungKhongNghe() {
        mode(ZaloMode.CONFIRM);
        for (State s : new State[]{State.LOGIN_REQUIRED, State.UNREACHABLE, State.CONNECTING, State.STOPPED}) {
            fake.status = ZaloGateway.Status.of(s);
            fake.eventCalls = 0;
            assertThat(zalo.poll()).isZero();
            assertThat(fake.eventCalls).isZero();
            assertThat(cfg.tripped()).isFalse();
        }
    }

    @Test
    void moLaiSauKhiDungChiKhiNguoiBamVaGoiTiepPhienDaLuu() {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        zalo.stopNow("mẹ");
        assertThat(cfg.tripped()).isTrue();
        assertThat(zalo.reopen("mẹ", true)).isTrue();
        assertThat(cfg.tripped()).isFalse();
        assertThat(fake.resumes).isEqualTo(1);
        assertThat(cfg.effectiveMode()).isEqualTo(ZaloMode.CONFIRM);
    }

    @Test
    void dangXuatThiDungVaQuetQrXongDungTaiKhoanThiMoLai() {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        zalo.logout("mẹ");
        assertThat(cfg.tripped()).isTrue();
        fake.login = ZaloSession.Login.of(ZaloSession.LoginState.WAITING_SCAN);
        zalo.loginStatus("mẹ");
        assertThat(cfg.tripped()).isTrue();
        fake.login = ZaloSession.Login.of(ZaloSession.LoginState.ACCOUNT_MISMATCH);
        zalo.loginStatus("mẹ");
        assertThat(cfg.tripped()).isTrue();
        fake.login = ZaloSession.Login.of(ZaloSession.LoginState.DONE);
        zalo.loginStatus("mẹ");
        assertThat(cfg.tripped()).isFalse();
    }

    // ---------- nhật ký ----------

    @Test
    void nhatKyKhongChuaSoDienThoaiTenHayUid() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        fake.pushAdd("70000077", null, 2);
        zalo.poll();
        zalo.addNext();
        var all = jdbc.sql("SELECT concat_ws('|', action, result, code, actor) FROM zalo_auto_log").query(String.class).list();
        assertThat(all).isNotEmpty();
        String joined = String.join("\n", all);
        assertThat(joined).doesNotContain("900000001").doesNotContain("700000001").doesNotContain("70000077")
                .doesNotContain("Khach");
        // bảng log không có cột nào khác ngoài mã lời mời và mã nhóm
        assertThat(jdbc.sql("""
                SELECT string_agg(column_name, ',' ORDER BY column_name) FROM information_schema.columns
                WHERE table_name = 'zalo_auto_log'""").query(String.class).single())
                .isEqualTo("action,actor,at,code,group_id,id,invitation_id,result");
    }

    // ---------- sửa theo rà soát độc lập ----------

    @Test
    void mauUidTrungVoiKhachKhacKhongLamKetBoNgheVaBoThem() {
        mode(ZaloMode.CONFIRM);
        sent(1);
        sent(2);
        fake.pushAdd("700000001", "8490000000" + 1, 1);
        fake.pushAdd("700000001", "8490000000" + 2, 2); // cùng uid, số của khách khác
        assertThat(zalo.poll()).isEqualTo(2);
        assertThat(cfg.cursor()).isEqualTo(2);
        assertThat(addState(1)).isEqualTo("WAITING_APPROVAL");
        assertThat(addState(2)).isNull();
        assertThat(status(2)).isEqualTo("SENT");
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_friend_event WHERE outcome = 'BLOCKED'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void lanGoiTruocChetGiuaChungThiDungKhongThemLai() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        jdbc.sql("INSERT INTO zalo_auto_log (at, action, invitation_id, result, actor) VALUES (now(), 'THEM_NHOM', :i, 'DANG_GOI', 'system')")
                .param("i", inv(1)).update();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
        assertThat(cfg.trippedReason()).isEqualTo("INTERRUPTED");
        assertThat(fake.adds).isEmpty();
    }

    @Test
    void dangGoiDuocDanhDauTruocKhiGoiVaChayXongThiXoaDau() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        fake.willReturn(AddResult.ok());
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(addState(1)).isEqualTo("ADDED");
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_auto_log WHERE result = 'DANG_GOI'").query(Long.class).single()).isZero();
    }

    @Test
    void chuaChacDungTaiKhoanPhuThiKhongThemAi() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        fake.status = new ZaloGateway.Status(State.LISTENING, null, null, null, null, null);
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.UNREACHABLE);
        assertThat(fake.adds).isEmpty();
        fake.status = new ZaloGateway.Status(State.LISTENING, null, false, null, null, null);
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
        assertThat(cfg.trippedReason()).isEqualTo("ACCOUNT_MISMATCH");
        assertThat(fake.adds).isEmpty();
    }

    @Test
    void khachKhongConDuDieuKienBiBoQuaKhongDotHanMuc() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        contacts.suppress(jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", inv(1)).query(Long.class).single(), "test");
        zalo.addNext();
        assertThat(cfg.quota().usedToday()).isZero();
        assertThat(cfg.quota().usedLastHour()).isZero();
    }

    private void addLog(String at, long groupId, String result) {
        jdbc.sql("INSERT INTO zalo_auto_log (at, action, invitation_id, group_id, result, actor) VALUES (CAST(:at AS timestamptz), 'THEM_NHOM', NULL, :g, :r, 'system')")
                .param("at", at).param("g", groupId).param("r", result).update();
    }

    @Test
    void bonLanLienLoiRiengNguoiCungMotNhomThiTamDungNhomKhongDungCauDao() {
        mode(ZaloMode.AUTO);
        for (int i = 1; i <= 5; i++) {
            sent(i);
            friend(i, i);
        }
        zalo.poll();
        fake.defaultResult = AddResult.of(Outcome.PERSON_ERROR, "Z1");
        for (int i = 1; i <= 3; i++) {
            assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.PERSON_FAILED);
            clock.advance(Duration.ofMinutes(2));
        }
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.GROUP_FAILED);
        assertThat(cfg.tripped()).isFalse(); // vài khách xui không làm cả hệ thống dừng
        assertThat(groups.find(north).orElseThrow().status()).isEqualTo(GroupStatus.PAUSED);
        assertThat(fake.adds).hasSize(4);
        clock.advance(Duration.ofMinutes(2));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NO_GROUP); // nhóm đã tạm dừng thì không thêm nữa
        assertThat(fake.adds).hasSize(4);
    }

    @Test
    void loiRiengNguoiXenKeVoiThanhCongKhongBaoGioDungGi() {
        mode(ZaloMode.AUTO);
        for (int i = 1; i <= 6; i++) {
            sent(i);
            friend(i, i);
        }
        zalo.poll();
        for (int i = 1; i <= 6; i++) {
            fake.willReturn(i % 2 == 0 ? AddResult.ok() : AddResult.of(Outcome.PERSON_ERROR, "Z1"));
            zalo.addNext();
            clock.advance(Duration.ofMinutes(2));
        }
        assertThat(cfg.tripped()).isFalse();
        assertThat(groups.find(north).orElseThrow().status()).isEqualTo(GroupStatus.OPEN);
    }

    @Test
    void saoTrongTamLanGanNhatLoiRiengNguoiLanNhieuNhomThiDungCauDao() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        // lịch sử trong ngày: 5 lần lỗi riêng người xen hai nhóm, 2 lần thành công (không có chuỗi 4 liền cùng một nhóm)
        String[] seq = {"PERSON_ERROR", "PERSON_ERROR", "OK", "PERSON_ERROR", "PERSON_ERROR", "OK", "PERSON_ERROR"};
        for (int i = 0; i < seq.length; i++) {
            addLog("2026-11-22 0" + (1 + i) + ":00:00+07", i % 2 == 0 ? north : south, seq[i]);
        }
        fake.willReturn(AddResult.of(Outcome.PERSON_ERROR, "Z1"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED); // lần thứ 6 trong 8 lần gần nhất
        assertThat(cfg.trippedReason()).isEqualTo("NHIEU_LOI_NGUOI");
    }

    @Test
    void loiCaNhomThiTamDungNhomDoTraKhachVeHangDoiVaKhongDungCauDao() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        fake.willReturn(AddResult.of(Outcome.GROUP_ERROR, "Z178"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.GROUP_FAILED);
        assertThat(cfg.tripped()).isFalse();
        assertThat(groups.find(north).orElseThrow().status()).isEqualTo(GroupStatus.PAUSED);
        assertThat(addState(1)).isEqualTo("QUEUED"); // không phải lỗi của khách: chờ nhóm khác, không đánh dấu FAILED
        // có nhóm khác cùng miền thì khách được thêm ở nhịp sau
        long other = groups.create("Nhóm Bắc 2", "https://zalo.me/g/bac2", 50, "R:BAC");
        groups.setStatus(other, GroupStatus.OPEN);
        groups.setZaloGroupId(other, "1003");
        fake.willReturn(AddResult.ok());
        clock.advance(Duration.ofMinutes(2));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        assertThat(fake.adds.get(fake.adds.size() - 1)[1]).isEqualTo("1003");
    }

    @Test
    void baLanLienLoiCaNhomCuaBaNhomThiDungCauDao() {
        mode(ZaloMode.AUTO);
        for (int i = 1; i <= 4; i++) {
            sent(i);
            friend(i, i);
        }
        for (int k = 2; k <= 3; k++) {
            long g = groups.create("Nhóm Bắc " + k, "https://zalo.me/g/bac" + k, 50, "R:BAC");
            groups.setStatus(g, GroupStatus.OPEN);
            groups.setZaloGroupId(g, "100" + (k + 1));
        }
        zalo.poll();
        fake.defaultResult = AddResult.of(Outcome.GROUP_ERROR, "Z178");
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.GROUP_FAILED);
        clock.advance(Duration.ofMinutes(2));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.GROUP_FAILED);
        clock.advance(Duration.ofMinutes(2));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
        assertThat(cfg.trippedReason()).isEqualTo("NHIEU_LOI_NHOM");
    }

    @Test
    void khongNoiDuocRoiThiKhachVeLaiTrangThaiCu() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        fake.willReturn(AddResult.of(Outcome.UNREACHABLE, "BRIDGE_DOWN"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.UNREACHABLE);
        assertThat(addState(1)).isEqualTo("QUEUED");
        assertThat(jdbc.sql("SELECT count(*) FROM zalo_auto_log WHERE result = 'DANG_GOI'").query(Long.class).single()).isZero();
    }

    @Test
    void moLaiLyDoNghiemTrongPhaiTickVaChiBoCauDaoKhiCauNoiNgheLai() {
        cfg.setMode(ZaloMode.CONFIRM, false, "mẹ");
        cfg.trip("RESTRICTED:Z9", "system");
        assertThatThrownBy(() -> zalo.reopen("mẹ", false)).isInstanceOf(ImportException.class).hasMessageContaining("tick");
        assertThat(cfg.tripped()).isTrue();
        fake.resumeResult = false;
        assertThat(zalo.reopen("mẹ", true)).isFalse();
        assertThat(cfg.tripped()).isTrue(); // cầu nối chưa nghe lại được thì vẫn dừng
        fake.resumeResult = true;
        assertThat(zalo.reopen("mẹ", true)).isTrue();
        assertThat(cfg.tripped()).isFalse();
        // lý do nhẹ (người tự dừng) không cần tick
        zalo.stopNow("mẹ");
        assertThat(zalo.reopen("mẹ", false)).isTrue();
    }

    @Test
    void boNoiLoiKhiNgheSuKienVanChayTiepViecThem() {
        mode(ZaloMode.AUTO);
        sent(1);
        friend(1, 1);
        zalo.poll();
        fake.eventsFail = true;
        zalo.tick();
        assertThat(fake.adds).hasSize(1);
    }

    @Test
    void khachDaDuocGuiLinkNhomTayThiKetBanSauNayKhongXepVaoHangThem() {
        mode(ZaloMode.AUTO);
        sent(1);
        invites.markFriended(inv(1)); // mẹ đã đánh dấu kết bạn tay trước khi sự kiện của bộ nối tới
        invites.markGroupLinkSent(inv(1), north);

        friend(1, 1);
        zalo.poll();

        assertThat(status(1)).isEqualTo("FRIENDED");
        assertThat(addState(1)).isNull();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOTHING);
        assertThat(fake.adds).isEmpty(); // không có lệnh thêm nhóm nào
    }
}
