-- AI đề xuất chiến dịch: chỉ mời khách đã mua một nhóm hàng (NULL = mọi nhóm). Chỉ làm hẹp tập khách.
ALTER TABLE campaign_proposal ADD COLUMN product_group TEXT CHECK (product_group IN ('AO_DIEU_HOA', 'PIN', 'PHU_KIEN_KHAC'));
