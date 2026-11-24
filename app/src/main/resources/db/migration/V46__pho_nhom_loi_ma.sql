-- Đặt phó nhóm (mẹ) lỗi: ghi mã lỗi Zalo thật (dạng Z118, không chứa mã người) và số lần đã bấm "Thử lại phó nhóm" (trần 5 mỗi nhóm).
ALTER TABLE zalo_group_proposal ADD COLUMN deputy_code TEXT;
ALTER TABLE zalo_group_proposal ADD COLUMN deputy_tries INT NOT NULL DEFAULT 0;
