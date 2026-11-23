-- Cảnh báo sớm khi tài khoản Zalo phụ có dấu hiệu bị hạn chế (23/11/2026). Mỗi LOẠI cảnh báo chỉ có MỘT dòng đang mở
-- (resolved_at IS NULL); hết dấu hiệu thì đóng dòng đó. Không có cột nào chứa số điện thoại, tên, mã Zalo hay nội dung tin:
-- detail chỉ là mã kỹ thuật ngắn (ví dụ "SYSTEM_ERROR:Z123") hoặc tỷ lệ ("7/10").
CREATE TABLE zalo_health_alert (
    id           BIGSERIAL PRIMARY KEY,
    kind         TEXT        NOT NULL,
    severity     TEXT        NOT NULL CHECK (severity IN ('WARN', 'CRITICAL')),
    detail       TEXT,
    opened_at    TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    resolved_at  TIMESTAMPTZ,
    notified_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_zalo_health_alert_open ON zalo_health_alert (kind) WHERE resolved_at IS NULL;
CREATE INDEX idx_zalo_health_alert_opened ON zalo_health_alert (opened_at DESC);
