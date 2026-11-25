-- Mã người gửi chỉ có nghĩa trong tài khoản cầu nối đã nhận tin.
ALTER TABLE reply_draft ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);
ALTER TABLE warranty_case ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 REFERENCES zalo_account (id);

UPDATE reply_draft d SET account_id = i.account_id FROM invitation i WHERE d.invitation_id = i.id;
UPDATE warranty_case w SET account_id = i.account_id FROM invitation i WHERE w.invitation_id = i.id;

DROP INDEX uq_reply_draft_open;
CREATE UNIQUE INDEX uq_reply_draft_open ON reply_draft (account_id, zalo_uid)
    WHERE state IN ('DRAFT', 'READY', 'SENDING', 'HANDOFF');
DROP INDEX uq_warranty_case_open;
CREATE UNIQUE INDEX uq_warranty_case_open ON warranty_case (account_id, zalo_uid) WHERE state = 'OPEN';
