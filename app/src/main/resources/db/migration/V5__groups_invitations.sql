-- Mốc 1C: nhóm Zalo, chiến dịch mời, lời mời có token riêng, cài đặt chung.
-- Chưa gửi tin cho ai: chỉ tạo bản ghi và theo dõi lượt bấm.

-- Cài đặt dạng khóa/giá trị (ví dụ cờ "tạm dừng tất cả chiến dịch").
CREATE TABLE app_setting (
    key        TEXT        PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT        NOT NULL DEFAULT 'system'
);

-- Nhóm Zalo do mẹ tạo, link nhập tay. Số khách KHÔNG gồm tài khoản mẹ và bot.
CREATE TABLE zalo_group (
    id               BIGSERIAL PRIMARY KEY,
    name             TEXT        NOT NULL,
    invite_link      TEXT,
    target_customers INT         NOT NULL DEFAULT 5 CHECK (target_customers BETWEEN 1 AND 500),
    actual_customers INT         NOT NULL DEFAULT 0 CHECK (actual_customers >= 0),
    -- lần cuối mẹ/quản trị đếm lại số khách thật; lượt bấm sau thời điểm này mới tính là "đang chờ"
    counted_at       TIMESTAMPTZ,
    status           TEXT        NOT NULL DEFAULT 'PREPARING'
                     CHECK (status IN ('PREPARING', 'OPEN', 'PAUSED', 'CLOSED')),
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invitation_campaign (
    id               BIGSERIAL PRIMARY KEY,
    name             TEXT        NOT NULL,
    message_template TEXT        NOT NULL,
    expires_days     INT         NOT NULL CHECK (expires_days BETWEEN 1 AND 90),
    created_by       TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Một lời mời cho một khách (mục 6 file 01). Token là chuỗi ngẫu nhiên 128 bit, không chứa SĐT.
-- Khách bị xóa theo yêu cầu thì lời mời bị xóa theo (ON DELETE CASCADE).
CREATE TABLE invitation (
    id               BIGSERIAL PRIMARY KEY,
    campaign_id      BIGINT      NOT NULL REFERENCES invitation_campaign (id) ON DELETE CASCADE,
    customer_id      BIGINT      NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    token            TEXT        NOT NULL UNIQUE,
    -- READY: đã tạo, chưa gửi. SENT: đã gửi/xuất để gửi. CLICKED: khách đã bấm nút (CHỈ là lượt bấm, không phải đã vào
    -- nhóm). CANCELLED: bị hủy sau khi đã gửi (hủy trước khi gửi thì xóa dòng để khách còn được mời lại).
    status           TEXT        NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'SENT', 'CLICKED', 'CANCELLED')),
    group_id         BIGINT      REFERENCES zalo_group (id) ON DELETE SET NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    sent_at          TIMESTAMPTZ,
    sent_channel     TEXT,
    first_clicked_at TIMESTAMPTZ,
    last_clicked_at  TIMESTAMPTZ,
    click_count      INT         NOT NULL DEFAULT 0,
    cancelled_reason TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Mỗi khách chỉ được mời MỘT lần (D5, mục 6.8 file 01: không nhắn thúc ép lặp lại).
CREATE UNIQUE INDEX uq_invitation_customer ON invitation (customer_id);
CREATE INDEX idx_invitation_campaign ON invitation (campaign_id, status);
CREATE INDEX idx_invitation_group ON invitation (group_id, first_clicked_at);
