package vn.shop.crm.zalo;

/** Một đường nối tới Zalo: ba việc của {@link ZaloGateway}, quản lý phiên {@link ZaloSession}, gửi tin {@link ZaloMessenger} và tạo nhóm {@link ZaloGroupMaker} và tra người {@link ZaloPersonFinder}. Để đăng ký MỘT bean. */
public interface ZaloLink extends ZaloGateway, ZaloSession, ZaloMessenger, ZaloGroupMaker, ZaloPersonFinder {
}
