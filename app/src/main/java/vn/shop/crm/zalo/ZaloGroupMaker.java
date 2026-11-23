package vn.shop.crm.zalo;

import java.util.List;

/**
 * Việc thứ năm của bộ nối (D19): tạo MỘT nhóm Zalo với vài khách đã đồng ý vào nhóm, bật link mời và thêm mẹ làm phó nhóm. Tách khỏi
 * {@link ZaloGateway} (vẫn đúng ba việc). Chỉ {@link ZaloGroupCreationService} được gọi giao diện này: chốt chặn (nấc, trần mỗi ngày,
 * khoảng nghỉ, khung giờ, cầu dao, duyệt) nằm ở đó. Không có đổi chủ nhóm, đổi tên, xóa nhóm hay đuổi người.
 */
public interface ZaloGroupMaker {

    enum GroupOutcome {
        OK,
        /** Đầu vào sai (tên, danh sách thành viên, phó nhóm): chưa chạm Zalo. */
        PERSON_ERROR,
        /** Cầu nối từ chối vì tạo quá sớm sau lần trước. Chắc chắn chưa tạo. */
        RATE_LIMITED,
        SESSION_LOST,
        RESTRICTED,
        /** Mọi lỗi không nhận ra, kể cả không biết kết quả (hết giờ chờ, Zalo không trả mã nhóm): dừng. */
        SYSTEM_ERROR,
        /** Chưa gọi được tới cầu nối: chưa chạm Zalo. */
        UNREACHABLE
    }

    /**
     * @param groupId    mã nhóm Zalo vừa tạo (chỉ khi OK)
     * @param link       link mời https://zalo.me/..., null nếu bật link lỗi
     * @param added      mã Zalo của những người Zalo xác nhận đã vào nhóm
     * @param failed     những người chưa vào được
     * @param deputy     OK, FAILED hoặc NONE
     * @param notCreated cầu nối CHẮC CHẮN chưa tạo gì (từ chối trước khi chạm Zalo); false = đã tạo hoặc không biết
     */
    record Created(GroupOutcome outcome, String code, String groupId, String link, List<String> added, List<String> failed,
                   String deputy, int retryAfterSeconds, boolean notCreated) {
        public static Created of(GroupOutcome outcome, String code, boolean notCreated) {
            return new Created(outcome, code, null, null, List.of(), List.of(), "NONE", 0, notCreated);
        }
    }

    /** Tạo nhóm. Không bao giờ ném lỗi. {@code deputyUid} có thể null. */
    Created createGroup(String name, List<String> memberUids, String deputyUid);
}
