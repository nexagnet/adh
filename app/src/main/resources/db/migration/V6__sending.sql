-- Mốc 1D: nhật ký gửi (giữ tối thiểu 1 năm) và trạng thái FAILED cho lời mời.
-- Gửi thật mặc định TẮT (app.sending.enabled=false); chế độ thử chỉ ghi DRY_RUN.

ALTER TABLE invitation DROP CONSTRAINT invitation_status_check;
ALTER TABLE invitation ADD CONSTRAINT invitation_status_check
    CHECK (status IN ('READY', 'SENT', 'CLICKED', 'CANCELLED', 'FAILED'));
ALTER TABLE invitation ADD COLUMN last_error TEXT;

-- Không có khóa ngoại: nhật ký phải còn nguyên sau khi xóa khách; chỉ lưu 3 số cuối của SĐT và không lưu nội dung tin.
CREATE TABLE send_log (
    id            BIGSERIAL PRIMARY KEY,
    invitation_id BIGINT,
    campaign_id   BIGINT,
    customer_id   BIGINT,
    phone_tail    TEXT        NOT NULL,
    channel       TEXT        NOT NULL,
    -- SENT: đã gửi (hoặc xuất để gửi tay). FAILED: nhà cung cấp báo lỗi. SKIPPED: không đủ điều kiện lúc gửi.
    -- DRY_RUN: chạy thử, không gửi gì.
    outcome       TEXT        NOT NULL CHECK (outcome IN ('SENT', 'FAILED', 'SKIPPED', 'DRY_RUN')),
    reason        TEXT,
    cost_vnd      INT         NOT NULL DEFAULT 0,
    template_sha  TEXT,
    recorded_by   TEXT        NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_send_log_time ON send_log (recorded_at);
CREATE INDEX idx_send_log_invitation ON send_log (invitation_id);
