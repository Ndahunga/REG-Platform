-- Rotation configuration, cycles, the monthly schedule and recipient assignments (Task 3B-1).
--
-- STORAGE ONLY. Nothing here is a financial record: no ledger entry, balance, obligation, arrear or distribution reads or
-- writes these tables yet. Contribution formulas are unchanged. Additive; no existing table is altered.
-- Compatible with PostgreSQL (production) and H2 in PostgreSQL mode (tests): no partial indexes, no triggers.
--
-- Safe cancellation and replacement without deleting history
--   A plain UNIQUE (cooperative_id, period_year, period_month) on the schedule would let a cancelled or draft cycle
--   block a valid replacement forever. Instead every schedule row carries slot_key ('YYYY-MM'), which is NULL unless the
--   row currently holds its month in an ACTIVE (or completed) cycle, and UNIQUE (cooperative_id, slot_key) guarantees at
--   most one live period per month per scheme. NULLs never collide, so DRAFT cycles may overlap freely and a CANCELLED
--   cycle releases its months (slot_key set to NULL) while its rows stay as history. Activation sets the keys, so two
--   overlapping activations cannot both succeed even if application checks race: the database refuses the second.
--
-- Scheme-scoped integrity
--   Composite foreign keys tie every period to a cycle of the SAME scheme and every scheduled recipient to a membership of
--   the SAME scheme, so a recipient or cycle from another scheme is structurally impossible.
--
-- Immutability
--   rotation_configurations and rotation_recipient_changes are insert-only (the application never updates or deletes
--   them). Which configuration governs a period is derived from effective dates, never stored on the schedule, so a
--   later configuration cannot alter an earlier period; obligations (Task 3B-2) will snapshot it.

CREATE TABLE rotation_configurations (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID            NOT NULL,
    effective_year      INT             NOT NULL,
    effective_month     INT             NOT NULL,
    rotating_bps        INT             NOT NULL,
    retained_bps        INT             NOT NULL,
    created_by          UUID            NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_rotation_configurations_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_rotation_configurations_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_rotation_configurations_effective UNIQUE (cooperative_id, effective_year, effective_month),
    CONSTRAINT chk_rotation_configurations_year CHECK (effective_year BETWEEN 2000 AND 2100),
    CONSTRAINT chk_rotation_configurations_month CHECK (effective_month BETWEEN 1 AND 12),
    -- ROTATING is 10000/0; HYBRID has both portions positive. The two always total exactly 100%.
    CONSTRAINT chk_rotation_configurations_bps CHECK (
        rotating_bps BETWEEN 1 AND 10000
        AND retained_bps BETWEEN 0 AND 9999
        AND rotating_bps + retained_bps = 10000
    )
);

CREATE INDEX idx_rotation_configurations_lookup
    ON rotation_configurations (cooperative_id, effective_year, effective_month);

CREATE TABLE rotation_cycles (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID            NOT NULL,
    cycle_number        INT             NOT NULL,
    start_year          INT             NOT NULL,
    start_month         INT             NOT NULL,
    period_count        INT             NOT NULL,
    status              VARCHAR(16)     NOT NULL,
    idempotency_key     VARCHAR(128),
    created_by          UUID            NOT NULL,
    activated_by        UUID,
    activated_at        TIMESTAMPTZ,
    cancelled_by        UUID,
    cancelled_at        TIMESTAMPTZ,
    cancel_reason       VARCHAR(1000),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_rotation_cycles_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_rotation_cycles_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_rotation_cycles_activated_by FOREIGN KEY (activated_by) REFERENCES users (id),
    CONSTRAINT fk_rotation_cycles_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id),
    CONSTRAINT uq_rotation_cycles_number UNIQUE (cooperative_id, cycle_number),
    CONSTRAINT uq_rotation_cycles_idempotency UNIQUE (cooperative_id, idempotency_key),
    CONSTRAINT uq_rotation_cycles_id_scope UNIQUE (id, cooperative_id),
    CONSTRAINT chk_rotation_cycles_status CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_rotation_cycles_start CHECK (
        start_year BETWEEN 2000 AND 2100 AND start_month BETWEEN 1 AND 12
    ),
    CONSTRAINT chk_rotation_cycles_count CHECK (period_count BETWEEN 1 AND 240),
    CONSTRAINT chk_rotation_cycles_cancel CHECK (
        status <> 'CANCELLED'
        OR (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL)
    ),
    CONSTRAINT chk_rotation_cycles_activation CHECK (
        status NOT IN ('ACTIVE', 'COMPLETED') OR (activated_by IS NOT NULL AND activated_at IS NOT NULL)
    )
);

CREATE INDEX idx_rotation_cycles_scheme ON rotation_cycles (cooperative_id, status);

CREATE TABLE rotation_periods (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    cycle_id                    UUID            NOT NULL,
    sequence_number             INT             NOT NULL,
    period_year                 INT             NOT NULL,
    period_month                INT             NOT NULL,
    scheduled_recipient_user_id UUID            NOT NULL,
    status                      VARCHAR(16)     NOT NULL DEFAULT 'SCHEDULED',
    slot_key                    VARCHAR(7),
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_rotation_periods_cycle FOREIGN KEY (cycle_id, cooperative_id)
        REFERENCES rotation_cycles (id, cooperative_id),
    CONSTRAINT fk_rotation_periods_recipient FOREIGN KEY (scheduled_recipient_user_id, cooperative_id)
        REFERENCES cooperative_memberships (user_id, cooperative_id),
    CONSTRAINT uq_rotation_periods_id_scope UNIQUE (id, cooperative_id),
    CONSTRAINT uq_rotation_periods_sequence UNIQUE (cycle_id, sequence_number),
    CONSTRAINT uq_rotation_periods_month UNIQUE (cycle_id, period_year, period_month),
    -- One turn per member per cycle (cycle-scoped, so another or a cancelled cycle never interferes).
    CONSTRAINT uq_rotation_periods_recipient UNIQUE (cycle_id, scheduled_recipient_user_id),
    -- At most one LIVE period per month per scheme; NULL (draft, cancelled) never collides.
    CONSTRAINT uq_rotation_periods_live_slot UNIQUE (cooperative_id, slot_key),
    CONSTRAINT chk_rotation_periods_status CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    CONSTRAINT chk_rotation_periods_year CHECK (period_year BETWEEN 2000 AND 2100),
    CONSTRAINT chk_rotation_periods_month CHECK (period_month BETWEEN 1 AND 12),
    CONSTRAINT chk_rotation_periods_sequence CHECK (sequence_number >= 1),
    CONSTRAINT chk_rotation_periods_cancelled_slot CHECK (status = 'SCHEDULED' OR slot_key IS NULL)
);

CREATE INDEX idx_rotation_periods_month ON rotation_periods (cooperative_id, period_year, period_month);
CREATE INDEX idx_rotation_periods_recipient ON rotation_periods (cooperative_id, scheduled_recipient_user_id);

CREATE TABLE rotation_recipient_changes (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    rotation_period_id          UUID            NOT NULL,
    previous_recipient_user_id  UUID            NOT NULL,
    new_recipient_user_id       UUID            NOT NULL,
    reason                      VARCHAR(1000)   NOT NULL,
    changed_by                  UUID            NOT NULL,
    changed_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_rotation_changes_period FOREIGN KEY (rotation_period_id, cooperative_id)
        REFERENCES rotation_periods (id, cooperative_id),
    CONSTRAINT fk_rotation_changes_previous FOREIGN KEY (previous_recipient_user_id, cooperative_id)
        REFERENCES cooperative_memberships (user_id, cooperative_id),
    CONSTRAINT fk_rotation_changes_new FOREIGN KEY (new_recipient_user_id, cooperative_id)
        REFERENCES cooperative_memberships (user_id, cooperative_id),
    CONSTRAINT fk_rotation_changes_by FOREIGN KEY (changed_by) REFERENCES users (id),
    CONSTRAINT chk_rotation_changes_different CHECK (previous_recipient_user_id <> new_recipient_user_id),
    CONSTRAINT chk_rotation_changes_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
);

CREATE INDEX idx_rotation_changes_period ON rotation_recipient_changes (rotation_period_id, changed_at);
