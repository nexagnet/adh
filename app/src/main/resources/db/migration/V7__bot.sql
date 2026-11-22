-- Mốc 1E: khung bot trong nhóm (chưa nối kênh thật), FAQ do cửa hàng duyệt, việc cần mẹ xử lý.
-- Bot mặc định TẮT toàn hệ thống, TẮT theo từng nhóm, và ở chế độ DRY_RUN (chỉ ghi "sẽ trả lời gì").

ALTER TABLE zalo_group ADD COLUMN bot_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- Khi mẹ vừa trả lời trong nhóm, bot im lặng đến mốc này để không trả lời trùng.
ALTER TABLE zalo_group ADD COLUMN human_active_until TIMESTAMPTZ;

-- Câu hỏi thường gặp do cửa hàng soạn và duyệt. Bot chỉ trả lời bằng nội dung ở đây, không tự bịa giá/tồn kho/bảo hành.
CREATE TABLE faq_entry (
    id         BIGSERIAL PRIMARY KEY,
    title      TEXT        NOT NULL,
    keywords   TEXT        NOT NULL,
    answer     TEXT        NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Mỗi tin nhắn nhóm xử lý đúng một lần (khóa duy nhất theo nhóm + mã tin). KHÔNG lưu nội dung tin nhắn.
CREATE TABLE bot_event (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT      NOT NULL REFERENCES zalo_group (id) ON DELETE CASCADE,
    message_id  TEXT        NOT NULL,
    sender_kind TEXT        NOT NULL CHECK (sender_kind IN ('CUSTOMER', 'OWNER', 'BOT')),
    -- RECEIVED: vừa nhận. SELF: tin của bot. HUMAN_ACTIVE: mẹ đang trả lời. BOT_OFF: bot tắt. RATE_LIMITED: trả lời quá dày.
    -- IGNORED: tin không hướng tới cửa hàng. ANSWERED: trả lời bằng FAQ. HANDOFF: chuyển người thật.
    event_type  TEXT        NOT NULL DEFAULT 'RECEIVED'
                CHECK (event_type IN ('RECEIVED', 'SELF', 'HUMAN_ACTIVE', 'BOT_OFF', 'RATE_LIMITED', 'IGNORED',
                                      'ANSWERED', 'HANDOFF')),
    intent      TEXT,
    reply_text  TEXT,
    dry_run     BOOLEAN     NOT NULL DEFAULT TRUE,
    -- true nếu tin trả lời đã thật sự được gửi qua kênh
    sent        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bot_event_message UNIQUE (group_id, message_id)
);
CREATE INDEX idx_bot_event_group_time ON bot_event (group_id, created_at);

-- Việc cần mẹ xử lý: OPEN -> ACKNOWLEDGED -> RESOLVED. Trích đoạn đã che SĐT/số dài, tối đa 200 ký tự.
CREATE TABLE handoff (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT      NOT NULL REFERENCES zalo_group (id) ON DELETE CASCADE,
    reason      TEXT        NOT NULL CHECK (reason IN ('ORDER', 'COMPLAINT', 'HUMAN', 'UNSURE')),
    excerpt     TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    acked_at    TIMESTAMPTZ,
    acked_by    TEXT,
    resolved_at TIMESTAMPTZ,
    resolved_by TEXT
);
CREATE INDEX idx_handoff_status ON handoff (status, created_at);
