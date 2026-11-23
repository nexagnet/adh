package vn.shop.crm.zalo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Khóa hình dạng của giao diện (ADR-0002 mục 3.1, 4; D18): Zalo chỉ có BA việc, cộng HAI hàm gửi tin riêng cho từng khách. Thêm hàm nào (gửi tin, gửi lời mời kết bạn,
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

    @Test
    void messengerChiCoGuiTheoSoHoacTheoMa() {
        assertThat(names(ZaloMessenger.class)).containsExactly("sendByPhone", "sendByUid");
    }

    @Test
    void taoNhomChiCoMotHamVaKhongCoViecNguyHiem() {
        assertThat(names(ZaloGroupMaker.class)).containsExactly("createGroup");
        for (Class<?> c : List.of(ZaloGroupMaker.class, ZaloGroupCreationService.class, ZcaBridgeGateway.class)) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                assertThat(n).as(c.getSimpleName() + "." + m.getName()).doesNotContain("disperse", "changegroupowner", "removeuser",
                        "deletegroup", "leavegroup", "blockuser", "changegroupname");
            }
        }
    }

    /** D18: khoảng cách giữa hai tin là CỐ ĐỊNH. Không dùng số ngẫu nhiên để rải thời gian gửi (không né phát hiện). */
    @Test
    void khongDungSoNgauNhienTrongMaGuiTin() throws IOException {
        for (String dir : List.of("zalo", "testrun", "send")) {
            Path root = Path.of("src/main/java/vn/shop/crm", dir);
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String code = Files.readString(f);
                    assertThat(code).as(f.toString()).doesNotContain("ThreadLocalRandom", "java.util.Random", "SecureRandom", "Math.random");
                }
            }
        }
    }
}
