CREATE TABLE handoff_notification (
    handoff_id bigint PRIMARY KEY REFERENCES handoff(id) ON DELETE CASCADE,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SENDING', 'SENT', 'UNKNOWN')),
    queued_at timestamptz NOT NULL DEFAULT now(),
    attempted_at timestamptz,
    sent_at timestamptz
);
