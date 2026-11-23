-- D21 PR 5b (23/11/2026): sổ tin của cổng gửi nhận thêm hai loại tin nhắc của chiến dịch nhiều lần gửi (lần hai và lần ba).
-- Tin nhắc tính vào hạn mức ngày cùng Tin 1 (ZaloMessageService). Không đổi dữ liệu cũ.
ALTER TABLE zalo_msg_log DROP CONSTRAINT zalo_msg_log_kind_check;
ALTER TABLE zalo_msg_log ADD CONSTRAINT zalo_msg_log_kind_check
    CHECK (kind IN ('INVITE', 'LINK', 'WELCOME', 'REPLY', 'FOLLOWUP2', 'FOLLOWUP3'));
