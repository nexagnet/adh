-- Dự báo thời tiết theo tỉnh (nguồn MET Norway, CC BY 4.0). Chỉ lưu nhiệt độ và loại thời tiết theo mã tỉnh,
-- KHÔNG liên quan tới dữ liệu khách. Dùng để nhắc việc cho mẹ, không tự gửi gì cho khách.
CREATE TABLE weather_forecast (
    province   TEXT        PRIMARY KEY,
    min_temp   NUMERIC(4, 1) NOT NULL,
    max_temp   NUMERIC(4, 1) NOT NULL,
    symbol     TEXT,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
