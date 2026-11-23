package vn.shop.crm.zalo;

import java.util.List;

/**
 * Bản mặc định: KHÔNG làm gì và không gọi gì ra ngoài. Khi bộ nối tắt, luồng D13/D15 vẫn là bán tự động:
 * người cầm tài khoản phụ nhắn, kết bạn và thêm vào nhóm bằng tay rồi bấm đánh dấu trên trang quản trị.
 */
public class ManualZaloGateway implements ZaloLink {

    @Override
    public Status status() {
        return Status.of(State.DISABLED);
    }

    @Override
    public List<FriendEvent> friendEvents(long afterSeq) {
        return List.of();
    }

    @Override
    public AddResult addToGroup(String uid, String zaloGroupId) {
        return AddResult.of(Outcome.UNREACHABLE, "DISABLED");
    }

    @Override
    public GroupList listGroups() {
        return GroupList.failed("DISABLED");
    }

    @Override
    public Login startLogin() {
        return Login.of(LoginState.IDLE);
    }

    @Override
    public Login loginStatus() {
        return Login.of(LoginState.IDLE);
    }

    @Override
    public boolean logout() {
        return false;
    }

    @Override
    public boolean stop() {
        return false;
    }

    @Override
    public boolean resume() {
        return false;
    }

    @Override
    public Sent sendByPhone(Kind kind, String phoneDigits, String text) {
        return Sent.of(Result.UNREACHABLE, "DISABLED", true);
    }

    @Override
    public Sent sendByUid(Kind kind, String uid, String text) {
        return Sent.of(Result.UNREACHABLE, "DISABLED", true);
    }

    @Override
    public Created createGroup(String name, java.util.List<String> memberUids, String deputyUid) {
        return Created.of(ZaloGroupMaker.GroupOutcome.UNREACHABLE, "DISABLED", true);
    }
}
