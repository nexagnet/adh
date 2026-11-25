-- Ghi chú của chủ shop cho AI đề xuất chiến dịch (25/11/2026).
-- Mỗi ghi chú có NGÀY HẾT HẠN: hết hạn thì tự không còn được đưa cho AI (khỏi bị cũ). Gỡ tay thì đánh dấu removed_at, không xóa dòng.
-- Chỉ là chữ do chủ shop tự gõ (không số điện thoại, đường dẫn, email: mã từ chối lúc lưu); không lấy gì từ khách.
CREATE TABLE advisor_note (
    id          BIGSERIAL PRIMARY KEY,
    body        TEXT        NOT NULL CHECK (char_length(body) BETWEEN 5 AND 300),
    expires_on  DATE        NOT NULL,
    created_by  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at  TIMESTAMPTZ,
    removed_by  TEXT
);
CREATE INDEX idx_advisor_note_active ON advisor_note (expires_on) WHERE removed_at IS NULL;
