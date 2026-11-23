package vn.shop.crm.zalo;

/**
 * Việc thứ sáu của bộ nối (D19): tra tên và ảnh đại diện của MỘT số điện thoại để chủ shop xác nhận đúng mẹ trước khi làm phó nhóm.
 * Chỉ đọc: không nhắn, không kết bạn. Chỉ {@link ZaloDeputyService} được gọi giao diện này (hạn mức, cầu dao, không tra lặp nằm ở đó).
 */
public interface ZaloPersonFinder {

    enum FindResult {
        OK,
        /** Không tìm thấy (không có Zalo hoặc không cho tìm bằng số), số sai. Không dừng. */
        PERSON_ERROR,
        /** Cầu nối từ chối vì quá số lần tra mỗi giờ. Chưa chạm Zalo. */
        RATE_LIMITED,
        SESSION_LOST,
        RESTRICTED,
        SYSTEM_ERROR,
        /** Chưa gọi được tới cầu nối: chưa chạm Zalo. */
        UNREACHABLE
    }

    /**
     * @param uid    mã Zalo (chỉ khi OK)
     * @param name   tên hiển thị trên Zalo (chỉ khi OK)
     * @param avatar ảnh nhỏ dạng data URI, hoặc null
     */
    record Found(FindResult result, String code, String uid, String name, String avatar, int retryAfterSeconds) {
        public static Found of(FindResult result, String code) {
            return new Found(result, code, null, null, null, 0);
        }
    }

    /** Tra số (chữ số, dạng 849...). Không bao giờ ném lỗi. */
    Found findByPhone(String phoneDigits);
}
