-- Trạng thái J&T Express thấy trong file "ĐƠN HÀNG" thật (xuất 21/11/2026, cột "Trạng thái vận đơn").
-- "Đã lấy hàng" = bưu tá đã lấy hàng, chưa phải giao xong => IN_TRANSIT (KHÔNG tính là mua thành công).
-- "Kiện vấn đề" cố ý KHÔNG gán: chưa rõ nghĩa, để chủ shop tự chọn trong màn hình Trạng thái đơn.
INSERT INTO status_mapping (carrier, status_key, sample_text, canonical_status) VALUES
    ('JT_EXPRESS', 'da lay hang',         'Đã lấy hàng',         'IN_TRANSIT'),
    ('JT_EXPRESS', 'dang van chuyen',     'Đang vận chuyển',     'IN_TRANSIT'),
    ('JT_EXPRESS', 'giao thanh cong',     'Giao thành công',     'DELIVERED'),
    ('JT_EXPRESS', 'huy',                 'Hủy',                 'CANCELLED'),
    ('JT_EXPRESS', 'ky nhan chuyen hoan', 'Ký nhận chuyển hoàn', 'RETURNED')
ON CONFLICT (carrier, status_key) DO NOTHING;
