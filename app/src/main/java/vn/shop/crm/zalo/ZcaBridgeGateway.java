package vn.shop.crm.zalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gọi dịch vụ cầu nối Node (zca-js) qua HTTP nội bộ có khóa chung. Hợp đồng ở {@code docs/ZALO-BRIDGE-API.md}.
 * Chỉ tạo khi bật {@code app.zalo.auto.enabled}. Không bao giờ ghi số điện thoại, tên hay uid vào log.
 *
 * <p>Lỗi theo thứ tự an toàn: không nối được tới cầu nối = {@link Outcome#UNREACHABLE} (chưa chạm Zalo, không dừng);
 * hết giờ chờ lúc đang thêm người = không biết kết quả = {@link Outcome#SYSTEM_ERROR} (thất bại kín, dừng);
 * bất kỳ câu trả lời lạ nào cũng là {@link Outcome#SYSTEM_ERROR}.
 */
public class ZcaBridgeGateway implements ZaloLink {

    private static final Logger log = LoggerFactory.getLogger(ZcaBridgeGateway.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Mã Zalo (người dùng và nhóm) là chuỗi số; không số thì không gọi, để đường dẫn/body không thể bị chèn gì khác. */
    static final Pattern ZALO_ID = Pattern.compile("\\d{1,32}");

    private final HttpClient http;
    private final String base;
    private final String key;

    public ZcaBridgeGateway(String bridgeUrl, String bridgeKey) {
        this(bridgeUrl, bridgeKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).build());
    }

    ZcaBridgeGateway(String bridgeUrl, String bridgeKey, HttpClient http) {
        this.base = bridgeUrl.endsWith("/") ? bridgeUrl.substring(0, bridgeUrl.length() - 1) : bridgeUrl;
        this.key = bridgeKey;
        this.http = http;
    }

    // ---------- ZaloGateway: ba việc ----------

    @Override
    public Status status() {
        try {
            JsonNode n = call("GET", "/status", null, 10);
            return new Status(state(n.path("state").asString("")), text(n, "tripReason"),
                    n.hasNonNull("accountOk") ? n.path("accountOk").asBoolean() : null,
                    instant(n, "lastEventAt"), instant(n, "sessionSince"), text(n, "detail"));
        } catch (Unreachable e) {
            return Status.of(State.UNREACHABLE);
        } catch (Exception e) {
            log.warn("Cầu nối trả trạng thái lạ: {}", e.getClass().getSimpleName());
            return new Status(State.UNREACHABLE, null, null, null, null, "Câu trả lời lạ");
        }
    }

    @Override
    public List<FriendEvent> friendEvents(long afterSeq) {
        try {
            JsonNode n = call("GET", "/events?after=" + Math.max(0, afterSeq), null, 10);
            List<FriendEvent> out = new ArrayList<>();
            for (JsonNode e : n.path("events")) {
                String uid = e.path("uid").asString("");
                long seq = e.path("seq").asLong(-1);
                if (seq < 0 || !ZALO_ID.matcher(uid).matches()) {
                    continue; // sự kiện hỏng: bỏ qua, không đoán
                }
                out.add(new FriendEvent(seq, uid, text(e, "phone"), instant(e, "at") == null ? Instant.now() : instant(e, "at")));
            }
            out.sort(java.util.Comparator.comparingLong(FriendEvent::seq));
            return out;
        } catch (Exception e) {
            log.warn("Không đọc được sự kiện từ cầu nối: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public AddResult addToGroup(String uid, String zaloGroupId) {
        if (uid == null || zaloGroupId == null || !ZALO_ID.matcher(uid).matches() || !ZALO_ID.matcher(zaloGroupId).matches()) {
            return AddResult.of(Outcome.PERSON_ERROR, "BAD_ID");
        }
        try {
            String body = JSON.writeValueAsString(java.util.Map.of("uid", uid));
            JsonNode n = call("POST", "/groups/" + zaloGroupId + "/members", body, 45);
            Outcome outcome;
            try {
                outcome = Outcome.valueOf(n.path("outcome").asString(""));
            } catch (IllegalArgumentException e) {
                return AddResult.of(Outcome.SYSTEM_ERROR, "BAD_REPLY");
            }
            return AddResult.of(outcome, safeCode(text(n, "code")));
        } catch (Unreachable e) {
            return AddResult.of(Outcome.UNREACHABLE, "BRIDGE_DOWN");
        } catch (TimedOut e) {
            // Không biết đã thêm hay chưa: coi là lỗi hệ thống để dừng, người kiểm tra nhóm bằng mắt.
            return AddResult.of(Outcome.SYSTEM_ERROR, "TIMEOUT");
        } catch (Exception e) {
            log.warn("Lỗi khi thêm người vào nhóm qua cầu nối: {}", e.getClass().getSimpleName());
            return AddResult.of(Outcome.SYSTEM_ERROR, "BRIDGE_ERROR");
        }
    }

    // ---------- ZaloSession: phiên đăng nhập ----------

    @Override
    public Login startLogin() {
        return login("POST");
    }

    @Override
    public Login loginStatus() {
        return login("GET");
    }

    private Login login(String method) {
        try {
            JsonNode n = call(method, "/session/login", method.equals("POST") ? "{}" : null, 20);
            LoginState state;
            try {
                state = LoginState.valueOf(n.path("state").asString(""));
            } catch (IllegalArgumentException e) {
                state = LoginState.FAILED;
            }
            byte[] png = null;
            String qr = text(n, "qr");
            if (qr != null && !qr.isBlank()) {
                png = Base64.getDecoder().decode(qr);
            }
            return new Login(state, png);
        } catch (Exception e) {
            return Login.of(LoginState.FAILED);
        }
    }

    @Override
    public boolean logout() {
        return post("/session/logout");
    }

    @Override
    public boolean stop() {
        return post("/session/stop");
    }

    @Override
    public boolean resume() {
        return post("/session/resume");
    }

    private boolean post(String path) {
        try {
            return call("POST", path, "{}", 20).path("ok").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- HTTP ----------

    private static final class Unreachable extends RuntimeException {
        Unreachable(Throwable cause) {
            super(cause);
        }
    }

    private static final class TimedOut extends RuntimeException {
        TimedOut(Throwable cause) {
            super(cause);
        }
    }

    private JsonNode call(String method, String path, String body, int timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("X-Bridge-Key", key).header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IOException("HTTP " + r.statusCode());
            }
            return JSON.readTree(r.body());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new Unreachable(e);
        } catch (HttpTimeoutException e) {
            throw new TimedOut(e);
        } catch (ClosedChannelException e) {
            throw new Unreachable(e);
        }
    }

    private static State state(String raw) {
        try {
            return State.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return State.UNREACHABLE;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString();
    }

    private static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Mã lỗi chỉ gồm chữ, số, gạch: không để chuỗi lạ (có thể chứa dữ liệu khách) lọt vào nhật ký. */
    static String safeCode(String code) {
        if (code == null) {
            return null;
        }
        String c = code.replaceAll("[^A-Za-z0-9_\\-]", "");
        return c.isEmpty() ? null : c.substring(0, Math.min(40, c.length()));
    }
}
