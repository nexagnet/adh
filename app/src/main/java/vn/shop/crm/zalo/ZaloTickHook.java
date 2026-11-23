package vn.shop.crm.zalo;

/** Chỗ cắm cho phần chạy thử: được gọi mỗi nhịp của bộ hẹn giờ, SAU việc của chiến dịch thật. Lỗi không làm hỏng nhịp. */
public interface ZaloTickHook {
    void tick();
}
