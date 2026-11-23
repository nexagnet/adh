package vn.shop.crm.zalo;

/**
 * Việc thứ tư của bộ nối (D18, ADR-0002 mục 12): gửi MỘT tin văn bản cho MỘT khách đã mua, qua tài khoản Zalo phụ. Tách khỏi
 * {@link ZaloGateway} (vẫn đúng ba việc). Không có gửi lời mời kết bạn, không gửi hàng loạt, không đính kèm.
 * Chỉ {@link vn.shop.crm.zalo.ZaloMessageService} được gọi giao diện này: chốt chặn (hạn mức, giờ, khoảng nghỉ, cầu dao) nằm ở đó.
 */
public interface ZaloMessenger {

    enum Kind {
        /** Tin 1: mời kết bạn để vào nhóm, gửi theo số điện thoại. */
        INVITE,
        /** Tin 2: link nhóm, gửi theo mã Zalo của khách đã thành bạn. */
        LINK,
        /** Tin thứ ba (D19): MỘT tin mẫu cố định trả lời người từng từ chối mà vừa TỰ nhắn tới muốn mua. Chỉ theo mã Zalo, chỉ cho người vừa nhắn trước. */
        WELCOME,
        /** Tin thứ tư (D20): câu trả lời do trợ lý AI soạn (đã qua kiểm tra cứng) cho người vừa TỰ nhắn tới. Chỉ theo mã Zalo, cùng chốt chặn như WELCOME. */
        REPLY
    }

    enum Result {
        OK,
        /** Lỗi riêng người nhận (không tìm thấy trên Zalo, đã gửi loại tin này rồi...). Không dừng. */
        PERSON_ERROR,
        /** Cầu nối từ chối vì gửi quá sớm sau tin trước. Chưa gửi, thử lại sau. */
        RATE_LIMITED,
        SESSION_LOST,
        RESTRICTED,
        /** Lỗi hệ thống không nhận ra, kể cả không biết kết quả (hết giờ chờ, Zalo không xác nhận): dừng. */
        SYSTEM_ERROR,
        /** Chưa gọi được tới cầu nối: chưa chạm Zalo. */
        UNREACHABLE
    }

    /**
     * @param uid mã Zalo của người nhận khi cầu nối biết (tin 1 theo số điện thoại trả về mã tra được)
     * @param notSent cầu nối CHẮC CHẮN chưa gửi gì (từ chối trước khi chạm Zalo); false = đã gửi hoặc không biết
     */
    record Sent(Result result, String code, String uid, int retryAfterSeconds, boolean notSent) {
        public static Sent of(Result result, String code, boolean notSent) {
            return new Sent(result, code, null, 0, notSent);
        }
    }

    /** Tin 1: tra số điện thoại (chữ số, dạng 849...) rồi gửi. */
    Sent sendByPhone(Kind kind, String phoneDigits, String text);

    /** Tin 2: gửi cho mã Zalo đã biết. */
    Sent sendByUid(Kind kind, String uid, String text);
}
