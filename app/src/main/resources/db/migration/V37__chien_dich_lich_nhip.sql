-- D21 PR 4 (23/11/2026): lịch và nhịp RIÊNG của chiến dịch (docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md mục 5.1, 5.2).
-- NULL = theo hệ thống, nên mọi chiến dịch cũ chạy y như trước. Giá trị ở đây chỉ làm CHẶT hơn trần hệ thống, không bao giờ nới:
-- mã tính giá trị hiệu lực ở MỘT chỗ (CampaignPolicy.effective), các chốt chặn của hệ thống (khung giờ, hạn mức ngày, khoảng nghỉ, cầu dao) vẫn chạy sau đó.
ALTER TABLE invitation_campaign ADD COLUMN start_on    DATE;
ALTER TABLE invitation_campaign ADD COLUMN end_on      DATE;
ALTER TABLE invitation_campaign ADD COLUMN window_from TIME;
ALTER TABLE invitation_campaign ADD COLUMN window_to   TIME;
ALTER TABLE invitation_campaign ADD COLUMN daily_cap   INT;
ALTER TABLE invitation_campaign ADD COLUMN gap_seconds INT;
ALTER TABLE invitation_campaign ADD CONSTRAINT chk_campaign_schedule CHECK (
        (window_from IS NULL) = (window_to IS NULL)
        AND (window_from IS NULL OR window_to > window_from)
        AND (start_on IS NULL OR end_on IS NULL OR end_on >= start_on)
        AND (daily_cap IS NULL OR daily_cap >= 1)
        AND (gap_seconds IS NULL OR gap_seconds BETWEEN 30 AND 3600));
