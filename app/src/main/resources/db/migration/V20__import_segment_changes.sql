-- Số khách đổi nhóm (mua 1 lần <-> từ 2 lần) sau mỗi lần nhập file; chỉ có sau khi xác nhận nhập.
ALTER TABLE import_batch ADD COLUMN segment_changes INTEGER;
