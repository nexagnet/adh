-- Hồ sơ khách nạp cho AI khi soạn câu trả lời (docs/THIET-KE-AI-NGU-CANH-KHACH.md).
-- context_fields: khóa các trường hồ sơ đã gửi cho AI ở việc này (ví dụ "khu_vuc,da_mua,nhom"), để chủ shop kiểm tra sau.
-- CHỈ lưu tên trường, KHÔNG lưu giá trị (không có tên hàng, ngày, số điện thoại... trong cột này). NULL = không gửi hồ sơ nào.
ALTER TABLE reply_draft ADD COLUMN context_fields TEXT;
