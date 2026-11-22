-- Đường nhắn bằng tài khoản Zalo phụ của cửa hàng (chủ shop chốt 21/11/2026, D13): người gửi tay nhắn tin tư vấn MỘT lần cho
-- khách mua 1 lần; ai đồng ý kết bạn thì được thêm vào nhóm Zalo cùng khu vực. Hệ thống KHÔNG tự gửi, chỉ ghi lại các bước
-- do người đó đánh dấu: đã nhắn (SENT) -> đã kết bạn (FRIENDED) -> đã vào nhóm (JOINED).
ALTER TABLE invitation DROP CONSTRAINT invitation_status_check;
ALTER TABLE invitation ADD CONSTRAINT invitation_status_check
    CHECK (status IN ('READY', 'SENT', 'CLICKED', 'CANCELLED', 'FAILED', 'FRIENDED', 'JOINED'));
ALTER TABLE invitation ADD COLUMN friended_at TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN joined_at TIMESTAMPTZ;
