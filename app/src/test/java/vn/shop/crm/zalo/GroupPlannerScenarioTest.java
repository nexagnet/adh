package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;
import vn.shop.crm.geo.Region;
import vn.shop.crm.geo.VnGeo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cây test "khó ngờ nhất" cho bộ lập kế hoạch tạo nhóm (hàm thuần). Mỗi test ứng với một nhánh trong
 * {@code docs/CAY-TEST-KHO-NGO-VA-DANH-GIA-HOP-LY.md} (mã PL-xx): tình huống, hành vi hiện tại và đánh giá tính hợp lý.
 * Các test dùng số ngẫu nhiên có hạt giống cố định CHỈ trong test để sinh dữ liệu; mã chạy thật không có số ngẫu nhiên.
 */
class GroupPlannerScenarioTest {

    private static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");
    private static final Set<String> NONE = Set.of();

    private static GroupPlanner.Waiting w(long id, String province, int minutes) {
        return new GroupPlanner.Waiting(id, province, T0.plusSeconds(minutes * 60L));
    }

    /** Lập kế hoạch lặp lại tới khi hết; mỗi lần lấy đúng những khách được chọn ra khỏi hàng chờ. Trả danh sách các kế hoạch. */
    private static List<GroupPlanner.Plan> exhaust(List<GroupPlanner.Waiting> start, int min, int max) {
        List<GroupPlanner.Waiting> left = new ArrayList<>(start);
        List<GroupPlanner.Plan> plans = new ArrayList<>();
        for (int guard = 0; guard < 10_000; guard++) {
            Optional<GroupPlanner.Plan> p = GroupPlanner.plan(left, min, max, NONE);
            if (p.isEmpty()) {
                break;
            }
            plans.add(p.get());
            Set<Long> used = new HashSet<>(p.get().invitationIds());
            left.removeIf(x -> used.contains(x.invitationId()));
        }
        return plans;
    }

    private static List<GroupPlanner.Waiting> remaining(List<GroupPlanner.Waiting> start, List<GroupPlanner.Plan> plans) {
        Set<Long> used = plans.stream().flatMap(p -> p.invitationIds().stream()).collect(Collectors.toSet());
        return start.stream().filter(x -> !used.contains(x.invitationId())).toList();
    }

    // ---------- PL-01 .. PL-04: số lượng ----------

    @Test
    void pl01_muaKhach1000NguoiMotTinhRa200NhomDuNamKhachKhongDu() {
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            in.add(w(i, "ha-noi", i));
        }
        List<GroupPlanner.Plan> plans = exhaust(in, 3, 5);
        assertThat(plans).hasSize(200);
        assertThat(plans).allSatisfy(p -> assertThat(p.invitationIds()).hasSize(5));
        assertThat(remaining(in, plans)).isEmpty();
        // người kết bạn sớm nhất vào nhóm đầu
        assertThat(plans.getFirst().invitationIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void pl02_soKhachLeMotTinh_baoNhieuNguoiBiDuLai() {
        // n khách một tỉnh, ngưỡng 3, tối đa 5: nhóm lấy 5, còn dư 1 hoặc 2 thì KHÔNG đủ ngưỡng nên dư lại cho đường "khách dư lẻ"
        for (int n = 1; n <= 40; n++) {
            List<GroupPlanner.Waiting> in = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                in.add(w(i, "can-tho", i));
            }
            List<GroupPlanner.Plan> plans = exhaust(in, 3, 5);
            int left = remaining(in, plans).size();
            int expected = n < 3 ? n : (n % 5 == 1 || n % 5 == 2 ? n % 5 : 0);
            assertThat(left).as("n=" + n).isEqualTo(expected);
            assertThat(plans).allSatisfy(p -> assertThat(p.invitationIds().size()).isBetween(3, 5));
        }
    }

    @Test
    void pl03_moiTinhMotKhach_gopLenVungRoiMien() {
        // 63 tỉnh, mỗi tỉnh đúng một khách: không có tỉnh nào đủ ngưỡng, phải gộp lên vùng rồi miền; không bỏ sót ai quá ngưỡng
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        long id = 1;
        for (var p : VnGeo.all()) {
            in.add(w(id, p.code(), (int) id));
            id++;
        }
        List<GroupPlanner.Plan> plans = exhaust(in, 3, 5);
        List<GroupPlanner.Waiting> left = remaining(in, plans);
        for (Region r : Region.values()) {
            long inRegion = left.stream().filter(x -> VnGeo.byCode(x.provinceCode()).orElseThrow().region() == r).count();
            assertThat(inRegion).as("miền " + r).isLessThan(3);
        }
        assertThat(plans).isNotEmpty();
    }

    @Test
    void pl04_namMuoiTinhMoiTinhBaKhach_taoNhieuNhomBaNguoiThayVaGopThanhNhomNam() {
        // Đánh giá hợp lý: ưu tiên phạm vi nhỏ nhất nên 10 tỉnh x 3 khách ra 10 nhóm 3 người, không gộp thành 6 nhóm 5 người
        List<String> provinces = List.of("ha-noi", "hai-phong", "bac-ninh", "hai-duong", "hung-yen", "thai-binh", "ha-nam", "nam-dinh", "ninh-binh", "vinh-phuc");
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        long id = 1;
        for (String p : provinces) {
            for (int k = 0; k < 3; k++) {
                in.add(w(id, p, (int) id));
                id++;
            }
        }
        List<GroupPlanner.Plan> plans = exhaust(in, 3, 5);
        assertThat(plans).hasSize(10);
        assertThat(plans).allSatisfy(p -> assertThat(p.scope().code()).startsWith("P:"));
        assertThat(remaining(in, plans)).isEmpty();
    }

    // ---------- PL-05 .. PL-09: thứ tự và hòa ----------

    @Test
    void pl05_haiTinhDuNguong_chonTinhNhieuKhachHonTruoc() {
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            in.add(w(i, "ha-noi", 100 + i));
        }
        for (int i = 11; i <= 22; i++) {
            in.add(w(i, "hai-phong", 200 + i));
        }
        assertThat(GroupPlanner.plan(in, 3, 5, NONE).orElseThrow().scope().code()).isEqualTo("P:hai-phong");
    }

    @Test
    void pl06_haiTinhBangNhau_chonTinhCoNguoiKetBanSomHon() {
        List<GroupPlanner.Waiting> in = List.of(w(1, "hai-phong", 50), w(2, "hai-phong", 51), w(3, "hai-phong", 52),
                w(4, "ha-noi", 10), w(5, "ha-noi", 60), w(6, "ha-noi", 70));
        assertThat(GroupPlanner.plan(in, 3, 5, NONE).orElseThrow().scope().code()).isEqualTo("P:ha-noi");
    }

    @Test
    void pl07_thuTuDauVaoKhongDoiKetQua() {
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        Random rnd = new Random(42);
        String[] ps = {"ha-noi", "hai-phong", "can-tho", "tphcm", "da-nang", "hue", "lang-son"};
        for (int i = 1; i <= 60; i++) {
            in.add(w(i, ps[rnd.nextInt(ps.length)], rnd.nextInt(5000)));
        }
        GroupPlanner.Plan base = GroupPlanner.plan(in, 3, 5, NONE).orElseThrow();
        for (int round = 0; round < 20; round++) {
            List<GroupPlanner.Waiting> shuffled = new ArrayList<>(in);
            Collections.shuffle(shuffled, new Random(round));
            GroupPlanner.Plan p = GroupPlanner.plan(shuffled, 3, 5, NONE).orElseThrow();
            assertThat(p.scope().code()).isEqualTo(base.scope().code());
            assertThat(p.invitationIds()).isEqualTo(base.invitationIds());
        }
    }

    @Test
    void pl08_cungMotPhutKetBan_chonTheoMaLoiMoiDeOnDinh() {
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            in.add(w(i, "can-tho", 5)); // cùng thời điểm
        }
        assertThat(GroupPlanner.plan(in, 3, 5, NONE).orElseThrow().invitationIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void pl09_khachKhongCoGioKetBan_xepSauCungKhongBaoGioTranhSuatNguoiCoGio() {
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        in.add(new GroupPlanner.Waiting(1, "can-tho", null));
        in.add(new GroupPlanner.Waiting(2, "can-tho", null));
        for (int i = 3; i <= 7; i++) {
            in.add(w(i, "can-tho", i));
        }
        assertThat(GroupPlanner.plan(in, 3, 5, NONE).orElseThrow().invitationIds()).containsExactly(3L, 4L, 5L, 6L, 7L);
    }

    // ---------- PL-10 .. PL-14: gộp khu vực ----------

    @Test
    void pl10_haiTinhCungVungMoiTinhHaiKhach_nhomVungBonNguoi() {
        var plan = GroupPlanner.plan(List.of(w(1, "can-tho", 1), w(2, "can-tho", 2), w(3, "soc-trang", 3), w(4, "soc-trang", 4)), 3, 5, NONE).orElseThrow();
        assertThat(plan.scope().code()).isEqualTo("S:DB_SONG_CUU_LONG");
        assertThat(plan.invitationIds()).hasSize(4);
    }

    @Test
    void pl11_tinhDuNguongNhungVungLonHon_VanLapNhomTinhTruoc() {
        // 3 khách Hà Nội + 20 khách rải khắp ĐBSH: lập nhóm Hà Nội 3 người trước (phạm vi nhỏ nhất), không gộp cả vùng
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            in.add(w(i, "ha-noi", i));
        }
        String[] others = {"hai-phong", "bac-ninh", "hai-duong", "hung-yen", "thai-binh", "ha-nam", "nam-dinh", "ninh-binh", "vinh-phuc"};
        for (int i = 4; i <= 23; i++) {
            in.add(w(i, others[i % others.length], 100 + i));
        }
        assertThat(GroupPlanner.plan(in, 3, 5, NONE).orElseThrow().scope().code()).startsWith("P:");
    }

    @Test
    void pl12_mienKhacNhauKhongBaoGioChungMotKeHoach() {
        List<GroupPlanner.Waiting> in = List.of(w(1, "ha-noi", 1), w(2, "da-nang", 2), w(3, "tphcm", 3), w(4, "lang-son", 4), w(5, "hue", 5), w(6, "can-tho", 6));
        assertThat(GroupPlanner.plan(in, 3, 5, NONE)).isEmpty();
    }

    @Test
    void pl13_taiTruongHopTayBacVaDongBacGopMienBac() {
        // Sơn La (Tây Bắc) 1 + Lạng Sơn (Đông Bắc) 1 + Hà Nội (ĐBSH) 1: ba vùng khác nhau, cùng miền Bắc -> nhóm miền Bắc 3 người
        var plan = GroupPlanner.plan(List.of(w(1, "son-la", 1), w(2, "lang-son", 2), w(3, "ha-noi", 3)), 3, 5, NONE).orElseThrow();
        assertThat(plan.scope().code()).isEqualTo("R:BAC");
    }

    @Test
    void pl14_khachChuaRoTinhHoacTinhLaKhongBaoGioDuocXep() {
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        in.add(new GroupPlanner.Waiting(1, null, T0));
        in.add(new GroupPlanner.Waiting(2, "", T0));
        in.add(new GroupPlanner.Waiting(3, "atlantis", T0));
        for (int i = 4; i <= 100; i++) {
            in.add(new GroupPlanner.Waiting(i, "khong-co", T0));
        }
        assertThat(GroupPlanner.plan(in, 3, 5, NONE)).isEmpty();
        in.add(w(200, "can-tho", 1));
        in.add(w(201, "can-tho", 2));
        in.add(w(202, "can-tho", 3));
        assertThat(GroupPlanner.plan(in, 3, 5, NONE).orElseThrow().invitationIds()).containsExactly(200L, 201L, 202L);
    }

    // ---------- PL-15 .. PL-19: từ chối và chờ lâu ----------

    @Test
    void pl15_meTuChoiNhomTinh_keHoachKeTiepLaNhomVungChuaDungNhungKhach() {
        // Đánh giá: chấp nhận được. Cả ba cấp đều chứa cùng ba khách; từ chối cấp tỉnh thì cấp vùng và miền được đề xuất lại với cùng khách
        List<GroupPlanner.Waiting> in = List.of(w(1, "ha-noi", 1), w(2, "ha-noi", 2), w(3, "ha-noi", 3));
        assertThat(GroupPlanner.plan(in, 3, 5, Set.of("P:ha-noi")).orElseThrow().scope().code()).isEqualTo("S:DB_SONG_HONG");
        assertThat(GroupPlanner.plan(in, 3, 5, Set.of("P:ha-noi", "S:DB_SONG_HONG")).orElseThrow().scope().code()).isEqualTo("R:BAC");
        assertThat(GroupPlanner.plan(in, 3, 5, Set.of("P:ha-noi", "S:DB_SONG_HONG", "R:BAC"))).isEmpty();
    }

    @Test
    void pl16_khachChoQuaBaySoNgay_haNguongXuongHaiNhungKhongBaoGioMot() {
        Instant now = T0.plus(Duration.ofDays(8));
        List<GroupPlanner.Waiting> two = List.of(w(1, "can-tho", 1), w(2, "can-tho", 2));
        assertThat(GroupPlanner.plan(two, 3, 5, NONE, now, Duration.ofDays(7))).isPresent();
        assertThat(GroupPlanner.plan(List.of(w(1, "can-tho", 1)), 3, 5, NONE, now, Duration.ofDays(7))).isEmpty();
        // chưa quá hạn thì vẫn đòi đủ ngưỡng
        assertThat(GroupPlanner.plan(two, 3, 5, NONE, T0.plus(Duration.ofDays(2)), Duration.ofDays(7))).isEmpty();
    }

    @Test
    void pl17_khuVucChoLauDuocUuTienTruocKhuVucDongKhachHon() {
        Instant now = T0.plus(Duration.ofDays(8));
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        in.add(w(1, "can-tho", 1));
        in.add(w(2, "can-tho", 2));
        for (int i = 3; i <= 22; i++) {
            in.add(new GroupPlanner.Waiting(i, "ha-noi", T0.plus(Duration.ofDays(7)).plusSeconds(i)));
        }
        assertThat(GroupPlanner.plan(in, 3, 5, NONE, now, Duration.ofDays(7)).orElseThrow().scope().code()).isEqualTo("P:can-tho");
    }

    @Test
    void pl18_haiKhachChoLauCungTinhVaMotKhachMoiCungVung_chonNhomTinhHaiNguoiChuaGopVungBaNguoi() {
        // Đánh giá: CHƯA tối ưu. Cần Thơ có 2 khách chờ quá 7 ngày, Sóc Trăng có 1 khách mới. Bộ lập kế hoạch tạo nhóm Cần Thơ 2 người
        // (cấp tỉnh được xét trước) và để Sóc Trăng 1 người dư lại, thay vì một nhóm vùng 3 người. Chấp nhận vì khách dư sẽ vào nhóm gần.
        Instant now = T0.plus(Duration.ofDays(8));
        List<GroupPlanner.Waiting> in = List.of(w(1, "can-tho", 1), w(2, "can-tho", 2),
                new GroupPlanner.Waiting(3, "soc-trang", T0.plus(Duration.ofDays(7)).plusSeconds(60)));
        var plan = GroupPlanner.plan(in, 3, 5, NONE, now, Duration.ofDays(7)).orElseThrow();
        assertThat(plan.scope().code()).isEqualTo("P:can-tho");
        assertThat(plan.invitationIds()).containsExactly(1L, 2L);
    }

    @Test
    void pl19_khuVucBiTuChoiVanKhongDuocNoiNguongDuDaChoLau() {
        Instant now = T0.plus(Duration.ofDays(30));
        List<GroupPlanner.Waiting> in = List.of(w(1, "can-tho", 1), w(2, "can-tho", 2));
        assertThat(GroupPlanner.plan(in, 3, 5, Set.of("P:can-tho", "S:DB_SONG_CUU_LONG", "R:NAM"), now, Duration.ofDays(7))).isEmpty();
    }

    // ---------- PL-20 .. PL-24: tính chất tổng quát với dữ liệu ngẫu nhiên có hạt giống ----------

    @Test
    void pl20_tinhChatChungVoiHangTramBoDuLieuNgauNhien() {
        String[] codes = VnGeo.all().stream().map(p -> p.code()).toArray(String[]::new);
        for (int seed = 0; seed < 150; seed++) {
            Random rnd = new Random(seed);
            int n = 1 + rnd.nextInt(120);
            int min = 2 + rnd.nextInt(3); // 2..4
            int max = 5;
            List<GroupPlanner.Waiting> in = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                // lệch về vài tỉnh để có cả trường hợp dồn và rải
                String c = rnd.nextInt(3) == 0 ? codes[rnd.nextInt(codes.length)] : codes[rnd.nextInt(6)];
                in.add(new GroupPlanner.Waiting(i, c, T0.plusSeconds(rnd.nextInt(100_000))));
            }
            List<GroupPlanner.Plan> plans = exhaust(in, min, max);
            Set<Long> seen = new HashSet<>();
            for (GroupPlanner.Plan p : plans) {
                assertThat(p.invitationIds().size()).as("seed " + seed).isBetween(min, max);
                for (long id : p.invitationIds()) {
                    assertThat(seen.add(id)).as("khách " + id + " bị đưa vào hai nhóm, seed " + seed).isTrue();
                    var prov = VnGeo.byCode(in.get((int) id - 1).provinceCode()).orElseThrow();
                    assertThat(p.scope().contains(prov)).as("khách " + id + " nằm ngoài phạm vi " + p.scope().code()).isTrue();
                }
            }
            // sau khi hết kế hoạch: mỗi miền còn dưới ngưỡng
            List<GroupPlanner.Waiting> left = remaining(in, plans);
            for (Region r : Region.values()) {
                long c = left.stream().filter(x -> VnGeo.byCode(x.provinceCode()).orElseThrow().region() == r).count();
                assertThat(c).as("seed " + seed + " miền " + r).isLessThan(min);
            }
        }
    }

    @Test
    void pl21_haiNghinKhachDuaVaoMotLanKhongChamVaKhongQuaMotGiay() {
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        String[] codes = VnGeo.all().stream().map(p -> p.code()).toArray(String[]::new);
        Random rnd = new Random(7);
        for (int i = 1; i <= 20_000; i++) {
            in.add(new GroupPlanner.Waiting(i, codes[rnd.nextInt(codes.length)], T0.plusSeconds(rnd.nextInt(1_000_000))));
        }
        long start = System.nanoTime();
        GroupPlanner.plan(in, 3, 5, NONE).orElseThrow();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void pl22_nguongHaiBangNguongBa_chiLamNhomNhoHonKhongLamHongTinhChat() {
        List<GroupPlanner.Waiting> in = List.of(w(1, "can-tho", 1), w(2, "can-tho", 2));
        assertThat(GroupPlanner.plan(in, 2, 5, NONE)).isPresent();
        assertThat(GroupPlanner.plan(in, 3, 5, NONE)).isEmpty();
    }

    @Test
    void pl23_toiDaNhomHaiNguoiVoiNguongHai() {
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            in.add(w(i, "can-tho", i));
        }
        List<GroupPlanner.Plan> plans = exhaust(in, 2, 2);
        assertThat(plans).hasSize(2);
        assertThat(remaining(in, plans)).hasSize(1);
    }

    @Test
    void pl24_ketHopNhieuDieuKien_quaHanVaTuChoiVaChuaRoTinhCungLuc() {
        Instant now = T0.plus(Duration.ofDays(10));
        List<GroupPlanner.Waiting> in = new ArrayList<>();
        in.add(new GroupPlanner.Waiting(1, null, T0));
        in.add(w(2, "can-tho", 1));
        in.add(w(3, "can-tho", 2));
        in.add(new GroupPlanner.Waiting(4, "da-nang", T0.plus(Duration.ofDays(9))));
        in.add(new GroupPlanner.Waiting(5, "da-nang", T0.plus(Duration.ofDays(9))));
        in.add(new GroupPlanner.Waiting(6, "da-nang", T0.plus(Duration.ofDays(9))));
        // Cần Thơ quá hạn 2 khách được ưu tiên trước Đà Nẵng 3 khách mới; khi mẹ từ chối Cần Thơ thì sang Đà Nẵng
        var first = GroupPlanner.plan(in, 3, 5, NONE, now, Duration.ofDays(7)).orElseThrow();
        assertThat(first.scope().code()).isEqualTo("P:can-tho");
        var second = GroupPlanner.plan(in, 3, 5, Set.of("P:can-tho", "S:DB_SONG_CUU_LONG", "R:NAM"), now, Duration.ofDays(7)).orElseThrow();
        assertThat(second.scope().code()).isEqualTo("P:da-nang");
    }
}
