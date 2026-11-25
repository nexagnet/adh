-- AI đề xuất chiến dịch dùng cùng bộ lọc khách với người (docs/THIET-KE-CAU-HINH-CHIEN-DICH-LINH-DONG.md, PR 4).
-- JSON theo khuôn CustomerFilter (đã qua kiểm cứng); NULL = không có bộ lọc ngoài các núm cũ (weekdays, pick_order, min_days, max_days, product_group).
ALTER TABLE campaign_proposal ADD COLUMN filter_json JSONB;
