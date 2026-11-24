-- Dự báo nhiều ngày (khoảng 7 đến 9 ngày từ MET Norway) theo tỉnh: [["2026-11-25", thấp nhất, cao nhất, tổng mưa mm], ...].
-- Chỉ là số theo tỉnh, không liên quan dữ liệu khách. NULL với dòng cũ cho tới lần làm mới dự báo kế tiếp.
ALTER TABLE weather_forecast ADD COLUMN daily JSONB;
