-- Lưu lần đọc và dấu gọi bổ nhiệm trước khi gọi Zalo. Không sửa nhóm hay lượt giữ chỗ.
CREATE TABLE zalo_group_deputy_check (
    group_id BIGINT PRIMARY KEY REFERENCES zalo_group(id) ON DELETE CASCADE,
    target_hash VARCHAR(64) NOT NULL,
    state TEXT NOT NULL,
    code TEXT,
    checked_at TIMESTAMPTZ,
    next_check_at TIMESTAMPTZ NOT NULL,
    last_write_at TIMESTAMPTZ,
    write_attempts INT NOT NULL DEFAULT 0 CHECK (write_attempts >= 0)
);
