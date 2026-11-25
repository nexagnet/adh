package vn.shop.crm.zalo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalTime;

/**
 * Cấu hình bộ nối Zalo không chính thức (D16, ADR-0002). MẶC ĐỊNH TẮT: không đặt gì thì không có lệnh gọi Zalo nào.
 * Hạn mức mặc định do chủ shop chốt 22/11/2026 (50 người/ngày, rải 07:00–22:00, tối đa 5/giờ, cách nhau ≥ 2 phút);
 * trên trang quản trị chỉnh được đến các trần cứng ở đây (muốn nâng trần phải đổi cấu hình triển khai).
 */
@ConfigurationProperties(prefix = "app.zalo.auto")
public record ZaloAutoProperties(
        /* Công tắc thứ nhất (biến môi trường APP_ZALO_AUTO_ENABLED). Công tắc thứ hai là nấc chọn trên trang quản trị. */
        @DefaultValue("false") boolean enabled,
        /* Địa chỉ dịch vụ cầu nối (mạng nội bộ Docker) và khóa chung. */
        @DefaultValue("http://zalo-bridge:3100") String bridgeUrl,
        @DefaultValue("") String bridgeKey,
        @DefaultValue("07:00") LocalTime windowStart,
        @DefaultValue("22:00") LocalTime windowEnd,
        @DefaultValue("150") int defaultDaily,
        @DefaultValue("15") int defaultHourly,
        @DefaultValue("2") int defaultGapMinutes,
        /* Trần cứng: trang quản trị không cho chỉnh vượt các số này. */
        @DefaultValue("300") int maxDaily,
        @DefaultValue("30") int maxHourly,
        /* Khoảng cách tối thiểu giữa hai lần thêm mà trang quản trị cho đặt (không cho đặt thấp hơn). */
        @DefaultValue("2") int minGapFloorMinutes,
        /* D18: tự gửi tin cho khách. Khung giờ hẹp hơn khung giờ thêm nhóm, hạn mức nhỏ, khoảng nghỉ cố định (không ngẫu nhiên). */
        @DefaultValue("08:00") LocalTime msgWindowStart,
        @DefaultValue("22:00") LocalTime msgWindowEnd,
        @DefaultValue("10") int msgDefaultDaily,
        @DefaultValue("120") int msgDefaultGapSeconds,
        /* Trần cứng của số KHÁCH được nhắn chủ động mỗi ngày (chỉ Tin 1; Tin 2 và tin mẫu trả lời không tính, 23/11/2026: 50 khách/ngày)
           và sàn của khoảng nghỉ; trang quản trị không chỉnh vượt các số này. Cầu nối có phanh khẩn cấp riêng đếm MỌI tin (BRIDGE_MSG_DAILY,
           mặc định 150) và khoảng nghỉ tối thiểu riêng (BRIDGE_MSG_GAP_SEC, phải ≤ sàn này). */
        @DefaultValue("50") int msgMaxDaily,
        @DefaultValue("100") int msgMinGapSeconds,
        /* Lời mời kết bạn (24/11/2026): trần mỗi ngày mặc định thấp (10) vì Zalo dễ hạn chế tài khoản gửi kết bạn cho người lạ; trần cứng 50. 0 = tắt hẳn lời mời kết bạn. */
        @DefaultValue("10") int friendDefaultDaily,
        @DefaultValue("50") int friendMaxDaily,
        /* Tự thu hồi lời mời kết bạn quá số ngày chưa ai đồng ý (25/11/2026): mặc định 7 ngày (0 = tắt, chỉnh ở /zalo), tối đa mỗi ngày bao nhiêu lần thu hồi
           (cầu nối có trần riêng BRIDGE_UNDO_DAILY, phải >= số này). */
        @DefaultValue("7") int friendWithdrawDays,
        @DefaultValue("20") int friendUndoDaily,
        /* Nhận lời mời kết bạn của khách (25/11/2026): tối đa mỗi ngày bao nhiêu lần nhận (cầu nối có trần riêng BRIDGE_ACCEPT_DAILY, phải >= số này). */
        @DefaultValue("40") int friendAcceptDaily,
        /* Đối chiếu danh sách bạn của tài khoản phụ (chỉ đọc, 24/11/2026): bao nhiêu phút một lần (và ngay khi cầu nối nối lại), tối thiểu 10. */
        @DefaultValue("20") int friendSyncMinutes) {

    public ZaloAutoProperties {
        if (maxDaily < 1) {
            maxDaily = 1;
        }
        if (maxHourly < 1) {
            maxHourly = 1;
        }
        if (minGapFloorMinutes < 1) {
            minGapFloorMinutes = 1;
        }
        if (msgMaxDaily < 1) {
            msgMaxDaily = 1;
        }
        if (msgMinGapSeconds < 30) {
            msgMinGapSeconds = 30;
        }
        if (friendSyncMinutes < 10) {
            friendSyncMinutes = 10;
        }
        if (friendWithdrawDays < 0 || friendWithdrawDays > 90) {
            friendWithdrawDays = 7;
        }
        if (friendAcceptDaily < 1) {
            friendAcceptDaily = 1;
        }
        if (friendUndoDaily < 1) {
            friendUndoDaily = 1;
        }
        if (friendMaxDaily < 1) {
            friendMaxDaily = 1;
        }

        if (friendDefaultDaily < 0 || friendDefaultDaily > friendMaxDaily) {
            friendDefaultDaily = Math.min(10, friendMaxDaily);
        }
    }
}
