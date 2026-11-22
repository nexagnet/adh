-- Trạng thái J&T Express thấy trên cổng khách hàng (12 thẻ, xác nhận 21/11/2026), chỉ gán những cái nghĩa đã rõ
-- và KHÔNG BAO GIỜ là giao thành công. Những cái còn mơ hồ ("Kiện vấn đề", "Kết thúc", "Kí nhận CPN", "Đã đối soát")
-- cố ý để trống: chủ shop tự chọn nghĩa trong màn hình Trạng thái đơn.
INSERT INTO status_mapping (carrier, status_key, sample_text, canonical_status) VALUES
    ('JT_EXPRESS', 'doi lay hang',          'Đợi lấy hàng',         'CREATED'),
    ('JT_EXPRESS', 'dang giao hang',        'Đang giao hàng',       'IN_TRANSIT'),
    ('JT_EXPRESS', 'dang ky chuyen hoan',   'Đăng ký chuyển hoàn',  'RETURNING'),
    ('JT_EXPRESS', 'dang chuyen hoan',      'Đang chuyển hoàn',     'RETURNING')
ON CONFLICT (carrier, status_key) DO NOTHING;
