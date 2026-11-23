package vn.shop.crm.zalo;

import java.time.Instant;
import java.util.List;

/**
 * Việc thứ bảy của bộ nối (D19): đọc tin nhắn RIÊNG (1-1) mà người đã nhận Tin 1 hoặc Tin 2 gửi lại cho tài khoản phụ, để AI nhận ra khách
 * từ chối. Chỉ đọc. Cầu nối tự lọc: tin của người chưa từng nhận tin của hệ thống bị bỏ ngay ở đó. Không đọc tin nhóm, không đọc lịch sử.
 * Chỉ {@link ZaloInboxService} được gọi giao diện này; nội dung tin không bao giờ vào log.
 */
public interface ZaloInboxReader {

    /**
     * @param seq  số thứ tự tăng dần do cầu nối cấp; Adh nhớ số lớn nhất đã xử lý
     * @param text nội dung (tối đa 500 ký tự), CHƯA che: phải qua PiiMasker trước khi đưa cho AI hoặc lưu
     */
    record Inbound(long seq, String uid, String text, Instant at) {
    }

    /** Tin mới có số thứ tự lớn hơn {@code afterSeq}, tăng dần. Rỗng nếu tắt, không có hoặc không gọi được. Không bao giờ ném lỗi. */
    List<Inbound> inbound(long afterSeq);
}
