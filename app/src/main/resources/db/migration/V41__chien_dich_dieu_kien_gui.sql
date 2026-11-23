-- D21 PR 4 phần còn lại (23/11/2026): điều kiện gửi riêng của chiến dịch, CHỈ làm chặt hơn hệ thống (docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md mục 5.3a).
-- weekdays: các ngày trong tuần được gửi, mỗi ngày một bit (bit 0 = thứ Hai ... bit 6 = Chủ nhật); NULL = mọi ngày (theo hệ thống).
-- hold_on_rain: hoãn gửi cho khách ở tỉnh đang có mưa lớn theo dự báo; chỉ HOÃN, không bao giờ làm gửi nhanh hơn hay nhiều hơn.
-- stop_refusal_pct: ngưỡng tự dừng riêng (phần trăm khách từ chối trên số Tin 1 đã gửi); NULL = theo ngưỡng chung của hệ thống, chỉ được thấp hơn.
-- auto_stopped_*: dấu vết lần hệ thống tự bỏ duyệt tự gửi vì tỷ lệ từ chối cao; auto_stop_since: người duyệt lại bằng tay thì bộ đếm tỷ lệ từ chối bắt đầu lại từ lúc đó.
ALTER TABLE invitation_campaign ADD COLUMN weekdays SMALLINT;
ALTER TABLE invitation_campaign ADD COLUMN hold_on_rain BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE invitation_campaign ADD COLUMN stop_refusal_pct INT;
ALTER TABLE invitation_campaign ADD COLUMN auto_stopped_at TIMESTAMPTZ;
ALTER TABLE invitation_campaign ADD COLUMN auto_stopped_reason TEXT;
ALTER TABLE invitation_campaign ADD COLUMN auto_stop_since TIMESTAMPTZ;
ALTER TABLE invitation_campaign ADD CONSTRAINT chk_campaign_rules CHECK (
        (weekdays IS NULL OR weekdays BETWEEN 1 AND 127)
        AND (stop_refusal_pct IS NULL OR stop_refusal_pct BETWEEN 1 AND 100));
