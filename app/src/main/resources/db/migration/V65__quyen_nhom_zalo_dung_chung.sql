ALTER TABLE zalo_group ADD COLUMN shared_add_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE zalo_group ADD COLUMN shared_identity_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE zalo_account ADD COLUMN shared_identity_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE zalo_group_add_grant (
    group_id        BIGINT NOT NULL REFERENCES zalo_group(id) ON DELETE CASCADE,
    account_id      BIGINT NOT NULL REFERENCES zalo_account(id),
    bridge_group_id TEXT   NOT NULL CHECK (bridge_group_id ~ '^[0-9]{1,32}$'),
    account_tag     TEXT   NOT NULL CHECK (account_tag ~ '^[0-9a-f]{12}$'),
    owner_tag       TEXT   NOT NULL CHECK (owner_tag ~ '^[0-9a-f]{12}$'),
    revision        BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    state           TEXT   NOT NULL CHECK (state IN ('APPROVED', 'REVIEW', 'REVOKED', 'SUSPENDED')),
    approved_by     TEXT   NOT NULL,
    verified_at     TIMESTAMPTZ NOT NULL,
    changed_by      TEXT   NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, account_id),
    CHECK (account_tag <> owner_tag)
);

CREATE UNIQUE INDEX uq_zalo_group_add_grant_mapping
    ON zalo_group_add_grant (account_id, bridge_group_id) WHERE state = 'APPROVED';

-- Đổi mã gốc, chủ nhóm hoặc link làm mất bằng chứng cũ. Khôi phục giá trị cũ vẫn phải xác minh lại.
CREATE FUNCTION invalidate_zalo_group_grants() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.account_id, NEW.zalo_group_id, NEW.invite_link)
        IS DISTINCT FROM (OLD.account_id, OLD.zalo_group_id, OLD.invite_link) THEN
        NEW.shared_identity_revision := OLD.shared_identity_revision + 1;
        UPDATE zalo_group_add_grant
        SET state = 'REVIEW', revision = revision + 1, changed_by = 'system', changed_at = now()
        WHERE group_id = OLD.id AND state = 'APPROVED';
    ELSIF NEW.shared_add_enabled IS DISTINCT FROM OLD.shared_add_enabled THEN
        UPDATE zalo_group_add_grant
        SET revision = revision + 1, changed_by = 'system', changed_at = now()
        WHERE group_id = OLD.id AND state = 'APPROVED';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER zalo_group_shared_identity
    BEFORE UPDATE OF account_id, zalo_group_id, invite_link, shared_add_enabled ON zalo_group
    FOR EACH ROW EXECUTE FUNCTION invalidate_zalo_group_grants();

CREATE FUNCTION invalidate_zalo_account_group_grants() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.account_tag IS DISTINCT FROM OLD.account_tag THEN
        NEW.shared_identity_revision := OLD.shared_identity_revision + 1;
        UPDATE zalo_group_add_grant a
        SET state = 'REVIEW', revision = revision + 1, changed_by = 'system', changed_at = now()
        FROM zalo_group g
        WHERE g.id = a.group_id AND (a.account_id = OLD.id OR g.account_id = OLD.id)
            AND a.state = 'APPROVED';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER zalo_account_shared_identity
    BEFORE UPDATE OF account_tag ON zalo_account
    FOR EACH ROW EXECUTE FUNCTION invalidate_zalo_account_group_grants();
