-- Tài khoản thứ hai chỉ được xếp lịch sau khi quản trị xác nhận danh tính thật của cầu nối.
UPDATE zalo_account SET enabled = false WHERE id <> 1 AND account_tag IS NULL;
ALTER TABLE zalo_account ALTER COLUMN enabled SET DEFAULT FALSE;
CREATE UNIQUE INDEX uq_zalo_account_tag ON zalo_account (account_tag) WHERE account_tag IS NOT NULL;
