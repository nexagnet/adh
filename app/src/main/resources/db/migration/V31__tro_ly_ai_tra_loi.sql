-- D20 (23/11/2026): trợ lý AI tự soạn câu trả lời cho khách TỰ nhắn tới (docs/THIET-KE-AI-TRA-LOI.md).
-- Loại tin thứ tư: REPLY, trả lời người vừa nhắn tới (cùng chốt chặn với WELCOME ở bộ nối: chỉ theo mã Zalo, trong 24 giờ, một lần cho mỗi tin đến).
ALTER TABLE zalo_msg_log DROP CONSTRAINT zalo_msg_log_kind_check;
ALTER TABLE zalo_msg_log ADD CONSTRAINT zalo_msg_log_kind_check CHECK (kind IN ('INVITE', 'LINK', 'WELCOME', 'REPLY'));

-- Mỗi dòng là MỘT việc trả lời một khách (hoặc một việc chuyển người thật).
--   DRAFT:      nấc "chỉ soạn nháp": AI đã soạn, KHÔNG gửi; người thật xem rồi tự trả lời nếu muốn.
--   READY:      nấc tự động: đã qua mọi kiểm tra, đang chờ tới lượt gửi.
--   SENDING / SENT / FAILED: đang gửi, đã gửi, lỗi không rõ (không tự gửi lại).
--   HANDOFF:    cần người thật (ngoài phạm vi, AI lỗi, câu AI soạn không qua kiểm tra, quá 24 giờ, gửi không được). assigned_to để dành cho
--               giai đoạn 2 (đội bán hàng nhiều người); giai đoạn 1 để trống.
--   DONE:       người thật đã xử lý hoặc bỏ qua.  SUPERSEDED: khách nhắn thêm nên việc mới thay thế.
-- inbound_text: văn bản khách ĐÃ CHE (PiiMasker), tối đa 500 ký tự; reply_text là câu AI soạn. Cả hai tự xóa sau 30 ngày. Không lưu số điện thoại.
CREATE TABLE reply_draft (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      BIGINT       NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    invitation_id    BIGINT       REFERENCES invitation (id) ON DELETE SET NULL,
    zalo_uid         TEXT         NOT NULL,
    state            TEXT         NOT NULL CHECK (state IN ('DRAFT', 'READY', 'SENDING', 'SENT', 'FAILED', 'HANDOFF', 'DONE', 'SUPERSEDED')),
    reason           TEXT,
    detail           TEXT,
    scope_confidence NUMERIC(4, 3),
    inbound_text     TEXT CHECK (inbound_text IS NULL OR char_length(inbound_text) <= 500),
    reply_text       TEXT CHECK (reply_text IS NULL OR char_length(reply_text) <= 1000),
    inbound_at       TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    assigned_to      TEXT,
    resolved_by      TEXT
);

-- Mỗi khách chỉ có tối đa một việc "đang mở" (nháp, chờ gửi, đang gửi, cần người thật).
CREATE UNIQUE INDEX uq_reply_draft_open ON reply_draft (zalo_uid) WHERE state IN ('DRAFT', 'READY', 'SENDING', 'HANDOFF');
CREATE INDEX idx_reply_draft_state ON reply_draft (state, created_at DESC);
