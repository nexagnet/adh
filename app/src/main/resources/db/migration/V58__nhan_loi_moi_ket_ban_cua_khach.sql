-- Tự NHẬN lời mời kết bạn của khách đã nhận Tin 1 mà vừa tự gửi lời mời tới tài khoản phụ (25/11/2026, chủ dự án duyệt).
--   friend_accept_at: khóa chống nhận hai lần; friend_accept_outcome DONE (đã nhận), FAILED (Zalo từ chối, ví dụ lời mời không còn) hoặc UNKNOWN (không rõ, KHÔNG tự thử lại).
-- Dòng khách và số điện thoại giữ nguyên.
ALTER TABLE zalo_msg_log DROP CONSTRAINT zalo_msg_log_kind_check;
ALTER TABLE zalo_msg_log ADD CONSTRAINT zalo_msg_log_kind_check
    CHECK (kind IN ('INVITE', 'LINK', 'WELCOME', 'REPLY', 'FOLLOWUP2', 'FOLLOWUP3', 'FRIEND', 'ASK_FRIEND', 'UNDO_FRIEND', 'ACCEPT_FRIEND'));
ALTER TABLE invitation ADD COLUMN friend_accept_at TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN friend_accept_outcome TEXT CHECK (friend_accept_outcome IN ('DONE', 'FAILED', 'UNKNOWN'));
