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
        for (Class<?> c : List.of(ZaloGateway.class, ZaloSession.class, ZaloAutoService.class)) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                assertThat(n).as(c.getSimpleName() + "." + m.getName()).doesNotContain("sendmessage", "sendfriend",
                        "friendrequest", "findusers", "lookup", "broadcast", "sendtext");
            }
        }
    }

    @Test
    void messengerChiCoGuiTheoSoHoacTheoMa() {
        assertThat(names(ZaloMessenger.class)).containsExactly("sendByPhone", "sendByUid", "sendFriendRequest");
    }

    /** 24/11/2026: lời mời kết bạn (Kind.FRIEND) chỉ có MỘT nơi gửi, chỉ theo mã Zalo của người đã nhận Tin 1, qua cổng gửi chung. */
    @Test
    void loiMoiKetBanChiGuiTuMotNoiVaChiTheoMaZalo() throws IOException {
        List<String> users = new java.util.ArrayList<>();
        try (Stream<Path> all = Files.walk(Path.of("src/main/java"))) {
            for (Path f : all.filter(x -> x.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f);
                if (text.contains("Kind.FRIEND") || text.contains("sendFriendRequest")) {
                    users.add(f.getFileName().toString());
                }
            }
        }
        assertThat(users).containsExactlyInAnyOrder("ZaloFriendRequestSender.java", "ZaloMessageService.java", "ZaloMessenger.java",
                "ZcaBridgeGateway.java", "ManualZaloGateway.java", "SafeZaloLink.java");
        String sender = Files.readString(Path.of("src/main/java/vn/shop/crm/zalo/ZaloFriendRequestSender.java"));
        assertThat(sender).contains("sendFriendRequest", "friend_request_at IS NULL", "inbound_at IS NULL", "policy.blocker",
                        "testMode.onlyTestSql", "contacts.stillEligible", "UNKNOWN")
                .doesNotContain("sendByPhone", "sendByUid", "addToGroup", "createGroup", "findByPhone", "while", "Random");
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

    @Test
    void traNguoiChiCoMotHamChiDocVaKhongCoViecNhacHayKetBan() {
        assertThat(names(ZaloPersonFinder.class)).containsExactly("findByPhone");
        for (Class<?> c : List.of(ZaloPersonFinder.class, ZaloDeputyService.class)) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                assertThat(n).as(c.getSimpleName() + "." + m.getName()).doesNotContain("send", "friend", "invite", "broadcast", "scan", "list");
            }
        }
    }

    /** D19: đọc tin riêng chỉ có MỘT hàm chỉ đọc; phần nhận diện từ chối không có đường nào để trả lời khách. */
    @Test
    void docTinRiengChiCoMotHamChiDocVaKhongCoDuongTraLoiKhach() throws IOException {
        assertThat(names(ZaloInboxReader.class)).containsExactly("inbound");
        for (Class<?> c : List.of(ZaloInboxReader.class, ZaloInboxService.class)) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                assertThat(n).as(c.getSimpleName() + "." + m.getName()).doesNotContain("send", "reply", "friend", "invite", "broadcast");
            }
        }
        // phần nhận diện không được đụng tới giao diện gửi tin, thêm nhóm hay tạo nhóm
        List<Path> files = new java.util.ArrayList<>();
        try (Stream<Path> inbox = Files.walk(Path.of("src/main/java/vn/shop/crm/inbox"))) {
            files.addAll(inbox.filter(x -> x.toString().endsWith(".java")).toList());
        }
        files.add(Path.of("src/main/java/vn/shop/crm/zalo/ZaloInboxService.java"));
        for (Path f : files) {
            assertThat(Files.readString(f)).as(f.toString()).doesNotContain("ZaloMessenger", "sendByPhone", "sendByUid", "addToGroup",
                    "ZaloGroupMaker", "createGroup", "ZaloPersonFinder", "findByPhone");
        }
    }

    /** D19: tin thứ ba (WELCOME) chỉ có MỘT nơi gửi, chỉ theo mã Zalo, và phần nhận diện không tự gửi gì. */
    @Test
    void tinMauChiGuiTuMotNoiVaChiTheoMaZalo() throws IOException {
        List<Path> offenders = new java.util.ArrayList<>();
        try (Stream<Path> all = Files.walk(Path.of("src/main/java"))) {
            for (Path f : all.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).contains("Kind.WELCOME") && !f.getFileName().toString().equals("ZaloWelcomeSender.java")) {
                    offenders.add(f);
                }
            }
        }
        assertThat(offenders).isEmpty();
        String sender = Files.readString(Path.of("src/main/java/vn/shop/crm/zalo/ZaloWelcomeSender.java"));
        assertThat(sender).contains("Kind.WELCOME", "sendByUid").doesNotContain("sendByPhone", "addToGroup", "createGroup", "findByPhone");
        // phần quyết định (ComebackService) không có đường gửi tin nào
        try (Stream<Path> inbox = Files.walk(Path.of("src/main/java/vn/shop/crm/inbox"))) {
            for (Path f : inbox.filter(x -> x.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("ZaloMessageService", "ZaloMessenger", "sendByUid");
            }
        }
        // WELCOME chỉ chạy ở nấc tự động và không có vòng thử lại tự động
        assertThat(sender).contains("Mode.AUTO").doesNotContain("while", "for (");
    }

    /** D20: tin trả lời của trợ lý AI (REPLY) chỉ có MỘT nơi gửi, chỉ theo mã Zalo, chỉ ở nấc tự động; phần soạn câu không có đường gửi nào. */
    @Test
    void tinTraLoiChiGuiTuMotNoiVaChiTheoMaZalo() throws IOException {
        List<Path> offenders = new java.util.ArrayList<>();
        try (Stream<Path> all = Files.walk(Path.of("src/main/java"))) {
            for (Path f : all.filter(x -> x.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString();
                if (Files.readString(f).contains("Kind.REPLY") && !name.equals("ZaloReplySender.java")) {
                    offenders.add(f);
                }
            }
        }
        assertThat(offenders).isEmpty();
        String sender = Files.readString(Path.of("src/main/java/vn/shop/crm/zalo/ZaloReplySender.java"));
        assertThat(sender).contains("Kind.REPLY", "sendByUid", "Mode.AUTO")
                .doesNotContain("sendByPhone", "addToGroup", "createGroup", "findByPhone", "while", "for (");
        for (String f : List.of("ReplyService", "ReplyAssistant", "ReplyValidator", "ReplyInfo")) {
            assertThat(Files.readString(Path.of("src/main/java/vn/shop/crm/inbox/" + f + ".java"))).as(f)
                    .doesNotContain("ZaloMessageService", "ZaloMessenger", "sendByUid", "sendByPhone");
        }
        // người soạn câu chỉ nói chuyện với AI qua AiGate, không có đường khác ra ngoài
        assertThat(Files.readString(Path.of("src/main/java/vn/shop/crm/inbox/ReplyAssistant.java"))).contains("AiGate")
                .doesNotContain("HttpClient", "RestClient", "WebClient");
    }

    /** D21 PR 5b: tin nhắc (FOLLOWUP2/3) chỉ có MỘT nơi gửi, chỉ theo mã Zalo, chỉ khi phần đọc tin riêng chạy, ghi sổ đếm cứng trước khi gọi. */
    @Test
    void tinNhacChiGuiTuMotNoiVaChiTheoMaZalo() throws IOException {
        List<Path> offenders = new java.util.ArrayList<>();
        try (Stream<Path> all = Files.walk(Path.of("src/main/java"))) {
            for (Path f : all.filter(x -> x.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString();
                if (Files.readString(f).contains("Kind.FOLLOWUP") && !name.equals("ZaloFollowupSender.java")) {
                    offenders.add(f);
                }
            }
        }
        assertThat(offenders).isEmpty();
        String sender = Files.readString(Path.of("src/main/java/vn/shop/crm/zalo/ZaloFollowupSender.java"));
        assertThat(sender).contains("Kind.FOLLOWUP2", "Kind.FOLLOWUP3", "sendByUid", "inbox.running()", "CampaignSteps.STOP_FOOTER",
                        "INSERT INTO invitation_touch", "ON CONFLICT DO NOTHING", "inbound_at IS NULL", "policy.blocker", "testMode.onlyTestSql")
                .doesNotContain("sendByPhone", "addToGroup", "createGroup", "findByPhone", "while");
        // phần soạn tin nhắc (cấu hình) và phần nhận tin riêng không có đường gửi nào
        assertThat(Files.readString(Path.of("src/main/java/vn/shop/crm/invite/CampaignSteps.java")))
                .doesNotContain("ZaloMessenger", "sendByUid", "sendByPhone", "Kind.");
        try (Stream<Path> inbox = Files.walk(Path.of("src/main/java/vn/shop/crm/inbox"))) {
            for (Path f : inbox.filter(x -> x.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("FOLLOWUP");
            }
        }
    }

    /** D18: khoảng cách giữa hai tin là CỐ ĐỊNH. Không dùng số ngẫu nhiên để rải thời gian gửi (không né phát hiện). */
    @Test
    void khongDungSoNgauNhienTrongMaGuiTin() throws IOException {
        for (String dir : List.of("zalo", "testrun", "send", "inbox")) {
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
