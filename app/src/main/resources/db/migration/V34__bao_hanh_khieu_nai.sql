-- Phân loại bảo hành và khiếu nại bằng AI (23/11/2026, docs/THIET-KE-BAO-HANH-KHIEU-NAI.md).
-- Khách đã nhận tin của hệ thống tự nhắn riêng phàn nàn hoặc báo lỗi: AI phân loại (loại việc, mức gấp), tóm tắt, soạn NHÁP cho người thật duyệt.
-- Hệ thống KHÔNG tự trả lời khách, KHÔNG hứa bảo hành hay hoàn tiền ở bảng này; đơn đã mua được tra lúc hiển thị (không sao chép vào bảng).
--   OPEN: đang chờ người thật xử lý.  DONE: người thật đã xử lý (chữ khách, tóm tắt và nháp bị xóa ngay).
-- inbound_text là văn bản khách ĐÃ CHE (PiiMasker, tối đa 500 ký tự); summary và draft_text do AI soạn từ văn bản đã che. Cả ba tự xóa sau 30 ngày.
-- Không lưu số điện thoại. Không bao giờ tự xóa khách (CLAUDE.md mục 5a).
CREATE TABLE warranty_case (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT       NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    invitation_id BIGINT       REFERENCES invitation (id) ON DELETE SET NULL,
    zalo_uid      TEXT         NOT NULL,
    state         TEXT         NOT NULL CHECK (state IN ('OPEN', 'DONE')),
    category      TEXT         NOT NULL CHECK (category IN ('LOI_SAN_PHAM', 'GIAO_HANG', 'HUONG_DAN', 'HOAN_TIEN', 'KHAC', 'CHUA_PHAN_LOAI')),
    urgency       TEXT         NOT NULL CHECK (urgency IN ('CAO', 'THUONG', 'THAP', 'CHUA_RO')),
    needs_photo   BOOLEAN      NOT NULL DEFAULT FALSE,
    summary       TEXT CHECK (summary IS NULL OR char_length(summary) <= 600),
    draft_text    TEXT CHECK (draft_text IS NULL OR char_length(draft_text) <= 1000),
    inbound_text  TEXT CHECK (inbound_text IS NULL OR char_length(inbound_text) <= 500),
    message_count INT          NOT NULL DEFAULT 1,
    inbound_at    TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_by   TEXT,
    assigned_to   TEXT
);

-- Mỗi khách (mã Zalo) chỉ có tối đa một việc đang mở; khách nhắn thêm thì chữ được ghi tiếp vào việc đó.
CREATE UNIQUE INDEX uq_warranty_case_open ON warranty_case (zalo_uid) WHERE state = 'OPEN';
CREATE INDEX idx_warranty_case_state ON warranty_case (state, created_at DESC);
