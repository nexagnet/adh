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
            return new Status(state(n.path("state").asString("")), safeCode(text(n, "tripReason")),
                    n.hasNonNull("accountOk") ? n.path("accountOk").asBoolean() : null,
                    instant(n, "lastEventAt"), instant(n, "sessionSince"), safeCode(text(n, "detail")));
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
    public List<Inbound> inbound(long afterSeq) {
        try {
            JsonNode n = call("GET", "/inbox?after=" + Math.max(0, afterSeq), null, 10);
            if (!n.path("enabled").asBoolean(false)) {
                return List.of();
            }
            List<Inbound> out = new ArrayList<>();
            for (JsonNode e : n.path("messages")) {
                String uid = e.path("uid").asString("");
                String text = e.path("text").asString("");
                long seq = e.path("seq").asLong(-1);
                if (seq < 0 || !ZALO_ID.matcher(uid).matches() || text.isBlank()) {
                    continue; // tin hỏng: bỏ qua, không đoán
                }
                Instant at = instant(e, "at");
                out.add(new Inbound(seq, uid, text.length() > 500 ? text.substring(0, 500) : text, at == null ? Instant.now() : at));
            }
            out.sort(java.util.Comparator.comparingLong(Inbound::seq));
            return out;
        } catch (Exception e) {
            log.warn("Không đọc được tin riêng từ cầu nối: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public FriendCheck friendsAmong(List<String> uids) {
        if (uids == null || uids.isEmpty() || uids.size() > 500 || !uids.stream().allMatch(u -> u != null && ZALO_ID.matcher(u).matches())) {
            return FriendCheck.failed("BAD_INPUT");
        }
        try {
            JsonNode n = call("POST", "/friends/check", JSON.writeValueAsString(java.util.Map.of("uids", uids)), 60);
            if (!n.path("ok").asBoolean(false)) {
                return FriendCheck.failed(safeCode(text(n, "code")) == null ? "FAILED" : safeCode(text(n, "code")));
            }
            java.util.Set<String> asked = new java.util.HashSet<>(uids);
            List<String> out = new ArrayList<>();
            for (JsonNode f : n.path("friends")) {
                String uid = f.asString("");
                if (asked.contains(uid)) { // chỉ nhận lại đúng những mã đã hỏi, bỏ mọi mã khác
                    out.add(uid);
                }
            }
            return FriendCheck.of(out);
        } catch (Unreachable e) {
            return FriendCheck.failed("BRIDGE_DOWN");
        } catch (Exception e) {
            log.warn("Không đối chiếu được bạn bè qua cầu nối: {}", e.getClass().getSimpleName());
            return FriendCheck.failed("BRIDGE_ERROR");
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
            if (outcome == Outcome.UNREACHABLE) {
                return AddResult.of(Outcome.SYSTEM_ERROR, "BAD_REPLY"); // chỉ chính Adh quyết "không nối được", cầu nối không được nói vậy
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

    // ---------- ZaloMessenger: việc thứ tư (D18), gửi MỘT tin cho MỘT người ----------

    /** Mã không gửi được vì chính cầu nối từ chối TRƯỚC khi chạm Zalo (xem bridge.mjs): chắc chắn chưa có tin nào đi. */
    private static final java.util.Set<String> NOT_SENT_CODES = java.util.Set.of("BUSY", "NOT_CONNECTED", "NOT_ACTIVE", "WINDOW",
            "MSG_LIMIT", "FRIEND_LIMIT", "LOGIN_REQUIRED", "BAD_INPUT", "BAD_PHONE", "BAD_ID", "NOT_REPLYABLE",
            "NO_INVITE", "NO_PREVIOUS", "CUSTOMER_REPLIED");

    @Override
    public Sent sendByPhone(Kind kind, String phoneDigits, String text) {
        if (kind.followup()) {
            return Sent.of(Result.PERSON_ERROR, "BAD_INPUT", true); // tin nhắc chỉ theo mã Zalo của người đã nhận Tin 1
        }
        if (phoneDigits == null || !phoneDigits.matches("\\d{8,15}")) {
            return Sent.of(Result.PERSON_ERROR, "BAD_PHONE", true);
        }
        return message(java.util.Map.of("kind", kind.name(), "phone", phoneDigits, "text", String.valueOf(text)));
    }

    @Override
    public Sent sendByUid(Kind kind, String uid, String text) {
        if (uid == null || !ZALO_ID.matcher(uid).matches()) {
            return Sent.of(Result.PERSON_ERROR, "BAD_ID", true);
        }
        return message(java.util.Map.of("kind", kind.name(), "uid", uid, "text", String.valueOf(text)));
    }

    /** Lời mời kết bạn: chỉ theo mã Zalo của người đã nhận Tin 1 (cầu nối kiểm lại), câu tối đa 150 ký tự. */
    @Override
    public Sent sendFriendRequest(String uid, String text) {
        if (uid == null || !ZALO_ID.matcher(uid).matches()) {
            return Sent.of(Result.PERSON_ERROR, "BAD_ID", true);
        }
        if (text == null || text.isBlank() || text.strip().length() > 150) {
            return Sent.of(Result.PERSON_ERROR, "BAD_INPUT", true);
        }
        return message("/friend-requests", java.util.Map.of("uid", uid, "text", text.strip()));
    }

    private Sent message(java.util.Map<String, String> body) {
        return message("/messages", body);
    }

    private Sent message(String path, java.util.Map<String, String> body) {
        try {
            JsonNode n = call("POST", path, JSON.writeValueAsString(body), 90);
            Result result;
            try {
                result = Result.valueOf(n.path("outcome").asString(""));
            } catch (IllegalArgumentException e) {
                return Sent.of(Result.SYSTEM_ERROR, "BAD_REPLY", false);
            }
            if (result == Result.UNREACHABLE) {
                return Sent.of(Result.SYSTEM_ERROR, "BAD_REPLY", false); // chỉ chính Adh quyết "không nối được"
            }
            String code = safeCode(text(n, "code"));
            String uid = text(n, "uid");
            if (uid != null && !ZALO_ID.matcher(uid).matches()) {
                uid = null;
            }
            boolean notSent = result == Result.RATE_LIMITED
                    || ((result == Result.RESTRICTED || result == Result.SESSION_LOST || result == Result.SYSTEM_ERROR
                    || result == Result.PERSON_ERROR) && code != null && NOT_SENT_CODES.contains(code));
            int retry = Math.max(0, Math.min(3600, n.path("retryAfterSec").asInt(0)));
            return new Sent(result, code, uid, retry, notSent);
        } catch (Unreachable e) {
            return Sent.of(Result.UNREACHABLE, "BRIDGE_DOWN", true);
        } catch (TimedOut e) {
            return Sent.of(Result.SYSTEM_ERROR, "TIMEOUT", false); // không biết đã gửi hay chưa
        } catch (Exception e) {
            log.warn("Lỗi khi gửi tin qua cầu nối: {}", e.getClass().getSimpleName());
            return Sent.of(Result.SYSTEM_ERROR, "BRIDGE_ERROR", false);
        }
    }

    // ---------- ZaloGroupMaker: việc thứ năm (D19), tạo MỘT nhóm ----------

    /** Mã cầu nối từ chối TRƯỚC khi chạm Zalo (xem bridge.mjs createGroup): chắc chắn chưa tạo nhóm nào. */
    private static final java.util.Set<String> NOT_CREATED_CODES = java.util.Set.of("BAD_NAME", "BAD_MEMBERS", "BAD_DEPUTY", "LOGIN_REQUIRED",
            "NOT_ACTIVE", "NOT_CONNECTED", "BUSY", "WINDOW", "GROUP_LIMIT");

    @Override
    public Created createGroup(String name, List<String> memberUids, String deputyUid) {
        if (name == null || name.isBlank() || name.length() > 100) {
            return Created.of(ZaloGroupMaker.GroupOutcome.PERSON_ERROR, "BAD_NAME", true);
        }
        if (memberUids == null || memberUids.isEmpty() || memberUids.size() > 5
                || memberUids.stream().anyMatch(u -> u == null || !ZALO_ID.matcher(u).matches())) {
            return Created.of(ZaloGroupMaker.GroupOutcome.PERSON_ERROR, "BAD_MEMBERS", true);
        }
        if (deputyUid != null && !ZALO_ID.matcher(deputyUid).matches()) {
            return Created.of(ZaloGroupMaker.GroupOutcome.PERSON_ERROR, "BAD_DEPUTY", true);
        }
        try {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("name", name);
            body.put("members", memberUids);
            if (deputyUid != null) {
                body.put("deputy", deputyUid);
            }
            JsonNode n = call("POST", "/groups", JSON.writeValueAsString(body), 120);
            ZaloGroupMaker.GroupOutcome outcome;
            try {
                outcome = ZaloGroupMaker.GroupOutcome.valueOf(n.path("outcome").asString(""));
            } catch (IllegalArgumentException e) {
                return Created.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "BAD_REPLY", false);
            }
            if (outcome == ZaloGroupMaker.GroupOutcome.UNREACHABLE) {
                return Created.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "BAD_REPLY", false); // chỉ chính Adh quyết "không nối được"
            }
            String code = safeCode(text(n, "code"));
            if (outcome != ZaloGroupMaker.GroupOutcome.OK) {
                int retry = Math.max(0, Math.min(86400, n.path("retryAfterSec").asInt(0)));
                boolean notCreated = outcome == ZaloGroupMaker.GroupOutcome.RATE_LIMITED || (code != null && NOT_CREATED_CODES.contains(code));
                return new Created(outcome, code, null, null, List.of(), List.of(), "NONE", retry, notCreated);
            }
            String groupId = text(n, "groupId");
            if (groupId == null || !ZALO_ID.matcher(groupId).matches()) {
                return Created.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "BAD_REPLY", false); // nhóm có thể đã tạo nhưng không có mã: dừng
            }
            String link = text(n, "link");
            if (link != null && !link.startsWith("https://")) {
                link = null;
            }
            String deputy = text(n, "deputy");
            if (!"OK".equals(deputy) && !"FAILED".equals(deputy)) {
                deputy = "NONE";
            }
            return new Created(outcome, null, groupId, link, uidList(n.path("added"), memberUids), uidList(n.path("failed"), memberUids),
                    deputy, "FAILED".equals(deputy) ? safeCode(text(n, "deputyCode")) : null, 0, false);
        } catch (Unreachable e) {
            return Created.of(ZaloGroupMaker.GroupOutcome.UNREACHABLE, "BRIDGE_DOWN", true);
        } catch (TimedOut e) {
            return Created.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "TIMEOUT", false); // không biết đã tạo hay chưa
        } catch (Exception e) {
            log.warn("Lỗi khi tạo nhóm qua cầu nối: {}", e.getClass().getSimpleName());
            return Created.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "BRIDGE_ERROR", false);
        }
    }

    @Override
    public DeputyResult setDeputy(String groupId, String deputyUid) {
        if (groupId == null || !ZALO_ID.matcher(groupId).matches() || deputyUid == null || !ZALO_ID.matcher(deputyUid).matches()) {
            return DeputyResult.of(ZaloGroupMaker.GroupOutcome.PERSON_ERROR, "BAD_ID");
        }
        try {
            JsonNode n = call("POST", "/groups/" + groupId + "/deputy", JSON.writeValueAsString(java.util.Map.of("uid", deputyUid)), 60);
            ZaloGroupMaker.GroupOutcome outcome;
            try {
                outcome = ZaloGroupMaker.GroupOutcome.valueOf(n.path("outcome").asString(""));
            } catch (IllegalArgumentException e) {
                return DeputyResult.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "BAD_REPLY");
            }
            if (outcome == ZaloGroupMaker.GroupOutcome.UNREACHABLE) {
                return DeputyResult.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "BAD_REPLY");
            }
            String code = safeCode(text(n, "code"));
            if (outcome != ZaloGroupMaker.GroupOutcome.OK) {
                return DeputyResult.of(outcome, code);
            }
            return new DeputyResult(outcome, code, "OK".equals(text(n, "deputy")) ? "OK" : "FAILED");
        } catch (Unreachable e) {
            return DeputyResult.of(ZaloGroupMaker.GroupOutcome.UNREACHABLE, "BRIDGE_DOWN");
        } catch (TimedOut e) {
            return DeputyResult.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "TIMEOUT");
        } catch (Exception e) {
            log.warn("Lỗi khi đặt phó nhóm qua cầu nối: {}", e.getClass().getSimpleName());
            return DeputyResult.of(ZaloGroupMaker.GroupOutcome.SYSTEM_ERROR, "BRIDGE_ERROR");
        }
    }

    /** Chỉ nhận những mã nằm trong danh sách đã gửi đi: cầu nối không thể "thêm" người lạ vào kết quả. */
    private static List<String> uidList(JsonNode arr, List<String> allowed) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : arr) {
            String v = e.asString("");
            if (allowed.contains(v) && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    // ---------- ZaloPersonFinder: việc thứ sáu (D19), tra MỘT số để xác nhận mẹ ----------

    private static final java.util.regex.Pattern AVATAR = java.util.regex.Pattern.compile("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]{1,150000}$");

    @Override
    public Found findByPhone(String phoneDigits) {
        if (phoneDigits == null || !phoneDigits.matches("\\d{8,15}")) {
            return Found.of(FindResult.PERSON_ERROR, "BAD_PHONE");
        }
        try {
            JsonNode n = call("POST", "/people/lookup", JSON.writeValueAsString(java.util.Map.of("phone", phoneDigits)), 40);
            FindResult result;
            try {
                result = FindResult.valueOf(n.path("outcome").asString(""));
            } catch (IllegalArgumentException e) {
                return Found.of(FindResult.SYSTEM_ERROR, "BAD_REPLY");
            }
            if (result == FindResult.UNREACHABLE) {
                return Found.of(FindResult.SYSTEM_ERROR, "BAD_REPLY"); // chỉ chính Adh quyết "không nối được"
            }
            String code = safeCode(text(n, "code"));
            if (result != FindResult.OK) {
                int retry = Math.max(0, Math.min(3600, n.path("retryAfterSec").asInt(0)));
                return new Found(result, code, null, null, null, retry);
            }
            String uid = text(n, "uid");
            if (uid == null || !ZALO_ID.matcher(uid).matches()) {
                return Found.of(FindResult.SYSTEM_ERROR, "BAD_REPLY");
            }
            String name = text(n, "name");
            name = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").strip();
            if (name.length() > 120) {
                name = name.substring(0, 120);
            }
            String avatar = text(n, "avatar");
            if (avatar != null && !AVATAR.matcher(avatar).matches()) {
                avatar = null; // chỉ nhận ảnh nhỏ đúng dạng, không nhận đường dẫn ngoài
            }
            return new Found(FindResult.OK, null, uid, name, avatar, 0);
        } catch (Unreachable e) {
            return Found.of(FindResult.UNREACHABLE, "BRIDGE_DOWN");
        } catch (TimedOut e) {
            return Found.of(FindResult.SYSTEM_ERROR, "TIMEOUT");
        } catch (Exception e) {
            log.warn("Lỗi khi tra người qua cầu nối: {}", e.getClass().getSimpleName());
            return Found.of(FindResult.SYSTEM_ERROR, "BRIDGE_ERROR");
        }
    }

    // ---------- ZaloSession: phiên đăng nhập ----------

    @Override
    public GroupList listGroups() {
        try {
            JsonNode n = call("GET", "/groups", null, 60);
            if (!n.path("ok").asBoolean(false)) {
                String code = safeCode(text(n, "code"));
                return GroupList.failed(code == null ? "LIST_FAILED" : code);
            }
            List<ZaloGroup> out = new ArrayList<>();
            for (JsonNode g : n.path("groups")) {
                String id = g.path("id").asString("");
                if (!ZALO_ID.matcher(id).matches()) {
                    continue; // dòng hỏng: bỏ qua, không đoán
                }
                String name = g.path("name").asString("").strip();
                out.add(new ZaloGroup(id, name.isEmpty() ? "(không tên)" : name.substring(0, Math.min(120, name.length())),
                        g.path("members").asInt(0), g.path("adminOnlyAdd").asBoolean(false), g.path("isAdmin").asBoolean(false)));
            }
            out.sort(java.util.Comparator.comparing(ZaloGroup::name, String.CASE_INSENSITIVE_ORDER));
            return new GroupList(true, null, out);
        } catch (Unreachable e) {
            return GroupList.failed("BRIDGE_DOWN");
        } catch (Exception e) {
            log.warn("Không đọc được danh sách nhóm từ cầu nối: {}", e.getClass().getSimpleName());
            return GroupList.failed("LIST_FAILED");
        }
    }

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
            String scanned = text(n, "scannedId");
            if (state != LoginState.ACCOUNT_MISMATCH || scanned == null || !ZALO_ID.matcher(scanned).matches()) {
                scanned = null; // chỉ nhận mã chữ số, và chỉ khi sai tài khoản
            }
            return new Login(state, png, scanned);
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
        }
        // Mọi lỗi khác (đứt giữa chừng, kênh đóng...) có thể xảy ra SAU khi yêu cầu đã tới cầu nối: không coi là "chưa chạm Zalo".
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
