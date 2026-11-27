package vn.shop.crm.zalo;

import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Khóa ghi lấy từ cấu hình tiến trình, nằm ngoài DB được khôi phục. */
@Component
final class ZaloRestoreFence {
    static final String CODE = "RESTORE_FENCED";

    // Mỗi kiểu kết quả dưới đây thuộc thao tác có thể thay đổi dữ liệu trên Zalo.
    private static final Map<Class<?>, Object> BLOCKED_RESULTS = Map.of(
            ZaloGateway.AddResult.class, ZaloGateway.AddResult.of(ZaloGateway.Outcome.UNREACHABLE, CODE),
            ZaloMessenger.Sent.class, ZaloMessenger.Sent.of(ZaloMessenger.Result.UNREACHABLE, CODE, true),
            ZaloGroupMaker.Created.class, ZaloGroupMaker.Created.of(ZaloGroupMaker.GroupOutcome.UNREACHABLE, CODE, true),
            ZaloGroupMaker.DeputyResult.class, ZaloGroupMaker.DeputyResult.of(ZaloGroupMaker.GroupOutcome.UNREACHABLE, CODE),
            ZaloReactor.Reacted.class, ZaloReactor.Reacted.failed(CODE, true));

    private final boolean locked;

    ZaloRestoreFence(@Value("${app.zalo.restore-fence:${APP_ZALO_RESTORE_FENCE:false}}") boolean locked) {
        this.locked = locked;
        if (locked) {
            LoggerFactory.getLogger(ZaloRestoreFence.class)
                    .warn("Khóa phục hồi Zalo đang bật. Ứng dụng chỉ đọc trạng thái cho đến khi mở khóa ngoài cơ sở dữ liệu.");
        }
    }

    boolean locked() {
        return locked;
    }

    static Object blockedResult(Class<?> resultType) {
        return BLOCKED_RESULTS.get(resultType);
    }
}
