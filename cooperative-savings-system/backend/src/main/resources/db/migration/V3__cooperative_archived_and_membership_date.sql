-- Phase 3: ARCHIVED cooperative status, membership_date assurance, useful indexes
-- Note: partial unique indexes (WHERE ...) are PostgreSQL-only; uniqueness for
-- registration_number among non-deleted rows is enforced in CooperativeService.

ALTER TABLE cooperatives DROP CONSTRAINT chk_cooperatives_status;
ALTER TABLE cooperatives ADD CONSTRAINT chk_cooperatives_status
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED', 'ARCHIVED'));

-- membership_date already exists in V1 (DATE NOT NULL DEFAULT CURRENT_DATE). No-op for schema parity.

CREATE INDEX IF NOT EXISTS idx_cooperatives_name ON cooperatives (name);
CREATE INDEX IF NOT EXISTS idx_memberships_coop_status ON cooperative_memberships (cooperative_id, membership_status);
