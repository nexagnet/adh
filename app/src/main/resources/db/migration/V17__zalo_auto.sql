-- D16 (bộ nối Zalo không chính thức, mặc định TẮT): nhận biết khách đã chấp nhận kết bạn trên tài khoản Zalo phụ và thêm
-- khách vào nhóm. Bảng chỉ lưu thứ tối thiểu: mã Zalo (uid) chỉ nằm ở lời mời đã ghép và ở hàng đợi xử lý tay.
-- KHÔNG lưu số điện thoại, tên của khách trong bất kỳ bảng nào ở đây.

-- Mã nhóm trong Zalo (khác link mời): bộ nối cần mã này để thêm người. Nhập tay ở mục Nhóm Zalo. Nhóm không có mã thì
-- không bao giờ được thêm người tự động.
ALTER TABLE zalo_group ADD COLUMN zalo_group_id TEXT;

-- Dấu vết trên lời mời: uid đã ghép, và việc "thêm vào nhóm bằng bộ nối" đang ở đâu.
--   WAITING_APPROVAL: chờ người duyệt (nấc CONFIRM/LISTEN_ONLY); QUEUED: đã được phép, chờ tới lượt trong hạn mức;
--   ADDED: bộ nối đã thêm; FAILED: lỗi theo từng người, KHÔNG tự thử lại (người xử lý tay).
ALTER TABLE invitation ADD COLUMN zalo_uid TEXT;
ALTER TABLE invitation ADD COLUMN zalo_add_state TEXT
    CHECK (zalo_add_state IN ('WAITING_APPROVAL', 'QUEUED', 'ADDED', 'FAILED'));
ALTER TABLE invitation ADD COLUMN zalo_add_code TEXT;
ALTER TABLE invitation ADD COLUMN zalo_add_at TIMESTAMPTZ;
ALTER TABLE invitation ADD COLUMN zalo_approved_by TEXT;
CREATE UNIQUE INDEX uq_invitation_zalo_uid ON invitation (zalo_uid) WHERE zalo_uid IS NOT NULL;
CREATE INDEX idx_invitation_zalo_state ON invitation (zalo_add_state) WHERE zalo_add_state IS NOT NULL;

-- Sự kiện "đã thành bạn" bộ nối báo về. Chỉ giữ uid khi còn cần xử lý tay (NO_PHONE); các loại khác xóa uid ngay.
--   MATCHED: ghép được lời mời; NO_PHONE: không lấy được số (đa số khách ẩn số) -> hàng đợi xử lý tay;
--   STRANGER: có số nhưng không khớp lời mời nào đã nhắn (người lạ, bạn của mẹ): không làm gì;
--   BLOCKED: khớp khách đã từ chối hoặc không còn đủ điều kiện: không làm gì;
--   RESOLVED / DISMISSED: người xử lý hàng đợi tay (gắn với khách / bỏ qua).
CREATE TABLE zalo_friend_event (
    id            BIGSERIAL PRIMARY KEY,
    bridge_seq    BIGINT      NOT NULL UNIQUE,
    uid           TEXT,
    event_at      TIMESTAMPTZ NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome       TEXT        NOT NULL
                  CHECK (outcome IN ('MATCHED', 'NO_PHONE', 'STRANGER', 'BLOCKED', 'RESOLVED', 'DISMISSED')),
    invitation_id BIGINT      REFERENCES invitation (id) ON DELETE SET NULL
);
CREATE INDEX idx_zalo_friend_event_open ON zalo_friend_event (outcome) WHERE outcome = 'NO_PHONE';

-- Nhật ký của bộ nối. Hành động: NGHE, GHEP, DUYET, THEM_NHOM, DUNG, MO_LAI, CAU_HINH, DANG_NHAP, DANG_XUAT.
-- Không có cột số điện thoại hay tên; uid không bao giờ vào đây.
CREATE TABLE zalo_auto_log (
    id            BIGSERIAL PRIMARY KEY,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    action        TEXT        NOT NULL,
    invitation_id BIGINT      REFERENCES invitation (id) ON DELETE SET NULL,
    group_id      BIGINT      REFERENCES zalo_group (id) ON DELETE SET NULL,
    result        TEXT        NOT NULL,
    code          TEXT,
    actor         TEXT        NOT NULL DEFAULT 'system'
);
CREATE INDEX idx_zalo_auto_log_action ON zalo_auto_log (action, at);
