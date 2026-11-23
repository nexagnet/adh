package vn.shop.crm.zalo;

/**
 * Chỗ cắm cho phần chạy thử: được xem một sự kiện kết bạn TRƯỚC khi ghép với khách thật. Trả true nếu sự kiện thuộc về
 * một số thử (đã xử lý, không ghép với khách thật nữa). Không có bean nào cắm vào thì luồng khách thật không đổi.
 * Gọi trong giao dịch của {@link ZaloAutoService#ingest}.
 */
public interface ZaloFriendTap {
    boolean accept(ZaloGateway.FriendEvent event);
}
