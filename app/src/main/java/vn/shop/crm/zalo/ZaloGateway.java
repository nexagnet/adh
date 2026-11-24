package vn.shop.crm.zalo;

import java.time.Instant;
import java.util.List;

/**
 * Giao diện duy nhất giữa Adh và Zalo (D16, ADR-0002 mục 3.1). Chỉ có BA việc, và cố ý không có hàm nào khác:
 * báo trạng thái, đọc sự kiện "đã thành bạn", thêm MỘT người vào MỘT nhóm. Không có gửi tin, gửi lời mời kết bạn,
 * tra số hàng loạt hay lệnh tùy ý. Mọi thứ còn lại của Adh không biết thư viện nào nằm bên dưới.
 */
public interface ZaloGateway {

    enum State {
        /** Chưa bật hoặc chưa cấu hình: không có cầu nối. */
        DISABLED,
        /** Gọi không tới cầu nối (chưa chạy, mạng nội bộ lỗi). Không phải lỗi của Zalo. */
        UNREACHABLE,
        /** Chưa có phiên Zalo hoặc phiên hết hạn/bị đăng xuất: cần người quét QR. */
        LOGIN_REQUIRED,
        CONNECTING,
        LISTENING,
        /** Cầu nối tự dừng (cầu dao phía cầu nối) và đang không làm gì. {@code tripReason} nói vì sao. */
        TRIPPED,
        /** Cầu nối được yêu cầu dừng, còn giữ phiên nhưng không nghe. */
        STOPPED
    }

    /**
     * @param tripReason  lý do cầu nối tự dừng, nếu có (mã ngắn, không chứa dữ liệu khách)
     * @param accountOk   tài khoản đang đăng nhập có đúng là tài khoản phụ đã khai báo không (null = chưa biết)
     * @param lastEventAt lần cuối nhận được một sự kiện kết bạn
     */
    record Status(State state, String tripReason, Boolean accountOk, Instant lastEventAt, Instant sessionSince,
                  String detail) {
        public static Status of(State state) {
            return new Status(state, null, null, null, null, null);
        }

        public boolean listening() {
            return state == State.LISTENING;
        }
    }

    /**
     * Một lần ai đó thành bạn của tài khoản phụ (loại ADD). Chỉ có mã Zalo; số điện thoại chỉ có khi người đó cho phép
     * (đo thật: khoảng 5/32 bạn hiện số), tên KHÔNG có. Bộ nối chỉ hỏi số của đúng mã này, không dò người khác.
     *
     * @param seq số thứ tự tăng dần do cầu nối cấp; Adh nhớ số lớn nhất đã nhận và hỏi tiếp từ đó
     */
    record FriendEvent(long seq, String uid, String phone, Instant at) {
    }

    enum Outcome {
        OK,
        /** Lỗi riêng người này (đã ở trong nhóm, bị chặn, nhóm đầy, nhóm chỉ quản trị viên được thêm...). Không làm cầu dao nhảy. */
        PERSON_ERROR,
        /** Lỗi của CẢ NHÓM khi thêm người (nhóm không còn, mình không đủ quyền...); chỉ có ở việc thêm vào nhóm. Không làm cầu dao nhảy ngay: Adh tạm dừng nhóm đó. */
        GROUP_ERROR,
        /** Phiên hết hạn/bị đăng xuất: dừng, cần quét QR. */
        SESSION_LOST,
        /** Zalo yêu cầu xác minh/captcha hoặc báo hạn chế, hoặc chạm trần của chính cầu nối: dừng ngay. */
        RESTRICTED,
        /** Mọi lỗi cấp hệ thống không nhận ra, kể cả không biết kết quả (hết giờ chờ): thất bại kín, dừng ngay. */
        SYSTEM_ERROR,
        /** Chưa gọi được tới Zalo (cầu nối không chạy). Chưa chạm Zalo nên không tính hạn mức, không dừng. */
        UNREACHABLE
    }

    record AddResult(Outcome outcome, String code) {
        public static AddResult ok() {
            return new AddResult(Outcome.OK, null);
        }

        public static AddResult of(Outcome outcome, String code) {
            return new AddResult(outcome, code);
        }
    }

    /** Trạng thái hiện tại. Không bao giờ ném lỗi: không gọi được thì trả {@link State#UNREACHABLE}. */
    Status status();

    /** Các sự kiện kết bạn có số thứ tự lớn hơn {@code afterSeq}, theo thứ tự tăng dần. Rỗng nếu không có hoặc không gọi được. */
    List<FriendEvent> friendEvents(long afterSeq);

    /**
     * Kết quả đối chiếu bạn bè (24/11/2026, chủ shop duyệt, CHỈ ĐỌC). {@code friends} chỉ gồm những mã Adh đã hỏi mà đang là bạn của tài khoản phụ.
     * {@code ok = false}: chưa đối chiếu được (mã lý do ngắn, không chứa dữ liệu khách); không phải lỗi của Zalo và không làm cầu dao nhảy.
     */
    record FriendCheck(boolean ok, String code, List<String> friends) {
        public static FriendCheck failed(String code) {
            return new FriendCheck(false, code, List.of());
        }

        public static FriendCheck of(List<String> friends) {
            return new FriendCheck(true, null, List.copyOf(friends));
        }
    }

    /**
     * Trong số các mã Zalo đã hỏi (tối đa 500, đều là khách đã nhận Tin 1), mã nào đã là bạn của tài khoản phụ. Chỉ đọc: không gửi, không
     * nhận lời mời, không lưu danh sách bạn; người không nằm trong danh sách hỏi không bao giờ được trả về. Không bao giờ ném lỗi.
     */
    FriendCheck friendsAmong(List<String> uids);

    /** Thêm một người đã là bạn vào một nhóm (mã nhóm Zalo). Không bao giờ ném lỗi; mọi kết quả nằm trong {@link AddResult}. */
    AddResult addToGroup(String uid, String zaloGroupId);
}
