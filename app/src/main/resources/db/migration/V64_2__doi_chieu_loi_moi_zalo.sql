CREATE TABLE zalo_friend_observation (
    account_id bigint NOT NULL REFERENCES zalo_account(id),
    invitation_id bigint NOT NULL REFERENCES invitation(id) ON DELETE CASCADE,
    state text NOT NULL CHECK (state IN ('FRIENDS', 'OUTGOING', 'INCOMING', 'NONE', 'UNAVAILABLE')),
    observed_at timestamptz NOT NULL,
    last_confirmed_state text CHECK (last_confirmed_state IN ('FRIENDS', 'OUTGOING', 'INCOMING', 'NONE')),
    last_confirmed_at timestamptz,
    needs_review boolean NOT NULL,
    PRIMARY KEY (account_id, invitation_id)
);

CREATE TABLE zalo_friend_observation_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id bigint NOT NULL,
    invitation_id bigint NOT NULL,
    state text NOT NULL,
    observed_at timestamptz NOT NULL,
    expected_outcome text NOT NULL CHECK (expected_outcome IN ('SENT', 'UNKNOWN')),
    needs_review boolean NOT NULL,
    FOREIGN KEY (account_id, invitation_id) REFERENCES zalo_friend_observation(account_id, invitation_id) ON DELETE CASCADE
);
CREATE INDEX zalo_friend_observation_event_target_idx
    ON zalo_friend_observation_event (account_id, invitation_id, observed_at DESC);
