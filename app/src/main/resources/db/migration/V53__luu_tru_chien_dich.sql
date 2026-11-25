-- Lưu trữ chiến dịch (25/11/2026): chiến dịch đã cũ hoặc hết hiệu lực được chủ shop đánh dấu "đã lưu trữ".
--   archived_at / archived_by: lúc và người lưu trữ. Khi lưu trữ, mã đồng thời bỏ duyệt tự gửi và tắt tự nhận khách mới của chiến dịch đó.
-- Chiến dịch đã lưu trữ không còn được tính là "đang chạy" khi AI đề xuất chiến dịch (kết quả thật vẫn được giữ để AI học),
-- không bao giờ tự gửi tin, và khôi phục được. Không xóa lời mời, khách hay số điện thoại nào.
ALTER TABLE invitation_campaign ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE invitation_campaign ADD COLUMN archived_by TEXT;
