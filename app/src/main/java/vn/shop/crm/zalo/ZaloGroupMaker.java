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
     * @param deputyCode mã lỗi Zalo thật (dạng Z118) khi đặt phó nhóm lỗi; null nếu không có
     * @param notCreated cầu nối CHẮC CHẮN chưa tạo gì (từ chối trước khi chạm Zalo); false = đã tạo hoặc không biết
     * @param welcome    kết quả tin chào đầu nhóm: OK, FAILED, SKIPPED hoặc NONE (không yêu cầu gửi)
     */
    record Created(GroupOutcome outcome, String code, String groupId, String link, List<String> added, List<String> failed,
                   String deputy, String deputyCode, int retryAfterSeconds, boolean notCreated, String welcome) {
        public Created(GroupOutcome outcome, String code, String groupId, String link, List<String> added, List<String> failed,
                       String deputy, String deputyCode, int retryAfterSeconds, boolean notCreated) {
            this(outcome, code, groupId, link, added, failed, deputy, deputyCode, retryAfterSeconds, notCreated, "NONE");
        }

        public Created(GroupOutcome outcome, String code, String groupId, String link, List<String> added, List<String> failed,
                       String deputy, int retryAfterSeconds, boolean notCreated) {
            this(outcome, code, groupId, link, added, failed, deputy, null, retryAfterSeconds, notCreated);
        }

        public static Created of(GroupOutcome outcome, String code, boolean notCreated) {
            return new Created(outcome, code, null, null, List.of(), List.of(), "NONE", 0, notCreated);
        }
    }

    /**
     * Kết quả đặt lại phó nhóm cho nhóm đã tạo.
     * @param deputy OK hoặc FAILED (mọi kết quả khác OK đều coi là FAILED)
     * @param code   mã lỗi (mã Zalo thật dạng Z118 hoặc mã của cầu nối như WINDOW)
     */
    record DeputyResult(GroupOutcome outcome, String code, String deputy) {
        public static DeputyResult of(GroupOutcome outcome, String code) {
            return new DeputyResult(outcome, code, "FAILED");
        }

        public boolean ok() {
            return outcome == GroupOutcome.OK && "OK".equals(deputy);
        }
    }

    /**
     * Đặt lại MỘT phó nhóm (mẹ) cho một nhóm đã tạo: cầu nối thêm mẹ vào nhóm nếu chưa có (Zalo chỉ cho đặt phó nhóm với người đang trong nhóm)
     * rồi đặt phó nhóm. Không bao giờ ném lỗi.
     */
    DeputyResult setDeputy(String groupId, String deputyUid);

    /**
     * Tạo nhóm. Không bao giờ ném lỗi. {@code deputyUid} có thể null. {@code welcome} (có thể null) là MỘT tin chào gửi vào chính nhóm vừa tạo,
     * đúng một lần ngay sau khi tạo (chủ dự án chốt 24/11/2026: không chịu khoảng nghỉ 2 phút hay hạn mức tin); gửi lỗi không làm hỏng việc tạo.
     */
    Created createGroup(String name, List<String> memberUids, String deputyUid, String welcome);
}
