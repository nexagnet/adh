-- Chiến dịch THỬ qua đúng bộ nối thật (23/11/2026), chạy trước chiến dịch thật. Màn hình này tạm thời: xong việc thì xóa.
-- Muốn xóa: bỏ gói vn.shop.crm.testrun, bỏ trang test-runs*.html và thêm một migration xóa các cột dưới đây.
-- Vẫn TÁCH HẲN khách thật: không khóa ngoại tới customer/invitation, không ghi send_log, contact_event, danh sách từ chối.

ALTER TABLE test_target DROP CONSTRAINT test_target_status_check;
ALTER TABLE test_target ADD CONSTRAINT test_target_status_check
    CHECK (status IN ('READY', 'SENT', 'FRIENDED', 'JOINED', 'DECLINED', 'FAILED'));

-- Cấu hình ngay trên giao diện: tin 2, nhóm đích, hạn mức riêng của đợt thử (chỉ có thể THẤP hơn hạn mức chung), có gửi tin 2 không.
ALTER TABLE test_run ADD COLUMN link_template TEXT;
ALTER TABLE test_run ADD COLUMN daily_limit INT;
ALTER TABLE test_run ADD COLUMN gap_seconds INT;
ALTER TABLE test_run ADD COLUMN zalo_group_id BIGINT REFERENCES zalo_group (id) ON DELETE SET NULL;
ALTER TABLE test_run ADD COLUMN send_link BOOLEAN NOT NULL DEFAULT TRUE;
-- Đợt thử chỉ tự chạy khi người dùng bấm "Bắt đầu" (TRUE), bấm "Dừng" thì về FALSE.
ALTER TABLE test_run ADD COLUMN running BOOLEAN NOT NULL DEFAULT FALSE;

-- Dấu vết từng số thử khi chạy tự động.
--   zalo_uid: mã Zalo cầu nối tra được lúc gửi tin 1; add_state: ADDED/FAILED; link_state: SENT/FAILED; last_code: mã kết quả ngắn.
ALTER TABLE test_target ADD COLUMN zalo_uid TEXT;
ALTER TABLE test_target ADD COLUMN add_state TEXT CHECK (add_state IN ('ADDED', 'FAILED'));
ALTER TABLE test_target ADD COLUMN link_state TEXT CHECK (link_state IN ('SENT', 'FAILED'));
ALTER TABLE test_target ADD COLUMN last_code TEXT;
CREATE INDEX idx_test_target_uid ON test_target (zalo_uid) WHERE zalo_uid IS NOT NULL;
