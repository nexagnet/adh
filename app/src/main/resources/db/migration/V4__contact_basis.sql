-- Mốc 1B: sổ cơ sở liên hệ, danh sách từ chối, nhật ký, yêu cầu về dữ liệu của khách, ghi chú tay.
-- Quy tắc nền: KHÔNG có dòng contact_basis = KHÔNG được liên hệ (mặc định chặn).

CREATE TABLE contact_basis (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT      NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    purpose      TEXT        NOT NULL CHECK (purpose IN ('GROUP_INVITE_POSTSALE')),
    basis        TEXT        NOT NULL CHECK (basis IN ('TRANSACTION_POSTSALE', 'OPT_IN', 'NONE')),
    status       TEXT        NOT NULL CHECK (status IN ('ELIGIBLE', 'BLOCKED', 'OPTED_OUT')),
    -- bằng chứng: vận đơn giao thành công (hãng:mã) hoặc mô tả bản ghi đăng ký
    evidence_ref TEXT,
    -- D3: chủ cửa hàng tự chịu trách nhiệm; ghi rõ để sau này có thể xem lại
    statement    TEXT        NOT NULL DEFAULT '',
    note         TEXT,
    recorded_by  TEXT        NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_contact_basis UNIQUE (customer_id, purpose)
);
CREATE INDEX idx_contact_basis_status ON contact_basis (status);

-- Danh sách từ chối / đã xóa theo yêu cầu. Chỉ lưu BĂM SHA-256 của SĐT (E.164), không lưu SĐT:
-- tồn tại độc lập với khách để vẫn chặn liên hệ và chặn nhập lại sau khi dữ liệu khách đã bị xóa.
CREATE TABLE suppression (
    phone_hash  TEXT        PRIMARY KEY,
    reason      TEXT        NOT NULL CHECK (reason IN ('OPT_OUT', 'ERASED')),
    source      TEXT,
    recorded_by TEXT        NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Nhật ký bất biến (chỉ thêm). Không có khóa ngoại để còn nguyên sau khi xóa khách; chỉ lưu 3 số cuối của SĐT.
CREATE TABLE contact_event (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT,
    phone_tail  TEXT        NOT NULL,
    event       TEXT        NOT NULL,
    detail      TEXT,
    recorded_by TEXT        NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_contact_event_customer ON contact_event (customer_id);

-- Yêu cầu của khách về dữ liệu (xem/xuất/ngừng liên hệ/xóa) kèm hạn phản hồi.
CREATE TABLE data_request (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT        NOT NULL CHECK (kind IN ('ACCESS', 'EXPORT', 'STOP_CONTACT', 'ERASE')),
    customer_id BIGINT,
    phone_tail  TEXT        NOT NULL,
    received_on DATE        NOT NULL,
    due_on      DATE        NOT NULL,
    done_on     DATE,
    note        TEXT,
    recorded_by TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ghi chú tay của bố mẹ về khách (ví dụ khách quen, đã gọi điện).
CREATE TABLE customer_note (
    customer_id BIGINT      PRIMARY KEY REFERENCES customer (id) ON DELETE CASCADE,
    note        TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
