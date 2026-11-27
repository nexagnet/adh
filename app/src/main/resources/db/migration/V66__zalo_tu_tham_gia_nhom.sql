CREATE TABLE zalo_shared_group_join (
    group_id bigint NOT NULL REFERENCES zalo_group(id) ON DELETE CASCADE,
    account_id bigint NOT NULL REFERENCES zalo_account(id),
    owner_group_id varchar(32) NOT NULL,
    group_revision bigint NOT NULL,
    owner_revision bigint NOT NULL,
    actor_revision bigint NOT NULL,
    grant_revision bigint NOT NULL,
    approved_by text NOT NULL,
    activate boolean NOT NULL DEFAULT false,
    state text NOT NULL CHECK (state IN ('DISPATCHED', 'PENDING', 'UNKNOWN', 'NOT_DISPATCHED', 'VERIFIED', 'CHANGED')),
    code varchar(48),
    attempt bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    next_check_at timestamptz,
    PRIMARY KEY (group_id, account_id)
);

CREATE INDEX ix_zalo_shared_group_join_due ON zalo_shared_group_join(next_check_at)
WHERE next_check_at IS NOT NULL;
