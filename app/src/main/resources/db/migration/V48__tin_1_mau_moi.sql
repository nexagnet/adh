-- Tin 1 mẫu mới (24/11/2026, chủ shop chốt): đổi mẫu đang lưu của các chiến dịch CHƯA gửi Tin 1 cho ai, và mẫu mặc định đang lưu ở cài đặt.
-- Chiến dịch đã gửi cho ít nhất một khách giữ nguyên mẫu để tin đã gửi và tin sắp gửi không lệch nhau.
-- Mẫu cũ được sao lưu sang bảng dưới đây trước khi đổi (không xóa gì, không đụng số điện thoại hay khách).
CREATE TABLE template_backup (
    id          bigserial PRIMARY KEY,
    kind        text        NOT NULL,
    ref_id      bigint,
    old_value   text        NOT NULL,
    backed_up_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO template_backup (kind, ref_id, old_value)
SELECT 'campaign_tin_1', k.id, k.message_template
FROM invitation_campaign k
WHERE NOT EXISTS (SELECT 1 FROM invitation i WHERE i.campaign_id = k.id AND i.sent_at IS NOT NULL);

UPDATE invitation_campaign k
SET message_template = E'Dạ em chào bác {ten}, em bên shop áo điều hòa Minh Hiệp ạ.\nBác từng mua áo bên em nên em mời bác vào nhóm khách hàng: mua trong nhóm giảm 10% so với TikTok, miễn ship ạ.\nBác bấm "Chấp nhận" lời mời kết bạn của em, em thêm bác vào nhóm liền ạ. Bác chưa cần thì cứ bỏ qua nhé.'
WHERE NOT EXISTS (SELECT 1 FROM invitation i WHERE i.campaign_id = k.id AND i.sent_at IS NOT NULL);

INSERT INTO template_backup (kind, ref_id, old_value)
SELECT 'setting_invite_template', NULL, value FROM app_setting WHERE key = 'invite.template';
-- Xóa mẫu đang lưu để hệ thống dùng mẫu mặc định mới.
DELETE FROM app_setting WHERE key = 'invite.template';
