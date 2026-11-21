package vn.shop.crm.send;

/**
 * Một cách đưa lời mời tới khách. Mọi bộ gửi đều đi qua {@link SendService} (cổng chặn gửi), không bao giờ được
 * gọi trực tiếp. Đường mời đang dùng là Zalo do mẹ gửi tay (ManualExportSender). Bộ gửi SMS/ZBS chưa dùng nên chưa được cài.
 */
public interface InvitationSender {

    /** Tên kênh, ghi vào nhật ký gửi. */
    String channel();

    /** True nếu bộ gửi gọi dịch vụ bên ngoài: khi đó phải có bật gửi thật và nằm trong giờ gửi. */
    boolean automated();

    /** False nếu chỉ chạy thử: không đổi trạng thái lời mời, chỉ ghi nhật ký DRY_RUN. */
    boolean realDelivery();

    /** Chi phí mỗi tin (đồng), để tính trần ngân sách tháng. */
    int costPerMessageVnd();

    SendResult send(OutboundInvite invite);

    record OutboundInvite(long invitationId, long customerId, String phone, String name, String message) {
    }

    record SendResult(boolean ok, String error) {
        public static SendResult success() {
            return new SendResult(true, null);
        }

        public static SendResult failure(String error) {
            return new SendResult(false, error);
        }
    }
}
