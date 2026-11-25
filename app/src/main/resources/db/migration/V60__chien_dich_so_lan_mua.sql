-- Số lần mua tối đa của khách mà chiến dịch được mời (docs/THIET-KE-CAU-HINH-CHIEN-DICH-LINH-DONG.md, PR 3; chủ dự án duyệt 25/11/2026).
-- Mặc định 1 = hành vi từ trước tới nay (chỉ khách mua đúng 1 lần). Lớn hơn 1: chiến dịch được mời cả khách mua nhiều lần, nhưng chỉ khách có số lần
-- mua không vượt mức này; mua thêm vượt mức thì mọi bước gửi dừng (ContactService.stillEligible). Mỗi khách vẫn chỉ nhận MỘT Tin 1 trong đời.
ALTER TABLE invitation_campaign ADD COLUMN max_purchases INT NOT NULL DEFAULT 1 CHECK (max_purchases BETWEEN 1 AND 20);
