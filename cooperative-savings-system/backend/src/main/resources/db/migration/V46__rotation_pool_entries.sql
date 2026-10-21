-- The rotating pool of a Rotating or Hybrid Saving Scheme: what was set aside for recipients before the verified
-- contributions started to arrive (OPENING), added to it later (TOP_UP) or released from it (EXPENSE).
--
-- These records are EARMARKS inside the scheme's available funds, not cash movements: they create no ledger entry and
-- never add to the scheme's money (the cash is already counted in the opening available funds or in the contribution
-- cash). They only say how much of that cash may cover a recipient's shortfall when the period's own verified
-- collections fall short of what was scheduled. Entries are immutable and carry who recorded them and why; a correction
-- is a new entry.

CREATE TABLE rotation_pool_entries (
    id              UUID            PRIMARY KEY,
    cooperative_id  UUID            NOT NULL,
    entry_type      VARCHAR(16)     NOT NULL,
    amount          NUMERIC(19,4)   NOT NULL,
    effective_date  DATE            NOT NULL,
    reason          VARCHAR(500)    NOT NULL,
    reference       VARCHAR(128),
    idempotency_key VARCHAR(128),
    -- set to the cooperative id on the OPENING entry only, so the database allows at most one opening pool per scheme
    opening_key     UUID,
    recorded_by     UUID            NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_rotation_pool_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_rotation_pool_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT chk_rotation_pool_type CHECK (entry_type IN ('OPENING', 'TOP_UP', 'EXPENSE')),
    CONSTRAINT chk_rotation_pool_amount CHECK (amount > 0),
    -- the key exists exactly on the OPENING entry and is the cooperative id (written so that a missing key is refused
    -- rather than passing as an unknown)
    CONSTRAINT chk_rotation_pool_opening_key CHECK (
        (CASE WHEN entry_type = 'OPENING' THEN 1 ELSE 0 END) = (CASE WHEN opening_key IS NULL THEN 0 ELSE 1 END)),
    CONSTRAINT chk_rotation_pool_opening_key_value CHECK (opening_key IS NULL OR opening_key = cooperative_id),
    CONSTRAINT uq_rotation_pool_opening UNIQUE (opening_key),
    CONSTRAINT uq_rotation_pool_idempotency UNIQUE (cooperative_id, idempotency_key)
);

CREATE INDEX idx_rotation_pool_cooperative ON rotation_pool_entries (cooperative_id, created_at);
