package vn.shop.crm.zalo;

/** Một đường nối tới Zalo: ba việc của {@link ZaloGateway}, quản lý phiên {@link ZaloSession} và gửi tin {@link ZaloMessenger}. Để đăng ký MỘT bean. */
public interface ZaloLink extends ZaloGateway, ZaloSession, ZaloMessenger {
}
