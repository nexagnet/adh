package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Khóa hình dạng của giao diện (ADR-0002 mục 3.1, 4): Zalo chỉ có BA việc. Thêm hàm nào (gửi tin, gửi lời mời kết bạn,
 * tra số hàng loạt) thì test này đỏ và phải có người cố ý sửa nó, cùng với hồ sơ quyết định.
 */
class ZaloGatewayShapeTest {

    private static List<String> names(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).sorted().toList();
    }

    @Test
    void gatewayChiCoBaViec() {
        assertThat(names(ZaloGateway.class)).containsExactly("addToGroup", "friendEvents", "status");
    }

    @Test
    void phienChiCoQuanLyDangNhapVaDocDanhSachNhom() {
        assertThat(names(ZaloSession.class)).containsExactly("listGroups", "loginStatus", "logout", "resume", "startLogin", "stop");
    }

    @Test
    void khongLopNaoTrongGoiZaloCoHamGuiTinHayKetBan() {
        for (Class<?> c : List.of(ZaloGateway.class, ZaloSession.class, ZcaBridgeGateway.class, ManualZaloGateway.class,
                ZaloAutoService.class)) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                assertThat(n).as(c.getSimpleName() + "." + m.getName()).doesNotContain("sendmessage", "sendfriend",
                        "friendrequest", "findusers", "lookup", "broadcast", "sendtext");
            }
        }
    }
}
