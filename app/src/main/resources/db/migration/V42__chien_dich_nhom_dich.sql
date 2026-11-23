-- D21 PR 6 (23/11/2026): nhóm đích riêng của chiến dịch, CHỈ làm chặt hơn hệ thống (docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md mục 5.2).
-- group_delivery: NULL = theo hệ thống (tự thêm khách đã kết bạn vào nhóm, lỗi riêng người thì gửi link). 'LINK_ONLY' = KHÔNG tự thêm và KHÔNG tự tạo nhóm
--   cho khách của chiến dịch này, chỉ gửi link nhóm (Tin 2) để khách tự bấm vào.
-- group_link_template: nội dung Tin 2 riêng của chiến dịch (NULL = mẫu chung). allow_group_creation = false: khách của chiến dịch không được đưa vào nhóm mới do hệ thống tự tạo.
ALTER TABLE invitation_campaign ADD COLUMN group_delivery TEXT;
ALTER TABLE invitation_campaign ADD COLUMN group_link_template TEXT;
ALTER TABLE invitation_campaign ADD COLUMN allow_group_creation BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE invitation_campaign ADD CONSTRAINT chk_campaign_group CHECK (group_delivery IS NULL OR group_delivery = 'LINK_ONLY');
