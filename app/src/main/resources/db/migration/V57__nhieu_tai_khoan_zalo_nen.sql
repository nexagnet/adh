-- D23 (25/11/2026, chủ dự án chốt): cho phép nhiều tài khoản Zalo phụ CỐ ĐỊNH chạy song song. Đây là PR NỀN, chưa đổi hành vi:
-- chỉ có MỘT tài khoản (mã 1) và mọi dòng cũ và mới đều thuộc tài khoản đó. Các PR sau mới gán tài khoản theo khách, hạn mức và cầu dao.
-- Mã Zalo (của khách, của mẹ, của nhóm) chỉ có nghĩa trong MỘT tài khoản, nên mọi bảng giữ mã Zalo phải biết tài khoản.
-- Khóa chung và địa chỉ cầu nối KHÔNG nằm ở đây (nằm ở .env); bảng chỉ giữ nhãn và dấu tài khoản (băm ngắn, không phải mã Zalo).
CREATE TABLE zalo_account (
    id          BIGSERIAL PRIMARY KEY,
    label       TEXT        NOT NULL,
    account_tag TEXT,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO zalo_account (id, label) VALUES (1, 'Tài khoản phụ 1');
SELECT setval(pg_get_serial_sequence('zalo_account', 'id'), 1);

ALTER TABLE invitation            ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);
ALTER TABLE zalo_msg_log          ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);
ALTER TABLE zalo_friend_event     ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);
ALTER TABLE zalo_group            ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);
ALTER TABLE zalo_group_proposal   ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);
ALTER TABLE zalo_health_alert     ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);
ALTER TABLE zalo_auto_log         ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);

-- Số thứ tự sự kiện (seq) do TỪNG cầu nối tự sinh, nên chỉ duy nhất trong một tài khoản.
ALTER TABLE zalo_friend_event DROP CONSTRAINT zalo_friend_event_bridge_seq_key;
ALTER TABLE zalo_friend_event ADD CONSTRAINT zalo_friend_event_account_seq_key UNIQUE (account_id, bridge_seq);
-- Mã Zalo của cùng một người khác nhau theo từng tài khoản hỏi, nên chỉ duy nhất trong một tài khoản.
DROP INDEX uq_invitation_zalo_uid;
CREATE UNIQUE INDEX uq_invitation_zalo_uid ON invitation (account_id, zalo_uid) WHERE zalo_uid IS NOT NULL;
-- Mỗi tài khoản có cảnh báo sức khỏe riêng, mỗi loại một dòng đang mở.
DROP INDEX uq_zalo_health_alert_open;
CREATE UNIQUE INDEX uq_zalo_health_alert_open ON zalo_health_alert (account_id, kind) WHERE resolved_at IS NULL;
