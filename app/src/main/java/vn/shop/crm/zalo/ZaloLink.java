package vn.shop.crm.zalo;

/** Một đường nối tới Zalo, đủ cả ba việc của {@link ZaloGateway} và quản lý phiên của {@link ZaloSession}. Để đăng ký MỘT bean. */
public interface ZaloLink extends ZaloGateway, ZaloSession {
}
