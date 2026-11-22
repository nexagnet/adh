-- Mã QR/link chung in trên giấy bỏ vào gói hàng: khách quét không có mã riêng, không biết là ai.
-- Mỗi điện thoại (nhớ bằng cookie ngẫu nhiên 128 bit) chỉ được tính một lần vào một nhóm.
-- Chỉ lưu mã ngẫu nhiên của trình duyệt: KHÔNG lưu số điện thoại, tên, địa chỉ IP.
CREATE TABLE open_entry (
    id         BIGSERIAL PRIMARY KEY,
    visitor    TEXT        NOT NULL UNIQUE,
    group_id   BIGINT      NOT NULL REFERENCES zalo_group (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_open_entry_group ON open_entry (group_id, created_at);
