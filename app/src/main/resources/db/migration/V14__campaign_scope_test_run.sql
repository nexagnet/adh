-- Chiến dịch theo khu vực: chỉ lấy khách ở một miền/vùng/tỉnh (NULL = mọi nơi; 'U' = chưa rõ khu vực).
-- Cùng cách ghi mã với zalo_group.geo_scope: 'R:NAM', 'S:DONG_NAM_BO', 'P:can-tho'.
ALTER TABLE invitation_campaign ADD COLUMN geo_scope TEXT;

-- Chạy thử trên tài khoản Zalo thật (chưa chạy chiến dịch thật). TÁCH HẲN khỏi khách thật: không có khóa ngoại tới
-- customer/invitation, không ghi send_log, contact_event, danh sách từ chối, và không đổi số khách của nhóm Zalo.
CREATE TABLE test_run (
    id               BIGSERIAL PRIMARY KEY,
    name             TEXT        NOT NULL,
    message_template TEXT        NOT NULL,
    created_by       TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Số điện thoại do người dùng tự nhập (số của mình hoặc người quen đã đồng ý nhận tin thử).
CREATE TABLE test_target (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT      NOT NULL REFERENCES test_run (id) ON DELETE CASCADE,
    phone       TEXT        NOT NULL,
    name        TEXT,
    province    TEXT,
    status      TEXT        NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'SENT', 'FRIENDED', 'JOINED', 'DECLINED')),
    group_id    BIGINT      REFERENCES zalo_group (id) ON DELETE SET NULL,
    sent_at     TIMESTAMPTZ,
    friended_at TIMESTAMPTZ,
    joined_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_test_target_run ON test_target (run_id);
