package vn.shop.crm.zalo;

import org.springframework.stereotype.Component;
import vn.shop.crm.zalo.ZaloMessenger.Kind;

/**
 * Chọn tài khoản Zalo phụ nhận KHÁCH MỚI (D23, chủ dự án chốt "chia đều theo chỗ trống"): khách chưa nhắn lần nào thuộc về tài khoản đang còn
 * nhiều chỗ trống nhất trong hạn mức khách mỗi ngày của chính nó, trong số các tài khoản đang được phép gửi ngay lúc này (đúng khung giờ, hết
 * khoảng nghỉ, không dừng, chưa chạm trần tổng); bằng nhau thì tài khoản mã nhỏ trước. Sau Tin 1 khách gắn CỐ ĐỊNH vào tài khoản đó
 * ({@code invitation.account_id}); không bao giờ chuyển hay nhắn bởi tài khoản khác. Đây là chia việc theo sức chứa công khai của từng
 * tài khoản, KHÔNG phải luân phiên để né: mỗi tài khoản vẫn giữ nguyên hạn mức, khoảng nghỉ cố định và cầu dao của riêng nó.
 */
@Component
public class ZaloAccountBalancer {

    private final ZaloAccountRegistry accounts;
    private final ZaloMessageService messages;

    public ZaloAccountBalancer(ZaloAccountRegistry accounts, ZaloMessageService messages) {
        this.accounts = accounts;
        this.messages = messages;
    }

    /** Tài khoản đang làm việc có được nhận một khách mới ở nhịp này không. Chỉ có một tài khoản thì luôn có. */
    public boolean mayTakeNewCustomer() {
        var active = accounts.active();
        long me = ZaloAccountContext.current();
        if (active.size() <= 1) {
            return true;
        }
        long best = -1;
        int bestFree = Integer.MIN_VALUE;
        for (long id : active) {
            int free = ZaloAccountContext.call(id, this::freeIfAble);
            if (free > bestFree) { // lặp theo mã tăng dần nên bằng nhau thì giữ tài khoản mã nhỏ
                bestFree = free;
                best = id;
            }
        }
        return best == me;
    }

    /** Chỗ trống hôm nay của tài khoản đang làm việc nếu được gửi Tin 1 ngay lúc này, ngược lại rất âm (không được chọn). */
    private int freeIfAble() {
        if (messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.INVITE, null, null).isPresent()) {
            return Integer.MIN_VALUE / 2;
        }
        return messages.limits().daily() - messages.usedToday(null);
    }
}
