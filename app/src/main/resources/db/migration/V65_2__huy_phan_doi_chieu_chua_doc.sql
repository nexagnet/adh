ALTER TABLE zalo_friend_read_job
    ADD COLUMN started_at timestamptz,
    ADD COLUMN cancel_requested_at timestamptz,
    ADD COLUMN lease_invitation_id bigint;

ALTER TABLE zalo_friend_read_job DROP CONSTRAINT zalo_friend_read_job_campaign_id_account_id_key;
CREATE INDEX zalo_friend_read_job_campaign_history ON zalo_friend_read_job (campaign_id, account_id, id DESC);

ALTER TABLE zalo_friend_read_item DROP CONSTRAINT zalo_friend_read_item_result_check;
ALTER TABLE zalo_friend_read_item ADD CONSTRAINT zalo_friend_read_item_result_check
    CHECK (result IN ('READ', 'FAILED', 'SKIPPED', 'CANCELLED'));
