package vn.shop.crm.zalo;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bộ nối GIẢ cho test và chạy thử không cần Zalo thật. Có đúng ba việc của {@link ZaloGateway} cộng quản lý phiên; ghi lại
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
        logouts = 0;
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
        return statusCalls + eventCalls + adds.size() + stops + resumes + logouts;
    }

    @Override
    public Status status() {
        statusCalls++;
        return status;
    }

    @Override
    public List<FriendEvent> friendEvents(long afterSeq) {
        eventCalls++;
        return events.stream().filter(e -> e.seq() > afterSeq).toList();
    }

    @Override
    public AddResult addToGroup(String uid, String zaloGroupId) {
        adds.add(new String[]{uid, zaloGroupId});
        return results.isEmpty() ? defaultResult : results.poll();
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
        return true;
    }
}
