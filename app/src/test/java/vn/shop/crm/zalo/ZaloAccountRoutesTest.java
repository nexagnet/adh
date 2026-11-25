package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D23: lời gọi tới Zalo đi đúng cầu nối của tài khoản đang làm việc, tài khoản chưa khai báo thì bị từ chối (không rơi sang tài khoản khác),
 * bối cảnh luôn được trả lại, và cấu hình tài khoản thêm bị kiểm chặt.
 */
class ZaloAccountRoutesTest {

    private final FakeZaloGateway one = new FakeZaloGateway();
    private final FakeZaloGateway two = new FakeZaloGateway();

    private ZaloAccountRoutes routes() {
        Map<Long, ZaloLink> m = new LinkedHashMap<>();
        m.put(1L, one);
        m.put(2L, two);
        return new ZaloAccountRoutes(m);
    }

    @Test
    void macDinhLaTaiKhoanMotVaGoiDenDungCauNoi() {
        ZaloLink link = routes().router();
        assertThat(ZaloAccountContext.current()).isEqualTo(1L);
        link.status();
        assertThat(one.statusCalls).isEqualTo(1);
        assertThat(two.statusCalls).isZero();
        ZaloAccountContext.run(2L, link::status);
        assertThat(one.statusCalls).isEqualTo(1);
        assertThat(two.statusCalls).isEqualTo(1);
    }

    @Test
    void moiViecCuaZaloLinkDiQuaDinhTuyen() {
        ZaloLink link = routes().router();
        ZaloAccountContext.run(2L, () -> {
            link.addToGroup("123", "456");
            link.friendEvents(0);
        });
        assertThat(two.adds).hasSize(1);
        assertThat(one.adds).isEmpty();
        assertThat(two.eventCalls).isEqualTo(1);
        assertThat(one.eventCalls).isZero();
    }

    @Test
    void taiKhoanChuaKhaiBaoBiTuChoiKhongRoiSangTaiKhoanKhac() {
        ZaloLink link = routes().router();
        assertThatThrownBy(() -> ZaloAccountContext.run(3L, link::status)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tài khoản Zalo 3");
        assertThat(one.statusCalls).isZero();
        assertThat(two.statusCalls).isZero();
    }

    @Test
    void boiCanhDuocTraLaiKeCaKhiLoi() {
        ZaloLink link = routes().router();
        assertThatThrownBy(() -> ZaloAccountContext.run(2L, () -> {
            throw new IllegalArgumentException("x");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(ZaloAccountContext.current()).isEqualTo(1L);
        ZaloAccountContext.run(2L, () -> ZaloAccountContext.run(1L, () -> assertThat(ZaloAccountContext.current()).isEqualTo(1L)));
        ZaloAccountContext.run(2L, () -> {
            ZaloAccountContext.run(1L, link::status);
            assertThat(ZaloAccountContext.current()).isEqualTo(2L);
        });
        assertThat(ZaloAccountContext.current()).isEqualTo(1L);
        assertThatThrownBy(() -> ZaloAccountContext.run(0L, () -> { })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boiCanhKhongRoSangLuongKhac() throws Exception {
        AtomicReference<Long> seen = new AtomicReference<>();
        ZaloAccountContext.run(2L, () -> {
            Thread t = new Thread(() -> seen.set(ZaloAccountContext.current()));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(seen.get()).isEqualTo(1L);
    }

    @Test
    void ngoaiLeCuaCauNoiDuocNemNguyenVen() {
        two.eventsFail = true;
        ZaloLink link = routes().router();
        List<Object> ok = new java.util.ArrayList<>();
        try {
            ZaloAccountContext.run(2L, () -> ok.add(link.friendEvents(0)));
        } catch (RuntimeException e) {
            ok.add(e);
        }
        assertThat(ok).hasSize(1);
        assertThat(two.eventCalls).isEqualTo(1);
    }

    @Test
    void danhSachTaiKhoan() {
        ZaloAccountRoutes r = routes();
        assertThat(r.accountIds()).containsExactly(1L, 2L);
        assertThat(r.has(2L)).isTrue();
        assertThat(r.has(3L)).isFalse();
        assertThatThrownBy(() -> new ZaloAccountRoutes(Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cauHinhTaiKhoanThemBiKiemChat() {
        assertThat(new ZaloAccountProperties(null).accounts()).isEmpty();
        assertThatThrownBy(() -> new ZaloAccountProperties(List.of(new ZaloAccountProperties.Account(1, "http://a", "k"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("từ 2");
        assertThatThrownBy(() -> new ZaloAccountProperties(List.of(new ZaloAccountProperties.Account(2, "http://a", "k"),
                new ZaloAccountProperties.Account(2, "http://b", "k")))).hasMessageContaining("Trùng");
        assertThatThrownBy(() -> new ZaloAccountProperties(List.of(new ZaloAccountProperties.Account(2, "", "k"))))
                .hasMessageContaining("thiếu");
        assertThatThrownBy(() -> new ZaloAccountProperties(List.of(new ZaloAccountProperties.Account(2, "http://a", " "))))
                .hasMessageContaining("thiếu");
    }
}
