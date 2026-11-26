package vn.shop.crm.zalo;

/**
 * Việc thứ tư của bộ nối (D18, ADR-0002 mục 12): gửi MỘT tin văn bản cho MỘT khách đã mua, qua tài khoản Zalo phụ. Tách khỏi
 * {@link ZaloGateway} (vẫn đúng ba việc). Từ 24/11/2026 có thêm lời mời kết bạn cho người đã nhận Tin 1 ({@link #sendFriendRequest}). Không gửi hàng loạt, không đính kèm.
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
        REPLY,
        /** Tin nhắc lần hai của chiến dịch nhiều lần gửi (D21): chỉ theo mã Zalo của người ĐÃ nhận Tin 1, tính vào hạn mức ngày như Tin 1. */
        FOLLOWUP2,
        /** Tin nhắc lần ba (D21), cùng luật như {@link #FOLLOWUP2}; cầu nối chỉ nhận khi lần hai đã gửi. */
        FOLLOWUP3,
        /** Lời mời kết bạn (24/11/2026): tài khoản phụ chủ động kết bạn với người ĐÃ nhận Tin 1. Chỉ theo mã Zalo; trần riêng mỗi ngày, không tính vào hạn mức khách nhắn chủ động. */
        FRIEND,
        /** Tin nhờ khách chủ động kết bạn lại (25/11/2026): chỉ gửi cho người ĐÃ nhận Tin 1 mà lời mời kết bạn không gửi được vì Zalo báo danh sách lời mời đầy (Z223). Chỉ theo mã Zalo, mỗi người một lần. */
        ASK_FRIEND,
        /** Thu hồi một lời mời kết bạn đã gửi mà quá N ngày chưa ai đồng ý (25/11/2026), để danh sách lời mời đang chờ của Zalo còn chỗ. Không nhắn gì cho người đó. */
        UNDO_FRIEND,
        /** Nhận lời mời kết bạn của khách đã nhận Tin 1 mà vừa tự gửi lời mời tới tài khoản phụ (25/11/2026). Chỉ theo mã Zalo, mỗi người một lần. */
        ACCEPT_FRIEND;

        /** Tin do shop chủ động nhắn khách (không phải trả lời, không phải Tin 2): tính vào hạn mức ngày. */
        public boolean proactive() {
            return this == INVITE || this == FOLLOWUP2 || this == FOLLOWUP3;
        }

        /** Tin trả lời người vừa TỰ nhắn tới (tin mẫu hoặc câu do trợ lý AI soạn): có khoảng nghỉ cố định riêng, ngắn hơn. */
        public boolean answersInbound() {
            return this == WELCOME || this == REPLY || this == ACCEPT_FRIEND;
        }

        public boolean followup() {
            return this == FOLLOWUP2 || this == FOLLOWUP3;
        }
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

    /** Trả lời sau lần kiểm cuối cùng tại biên gửi. */
    default Sent sendReply(String uid, String text, java.util.function.BooleanSupplier stillEligible) {
        return stillEligible.getAsBoolean() ? sendByUid(Kind.REPLY, uid, text) : replyNoLongerEligible();
    }

    static Sent replyNoLongerEligible() {
        return Sent.of(Result.PERSON_ERROR, "REPLY_NO_LONGER_ELIGIBLE", true);
    }

    /** Lời mời kết bạn kèm câu ngắn (tối đa 150 ký tự) cho MỘT người đã nhận Tin 1 (theo mã Zalo). */
    Sent sendFriendRequest(String uid, String text);

    /** Thu hồi MỘT lời mời kết bạn mà chính bộ nối đã gửi cho người này (chưa ai đồng ý). Không nhắn gì. */
    Sent undoFriendRequest(String uid);

    /** Nhận MỘT lời mời kết bạn mà người này (đã nhận Tin 1 từ chính bộ nối) vừa tự gửi tới tài khoản phụ. Bộ nối kiểm lại. Không nhắn gì. */
    Sent acceptFriendRequest(String uid);
}
