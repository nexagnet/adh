-- Khách từng từ chối rồi tự nhắn lại muốn mua (chủ shop chốt 23/11/2026, docs/THIET-KE-KHACH-QUAY-LAI.md).
-- Quy tắc: KHÔNG BAO GIỜ xóa dòng sổ từ chối (mã băm). Gỡ "không nhắn nữa" = đánh dấu đã gỡ kèm giờ; mọi chỗ chặn chỉ xét dòng CÒN HIỆU LỰC
-- (lifted_at IS NULL). Khách từ chối lần nữa thì dòng được kích hoạt lại. Dòng ERASED (khách yêu cầu xóa dữ liệu) KHÔNG BAO GIỜ gỡ được.
ALTER TABLE suppression ADD COLUMN lifted_at TIMESTAMPTZ;
ALTER TABLE suppression ADD COLUMN lifted_by TEXT;
ALTER TABLE suppression ADD CONSTRAINT ck_suppression_lift CHECK (lifted_at IS NULL OR reason = 'OPT_OUT');

-- Loại tin thứ ba: tin mẫu cố định trả lời người đã nhắn trước (WELCOME).
ALTER TABLE zalo_msg_log DROP CONSTRAINT zalo_msg_log_kind_check;
ALTER TABLE zalo_msg_log ADD CONSTRAINT zalo_msg_log_kind_check CHECK (kind IN ('INVITE', 'LINK', 'WELCOME'));

-- Mỗi dòng là MỘT lần khách từng từ chối nhắn lại. KHÔNG có số điện thoại, tên hay mã Zalo ở đây.
--   TASK:      việc nổi bật cho mẹ (chế độ "chỉ tạo việc", AI chưa đủ chắc, nhãn khác, AI lỗi). Mẹ có thể gỡ cờ tay.
--   LIFTED:    đã gỡ cờ (AI tự gỡ hoặc mẹ gỡ), có nút Hoàn tác; welcome_state theo dõi tin mẫu.
--   UNDONE:    đã hoàn tác việc gỡ cờ (giữ để đếm quy tắc 30 ngày).
--   DONE:      mẹ xử lý xong / giữ nguyên cờ.
-- lifted_at: giờ gỡ cờ, GIỮ LẠI cả khi hoàn tác để tính "tối đa một lần tự thêm mỗi 30 ngày".
-- excerpt: trích đoạn đã che ≤300 ký tự, chỉ ở dòng TASK, xóa khi mẹ quyết hoặc sau 30 ngày. undo_state: JSON để hoàn tác, không chứa nội dung tin.
CREATE TABLE comeback (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT       NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    invitation_id BIGINT       NOT NULL REFERENCES invitation (id) ON DELETE CASCADE,
    state         TEXT         NOT NULL CHECK (state IN ('TASK', 'LIFTED', 'UNDONE', 'DONE')),
    reason        TEXT         NOT NULL CHECK (reason IN ('AI_CHAC', 'CHI_TAO_VIEC', 'CHUA_CHAC', 'NHAN_KHAC', 'AI_LOI', 'AI_SAI_KHUON', 'KHONG_DU_DIEU_KIEN', 'DA_GO_GAN_DAY')),
    label         TEXT,
    confidence    NUMERIC(4, 3),
    excerpt       TEXT CHECK (excerpt IS NULL OR char_length(excerpt) <= 300),
    undo_state    TEXT,
    welcome_state TEXT CHECK (welcome_state IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
    welcome_code  TEXT,
    lifted_at     TIMESTAMPTZ,
    lifted_by     TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    resolved_by   TEXT
);
CREATE UNIQUE INDEX uq_comeback_open_task ON comeback (customer_id) WHERE state = 'TASK';
CREATE INDEX idx_comeback_customer ON comeback (customer_id, created_at DESC);
CREATE INDEX idx_comeback_state ON comeback (state, created_at DESC);
