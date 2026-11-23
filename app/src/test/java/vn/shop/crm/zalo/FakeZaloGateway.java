package vn.shop.crm.zalo;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bộ nối GIẢ cho test và chạy thử không cần Zalo thật. Có đúng ba việc của {@link ZaloGateway} cộng quản lý phiên và gửi tin; ghi lại
 * mọi lệnh gọi để test kiểm tra "không gọi gì" khi bộ nối tắt.
 */
public class FakeZaloGateway implements ZaloLink {

    public Status status = new Status(State.LISTENING, null, true, null, Instant.parse("2026-11-22T00:00:00Z"), null);
    private final List<FriendEvent> events = new ArrayList<>();
    private final Deque<AddResult> results = new ArrayDeque<>();
    public AddResult defaultResult = AddResult.ok();
    public Login login = Login.of(LoginState.IDLE);

    public int statusCalls;
    public int eventCalls;
    public final List<String[]> adds = new ArrayList<>();
    public int stops;
    public int resumes;
    public boolean resumeResult = true;
    public boolean eventsFail;
    public int logouts;

    /** Đưa về trạng thái ban đầu giữa hai test (bean dùng chung trong một ngữ cảnh Spring). */
    public void reset() {
        status = new Status(State.LISTENING, null, true, null, Instant.parse("2026-11-22T00:00:00Z"), null);
        events.clear();
        results.clear();
        defaultResult = AddResult.ok();
        login = Login.of(LoginState.IDLE);
        statusCalls = 0;
        eventCalls = 0;
        adds.clear();
        stops = 0;
        resumes = 0;
        resumeResult = true;
        eventsFail = false;
        logouts = 0;
        groupList = GroupList.failed("LOGIN_REQUIRED");
        groupListCalls = 0;
        messages.clear();
        messageResults.clear();
        defaultMessage = null;
        phoneToUid.clear();
        uidSeq = 0;
        creates.clear();
        createResults.clear();
        defaultCreate = null;
        finds.clear();
        findResults.clear();
        defaultFind = null;
    }

    // ---------- tạo nhóm (D19) ----------

    public record Create(String name, List<String> members, String deputy) {
    }

    public final List<Create> creates = new ArrayList<>();
    private final Deque<Created> createResults = new ArrayDeque<>();
    /** Mặc định: tạo OK, mã nhóm 5555, có link, mọi người vào được. */
    public Created defaultCreate;

    public void willCreate(Created... r) {
        createResults.addAll(List.of(r));
    }

    @Override
    public Created createGroup(String name, List<String> memberUids, String deputyUid) {
        creates.add(new Create(name, List.copyOf(memberUids), deputyUid));
        if (!createResults.isEmpty()) {
            return createResults.poll();
        }
        if (defaultCreate != null) {
            return defaultCreate;
        }
        return new Created(ZaloGroupMaker.GroupOutcome.OK, null, "5555", "https://zalo.me/g/fake5555", List.copyOf(memberUids), List.of(),
                deputyUid == null ? "NONE" : "OK", 0, false);
    }

    // ---------- tra người để xác nhận mẹ (D19) ----------

    public final List<String> finds = new ArrayList<>();
    private final Deque<Found> findResults = new ArrayDeque<>();
    /** Mặc định (null): không tìm thấy. */
    public Found defaultFind;

    public void willFind(Found... r) {
        findResults.addAll(List.of(r));
    }

    @Override
    public Found findByPhone(String phoneDigits) {
        finds.add(phoneDigits);
        if (!findResults.isEmpty()) {
            return findResults.poll();
        }
        return defaultFind != null ? defaultFind : Found.of(FindResult.PERSON_ERROR, "NOT_FOUND");
    }

    // ---------- gửi tin (D18) ----------

    /** Một lần gọi gửi tin: loại, số điện thoại (hoặc null), mã Zalo (hoặc null), nội dung. */
    public record Msg(Kind kind, String phone, String uid, String text) {
    }

    public final List<Msg> messages = new ArrayList<>();
    private final Deque<Sent> messageResults = new ArrayDeque<>();
    /** Mặc định: gửi OK, trả mã Zalo theo số điện thoại. */
    public Sent defaultMessage;
    public final java.util.Map<String, String> phoneToUid = new java.util.HashMap<>();
    private int uidSeq;

    public void willReturnMessage(Sent... r) {
        messageResults.addAll(List.of(r));
    }

    @Override
    public Sent sendByPhone(Kind kind, String phoneDigits, String text) {
        messages.add(new Msg(kind, phoneDigits, null, text));
        if (!messageResults.isEmpty()) {
            return messageResults.poll();
        }
        if (defaultMessage != null) {
            return defaultMessage;
        }
        String uid = phoneToUid.computeIfAbsent(phoneDigits, p -> "9" + (100000 + ++uidSeq));
        return new Sent(Result.OK, null, uid, 0, false);
    }

    @Override
    public Sent sendByUid(Kind kind, String uid, String text) {
        messages.add(new Msg(kind, null, uid, text));
        if (!messageResults.isEmpty()) {
            return messageResults.poll();
        }
        return defaultMessage != null ? defaultMessage : new Sent(Result.OK, null, uid, 0, false);
    }

    public void push(FriendEvent e) {
        events.add(e);
    }

    public void pushAdd(String uid, String phone, long seq) {
        events.add(new FriendEvent(seq, uid, phone, Instant.parse("2026-11-21T03:00:00Z").plusSeconds(seq)));
    }

    public void willReturn(AddResult... r) {
        results.addAll(List.of(r));
    }

    public int totalCalls() {
        return statusCalls + eventCalls + adds.size() + stops + resumes + logouts + messages.size() + creates.size() + finds.size();
    }

    @Override
    public Status status() {
        statusCalls++;
        return status;
    }

    @Override
    public List<FriendEvent> friendEvents(long afterSeq) {
        eventCalls++;
        if (eventsFail) {
            throw new IllegalStateException("giả lập lỗi");
        }
        return events.stream().filter(e -> e.seq() > afterSeq).toList();
    }

    @Override
    public AddResult addToGroup(String uid, String zaloGroupId) {
        adds.add(new String[]{uid, zaloGroupId});
        return results.isEmpty() ? defaultResult : results.poll();
    }

    public GroupList groupList = GroupList.failed("LOGIN_REQUIRED");
    public int groupListCalls;

    @Override
    public GroupList listGroups() {
        groupListCalls++;
        return groupList;
    }

    @Override
    public Login startLogin() {
        return login;
    }

    @Override
    public Login loginStatus() {
        return login;
    }

    @Override
    public boolean logout() {
        logouts++;
        return true;
    }

    @Override
    public boolean stop() {
        stops++;
        return true;
    }

    @Override
    public boolean resume() {
        resumes++;
        return resumeResult;
    }
}
