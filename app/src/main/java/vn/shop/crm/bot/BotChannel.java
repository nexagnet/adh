package vn.shop.crm.bot;

/**
 * Cổng gửi tin của bot vào nhóm. Chưa có bản cài thật: kênh (Zalo Bot/GMF/zca-js) chỉ được chọn sau spike
 * (docs/ADR-0001-chon-kenh-bot.md) và chủ cửa hàng đồng ý. Bản mặc định không gửi gì.
 */
public interface BotChannel {

    /** Trả true nếu tin đã thật sự được gửi vào nhóm. */
    boolean send(long groupId, String text);
}
