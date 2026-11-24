-- Bỏ Tin 2 (tin gửi link nhóm Zalo) 24/11/2026: hệ thống không tự gửi link nhóm nữa, cũng không còn mẫu tin hay chế độ "chỉ gửi link nhóm".
-- Khách bị lỗi riêng người khi thêm vào nhóm ở lại trạng thái lỗi (zalo_add_state = FAILED) để người xử lý ở trang Zalo.
-- Giữ nguyên bảng nhật ký tin (zalo_msg_log vẫn cho phép loại LINK để các dòng cũ còn đọc được) và cột invitation.zalo_link_state / group_link_sent_at (dữ liệu cũ).
-- Việc chờ gửi Tin 2 chưa gửi thì hủy, vì không còn nơi nào gửi.
UPDATE invitation SET zalo_link_state = NULL WHERE zalo_link_state = 'PENDING';
ALTER TABLE invitation_campaign DROP COLUMN group_delivery;
ALTER TABLE invitation_campaign DROP COLUMN group_link_template;
DELETE FROM app_setting WHERE key = 'invite.group-link-template';
