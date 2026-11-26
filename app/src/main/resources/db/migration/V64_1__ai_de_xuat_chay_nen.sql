ALTER TABLE advisor_run DROP CONSTRAINT advisor_run_status_check;
ALTER TABLE advisor_run ADD CONSTRAINT advisor_run_status_check
    CHECK (status IN ('RUNNING', 'OK', 'SKIPPED', 'INVALID', 'UNAVAILABLE'));
CREATE UNIQUE INDEX uq_advisor_run_running ON advisor_run (status) WHERE status = 'RUNNING';
