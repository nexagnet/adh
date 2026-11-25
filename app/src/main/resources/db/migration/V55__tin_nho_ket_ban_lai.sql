-- Zalo báo danh sách lời mời kết bạn đã đầy (Z223, 25/11/2026): hệ thống chỉ tạm dừng riêng việc gửi lời mời kết bạn, và gửi thêm MỘT tin cho khách đã nhận Tin 1
-- nhưng chưa được mời kết bạn, nhờ họ chủ động kết bạn lại với tài khoản phụ (loại tin ASK_FRIEND).
--   friend_ask_at: khóa chống gửi hai lần; friend_ask_outcome UNKNOWN nghĩa là đã gọi mà chưa biết kết quả (KHÔNG tự gửi lại).
--   friend_ask_template: nội dung tin này riêng của chiến dịch (rỗng = dùng mẫu mặc định), sửa được như Tin 1.
ALTER TABLE zalo_msg_log DROP CONSTRAINT zalo_msg_log_kind_check;
ALTER TABLE zalo_msg_log ADD CONSTRAINT zalo_msg_log_kind_check
    CHECK (kind IN ('INVITE', 'LINK', 'WELCOME', 'REPLY', 'FOLLOWUP2', 'FOLLOWUP3', 'FRIEND', 'ASK_FRIEND'));
ALTER TABLE invitation ADD COLUMN friend_ask_at TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN friend_ask_outcome TEXT CHECK (friend_ask_outcome IN ('SENT', 'FAILED', 'UNKNOWN'));
ALTER TABLE invitation_campaign ADD COLUMN friend_ask_template TEXT;
-- Tab "Cần xử lý tay" của chiến dịch: nút "Gửi lại kết bạn" chỉ dùng được MỘT lần cho mỗi khách (friend_request_retry_at), và nút "Đã xử lý" ẩn khách khỏi tab (attention_done_at).
-- Không xóa dòng nào, không đụng số điện thoại.
ALTER TABLE invitation ADD COLUMN friend_request_retry_at TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN attention_done_at TIMESTAMPTZ;
