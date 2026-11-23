-- D21 (23/11/2026): AI đề xuất chiến dịch (docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md).
-- AI chỉ ĐỀ XUẤT; bảng này lưu đề xuất và nhật ký để người dùng xem lý do, duyệt hoặc bỏ qua. Không có dữ liệu cá nhân nào:
-- snapshot_json là số liệu tổng hợp ẩn danh đã gửi cho AI (không tên, số điện thoại, địa chỉ, mã Zalo), ai_output là chữ AI trả về.

-- Mỗi lần người dùng bấm "Nhờ AI đề xuất" là một dòng, kể cả khi lỗi (để đếm trần số lần mỗi ngày).
CREATE TABLE advisor_run (
    id            BIGSERIAL PRIMARY KEY,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    TEXT        NOT NULL,
    status        TEXT        NOT NULL CHECK (status IN ('OK', 'SKIPPED', 'INVALID', 'UNAVAILABLE')),
    snapshot_json TEXT        NOT NULL,
    ai_output     TEXT CHECK (ai_output IS NULL OR char_length(ai_output) <= 20000),
    skip_reason   TEXT,
    detail        TEXT,
    rejected      JSONB       NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX idx_advisor_run_created ON advisor_run (created_at DESC);

-- Một đề xuất đã qua kiểm cứng.
--   PROPOSED: chờ người dùng; APPROVED: đã duyệt, campaign_id là chiến dịch được tạo; REJECTED: người dùng bỏ qua; EXPIRED: ngày bắt đầu đã qua mà chưa duyệt.
CREATE TABLE campaign_proposal (
    id                BIGSERIAL PRIMARY KEY,
    run_id            BIGINT        NOT NULL REFERENCES advisor_run (id) ON DELETE CASCADE,
    title             TEXT          NOT NULL,
    area              TEXT          NOT NULL,
    batch_size        INT           NOT NULL CHECK (batch_size >= 1),
    start_on          DATE          NOT NULL,
    end_on            DATE          NOT NULL,
    window_from       TIME          NOT NULL,
    window_to         TIME          NOT NULL,
    daily_cap         INT           NOT NULL CHECK (daily_cap >= 1),
    message_angle     TEXT          NOT NULL DEFAULT '',
    reasons           JSONB         NOT NULL,
    risks             JSONB         NOT NULL DEFAULT '[]'::jsonb,
    confidence        NUMERIC(4, 3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    confidence_capped BOOLEAN       NOT NULL DEFAULT FALSE,
    state             TEXT          NOT NULL DEFAULT 'PROPOSED' CHECK (state IN ('PROPOSED', 'APPROVED', 'REJECTED', 'EXPIRED')),
    decided_by        TEXT,
    decided_at        TIMESTAMPTZ,
    decision_note     TEXT,
    campaign_id       BIGINT REFERENCES invitation_campaign (id) ON DELETE SET NULL
);
CREATE INDEX idx_campaign_proposal_state ON campaign_proposal (state, run_id DESC);
