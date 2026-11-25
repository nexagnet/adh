package vn.shop.crm.zalo;

import org.springframework.stereotype.Component;
import vn.shop.crm.safety.TestMode;
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
    private final ZaloAutoService service;
    private final TestMode testMode;

    public ZaloAccountBalancer(ZaloAccountRegistry accounts, ZaloMessageService messages, ZaloAutoService service, TestMode testMode) {
        this.accounts = accounts;
        this.messages = messages;
        this.service = service;
        this.testMode = testMode;
    }

    record Allocation(boolean testNumbers, boolean ordinaryNumbers) {
    }

    private record Capacity(int free, boolean live) {
    }

    /** Ưu tiên cầu nối chạy thử cho số thử. Số thường chỉ xếp cho cầu nối chạy thật. */
    Allocation newCustomerAllocation() {
        var active = accounts.active();
        long me = ZaloAccountContext.current();
        if (active.size() <= 1) {
            boolean currentActive = active.contains(me);
            return new Allocation(currentActive, currentActive && testMode.live());
        }
        long bestPilot = -1;
        long bestOrdinary = -1;
        int mostFreePilot = Integer.MIN_VALUE;
        int mostFreeOrdinary = Integer.MIN_VALUE;
        for (long id : active) {
            Capacity capacity = ZaloAccountContext.call(id, () -> new Capacity(freeIfAble(), testMode.live()));
            if (!capacity.live() && capacity.free() > mostFreePilot) { // bằng nhau thì giữ tài khoản mã nhỏ
                mostFreePilot = capacity.free();
                bestPilot = id;
            }
            if (capacity.live() && capacity.free() > mostFreeOrdinary) {
                mostFreeOrdinary = capacity.free();
                bestOrdinary = id;
            }
        }
        long bestTest = bestPilot == -1 ? bestOrdinary : bestPilot;
        return new Allocation(bestTest == me, bestOrdinary == me);
    }

    /** Chỗ trống hôm nay của tài khoản đang làm việc nếu được gửi Tin 1 ngay lúc này, ngược lại rất âm (không được chọn). */
    private int freeIfAble() {
        if (messages.blocker(ZaloMessageService.SCOPE_REAL, Kind.INVITE, null, null).isPresent()) {
            return Integer.MIN_VALUE;
        }
        ZaloGateway.Status status = service.lastBridgeStatus();
        if (status == null) {
            status = service.bridgeStatus();
        }
        if (!status.listening() || !Boolean.TRUE.equals(status.accountOk())) {
            return Integer.MIN_VALUE;
        }
        return messages.limits().daily() - messages.usedToday(null);
    }
}
