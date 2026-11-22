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
}
