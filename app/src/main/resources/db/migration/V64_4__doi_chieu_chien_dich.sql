CREATE TABLE zalo_friend_read_job (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id bigint NOT NULL REFERENCES invitation_campaign(id) ON DELETE CASCADE,
    account_id bigint NOT NULL REFERENCES zalo_account(id),
    next_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    waiting_reason text,
    lease_token uuid,
    UNIQUE (campaign_id, account_id)
);

-- Hạn mức và phiên Zalo dùng chung cho mọi chiến dịch của một tài khoản.
CREATE UNIQUE INDEX zalo_friend_read_job_active_account ON zalo_friend_read_job (account_id)
    WHERE finished_at IS NULL;

CREATE TABLE zalo_friend_read_item (
    job_id bigint NOT NULL REFERENCES zalo_friend_read_job(id) ON DELETE CASCADE,
    invitation_id bigint NOT NULL,
    attempt_at timestamptz NOT NULL,
    retries smallint NOT NULL DEFAULT 0 CHECK (retries BETWEEN 0 AND 3),
    result text CHECK (result IN ('READ', 'FAILED', 'SKIPPED')),
    PRIMARY KEY (job_id, invitation_id)
);
