-- Số liệu thời tiết từng giờ trong 48 giờ tới (nhiệt độ, mưa, mây) để chạy thời tiết trên bản đồ.
-- Dạng gọn [[giây, nhiệt độ, mưa mm, mây %, biểu tượng], ...]; vẫn chỉ theo mã tỉnh, không liên quan tới dữ liệu khách.
ALTER TABLE weather_forecast ADD COLUMN hourly JSONB;
