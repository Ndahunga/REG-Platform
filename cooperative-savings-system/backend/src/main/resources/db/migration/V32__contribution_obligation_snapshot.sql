-- Period-specific contribution obligation snapshot and effective-dated due day (Task 3B-2).
--
-- Additive and non-destructive. NOTHING here moves money: no ledger entry, balance or payment is read or written, and
-- an obligation is never evidence that money was received. Compatible with PostgreSQL and H2 in PostgreSQL mode.
--
-- Why the existing contributions row can carry the snapshot
--   contributions already has one row per scheme, member, year and month (uq_contributions_period) and already stores the
--   expected amount and the share count that produced it. The new columns are all NULLABLE and describe how the rest of
--   the obligation was determined when the row was created. They are NULL for every pre-existing row, and the shape
--   check below makes them all-or-nothing, so a row can never claim half a snapshot.
--
-- Pre-existing rows vs new obligations
--   * Pre-existing rows: snapshot columns stay NULL. No due date, allocation percentage, model or payment time is
--     fabricated for them, and their paid_amount (a running aggregate that includes adjustments) is not reinterpreted.
--   * New rows for the current or a future month: snapshot written once, in the creating transaction, and never rewritten.
--   * New rows for a month that has already begun (late recording of history): NO snapshot, for the same reason.
--
-- Due day
--   contribution_due_day_settings is effective-dated and insert-only, like rotation_configurations. A scheme with no
--   row has NO configured due day and gets no overdue dates: the legacy contributions-day column on cooperatives
--   defaults to 1 for every scheme, so it cannot tell "chosen" from "never set" and is not used for arrears. A setting
--   applies from the first day of the month after it is made, so it can never backdate arrears.

CREATE TABLE contribution_due_day_settings (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID            NOT NULL,
    effective_year      INT             NOT NULL,
    effective_month     INT             NOT NULL,
    due_day             INT             NOT NULL,
    created_by          UUID            NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contribution_due_day_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_contribution_due_day_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_contribution_due_day_effective UNIQUE (cooperative_id, effective_year, effective_month),
    CONSTRAINT chk_contribution_due_day_year CHECK (effective_year BETWEEN 2000 AND 2100),
    CONSTRAINT chk_contribution_due_day_month CHECK (effective_month BETWEEN 1 AND 12),
    -- 29 to 31 fall back to the month's last day in months that are shorter.
    CONSTRAINT chk_contribution_due_day_value CHECK (due_day BETWEEN 1 AND 31)
);

CREATE INDEX idx_contribution_due_day_lookup
    ON contribution_due_day_settings (cooperative_id, effective_year, effective_month);

-- Lets a contribution reference a configuration only if it belongs to the same Saving Scheme.
ALTER TABLE rotation_configurations
    ADD CONSTRAINT uq_rotation_configurations_id_scope UNIQUE (id, cooperative_id);

ALTER TABLE contributions ADD COLUMN obligation_snapshot_at     TIMESTAMPTZ;
ALTER TABLE contributions ADD COLUMN unit_amount_snapshot       NUMERIC(19,4);
ALTER TABLE contributions ADD COLUMN due_date                   DATE;
ALTER TABLE contributions ADD COLUMN operating_model_snapshot   VARCHAR(16);
ALTER TABLE contributions ADD COLUMN rotation_configuration_id  UUID;
ALTER TABLE contributions ADD COLUMN rotation_period_id         UUID;
ALTER TABLE contributions ADD COLUMN rotating_bps_snapshot      INT;
ALTER TABLE contributions ADD COLUMN retained_bps_snapshot      INT;

ALTER TABLE contributions
    ADD CONSTRAINT fk_contributions_rotation_configuration
        FOREIGN KEY (rotation_configuration_id, cooperative_id)
        REFERENCES rotation_configurations (id, cooperative_id);
ALTER TABLE contributions
    ADD CONSTRAINT fk_contributions_rotation_period
        FOREIGN KEY (rotation_period_id, cooperative_id)
        REFERENCES rotation_periods (id, cooperative_id);

-- All-or-nothing: any snapshot column requires the snapshot timestamp, so pre-existing rows stay all NULL.
ALTER TABLE contributions ADD CONSTRAINT chk_contributions_snapshot_shape CHECK (
    obligation_snapshot_at IS NOT NULL
    OR (unit_amount_snapshot IS NULL
        AND due_date IS NULL
        AND operating_model_snapshot IS NULL
        AND rotation_configuration_id IS NULL
        AND rotation_period_id IS NULL
        AND rotating_bps_snapshot IS NULL
        AND retained_bps_snapshot IS NULL)
);
ALTER TABLE contributions ADD CONSTRAINT chk_contributions_snapshot_unit CHECK (
    unit_amount_snapshot IS NULL OR unit_amount_snapshot >= 0
);
ALTER TABLE contributions ADD CONSTRAINT chk_contributions_snapshot_model CHECK (
    operating_model_snapshot IS NULL OR operating_model_snapshot IN ('ACCUMULATING', 'ROTATING', 'HYBRID')
);
-- The split is both-or-neither, always totals 100%, and exists only for a rotating or hybrid model.
ALTER TABLE contributions ADD CONSTRAINT chk_contributions_snapshot_bps CHECK (
    (rotating_bps_snapshot IS NULL AND retained_bps_snapshot IS NULL AND rotation_configuration_id IS NULL)
    OR (rotating_bps_snapshot IS NOT NULL
        AND retained_bps_snapshot IS NOT NULL
        AND rotation_configuration_id IS NOT NULL
        AND rotating_bps_snapshot BETWEEN 1 AND 10000
        AND retained_bps_snapshot BETWEEN 0 AND 9999
        AND rotating_bps_snapshot + retained_bps_snapshot = 10000
        AND operating_model_snapshot IN ('ROTATING', 'HYBRID'))
);
