package vn.shop.crm.zalo;

/**
 * Việc thả tim (24/11/2026, chủ shop duyệt): thả MỘT biểu tượng tim vào MỘT tin riêng mà khách vừa gửi, khi AI xác định tin đó không cần đáp. Không gửi chữ.
 * Chỉ {@link ZaloReactionSender} được gọi giao diện này. Tin được chỉ bằng số thứ tự {@code seq} của {@link ZaloInboxReader}; cầu nối giữ mã tin Zalo
 * trong bộ nhớ và chỉ thả được vào tin nó đã nhận trong 24 giờ, mỗi tin một lần.
 */
public interface ZaloReactor {

    /**
     * @param ok     đã thả tim
     * @param code   mã lỗi ngắn khi không thả được
     * @param notSent chắc chắn chưa chạm Zalo
     */
    record Reacted(boolean ok, String code, boolean notSent) {
        public static Reacted done() {
            return new Reacted(true, null, false);
        }

        public static Reacted failed(String code, boolean notSent) {
            return new Reacted(false, code, notSent);
        }
    }

    /** Thả tim vào tin đến có số thứ tự {@code seq} của người có mã Zalo {@code uid}. */
    Reacted heart(long seq, String uid);
}
