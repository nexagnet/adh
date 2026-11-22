-- Sửa lỗi dữ liệu đã nhập: cột "Tỉnh/Thành phố gửi" (tỉnh của cửa hàng) của file Viettel Post bị nhận nhầm làm
-- địa chỉ hành chính nên mọi đơn Viettel Post bị nối thêm ", T.<tỉnh gửi>" vào cuối địa chỉ nhận và khu vực của khách
-- bị suy sai thành tỉnh của cửa hàng. Bản vá mã (FieldKey) đã loại cột "gửi"; migration này gỡ phần đã nối sai.

-- 1. Bỏ ánh xạ cột sai đã lưu để lần nhập sau không dùng lại.
DELETE FROM column_mapping WHERE field = 'ADMIN_AREA' AND lower(header) LIKE '%gửi';

-- 2. Gỡ đuôi ", <tỉnh gửi>" khỏi địa chỉ nhận của vận đơn (chỉ khi đúng là đuôi đó; địa chỉ gốc vẫn nằm trong raw_payload).
UPDATE shipment
SET receiver_address = left(receiver_address,
                            length(receiver_address) - length(raw_payload ->> 'Tỉnh/Thành phố gửi') - 2)
WHERE carrier = 'VIETTEL_POST'
  AND coalesce(raw_payload ->> 'Tỉnh/Thành phố gửi', '') <> ''
  AND right(receiver_address, length(raw_payload ->> 'Tỉnh/Thành phố gửi') + 2)
      = ', ' || (raw_payload ->> 'Tỉnh/Thành phố gửi');

-- 3. Địa chỉ đã sao vào bảng khách: gỡ cùng đuôi. Khu vực được tính lại khi khởi động (VnGeo.VERSION tăng lên 3).
UPDATE customer c
SET address = left(c.address, length(c.address) - length(s.sender) - 2)
FROM (SELECT DISTINCT receiver_phone, raw_payload ->> 'Tỉnh/Thành phố gửi' AS sender
      FROM shipment
      WHERE carrier = 'VIETTEL_POST' AND coalesce(raw_payload ->> 'Tỉnh/Thành phố gửi', '') <> '') s
WHERE s.receiver_phone = c.phone
  AND c.address IS NOT NULL
  AND right(c.address, length(s.sender) + 2) = ', ' || s.sender;
