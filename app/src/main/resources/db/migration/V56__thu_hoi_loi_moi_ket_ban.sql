-- Tự thu hồi lời mời kết bạn đã gửi mà quá N ngày chưa ai đồng ý (25/11/2026, chủ dự án duyệt) để danh sách lời mời đang chờ của Zalo luôn còn chỗ (Z223).
--   friend_undo_at: khóa chống thu hồi hai lần; friend_undo_outcome DONE (đã thu hồi), FAILED (Zalo từ chối, ví dụ lời mời không còn) hoặc UNKNOWN (không rõ, KHÔNG tự thử lại).
--   friend_request_outcome thêm WITHDRAWN: lời mời đã được thu hồi, không còn tính là đang chờ. Dòng khách và số điện thoại giữ nguyên.
ALTER TABLE zalo_msg_log DROP CONSTRAINT zalo_msg_log_kind_check;
ALTER TABLE zalo_msg_log ADD CONSTRAINT zalo_msg_log_kind_check
    CHECK (kind IN ('INVITE', 'LINK', 'WELCOME', 'REPLY', 'FOLLOWUP2', 'FOLLOWUP3', 'FRIEND', 'ASK_FRIEND', 'UNDO_FRIEND'));
ALTER TABLE invitation DROP CONSTRAINT IF EXISTS invitation_friend_request_outcome_check;
ALTER TABLE invitation ADD CONSTRAINT invitation_friend_request_outcome_check
    CHECK (friend_request_outcome IN ('SENT', 'FAILED', 'UNKNOWN', 'WITHDRAWN'));
ALTER TABLE invitation ADD COLUMN friend_undo_at TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN friend_undo_outcome TEXT CHECK (friend_undo_outcome IN ('DONE', 'FAILED', 'UNKNOWN'));
