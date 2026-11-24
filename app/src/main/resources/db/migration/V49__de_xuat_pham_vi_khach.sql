-- AI đề xuất chiến dịch: thêm các núm chọn khách và ngày gửi (đều chỉ làm hẹp, không nới giới hạn hệ thống).
--   weekdays: ngày trong tuần được gửi (bit 0 = thứ Hai ... bit 6 = Chủ nhật); NULL = mọi ngày.
--   pick_order: thứ tự lấy khách khi tạo chiến dịch.
--   min_days / max_days: lần mua gần nhất cách đây từ min_days đến max_days ngày; NULL = không giới hạn.
ALTER TABLE campaign_proposal ADD COLUMN weekdays   SMALLINT CHECK (weekdays BETWEEN 1 AND 127);
ALTER TABLE campaign_proposal ADD COLUMN pick_order TEXT NOT NULL DEFAULT 'OLDEST_APPROVED'
    CHECK (pick_order IN ('OLDEST_APPROVED', 'NEWEST_PURCHASE', 'OLDEST_PURCHASE'));
ALTER TABLE campaign_proposal ADD COLUMN min_days   INT CHECK (min_days >= 0);
ALTER TABLE campaign_proposal ADD COLUMN max_days   INT CHECK (max_days >= 0);
ALTER TABLE campaign_proposal ADD CONSTRAINT chk_proposal_days CHECK (min_days IS NULL OR max_days IS NULL OR max_days >= min_days);
