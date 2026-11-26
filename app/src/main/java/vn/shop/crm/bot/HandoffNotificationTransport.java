package vn.shop.crm.bot;

/** Cổng gửi tới nhóm Zalo nội bộ đã xác nhận. Nội dung chỉ gồm liên kết về ADH. */
public interface HandoffNotificationTransport {
    enum Outcome { SENT, REJECTED_BEFORE_SEND, UNKNOWN }

    Outcome send(long handoffId, String text);
}
