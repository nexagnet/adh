-- D19: trợ lý AI đọc tin riêng của người đã nhận Tin 1 hoặc Tin 2 và nhận ra khách từ chối (docs/THIET-KE-AI-DOC-TIN-DEN.md mục 3).
-- Mỗi dòng là MỘT lần trợ lý quyết định gì đó về một lời mời, để mẹ xem và hoàn tác. KHÔNG lưu số điện thoại, tên hay mã Zalo của khách
-- ở đây (đã có ở bảng khách, lời mời).
--   AUTO_RECORDED: AI chắc từ ngưỡng (mặc định 0,8) nên đã ghi sổ từ chối; không giữ nội dung tin. Còn nút Hoàn tác.
--   HELD:          AI không chắc / lỗi / hết hạn mức: lời mời tạm dừng (CANCELLED, ghi trạng thái cũ ở prior_status), chờ mẹ quyết.
--   CONFIRMED:     mẹ xác nhận khách từ chối thật (ghi sổ từ chối như mẹ tự bấm).
--   RELEASED:      mẹ xem rồi cho tiếp tục (khôi phục lời mời).
--   UNDONE:        mẹ hoàn tác việc AI tự ghi từ chối (khôi phục sổ và lời mời).
-- excerpt: trích đoạn ĐÃ CHE (PiiMasker) tối đa 300 ký tự, chỉ có ở dòng HELD; tự xóa khi mẹ quyết xong hoặc sau 30 ngày.
-- undo_state: JSON (cơ sở liên hệ cũ, danh sách từ chối cũ) chỉ để hoàn tác; không chứa nội dung tin.
CREATE TABLE refusal_review (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT       NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    invitation_id BIGINT       NOT NULL REFERENCES invitation (id) ON DELETE CASCADE,
    state         TEXT         NOT NULL CHECK (state IN ('AUTO_RECORDED', 'HELD', 'CONFIRMED', 'RELEASED', 'UNDONE')),
    reason        TEXT         NOT NULL CHECK (reason IN ('AI_CHAC', 'AI_KHONG_CHAC', 'AI_LOI', 'AI_SAI_KHUON')),
    label         TEXT,
    confidence    NUMERIC(4, 3),
    excerpt       TEXT CHECK (excerpt IS NULL OR char_length(excerpt) <= 300),
    prior_status  TEXT         NOT NULL CHECK (prior_status IN ('SENT', 'CLICKED', 'FRIENDED')),
    undo_state    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    resolved_by   TEXT
);

-- Mỗi lời mời chỉ có tối đa một dòng đang "mở" (đang tạm dừng, hoặc AI vừa tự ghi và chưa ai xem lại).
CREATE UNIQUE INDEX uq_refusal_review_open ON refusal_review (invitation_id) WHERE state IN ('HELD', 'AUTO_RECORDED');
CREATE INDEX idx_refusal_review_state ON refusal_review (state, created_at DESC);
