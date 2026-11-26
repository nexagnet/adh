package vn.shop.crm.zalo;

/** Một đường nối tới Zalo: ba việc của {@link ZaloGateway}, quản lý phiên {@link ZaloSession}, gửi tin {@link ZaloMessenger} và tạo nhóm {@link ZaloGroupMaker} tra người {@link ZaloPersonFinder} và đọc tin riêng {@link ZaloInboxReader}. Để đăng ký MỘT bean. */
public interface ZaloLink extends ZaloGateway, ZaloSession, ZaloMessenger, ZaloGroupMaker, ZaloPersonFinder, ZaloInboxReader, ZaloReactor {

    enum FriendRequestState { FRIENDS, OUTGOING, INCOMING, NONE, UNAVAILABLE }

    /** Đọc trạng thái quan hệ với đúng một mã Zalo. UNAVAILABLE nghĩa là chưa có quan sát dùng được. */
    default FriendRequestState friendRequestState(String uid) {
        return FriendRequestState.UNAVAILABLE;
    }
}
