package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Thuật toán chọn khu vực tạo nhóm (hàm thuần): nhỏ nhất trước, ngưỡng, tối đa 5 khách kết bạn sớm nhất. */
class GroupPlannerTest {

    private static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");

    private static GroupPlanner.Waiting w(long id, String province, int minutes) {
        return new GroupPlanner.Waiting(id, province, T0.plusSeconds(minutes * 60L));
    }

    @Test
    void khongDuNguongThiKhongTaoNhom() {
        var plan = GroupPlanner.plan(List.of(w(1, "can-tho", 1), w(2, "can-tho", 2)), 3, 5, Set.of());
        assertThat(plan).isEmpty();
    }

    @Test
    void duKhachCungTinhThiChonTinhVaLayNguoiKetBanSomNhat() {
        List<GroupPlanner.Waiting> list = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            list.add(w(i, "can-tho", 10 - i)); // người 7 kết bạn sớm nhất
        }
        var plan = GroupPlanner.plan(list, 3, 5, Set.of()).orElseThrow();
        assertThat(plan.scope().code()).isEqualTo("P:can-tho");
        assertThat(plan.waitingInScope()).isEqualTo(7);
        assertThat(plan.invitationIds()).containsExactly(7L, 6L, 5L, 4L, 3L);
    }

    @Test
    void tinhNhoItKhachGopLenVungKhongTaoNhomMotHaiNguoi() {
        // 2 người Cần Thơ + 1 Long An + 1 Tiền Giang: cùng vùng Đồng bằng sông Cửu Long, mỗi tỉnh đều dưới ngưỡng
        var plan = GroupPlanner.plan(List.of(w(1, "can-tho", 1), w(2, "can-tho", 2), w(3, "long-an", 3), w(4, "tien-giang", 4)),
                3, 5, Set.of()).orElseThrow();
        assertThat(plan.scope().code()).isEqualTo("S:DB_SONG_CUU_LONG");
        assertThat(plan.invitationIds()).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void vungChuaDuThiGopLenMien() {
        // Đồng Nai (Đông Nam Bộ) 2 người + Cần Thơ (ĐBSCL) 2 người: khác vùng, cùng miền Nam
        var plan = GroupPlanner.plan(List.of(w(1, "dong-nai", 1), w(2, "dong-nai", 2), w(3, "can-tho", 3), w(4, "can-tho", 4)),
                3, 5, Set.of()).orElseThrow();
        assertThat(plan.scope().code()).isEqualTo("R:NAM");
    }

    @Test
    void khacMienKhongGopVaoMotNhom() {
        var plan = GroupPlanner.plan(List.of(w(1, "dong-nai", 1), w(2, "ha-noi", 2), w(3, "can-tho", 3)), 3, 5, Set.of());
        assertThat(plan).isEmpty();
    }

    @Test
    void bangSoKhachThiLayNoiCoNguoiKetBanSomNhat() {
        var plan = GroupPlanner.plan(List.of(w(1, "ha-noi", 5), w(2, "ha-noi", 6), w(3, "ha-noi", 7), w(4, "dong-nai", 1),
                w(5, "dong-nai", 2), w(6, "dong-nai", 3)), 3, 5, Set.of()).orElseThrow();
        // cả Hà Nội và Đồng Nai đủ ngưỡng ở cấp tỉnh, bằng số khách: chọn nơi có người kết bạn sớm nhất (Đồng Nai)
        assertThat(plan.scope().code()).isEqualTo("P:dong-nai");
    }

    @Test
    void khuVucBiTuChoiBiBoQuaVaTinhLaHoacTrongKhongXepDuoc() {
        List<GroupPlanner.Waiting> list = List.of(w(1, "can-tho", 1), w(2, "can-tho", 2), w(3, "can-tho", 3),
                w(4, null, 4), w(5, "khong-co-tinh-nay", 5));
        assertThat(GroupPlanner.plan(list, 3, 5, Set.of()).orElseThrow().invitationIds()).containsExactly(1L, 2L, 3L);
        // bỏ qua tỉnh đã bị từ chối: lên vùng thì vẫn chỉ có 3 người cùng vùng, nên đề xuất vùng
        var plan = GroupPlanner.plan(list, 3, 5, Set.of("P:can-tho")).orElseThrow();
        assertThat(plan.scope().code()).isEqualTo("S:DB_SONG_CUU_LONG");
        assertThat(GroupPlanner.plan(list, 3, 5, Set.of("P:can-tho", "S:DB_SONG_CUU_LONG", "R:NAM"))).isEmpty();
    }
}
