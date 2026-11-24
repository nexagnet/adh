-- Zalo từ chối Tin 1 vì người nhận không nhận tin nhắn của người lạ (mã Z122, đo thật 24/11/2026). Lỗi này chỉ của riêng người đó:
-- tài khoản phụ kết bạn trước, khách đồng ý thì Tin 1 được gửi lại một lần (chủ shop chốt 24/11/2026).
--   tin1_blocked_at: lúc Zalo từ chối Tin 1 (lời mời nằm trong "danh sách chờ kết bạn để gửi lại" của chiến dịch).
--   tin1_resent_at:  lúc đã giành chỗ gửi lại Tin 1 (khóa chống gửi hai lần; không rõ kết quả thì giữ nguyên, KHÔNG tự gửi lại).
ALTER TABLE invitation ADD COLUMN tin1_blocked_at TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN tin1_resent_at TIMESTAMPTZ;
CREATE INDEX idx_invitation_tin1_blocked ON invitation (campaign_id) WHERE tin1_blocked_at IS NOT NULL;
