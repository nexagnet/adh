-- Tài khoản phụ CHỦ ĐỘNG gửi lời mời kết bạn sau Tin 1 (chủ shop chốt 24/11/2026). Mỗi lời mời một dòng trong sổ tin (loại FRIEND, tính trần riêng
-- mỗi ngày) và dấu vết ngay trên lời mời: friend_request_at khóa chống gửi hai lần; outcome UNKNOWN nghĩa là đã gọi mà chưa biết kết quả (KHÔNG tự gửi lại).
ALTER TABLE zalo_msg_log DROP CONSTRAINT zalo_msg_log_kind_check;
ALTER TABLE zalo_msg_log ADD CONSTRAINT zalo_msg_log_kind_check
    CHECK (kind IN ('INVITE', 'LINK', 'WELCOME', 'REPLY', 'FOLLOWUP2', 'FOLLOWUP3', 'FRIEND'));
ALTER TABLE invitation ADD COLUMN friend_request_at TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN friend_request_outcome TEXT CHECK (friend_request_outcome IN ('SENT', 'FAILED', 'UNKNOWN'));
