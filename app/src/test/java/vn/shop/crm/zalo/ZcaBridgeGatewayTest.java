package vn.shop.crm.zalo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Hợp đồng HTTP giữa Adh và dịch vụ cầu nối (docs/ZALO-BRIDGE-API.md), thử với máy chủ giả trong bộ nhớ. */
class ZcaBridgeGatewayTest {

    HttpServer server;
    ZcaBridgeGateway gateway;
    final List<String> requests = new ArrayList<>();
    final List<String> keys = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
    final AtomicReference<String> reply = new AtomicReference<>("{}");
    volatile int httpStatus = 200;
    volatile long delayMillis = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            keys.add(ex.getRequestHeaders().getFirst("X-Bridge-Key"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = reply.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(httpStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        gateway = new ZcaBridgeGateway("http://127.0.0.1:" + server.getAddress().getPort(), "khoa-chung");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void trangThaiDocDuocVaGuiKhoaChung() {
        reply.set("""
                {"state":"LISTENING","tripReason":null,"accountOk":true,"lastEventAt":"2026-11-22T03:00:00Z",
                 "sessionSince":"2026-11-22T01:00:00Z","detail":null}""");
        var st = gateway.status();
        assertThat(st.state()).isEqualTo(ZaloGateway.State.LISTENING);
        assertThat(st.accountOk()).isTrue();
        assertThat(st.lastEventAt()).isNotNull();
        assertThat(keys).containsExactly("khoa-chung");
        assertThat(requests).containsExactly("GET /status");
    }

    @Test
    void trangThaiLaHoacKhongNoiDuocThiLaKhongGoiDuoc() {
        reply.set("{\"state\":\"SOMETHING_NEW\"}");
        assertThat(gateway.status().state()).isEqualTo(ZaloGateway.State.UNREACHABLE);
        httpStatus = 500;
        assertThat(gateway.status().state()).isEqualTo(ZaloGateway.State.UNREACHABLE);
        server.stop(0);
        assertThat(gateway.status().state()).isEqualTo(ZaloGateway.State.UNREACHABLE);
    }

    @Test
    void suKienDocDuocBoSuKienHongVaSapTheoSo() {
        reply.set("""
                {"events":[
                  {"seq":7,"uid":"123456","phone":"84912345678","at":"2026-11-22T03:00:00Z"},
                  {"seq":5,"uid":"654321","phone":null,"at":"2026-11-22T02:00:00Z"},
                  {"seq":9,"uid":"abc; DROP","phone":null,"at":"2026-11-22T02:00:00Z"},
                  {"uid":"111","phone":null}
                ]}""");
        var events = gateway.friendEvents(4);
        assertThat(requests).containsExactly("GET /events?after=4");
        assertThat(events).extracting(ZaloGateway.FriendEvent::seq).containsExactly(5L, 7L);
        assertThat(events.get(1).phone()).isEqualTo("84912345678");
        assertThat(events.get(0).phone()).isNull();
    }

    @Test
    void themVaoNhomGuiDungDuongDanVaBody() {
        reply.set("{\"outcome\":\"OK\"}");
        var r = gateway.addToGroup("123456", "987654");
        assertThat(r.outcome()).isEqualTo(ZaloGateway.Outcome.OK);
        assertThat(requests).containsExactly("POST /groups/987654/members");
        assertThat(bodies).containsExactly("{\"uid\":\"123456\"}");
    }

    @Test
    void maKhongPhaiChuSoThiKhongGoiGiHet() {
        for (String bad : new String[]{"12a", "", "../x", "1 2", "12345678901234567890123456789012345", null}) {
            assertThat(gateway.addToGroup(bad, "1").outcome()).isEqualTo(ZaloGateway.Outcome.PERSON_ERROR);
            assertThat(gateway.addToGroup("1", bad).outcome()).isEqualTo(ZaloGateway.Outcome.PERSON_ERROR);
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void ketQuaLaHoacHttpLoiLaLoiHeThongCauNoiTatLaKhongGoiDuoc() {
        reply.set("{\"outcome\":\"MAYBE\"}");
        assertThat(gateway.addToGroup("1", "2").outcome()).isEqualTo(ZaloGateway.Outcome.SYSTEM_ERROR);
        httpStatus = 502;
        assertThat(gateway.addToGroup("1", "2").outcome()).isEqualTo(ZaloGateway.Outcome.SYSTEM_ERROR);
        server.stop(0);
        assertThat(gateway.addToGroup("1", "2").outcome()).isEqualTo(ZaloGateway.Outcome.UNREACHABLE);
    }

    @Test
    void cauNoiKhongDuocTuNoiLaChuaChamZaloVaTrangThaiChiGiuMaAnToan() {
        reply.set("{\"outcome\":\"UNREACHABLE\"}");
        var r = gateway.addToGroup("1", "2");
        assertThat(r.outcome()).isEqualTo(ZaloGateway.Outcome.SYSTEM_ERROR);
        assertThat(r.code()).isEqualTo("BAD_REPLY");
        reply.set("{\"state\":\"TRIPPED\",\"tripReason\":\"RESTRICTED:Z1 Nguyen Van A\",\"detail\":\"0912 345 678\"}");
        var st = gateway.status();
        assertThat(st.tripReason()).isEqualTo("RESTRICTED:Z1NguyenVanA".replace(":", ""));
        assertThat(st.detail()).isEqualTo("0912345678");
    }

    @Test
    void maLoiChiGomChuSoGach() {
        reply.set("{\"outcome\":\"PERSON_ERROR\",\"code\":\"Nguyen Van A 0912345678\"}");
        var r = gateway.addToGroup("1", "2");
        assertThat(r.code()).isEqualTo("NguyenVanA0912345678".substring(0, Math.min(40, 20)));
        assertThat(r.code()).doesNotContain(" ");
    }

    @Test
    void phienQrDangXuatDungMoLai() {
        reply.set("{\"state\":\"WAITING_SCAN\",\"qr\":\"" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\"}");
        var login = gateway.startLogin();
        assertThat(login.state()).isEqualTo(ZaloSession.LoginState.WAITING_SCAN);
        assertThat(login.qrPng()).containsExactly(1, 2, 3);
        assertThat(requests).containsExactly("POST /session/login");
        reply.set("{\"ok\":true}");
        assertThat(gateway.stop()).isTrue();
        assertThat(gateway.resume()).isTrue();
        assertThat(gateway.logout()).isTrue();
        assertThat(requests).contains("POST /session/stop", "POST /session/resume", "POST /session/logout");
        reply.set("{\"ok\":false}");
        assertThat(gateway.stop()).isFalse();
    }

    @Test
    void maTaiKhoanVuaQuetChiNhanKhiSaiTaiKhoanVaChiLaChuSo() {
        reply.set("{\"state\":\"ACCOUNT_MISMATCH\",\"scannedId\":\"123456789\"}");
        assertThat(gateway.loginStatus().scannedId()).isEqualTo("123456789");
        reply.set("{\"state\":\"ACCOUNT_MISMATCH\",\"scannedId\":\"<script>1</script>\"}");
        assertThat(gateway.loginStatus().scannedId()).isNull();
        reply.set("{\"state\":\"WAITING_SCAN\",\"scannedId\":\"123456789\"}");
        assertThat(gateway.loginStatus().scannedId()).isNull();
    }

    @Test
    void danhSachNhomDocDuocBoDongHongVaKhongLoMaLa() {
        reply.set("""
                {"ok":true,"groups":[
                  {"id":"222","name":"Nhóm B","members":3,"adminOnlyAdd":true,"isAdmin":false},
                  {"id":"x; DROP","name":"Hỏng","members":1},
                  {"id":"111","name":"nhóm a","members":7,"adminOnlyAdd":false,"isAdmin":true}]}""");
        ZaloSession.GroupList l = gateway.listGroups();
        assertThat(requests).containsExactly("GET /groups");
        assertThat(keys).containsExactly("khoa-chung");
        assertThat(l.ok()).isTrue();
        assertThat(l.groups()).extracting(ZaloSession.ZaloGroup::id).containsExactly("111", "222");
        assertThat(l.groups().get(1).adminOnlyAdd()).isTrue();
        reply.set("{\"ok\":false,\"code\":\"LOGIN_REQUIRED\"}");
        assertThat(gateway.listGroups().code()).isEqualTo("LOGIN_REQUIRED");
        httpStatus = 500;
        assertThat(gateway.listGroups().code()).isEqualTo("LIST_FAILED");
    }

    // ---------- POST /messages (D18) ----------

    @Test
    void guiTinGuiDungDuongDanBodyVaDocKetQua() {
        reply.set("{\"outcome\":\"OK\",\"uid\":\"123456\"}");
        var r = gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "84912345678", "Xin chào");
        assertThat(r.result()).isEqualTo(ZaloMessenger.Result.OK);
        assertThat(r.uid()).isEqualTo("123456");
        assertThat(r.notSent()).isFalse();
        assertThat(requests).containsExactly("POST /messages");
        assertThat(bodies.get(0)).contains("\"kind\":\"INVITE\"", "\"phone\":\"84912345678\"", "Xin chào");
        assertThat(keys).containsExactly("khoa-chung");

        reply.set("{\"outcome\":\"OK\"}");
        gateway.sendByUid(ZaloMessenger.Kind.LINK, "654321", "link");
        assertThat(bodies.get(1)).contains("\"kind\":\"LINK\"", "\"uid\":\"654321\"").doesNotContain("phone");
    }

    @Test
    void guiTinDauVaoSaiKhongGoiBoNoi() {
        assertThat(gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "abc", "x").code()).isEqualTo("BAD_PHONE");
        assertThat(gateway.sendByUid(ZaloMessenger.Kind.INVITE, "1; DROP", "x").code()).isEqualTo("BAD_ID");
        assertThat(requests).isEmpty();
    }

    @Test
    void biGioiHanTocDoLaChacChanChuaGuiVaCoThoiGianCho() {
        reply.set("{\"outcome\":\"RATE_LIMITED\",\"retryAfterSec\":45}");
        var r = gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "84912345678", "x");
        assertThat(r.result()).isEqualTo(ZaloMessenger.Result.RATE_LIMITED);
        assertThat(r.notSent()).isTrue();
        assertThat(r.retryAfterSeconds()).isEqualTo(45);
    }

    @Test
    void maTuChoiTruocKhiChamZaloLaChuaGuiConLoiLaKhongBiet() {
        reply.set("{\"outcome\":\"SYSTEM_ERROR\",\"code\":\"BUSY\"}");
        assertThat(gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "84912345678", "x").notSent()).isTrue();
        reply.set("{\"outcome\":\"SYSTEM_ERROR\",\"code\":\"NO_ACK\"}");
        assertThat(gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "84912345678", "x").notSent()).isFalse();
        reply.set("{\"outcome\":\"RESTRICTED\",\"code\":\"MSG_LIMIT\"}");
        assertThat(gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "84912345678", "x").notSent()).isTrue();
        reply.set("{\"outcome\":\"MOT_GI_LA\"}");
        var bad = gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "84912345678", "x");
        assertThat(bad.result()).isEqualTo(ZaloMessenger.Result.SYSTEM_ERROR);
        assertThat(bad.notSent()).isFalse();
    }

    @Test
    void coBoNoiBaoUnreachableThiAdhKhongTinMaChiTuQuyet() {
        reply.set("{\"outcome\":\"UNREACHABLE\"}");
        var r = gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "84912345678", "x");
        assertThat(r.result()).isEqualTo(ZaloMessenger.Result.SYSTEM_ERROR);
        assertThat(r.notSent()).isFalse();
    }

    @Test
    void boNoiSapLaChuaGuiNhungHong500LaKhongBiet() {
        server.stop(0);
        var down = gateway.sendByPhone(ZaloMessenger.Kind.INVITE, "84912345678", "x");
        assertThat(down.result()).isEqualTo(ZaloMessenger.Result.UNREACHABLE);
        assertThat(down.notSent()).isTrue();
    }

    // ---------- tạo nhóm (D19) ----------

    @Test
    void taoNhomGuiDungBodyVaDocKetQuaChiLayNguoiTrongDanhSach() {
        reply.set("""
                {"outcome":"OK","groupId":"9876","link":"https://zalo.me/g/abc","added":["111","999"],"failed":["222"],"deputy":"OK"}""");
        var r = gateway.createGroup("Pin ADH - Miền Nam - 01", List.of("111", "222"), "333");
        assertThat(requests).containsExactly("POST /groups");
        assertThat(keys).containsExactly("khoa-chung");
        assertThat(bodies.getFirst()).contains("\"name\":\"Pin ADH - Miền Nam - 01\"").contains("\"members\":[\"111\",\"222\"]").contains("\"deputy\":\"333\"");
        assertThat(r.outcome()).isEqualTo(ZaloGroupMaker.GroupOutcome.OK);
        assertThat(r.groupId()).isEqualTo("9876");
        assertThat(r.link()).isEqualTo("https://zalo.me/g/abc");
        assertThat(r.added()).containsExactly("111"); // "999" không nằm trong danh sách đã gửi: bỏ
        assertThat(r.failed()).containsExactly("222");
        assertThat(r.deputy()).isEqualTo("OK");
    }

    @Test
    void taoNhomDauVaoSaiThiKhongGoiCauNoi() {
        assertThat(gateway.createGroup("", List.of("111"), null).code()).isEqualTo("BAD_NAME");
        assertThat(gateway.createGroup("x", List.of(), null).code()).isEqualTo("BAD_MEMBERS");
        assertThat(gateway.createGroup("x", List.of("1; DROP"), null).code()).isEqualTo("BAD_MEMBERS");
        assertThat(gateway.createGroup("x", List.of("1", "2", "3", "4", "5", "6"), null).code()).isEqualTo("BAD_MEMBERS");
        assertThat(gateway.createGroup("x", List.of("111"), "abc").code()).isEqualTo("BAD_DEPUTY");
        assertThat(requests).isEmpty();
    }

    @Test
    void taoNhomPhanLoaiChuaTaoVaKhongBiet() {
        reply.set("{\"outcome\":\"RATE_LIMITED\",\"retryAfterSec\":120}");
        var limited = gateway.createGroup("x", List.of("111"), null);
        assertThat(limited.outcome()).isEqualTo(ZaloGroupMaker.GroupOutcome.RATE_LIMITED);
        assertThat(limited.notCreated()).isTrue();
        assertThat(limited.retryAfterSeconds()).isEqualTo(120);
        reply.set("{\"outcome\":\"RESTRICTED\",\"code\":\"GROUP_LIMIT\"}");
        assertThat(gateway.createGroup("x", List.of("111"), null).notCreated()).isTrue();
        reply.set("{\"outcome\":\"SYSTEM_ERROR\",\"code\":\"NO_ACK\"}");
        assertThat(gateway.createGroup("x", List.of("111"), null).notCreated()).isFalse();
        reply.set("{\"outcome\":\"OK\",\"groupId\":\"abc\"}"); // thiếu mã nhóm hợp lệ: nhóm có thể đã tạo, dừng
        var noId = gateway.createGroup("x", List.of("111"), null);
        assertThat(noId.outcome()).isEqualTo(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR);
        assertThat(noId.notCreated()).isFalse();
        reply.set("{\"outcome\":\"UNREACHABLE\"}"); // cầu nối không được tự nói "không nối được"
        assertThat(gateway.createGroup("x", List.of("111"), null).outcome()).isEqualTo(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR);
        server.stop(0);
        var down = gateway.createGroup("x", List.of("111"), null);
        assertThat(down.outcome()).isEqualTo(ZaloGroupMaker.GroupOutcome.UNREACHABLE);
        assertThat(down.notCreated()).isTrue();
    }

    @Test
    void taoNhomLinkLaThiBoVaPhoNhomLaThiLaKhong() {
        reply.set("{\"outcome\":\"OK\",\"groupId\":\"9876\",\"link\":\"http://x\",\"added\":[],\"failed\":[\"111\"],\"deputy\":\"lạ\"}");
        var r = gateway.createGroup("x", List.of("111"), null);
        assertThat(r.link()).isNull();
        assertThat(r.deputy()).isEqualTo("NONE");
    }

    // ---------- tra người để xác nhận mẹ (D19) ----------

    private static final String AVATAR = "data:image/png;base64,iVBORw0KGgo=";

    @Test
    void traNguoiGuiDungSoVaDocTenAnhHopLe() {
        reply.set("{\"outcome\":\"OK\",\"uid\":\"900\",\"name\":\"Mẹ\\u0007 Lan\",\"avatar\":\"" + AVATAR + "\"}");
        var r = gateway.findByPhone("84900000009");
        assertThat(requests).containsExactly("POST /people/lookup");
        assertThat(keys).containsExactly("khoa-chung");
        assertThat(bodies.getFirst()).isEqualTo("{\"phone\":\"84900000009\"}");
        assertThat(r.result()).isEqualTo(ZaloPersonFinder.FindResult.OK);
        assertThat(r.uid()).isEqualTo("900");
        assertThat(r.name()).isEqualTo("Mẹ  Lan");
        assertThat(r.avatar()).isEqualTo(AVATAR);
    }

    @Test
    void traNguoiBoAnhLaVaKhongNhanMaSai() {
        reply.set("{\"outcome\":\"OK\",\"uid\":\"900\",\"name\":\"A\",\"avatar\":\"https://evil.example/x.png\"}");
        assertThat(gateway.findByPhone("84900000009").avatar()).isNull(); // chỉ nhận ảnh nhỏ dạng data URI, không mở nguồn ngoài
        reply.set("{\"outcome\":\"OK\",\"uid\":\"9;x\",\"name\":\"A\"}");
        assertThat(gateway.findByPhone("84900000009").result()).isEqualTo(ZaloPersonFinder.FindResult.SYSTEM_ERROR);
        reply.set("{\"outcome\":\"UNREACHABLE\"}"); // cầu nối không được tự nói "không nối được"
        assertThat(gateway.findByPhone("84900000009").result()).isEqualTo(ZaloPersonFinder.FindResult.SYSTEM_ERROR);
        reply.set("{\"outcome\":\"PERSON_ERROR\",\"code\":\"NOT_FOUND\"}");
        var nf = gateway.findByPhone("84900000009");
        assertThat(nf.result()).isEqualTo(ZaloPersonFinder.FindResult.PERSON_ERROR);
        assertThat(nf.code()).isEqualTo("NOT_FOUND");
        reply.set("{\"outcome\":\"RATE_LIMITED\",\"retryAfterSec\":1800}");
        assertThat(gateway.findByPhone("84900000009").retryAfterSeconds()).isEqualTo(1800);
    }

    @Test
    void traNguoiSoSaiKhongGoiCauNoiVaCauNoiTatThiUnreachable() {
        assertThat(gateway.findByPhone("12").code()).isEqualTo("BAD_PHONE");
        assertThat(gateway.findByPhone("84; DROP").code()).isEqualTo("BAD_PHONE");
        assertThat(gateway.findByPhone(null).code()).isEqualTo("BAD_PHONE");
        assertThat(requests).isEmpty();
        server.stop(0);
        assertThat(gateway.findByPhone("84900000009").result()).isEqualTo(ZaloPersonFinder.FindResult.UNREACHABLE);
    }
}
