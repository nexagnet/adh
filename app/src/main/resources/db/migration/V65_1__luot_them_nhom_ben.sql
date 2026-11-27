ALTER TABLE invitation ADD COLUMN zalo_group_add_counted boolean NOT NULL DEFAULT false;

-- Một lượt đã phát lệnh giữ chỗ đến khi xác nhận được kết quả.
CREATE TABLE zalo_group_add_attempt (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invitation_id bigint REFERENCES invitation(id) ON DELETE SET NULL,
    group_id bigint NOT NULL REFERENCES zalo_group(id),
    account_id bigint NOT NULL REFERENCES zalo_account(id),
    log_id bigint NOT NULL UNIQUE REFERENCES zalo_auto_log(id) ON DELETE CASCADE,
    bridge_group_id text,
    restored_evidence boolean NOT NULL DEFAULT false,
    grant_revision bigint,
    state text NOT NULL CHECK (state IN ('DISPATCHED', 'REVIEW', 'JOINED', 'NOT_ADDED', 'RECOUNTED')),
    previous_state text CHECK (previous_state IN ('QUEUED', 'WAITING_APPROVAL')),
    dispatched_at timestamptz NOT NULL DEFAULT now(),
    bridge_claimed_at timestamptz,
    finished_at timestamptz,
    result text,
    provider_outcome text,
    provider_code text,
    resolution_note text,
    resolved_by text,
    CHECK (restored_evidence OR (bridge_group_id IS NOT NULL AND previous_state IS NOT NULL))
);
CREATE UNIQUE INDEX zalo_group_add_attempt_active_invitation ON zalo_group_add_attempt(invitation_id)
    WHERE state IN ('DISPATCHED', 'REVIEW');
CREATE INDEX zalo_group_add_attempt_active_group ON zalo_group_add_attempt(group_id, invitation_id)
    WHERE state IN ('DISPATCHED', 'REVIEW');
CREATE UNIQUE INDEX zalo_group_add_attempt_dispatch_account ON zalo_group_add_attempt(account_id)
    WHERE state = 'DISPATCHED';

-- Sau khi xóa lời mời, chỉ giữ dấu vết vận hành. Lý do do người nhập có thể chứa dữ liệu nhận diện.
CREATE FUNCTION clear_erased_group_add_note() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.resolution_note := NULL;
    RETURN NEW;
END
$$;

CREATE TRIGGER zalo_group_add_erased_invitation
    BEFORE UPDATE OF invitation_id ON zalo_group_add_attempt
    FOR EACH ROW WHEN (OLD.invitation_id IS NOT NULL AND NEW.invitation_id IS NULL)
    EXECUTE FUNCTION clear_erased_group_add_note();
