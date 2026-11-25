-- Bộ lọc khách của chiến dịch (docs/THIET-KE-CAU-HINH-CHIEN-DICH-LINH-DONG.md, PR 1).
-- JSON theo khuôn CustomerFilter; NULL = không lọc gì (giống mọi chiến dịch tạo trước đây). Bộ lọc chỉ LÀM HẸP tập khách đủ điều kiện mời,
-- không chứa số điện thoại hay chữ tự do của khách. Chỉ đặt lúc tạo chiến dịch; chế độ tự nhận khách mới dùng đúng bộ lọc này.
ALTER TABLE invitation_campaign ADD COLUMN customer_filter JSONB;
