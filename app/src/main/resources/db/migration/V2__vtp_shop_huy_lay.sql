-- Thấy trong file Viettel Post thật (xuất 21/11/2026): "Shop hủy lấy" = shop tự hủy trước khi bưu tá lấy hàng.
-- Không bao giờ là giao thành công. "Đã hủy giao" cố ý KHÔNG gán ở đây: chưa rõ nghĩa, để chủ shop tự chọn.
INSERT INTO status_mapping (carrier, status_key, sample_text, canonical_status) VALUES
    ('VIETTEL_POST', 'shop huy lay', 'Shop hủy lấy', 'CANCELLED')
ON CONFLICT (carrier, status_key) DO NOTHING;
