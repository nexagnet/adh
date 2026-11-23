-- D21 PR 7 (23/11/2026): chính sách "khách từng từ chối tự nhắn lại muốn mua" RIÊNG của chiến dịch (docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md mục 5.2).
-- NULL = theo hệ thống. Nấc có hiệu lực của một khách luôn là nấc THẤP hơn của hệ thống và chiến dịch (OFF < TASKS < AUTO), nên chiến dịch không bao giờ
-- nới được nấc chung; chiến dịch chọn "chỉ gửi link nhóm" (V42) thì tối đa là TASKS (không tự thêm vào nhóm).
ALTER TABLE invitation_campaign ADD COLUMN comeback_mode TEXT;
ALTER TABLE invitation_campaign ADD CONSTRAINT chk_campaign_comeback CHECK (comeback_mode IS NULL OR comeback_mode IN ('OFF', 'TASKS', 'AUTO'));
