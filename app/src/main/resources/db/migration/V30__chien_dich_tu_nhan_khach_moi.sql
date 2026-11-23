-- Chiến dịch "tự nhận khách mới" (chủ shop chốt 23/11/2026): thay duyệt từng người bằng tiêu chí duyệt sẵn của chiến dịch.
-- first_seen_at: lúc khách xuất hiện lần đầu trong hệ thống (nhập đơn hằng ngày). Khách đã có trước migration này được đặt về epoch
-- nên KHÔNG BAO GIỜ bị coi là "khách mới"; chỉ khách nhập sau khi bật tự nhận mới được tự duyệt.
ALTER TABLE customer ADD COLUMN first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE customer SET first_seen_at = TIMESTAMPTZ 'epoch';

-- auto_intake_since: khác NULL = chiến dịch đang tự nhận khách mới (chỉ khách có first_seen_at từ mốc này, đủ điều kiện, đúng khu vực).
-- Tắt mặc định, chỉ bật được cho chiến dịch đã duyệt tự gửi, và chỉ MỘT chiến dịch tự nhận tại một thời điểm.
ALTER TABLE invitation_campaign ADD COLUMN auto_intake_since TIMESTAMPTZ;
ALTER TABLE invitation_campaign ADD COLUMN auto_intake_by TEXT;
CREATE UNIQUE INDEX uq_campaign_auto_intake ON invitation_campaign ((TRUE)) WHERE auto_intake_since IS NOT NULL;
