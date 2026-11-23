-- Nhập file "Lịch sử đối soát" của J&T (Dòng tiền -> Lịch sử đối soát -> Tải về).
-- Mỗi file là một kỳ đối soát (J&T đối soát thứ 2, 4, 6): hai dòng đầu là mã khách hàng và mã kỳ, tiêu đề ở dòng 3,
-- dòng cuối là "Tổng". File có tên, SĐT, địa chỉ khách nhưng bảng dưới đây KHÔNG lưu những thứ đó:
-- chỉ giữ mã vận đơn, mã đơn khách hàng, thời gian ký nhận và các khoản tiền. Khớp với đơn qua (hãng + mã vận đơn).

-- Lô nhập cũ là file danh sách đơn; lô mới là file đối soát. Hai loại dùng chung bảng lô để giữ file tạm giữa
-- bước xem trước và xác nhận, nhưng không lẫn vào danh sách lô nhập đơn và không tính vào mốc "cần xuất file từ ngày nào".
ALTER TABLE import_batch ADD COLUMN kind TEXT NOT NULL DEFAULT 'SHIPMENTS'
    CHECK (kind IN ('SHIPMENTS', 'SETTLEMENT'));

CREATE TABLE settlement_period (
    id            BIGSERIAL PRIMARY KEY,
    carrier       TEXT        NOT NULL,
    period_code   TEXT        NOT NULL,
    customer_code TEXT,
    period_from   DATE,
    period_to     DATE,
    -- tổng cuối file do chính J&T ghi, để đối chiếu với tổng cộng các dòng
    footer_cod    NUMERIC(15, 2),
    footer_net    NUMERIC(15, 2),
    last_batch_id BIGINT      NOT NULL REFERENCES import_batch (id),
    imported_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_settlement_period UNIQUE (carrier, period_code)
);

CREATE TABLE settlement_line (
    id            BIGSERIAL PRIMARY KEY,
    period_id     BIGINT         NOT NULL REFERENCES settlement_period (id) ON DELETE CASCADE,
    tracking_no   TEXT           NOT NULL,
    order_ref     TEXT,
    sent_at       TIMESTAMP,
    signed_at     TIMESTAMP,
    cod_collected NUMERIC(15, 2) NOT NULL,
    freight_fee   NUMERIC(15, 2) NOT NULL,
    cod_fee       NUMERIC(15, 2) NOT NULL,
    return_fee    NUMERIC(15, 2) NOT NULL,
    partial_fee   NUMERIC(15, 2) NOT NULL,
    adjustment    NUMERIC(15, 2) NOT NULL,
    net_payable   NUMERIC(15, 2) NOT NULL,
    adjusted_at   TIMESTAMP,
    note          TEXT,
    CONSTRAINT uq_settlement_line UNIQUE (period_id, tracking_no)
);
CREATE INDEX idx_settlement_line_tracking ON settlement_line (tracking_no);
