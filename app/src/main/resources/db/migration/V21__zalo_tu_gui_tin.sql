-- D18 (23/11/2026): hệ thống tự gửi Tin 1 cho khách đã mua bằng tài khoản Zalo phụ, qua bộ nối, có hạn mức nhỏ, khoảng nghỉ CỐ ĐỊNH,
-- chỉ trong giờ hợp lý, mỗi khách một lần. Chiến dịch phải được người dùng DUYỆT trước khi hệ thống tự gửi.
-- Không có cột nào lưu số điện thoại, tên hay nội dung tin; mã Zalo (uid) chỉ nằm ở lời mời của khách đó.

-- Chiến dịch chỉ được tự gửi khi đã có người duyệt (NULL = chưa duyệt = KHÔNG tự gửi). Bỏ duyệt thì dừng ngay.
ALTER TABLE invitation_campaign ADD COLUMN auto_send_approved_at TIMESTAMPTZ;
ALTER TABLE invitation_campaign ADD COLUMN auto_send_approved_by TEXT;

-- Mã Zalo của khách do cầu nối tra được lúc gửi Tin 1: để khi khách đồng ý kết bạn thì khớp theo mã, không cần số điện thoại
-- (đa số khách ẩn số nên trước đây phải xử lý tay).
ALTER TABLE invitation ADD COLUMN zalo_target_uid TEXT;
CREATE INDEX idx_invitation_zalo_target_uid ON invitation (zalo_target_uid) WHERE zalo_target_uid IS NOT NULL;

-- Tin 2 (link nhóm) tự gửi cho khách đã kết bạn mà bộ nối không thêm được vào nhóm vì lỗi riêng người.
--   PENDING: chờ tới lượt trong hạn mức; SENT: đã gửi; FAILED: gửi lỗi, KHÔNG tự thử lại.
ALTER TABLE invitation ADD COLUMN zalo_link_state TEXT CHECK (zalo_link_state IN ('PENDING', 'SENT', 'FAILED'));
CREATE INDEX idx_invitation_zalo_link_state ON invitation (zalo_link_state) WHERE zalo_link_state = 'PENDING';

-- Sổ tin nhắn đã gọi tới Zalo (chỉ thời điểm, loại tin, kết quả; KHÔNG có số, tên, uid, nội dung). Dùng chung cho chiến dịch thật
-- (scope REAL) và chạy thử (scope TEST:<mã đợt>) để hạn mức ngày và khoảng nghỉ tính chung cho cả hai.
--   DANG_GOI: đã bắt đầu gọi mà chưa có kết quả (sập giữa chừng thì cầu dao nhảy, không tự gửi lại).
CREATE TABLE zalo_msg_log (
    id      BIGSERIAL PRIMARY KEY,
    at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    scope   TEXT        NOT NULL,
    kind    TEXT        NOT NULL CHECK (kind IN ('INVITE', 'LINK')),
    ref_id  BIGINT,
    outcome TEXT        NOT NULL,
    code    TEXT
);
CREATE INDEX idx_zalo_msg_log_at ON zalo_msg_log (at);
