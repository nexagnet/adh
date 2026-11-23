-- Nội dung hàng hóa của từng vận đơn (cột "Nội dung hàng hóa" của J&T, "Tên Hàng" của Viettel Post):
-- để mở một khách ra là biết họ đã mua gì. Chỉ là tên hàng, không có thêm dữ liệu cá nhân.
ALTER TABLE shipment ADD COLUMN goods TEXT;

-- Đơn đã nhập trước đây: dòng gốc vẫn nằm trong raw_payload (khóa là đúng tên cột trong file), lấy lại từ đó.
-- Không đụng tới trạng thái, số lần mua hay phân loại khách. Đơn không có cột này thì để trống.
UPDATE shipment SET goods = left(btrim(COALESCE(
        raw_payload ->> 'Nội dung hàng hóa', raw_payload ->> 'Tên hàng hóa', raw_payload ->> 'Nội dung hàng',
        raw_payload ->> 'Tên Hàng', raw_payload ->> 'Tên hàng')), 500)
WHERE goods IS NULL AND raw_payload IS NOT NULL;
UPDATE shipment SET goods = NULL WHERE goods = '';
