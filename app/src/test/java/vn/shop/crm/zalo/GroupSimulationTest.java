package vn.shop.crm.zalo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vn.shop.crm.AbstractPostgresTest;
import vn.shop.crm.contact.ContactService;
import vn.shop.crm.geo.VnGeo;
import vn.shop.crm.imports.ImportService;
import vn.shop.crm.invite.GroupService;
import vn.shop.crm.invite.InviteService;
import vn.shop.crm.shipping.Carrier;
import vn.shop.crm.zalo.ZaloGateway.AddResult;
import vn.shop.crm.zalo.ZaloGateway.Outcome;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mô phỏng nhiều ngày với cơ sở dữ liệu thật và bộ nối GIẢ (mã CT-xx trong {@code docs/CAY-TEST-KHO-NGO-VA-DANH-GIA-HOP-LY.md}):
 * khách đồng ý kết bạn về liên tục theo ngày, nhiều tỉnh, nhóm cũ đầy hoặc vắng, hàng chờ dài vượt trần ngày, lỗi người và lỗi nhóm xen vào.
 * Mỗi nhịp mười phút trong khung 08:00 đến 20:00 gọi đúng các hàm mà bộ hẹn giờ gọi: đối chiếu bạn, thêm một người, xét tạo nhóm.
 * Kiểm bất biến (không kiểm từng bước): không vượt trần ngày và trần mềm, không xếp sai miền, không ai bị thêm hai lần, không ai bị bỏ quên không báo.
 */
@Import(ZaloTestConfig.class)
@TestPropertySource(properties = {
        "app.zalo.auto.enabled=true",
        "app.zalo.auto.bridge-key=test",
        "app.zalo.auto.initial-delay-millis=3600000",
        "app.zalo.auto.tick-millis=3600000",
        "app.zalo.group.deputy-id=99887766"})
class GroupSimulationTest extends AbstractPostgresTest {

    private static final String HEADER =
            "Mã vận đơn,Mã đơn hàng,Ngày tạo,Tên người nhận,SĐT người nhận,Địa chỉ người nhận,Trạng thái\n";

    @Autowired ImportService imports;
    @Autowired ContactService contacts;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired ZaloAutoService zalo;
    @Autowired ZaloAutoSettings auto;
    @Autowired ZaloGroupCreationService svc;
    @Autowired ZaloGroupSettings groupCfg;
    @Autowired FakeZaloGateway fake;
    @Autowired ZaloTestClock clock;

    /** Khách theo thứ tự nhập: mã tỉnh của khách số i (bắt đầu từ 1). */
    private final List<String> provinceOf = new ArrayList<>();
    private final List<Long> invitationOf = new ArrayList<>();

    private void importCustomers(Map<String, Integer> byProvinceName) {
        StringBuilder csv = new StringBuilder(HEADER);
        int n = 0;
        for (var e : byProvinceName.entrySet()) {
            for (int k = 0; k < e.getValue(); k++) {
                n++;
                csv.append("T").append(n).append(",DH").append(n).append(",05/01/2026 09:00:00,Khach ").append(n)
                        .append(String.format(",0911%06d", n)).append(",1 Duong A Xa B ").append(e.getKey()).append(",Giao thành công\n");
            }
        }
        long id = imports.createBatch(Carrier.VIETTEL_POST, "t.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        var view = imports.open(id, null);
        imports.commit(id, new ImportService.Mapping(view.headerRow(), view.suggested()));
        contacts.approveAllCandidates();
        invites.createCampaign("Đợt 1", "Chào {ten}, {cua_hang} tư vấn.", 14, 200);
        provinceOf.clear();
        invitationOf.clear();
        for (int i = 1; i <= n; i++) {
            invitationOf.add(jdbc.sql("SELECT i.id FROM invitation i JOIN customer c ON c.id = i.customer_id WHERE c.phone = :p")
                    .param("p", String.format("+84911%06d", i)).query(Long.class).single());
            provinceOf.add(jdbc.sql("SELECT province FROM customer WHERE phone = :p").param("p", String.format("+84911%06d", i))
                    .query(String.class).optional().orElse(null));
        }
    }

    @BeforeEach
    void setUp() {
        nextDay = 0;
        fake.reset();
        fake.uniqueGroupIds = true;
        zalo.resetFriendSync();
        auto.setMode(ZaloMode.AUTO, true, "mẹ");
        groupCfg.setMode(ZaloGroupMode.AUTO, true, "mẹ");
        clock.set(LocalDateTime.of(2026, 11, 22, 8, 0));
    }

    /** Khách i (từ 1) vừa đồng ý kết bạn lúc này: đã có mã Zalo, vào hàng đợi thêm nhóm. */
    private void agree(int i) {
        jdbc.sql("""
                        UPDATE invitation SET status = 'FRIENDED', sent_at = CAST(:t AS timestamptz) - interval '1 day',
                               friended_at = CAST(:t AS timestamptz), zalo_uid = :u, zalo_target_uid = :u, zalo_add_state = 'QUEUED'
                        WHERE id = :id""")
                .param("t", clock.instant().toString()).param("u", String.valueOf(5_000_000L + i)).param("id", invitationOf.get(i - 1)).update();
    }

    private int nextDay;
    private record Stat(int adds, int maxAddsPerDay, int groupsCreated) {
    }

    /** Chạy từ ngày hiện tại tới hết {@code days} ngày; mỗi ngày chạy các nhịp 10 phút từ 08:00 tới 20:00. {@code arrivals}: ngày -> các khách về ngày đó. */
    private Stat run(int days, Map<Integer, List<Integer>> arrivals) {
        LocalDate start = LocalDate.of(2026, 11, 22).plusDays(nextDay);
        nextDay += days;
        Map<LocalDate, Integer> addsPerDay = new HashMap<>();
        int adds = 0;
        for (int d = 0; d < days; d++) {
            LocalDate day = start.plusDays(d);
            for (int step = 0; step <= 72; step++) {
                clock.set(day.atTime(8, 0).plusMinutes(step * 10L));
                if (step == 0) {
                    for (int i : arrivals.getOrDefault(d, List.of())) {
                        agree(i);
                    }
                }
                zalo.poll();
                ZaloAutoService.Step s = zalo.addNext();
                if (s == ZaloAutoService.Step.ADDED) {
                    adds++;
                    addsPerDay.merge(day, 1, Integer::sum);
                }
                svc.step();
            }
        }
        return new Stat(adds, addsPerDay.values().stream().mapToInt(Integer::intValue).max().orElse(0), fake.creates.size());
    }

    private List<int[]> membersPerGroup() {
        return jdbc.sql("SELECT group_id, count(*) FROM invitation WHERE group_id IS NOT NULL GROUP BY group_id ORDER BY group_id")
                .query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).list();
    }

    private long waiting() {
        return jdbc.sql("SELECT count(*) FROM invitation WHERE status = 'FRIENDED' AND group_id IS NULL").query(Long.class).single();
    }

    private void assertNoCrossRegionAndNoDoubleAdd() {
        // không ai bị xếp vào nhóm của miền khác
        List<Object[]> rows = jdbc.sql("""
                        SELECT c.province, g.geo_scope FROM invitation i JOIN customer c ON c.id = i.customer_id JOIN zalo_group g ON g.id = i.group_id
                        WHERE i.group_id IS NOT NULL AND g.geo_scope IS NOT NULL""").query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2)}).list();
        for (Object[] r : rows) {
            var prov = VnGeo.byCode((String) r[0]).orElseThrow();
            var area = vn.shop.crm.geo.GeoArea.parse((String) r[1]).orElseThrow();
            assertThat(area.closeness(prov)).as("khách tỉnh " + r[0] + " vào nhóm " + r[1]).isGreaterThanOrEqualTo(0);
        }
        // mỗi mã Zalo của khách chỉ được thêm vào nhóm đúng một lần
        Map<String, Long> perUid = fake.adds.stream().collect(Collectors.groupingBy(a -> a[0], Collectors.counting()));
        assertThat(perUid.values()).allMatch(c -> c == 1L);
        assertThat(fake.creates.stream().flatMap(c -> c.members().stream()).collect(Collectors.groupingBy(m -> m, Collectors.counting())).values())
                .allMatch(c -> c == 1L);
    }

    private Map<String, Integer> mix() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Cần Thơ", 12);
        m.put("Sóc Trăng", 2);
        m.put("Hà Nội", 9);
        m.put("Hải Phòng", 4);
        m.put("Đà Nẵng", 3);
        m.put("TP. Hồ Chí Minh", 8);
        m.put("Lạng Sơn", 1);
        m.put("Huế", 1);
        m.put("Bắc Ninh", 1);
        return m;
    }

    // ---------- CT-01: khách về liên tục nhiều ngày, nhiều tỉnh ----------

    @Test
    void ct01_khachVeLienTucNhieuTinhTrongMuoiNgay() {
        importCustomers(mix());
        Map<Integer, List<Integer>> arrivals = new HashMap<>();
        arrivals.put(0, java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
        arrivals.put(1, java.util.stream.IntStream.rangeClosed(21, 30).boxed().toList());
        arrivals.put(2, java.util.stream.IntStream.rangeClosed(31, 35).boxed().toList());
        arrivals.put(4, java.util.stream.IntStream.rangeClosed(36, 41).boxed().toList());
        Stat st = run(10, arrivals);
        assertThat(st.maxAddsPerDay()).isLessThanOrEqualTo(50);
        assertNoCrossRegionAndNoDoubleAdd();
        assertThat(membersPerGroup()).allSatisfy(g -> assertThat(g[1]).isLessThanOrEqualTo(groups.softCap()));
        // sau mười ngày không ai bị bỏ quên: tất cả 41 khách đều đã vào một nhóm cùng miền
        assertThat(waiting()).isZero();
        assertThat(membersPerGroup().stream().mapToInt(g -> g[1]).sum()).isEqualTo(41);
        // khách dư lẻ không làm nhóm nào vượt trần mềm 8 và không tạo nhóm 1 đến 2 người
        assertThat(fake.creates).allSatisfy(c -> assertThat(c.members().size()).isBetween(3, 5));
    }

    // ---------- CT-02: hàng chờ dài vượt trần ngày ----------

    @Test
    void ct02_tamMuoiKhachVeMotLucVuotTranNgay_vaoTheoThuTuKetBanSangNgaySau() {
        auto.setLimits(50, 5, 2, "test"); // mặc định đã nâng lên 150/ngày và 15/giờ (24/11/2026); ở đây hạ lại để kiểm cơ chế vượt trần chuyển sang ngày sau
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Hà Nội", 80);
        importCustomers(m);
        long g = groups.create("Nhóm Hà Nội lớn", "https://zalo.me/g/hn", 200, "P:ha-noi");
        groups.setStatus(g, vn.shop.crm.invite.GroupStatus.OPEN);
        groups.setZaloGroupId(g, "9001");
        Map<Integer, List<Integer>> arrivals = new HashMap<>();
        arrivals.put(0, java.util.stream.IntStream.rangeClosed(1, 80).boxed().toList());
        Stat st = run(1, arrivals);
        assertThat(st.adds()).isEqualTo(50); // trần 50 người mỗi ngày
        assertThat(waiting()).isEqualTo(30);
        // người vào theo đúng thứ tự kết bạn: ai cùng giờ thì theo mã lời mời, nên 50 người đầu là 50 mã nhỏ nhất
        List<Long> added = jdbc.sql("SELECT id FROM invitation WHERE group_id IS NOT NULL ORDER BY id").query(Long.class).list();
        assertThat(added).isEqualTo(invitationOf.stream().sorted().limit(50).toList());
        Stat day2 = run(1, Map.of()); // ngày kế tiếp: 30 người còn lại vào hết, vẫn theo thứ tự
        assertThat(day2.adds()).isEqualTo(30);
        assertThat(waiting()).isZero();
    }

    // ---------- CT-03: nhóm lỗi, hệ thống tự lành bằng nhóm mới ----------

    @Test
    void ct03_nhomDuyNhatBaoLoiCaNhom_tamDungVaTuTaoNhomMoiChoKhachChoDo() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Hà Nội", 6);
        importCustomers(m);
        long g = groups.create("Nhóm Hà Nội cũ", "https://zalo.me/g/hn", 5, "P:ha-noi");
        groups.setStatus(g, vn.shop.crm.invite.GroupStatus.OPEN);
        groups.setZaloGroupId(g, "9001");
        fake.defaultResult = AddResult.of(Outcome.GROUP_ERROR, "Z178");
        for (int i = 1; i <= 6; i++) {
            agree(i);
        }
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.GROUP_FAILED);
        assertThat(groups.find(g).orElseThrow().status()).isEqualTo(vn.shop.crm.invite.GroupStatus.PAUSED);
        assertThat(auto.tripped()).isFalse();
        // nhóm cũ đã tạm dừng nên 6 khách chờ đủ ngưỡng: bộ tạo nhóm lập nhóm mới
        clock.advance(Duration.ofMinutes(15));
        assertThat(svc.step()).isEqualTo(ZaloGroupCreationService.Step.CREATED);
        assertThat(fake.creates).singleElement().satisfies(c -> assertThat(c.members()).hasSize(5));
        assertThat(waiting()).isEqualTo(1); // người thứ sáu chờ nhóm mới nhận (còn dưới trần mềm)
    }

    // ---------- CT-04: lỗi riêng người rải rác ----------

    @Test
    void ct04_moiKhachThuBaLoiRiengNguoi_nhungHangDoiVanChayVaCauDaoKhongNhay() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Hà Nội", 12);
        importCustomers(m);
        long g = groups.create("Nhóm Hà Nội lớn", "https://zalo.me/g/hn", 50, "P:ha-noi");
        groups.setStatus(g, vn.shop.crm.invite.GroupStatus.OPEN);
        groups.setZaloGroupId(g, "9001");
        for (int i = 1; i <= 12; i++) {
            agree(i);
        }
        zalo.poll();
        int ok = 0;
        int failed = 0;
        for (int k = 1; k <= 12; k++) {
            fake.willReturn(k % 3 == 0 ? AddResult.of(Outcome.PERSON_ERROR, "Z1") : AddResult.ok());
            clock.advance(Duration.ofMinutes(13)); // hạn mức 5 lần mỗi giờ tính cả lần lỗi: giãn ra cho khỏi chạm trần giờ
            ZaloAutoService.Step s = zalo.addNext();
            if (s == ZaloAutoService.Step.ADDED) {
                ok++;
            } else if (s == ZaloAutoService.Step.PERSON_FAILED) {
                failed++;
            }
        }
        assertThat(ok).isEqualTo(8);
        assertThat(failed).isEqualTo(4);
        assertThat(auto.tripped()).isFalse();
        assertThat(groups.find(g).orElseThrow().status()).isEqualTo(vn.shop.crm.invite.GroupStatus.OPEN);
    }

    // ---------- CT-05: mất sự kiện khi cầu nối khởi động lại ----------

    @Test
    void ct05_muoiKhachDongYLucCauNoiChet_doiChieuBanBeBatKhongBoSot() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Cần Thơ", 10);
        importCustomers(m);
        for (int i = 1; i <= 10; i++) {
            jdbc.sql("UPDATE invitation SET status = 'SENT', sent_at = now(), zalo_target_uid = :u WHERE id = :id")
                    .param("u", String.valueOf(5_000_000L + i)).param("id", invitationOf.get(i - 1)).update();
        }
        zalo.poll(); // cầu nối chết rồi sống lại: không có sự kiện nào
        for (int i = 1; i <= 10; i++) {
            fake.friends.add(String.valueOf(5_000_000L + i));
        }
        fake.friends.add("123456789"); // người ngoài hệ thống
        assertThat(zalo.syncFriends()).isEqualTo(10);
        assertThat(fake.lastAsked).hasSize(10).doesNotContain("123456789");
        Stat st = run(2, Map.of());
        assertThat(st.groupsCreated()).isEqualTo(2);
        assertThat(waiting()).isZero();
        assertNoCrossRegionAndNoDoubleAdd();
    }

    // ---------- CT-06: khách từ chối khi đang chờ ----------

    @Test
    void ct06_khachTuChoiTrongLucCho_khongBaoGioBiThemVaKhongGiuNhomChoNguoiDo() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Cần Thơ", 4);
        importCustomers(m);
        for (int i = 1; i <= 4; i++) {
            agree(i);
        }
        contacts.suppress(jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", invitationOf.get(0)).query(Long.class).single(), "test");
        contacts.suppress(jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", invitationOf.get(1)).query(Long.class).single(), "test");
        Stat st = run(1, Map.of());
        // còn 2 khách hợp lệ < ngưỡng 3: không tạo nhóm, không thêm ai; hai người từ chối không nằm trong bất kỳ nhóm nào
        assertThat(st.groupsCreated()).isZero();
        assertThat(fake.adds).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE group_id IS NOT NULL").query(Long.class).single()).isZero();
        // số điện thoại của họ vẫn còn trong hệ thống (không bao giờ tự xóa)
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(4);
    }

    // ---------- CT-07: khung giờ ----------

    @Test
    void ct07_khachVeLuc2230_choToi0800SangHomSauMoiDuocThem() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Hà Nội", 2);
        importCustomers(m);
        long g = groups.create("Nhóm Hà Nội", "https://zalo.me/g/hn", 50, "P:ha-noi");
        groups.setStatus(g, vn.shop.crm.invite.GroupStatus.OPEN);
        groups.setZaloGroupId(g, "9001");
        clock.set(LocalDateTime.of(2026, 11, 22, 22, 30));
        agree(1);
        agree(2);
        zalo.poll();
        assertThat(zalo.addNext()).isNotEqualTo(ZaloAutoService.Step.ADDED);
        clock.set(LocalDateTime.of(2026, 11, 23, 6, 59));
        assertThat(zalo.addNext()).isNotEqualTo(ZaloAutoService.Step.ADDED);
        clock.set(LocalDateTime.of(2026, 11, 23, 7, 0));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
    }

    // ---------- CT-08: hai bộ chạy chen nhau, thứ tự không đổi kết quả ----------

    @Test
    void ct08_baKhachChoVaMotNhomDaDay_taoNhomMoiChuKhongDoDonVaoNhomDayDuTheoThuTuNaoCung() {
        for (boolean addFirst : new boolean[]{true, false}) {
            jdbc.sql("TRUNCATE zalo_group_proposal_member, zalo_group_proposal, invitation, customer, invitation_campaign, zalo_group CASCADE").update();
            fake.reset();
            fake.uniqueGroupIds = true;
            zalo.resetFriendSync();
            Map<String, Integer> m = new java.util.LinkedHashMap<>();
            m.put("Hà Nội", 3);
            importCustomers(m);
            long g = groups.create("Nhóm Hà Nội đầy", "https://zalo.me/g/hn", 5, "P:ha-noi");
            groups.setStatus(g, vn.shop.crm.invite.GroupStatus.OPEN);
            groups.setZaloGroupId(g, "9001");
            groups.recount(g, 5);
            clock.set(LocalDateTime.of(2026, 11, 22, addFirst ? 10 : 12, 0)); // cách nhau quá mốc xét đề xuất mười phút
            for (int i = 1; i <= 3; i++) {
                agree(i);
            }
            zalo.poll();
            if (addFirst) {
                zalo.addNext();
                svc.step();
            } else {
                svc.step();
                zalo.addNext();
            }
            // đủ ngưỡng 3 khách nên lập nhóm mới cho cả ba; không ai bị đổ vào nhóm đầy cũ
            assertThat(fake.creates).as("addFirst=" + addFirst).hasSize(1);
            assertThat(fake.creates.getFirst().members()).hasSize(3);
            assertThat(fake.adds).as("addFirst=" + addFirst).isEmpty();
        }
    }

    // ---------- CT-09: cầu dao nhảy giữa chừng rồi mở lại ----------

    @Test
    void ct09_cauDaoNhayGiuaChung_khongAiBiThemHaiLanSauKhiMoLai() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Hà Nội", 8);
        importCustomers(m);
        long g = groups.create("Nhóm Hà Nội", "https://zalo.me/g/hn", 50, "P:ha-noi");
        groups.setStatus(g, vn.shop.crm.invite.GroupStatus.OPEN);
        groups.setZaloGroupId(g, "9001");
        for (int i = 1; i <= 8; i++) {
            agree(i);
        }
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        clock.advance(Duration.ofMinutes(3));
        fake.willReturn(AddResult.of(Outcome.SYSTEM_ERROR, "UNKNOWN"));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.TRIPPED);
        clock.advance(Duration.ofHours(1));
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.NOT_ACTIVE);
        auto.reopen("mẹ");
        zalo.poll();
        for (int k = 0; k < 20; k++) {
            clock.advance(Duration.ofMinutes(3));
            zalo.addNext();
        }
        assertThat(fake.adds.stream().map(a -> a[0]).distinct().count()).isEqualTo(fake.adds.size());
        // khách bị đánh dấu lỗi ở lần cầu dao nhảy KHÔNG được tự thêm lại (không biết đã thêm chưa)
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE zalo_add_state = 'FAILED'").query(Long.class).single()).isEqualTo(1);
    }

    // ---------- CT-10: cầu nối không nối được vài nhịp ----------

    @Test
    void ct10_cauNoiMatVaiNhip_khongMatKhachVaKhongTinhHanMuc() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Hà Nội", 3);
        importCustomers(m);
        long g = groups.create("Nhóm Hà Nội", "https://zalo.me/g/hn", 50, "P:ha-noi");
        groups.setStatus(g, vn.shop.crm.invite.GroupStatus.OPEN);
        groups.setZaloGroupId(g, "9001");
        clock.set(LocalDateTime.of(2026, 11, 22, 10, 0));
        for (int i = 1; i <= 3; i++) {
            agree(i);
        }
        zalo.poll();
        fake.defaultResult = AddResult.of(Outcome.UNREACHABLE, "BRIDGE_DOWN");
        for (int k = 0; k < 6; k++) {
            assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.UNREACHABLE);
            clock.advance(Duration.ofMinutes(3));
        }
        assertThat(auto.quota().usedToday()).isZero();
        fake.defaultResult = AddResult.ok();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
    }

    // ---------- CT-11: đề xuất chờ mẹ duyệt, trong lúc đó khách thay đổi ----------

    @Test
    void ct11_deXuatBaNguoiRoiMotNguoiTuChoiTruocKhiDuyet_taoNhomHaiNguoi_haiNguoiTuChoiThiHetHan() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Cần Thơ", 3);
        importCustomers(m);
        groupCfg.setMode(ZaloGroupMode.CONFIRM, false, "mẹ");
        for (int i = 1; i <= 3; i++) {
            agree(i);
        }
        assertThat(svc.step()).isEqualTo(ZaloGroupCreationService.Step.PROPOSED);
        long proposal = svc.recent(1).getFirst().id();
        contacts.suppress(jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", invitationOf.get(0)).query(Long.class).single(), "test");
        svc.approve(proposal, "mẹ");
        assertThat(svc.step()).isEqualTo(ZaloGroupCreationService.Step.CREATED);
        // Đánh giá: chấp nhận được. Đề xuất ban đầu 3 người, còn 2 người hợp lệ thì vẫn lập nhóm 2 người (mẹ đã duyệt), không tạo nhóm 1 người
        assertThat(fake.creates).singleElement().satisfies(c -> assertThat(c.members()).hasSize(2));
    }

    @Test
    void ct11b_deXuatConMotNguoiHopLeThiHetHanKhongTaoNhomMotNguoi() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Cần Thơ", 3);
        importCustomers(m);
        groupCfg.setMode(ZaloGroupMode.CONFIRM, false, "mẹ");
        for (int i = 1; i <= 3; i++) {
            agree(i);
        }
        svc.step();
        long proposal = svc.recent(1).getFirst().id();
        for (int k = 0; k < 2; k++) {
            contacts.suppress(jdbc.sql("SELECT customer_id FROM invitation WHERE id = :id").param("id", invitationOf.get(k)).query(Long.class).single(), "test");
        }
        svc.approve(proposal, "mẹ");
        assertThat(svc.step()).isEqualTo(ZaloGroupCreationService.Step.NOTHING);
        assertThat(fake.creates).isEmpty();
        assertThat(svc.recent(1).getFirst().state()).isEqualTo("EXPIRED");
    }

    // ---------- CT-12: khách trong đề xuất bị thêm vào nhóm khác trước khi đề xuất chạy ----------

    @Test
    void ct12_haiBoChayChenNhau_khachDaVaoNhomKhacKhongBiDuaVaoNhomMoiLanNua() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("Cần Thơ", 4);
        importCustomers(m);
        groupCfg.setMode(ZaloGroupMode.CONFIRM, false, "mẹ");
        for (int i = 1; i <= 4; i++) {
            agree(i);
        }
        svc.step();
        long proposal = svc.recent(1).getFirst().id();
        // mẹ tạo tay một nhóm Cần Thơ còn chỗ và hệ thống thêm một khách của đề xuất vào đó trước khi mẹ duyệt
        long g = groups.create("Nhóm tay", "https://zalo.me/g/ct", 50, "P:can-tho");
        groups.setStatus(g, vn.shop.crm.invite.GroupStatus.OPEN);
        groups.setZaloGroupId(g, "9002");
        auto.setMode(ZaloMode.AUTO, true, "mẹ");
        zalo.poll();
        assertThat(zalo.addNext()).isEqualTo(ZaloAutoService.Step.ADDED);
        String added = fake.adds.getFirst()[0];
        svc.approve(proposal, "mẹ");
        assertThat(svc.step()).isEqualTo(ZaloGroupCreationService.Step.CREATED);
        assertThat(fake.creates.getFirst().members()).doesNotContain(added);
        assertNoCrossRegionAndNoDoubleAdd();
    }
}
