-- Luồng kết bạn -> vào nhóm (D15, R4, R8): giữ chỗ nhóm cho khách vừa kết bạn, và ghi khách đã rời nhóm.
--   reserved_group_id, reserved_until: nhóm đang được giữ một chỗ cho khách này (mặc định 24 giờ); hết hạn thì gợi ý lại từ đầu.
--   left_at: khách đã ra khỏi nhóm (người gửi báo tay), số khách thật của nhóm giảm 1 khi khách đã được cộng.
ALTER TABLE invitation ADD COLUMN reserved_group_id BIGINT REFERENCES zalo_group (id) ON DELETE SET NULL;
ALTER TABLE invitation ADD COLUMN reserved_until TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN left_at TIMESTAMPTZ;
CREATE INDEX idx_invitation_reserved ON invitation (reserved_group_id) WHERE reserved_group_id IS NOT NULL;
