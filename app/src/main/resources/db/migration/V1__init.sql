-- Mốc 1A: nhập đơn từ file của hãng vận chuyển, chuẩn hóa, phân loại khách.

CREATE TABLE import_batch (
    id                  BIGSERIAL PRIMARY KEY,
    carrier             TEXT        NOT NULL,
    filename            TEXT        NOT NULL,
    sha256              TEXT        NOT NULL,
    file_content        BYTEA       NOT NULL,
    status              TEXT        NOT NULL DEFAULT 'PREVIEW' CHECK (status IN ('PREVIEW', 'COMMITTED')),
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at        TIMESTAMPTZ,
    total_rows          INT,
    valid_rows          INT,
    new_shipments       INT,
    updated_shipments   INT,
    unchanged_shipments INT,
    status_changes      INT,
    error_rows          INT,
    duplicate_in_file   INT,
    unmapped_statuses   INT
);
CREATE INDEX idx_import_batch_sha ON import_batch (sha256);

CREATE TABLE import_error (
    id         BIGSERIAL PRIMARY KEY,
    batch_id   BIGINT NOT NULL REFERENCES import_batch (id) ON DELETE CASCADE,
    row_number INT    NOT NULL,
    code       TEXT   NOT NULL,
    message    TEXT   NOT NULL,
    raw_row    TEXT
);
CREATE INDEX idx_import_error_batch ON import_error (batch_id);

-- Cấu hình ánh xạ cột lần dùng gần nhất, theo từng hãng.
CREATE TABLE import_setting (
    carrier    TEXT PRIMARY KEY,
    header_row INT NOT NULL DEFAULT 1
);
CREATE TABLE column_mapping (
    carrier TEXT NOT NULL,
    field   TEXT NOT NULL,
    header  TEXT NOT NULL,
    PRIMARY KEY (carrier, field)
);

-- Ánh xạ trạng thái gốc của hãng -> trạng thái chuẩn. Khóa là chuỗi đã bỏ dấu, chữ thường.
CREATE TABLE status_mapping (
    carrier          TEXT NOT NULL,
    status_key       TEXT NOT NULL,
    sample_text      TEXT NOT NULL,
    canonical_status TEXT NOT NULL,
    PRIMARY KEY (carrier, status_key)
);

CREATE TABLE shipment (
    id                 BIGSERIAL PRIMARY KEY,
    carrier            TEXT        NOT NULL,
    tracking_no        TEXT        NOT NULL,
    order_ref          TEXT,
    receiver_phone_raw TEXT        NOT NULL,
    receiver_phone     TEXT        NOT NULL,
    receiver_name      TEXT,
    receiver_address   TEXT,
    created_on         TIMESTAMP,
    delivered_on       TIMESTAMP,
    raw_status         TEXT        NOT NULL,
    status_key         TEXT        NOT NULL,
    canonical_status   TEXT        NOT NULL,
    raw_payload        JSONB       NOT NULL,
    first_batch_id     BIGINT      NOT NULL REFERENCES import_batch (id),
    last_batch_id      BIGINT      NOT NULL REFERENCES import_batch (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_shipment_carrier_tracking UNIQUE (carrier, tracking_no)
);
CREATE INDEX idx_shipment_phone ON shipment (receiver_phone);
CREATE INDEX idx_shipment_status_key ON shipment (carrier, status_key);

-- Lịch sử đổi trạng thái: bằng chứng cho các ca "đã giao rồi hoàn".
CREATE TABLE shipment_status_history (
    id               BIGSERIAL PRIMARY KEY,
    shipment_id      BIGINT      NOT NULL REFERENCES shipment (id) ON DELETE CASCADE,
    batch_id         BIGINT      NOT NULL REFERENCES import_batch (id),
    raw_status       TEXT        NOT NULL,
    canonical_status TEXT        NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_status_history_shipment ON shipment_status_history (shipment_id);

-- Khách duy nhất theo SĐT đã chuẩn hóa (định danh liên hệ, không khẳng định là một người thật).
CREATE TABLE customer (
    id                   BIGSERIAL PRIMARY KEY,
    phone                TEXT        NOT NULL UNIQUE,
    name                 TEXT,
    address              TEXT,
    last_order_on        TIMESTAMP,
    segment              TEXT        NOT NULL CHECK (segment IN ('NO_SUCCESSFUL_PURCHASE', 'ONE_TIME', 'REPEAT')),
    successful_purchases INT         NOT NULL,
    needs_review         BOOLEAN     NOT NULL DEFAULT FALSE,
    review_reasons       TEXT        NOT NULL DEFAULT '',
    calculated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_segment ON customer (segment);

-- Trạng thái đã biết của Viettel Post (nguồn: hướng dẫn "Quản lý vận đơn" của Viettel Post).
-- "Chờ xử lý" cố ý KHÔNG được ánh xạ: ý nghĩa chưa rõ, để chủ cửa hàng tự quyết.
INSERT INTO status_mapping (carrier, status_key, sample_text, canonical_status) VALUES
    ('VIETTEL_POST', 'moi tao',          'Mới tạo',          'CREATED'),
    ('VIETTEL_POST', 'cho lay',          'Chờ lấy',          'CREATED'),
    ('VIETTEL_POST', 'da lay',           'Đã lấy',           'IN_TRANSIT'),
    ('VIETTEL_POST', 'dang van chuyen',  'Đang vận chuyển',  'IN_TRANSIT'),
    ('VIETTEL_POST', 'dang giao',        'Đang giao',        'IN_TRANSIT'),
    ('VIETTEL_POST', 'phat tiep',        'Phát tiếp',        'IN_TRANSIT'),
    ('VIETTEL_POST', 'giao thanh cong',  'Giao thành công',  'DELIVERED'),
    ('VIETTEL_POST', 'da duyet hoan',    'Đã duyệt hoàn',    'RETURNING'),
    ('VIETTEL_POST', 'dang chuyen hoan', 'Đang chuyển hoàn', 'RETURNING'),
    ('VIETTEL_POST', 'da tra',           'Đã trả',           'RETURNED'),
    ('VIETTEL_POST', 'da huy',           'Đã hủy',           'CANCELLED');
