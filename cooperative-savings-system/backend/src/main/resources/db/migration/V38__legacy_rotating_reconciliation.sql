-- Controlled reconciliation of rotating contributions recorded WITHOUT verification evidence (Phase 3).
--
-- A reconciliation records, with credible evidence and an independent approval, that part of a legacy contribution's
-- paid amount was really received. It creates NO ledger entry and changes NO balance (the money is already in the
-- ledger exactly once); it only makes that amount verified, and therefore payout-eligible. The obligation snapshot and
-- the original scheduled recipient are copied from what the obligation already carries: nothing is invented, and an
-- obligation without them cannot be reconciled.

CREATE TABLE legacy_rotating_reconciliations (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    contribution_id             UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    period_year                 INT             NOT NULL,
    period_month                INT             NOT NULL,
    rotation_period_id          UUID            NOT NULL,
    original_recipient_user_id  UUID            NOT NULL,
    amount                      NUMERIC(19,4)   NOT NULL,
    status                      VARCHAR(16)     NOT NULL,
    method                      VARCHAR(32)     NOT NULL,
    basis                       VARCHAR(1000)   NOT NULL,
    reference                   VARCHAR(128),
    reference_key               VARCHAR(170),
    requested_by                UUID            NOT NULL,
    requested_at                TIMESTAMPTZ     NOT NULL,
    decided_by                  UUID,
    decided_at                  TIMESTAMPTZ,
    decision_note               VARCHAR(2000),
    withdrawn_by                UUID,
    withdrawn_at                TIMESTAMPTZ,
    -- the contribution id while the request is open: at most one open request per obligation
    open_key                    UUID,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_legacy_reconciliations_contribution
        FOREIGN KEY (contribution_id, cooperative_id, member_user_id)
        REFERENCES contributions (id, cooperative_id, member_user_id),
    CONSTRAINT fk_legacy_reconciliations_period
        FOREIGN KEY (rotation_period_id, cooperative_id) REFERENCES rotation_periods (id, cooperative_id),
    CONSTRAINT fk_legacy_reconciliations_recipient
        FOREIGN KEY (original_recipient_user_id, cooperative_id)
        REFERENCES cooperative_memberships (user_id, cooperative_id),
    CONSTRAINT fk_legacy_reconciliations_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_legacy_reconciliations_decided_by FOREIGN KEY (decided_by) REFERENCES users (id),
    CONSTRAINT fk_legacy_reconciliations_withdrawn_by FOREIGN KEY (withdrawn_by) REFERENCES users (id),
    CONSTRAINT uq_legacy_reconciliations_open UNIQUE (open_key),
    -- the same external reference cannot back two live reconciliations in one scheme (duplicate reconciliation)
    CONSTRAINT uq_legacy_reconciliations_reference UNIQUE (cooperative_id, reference_key),
    CONSTRAINT uq_legacy_reconciliations_id_scope UNIQUE (id, cooperative_id),
    CONSTRAINT chk_legacy_reconciliations_amount CHECK (amount > 0),
    CONSTRAINT chk_legacy_reconciliations_status CHECK (status IN ('REQUESTED', 'APPROVED', 'DECLINED', 'WITHDRAWN')),
    CONSTRAINT chk_legacy_reconciliations_method CHECK (method IN (
        'CASH_RECEIPT_CONFIRMED', 'MOBILE_MONEY_RECONCILED', 'BANK_RECONCILED', 'PROVIDER_CONFIRMED'
    )),
    -- only a cash confirmation may omit the external reference
    CONSTRAINT chk_legacy_reconciliations_reference CHECK (method = 'CASH_RECEIPT_CONFIRMED' OR reference IS NOT NULL),
    CONSTRAINT chk_legacy_reconciliations_shape CHECK (
        (status = 'REQUESTED' AND decided_by IS NULL AND decided_at IS NULL
            AND withdrawn_by IS NULL AND withdrawn_at IS NULL AND open_key = contribution_id)
        OR (status = 'APPROVED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND withdrawn_by IS NULL AND withdrawn_at IS NULL AND open_key IS NULL)
        OR (status = 'DECLINED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_note IS NOT NULL
            AND withdrawn_by IS NULL AND withdrawn_at IS NULL AND open_key IS NULL AND reference_key IS NULL)
        OR (status = 'WITHDRAWN' AND withdrawn_by = requested_by AND withdrawn_at IS NOT NULL
            AND decided_by IS NULL AND decided_at IS NULL AND open_key IS NULL AND reference_key IS NULL)
    ),
    -- independence: neither the requester nor the payer decides; the payer never requests
    CONSTRAINT chk_legacy_reconciliations_independent CHECK (
        requested_by <> member_user_id
        AND (decided_by IS NULL OR (decided_by <> requested_by AND decided_by <> member_user_id))
    )
);

CREATE INDEX idx_legacy_reconciliations_scheme ON legacy_rotating_reconciliations (cooperative_id, status);
CREATE INDEX idx_legacy_reconciliations_contribution ON legacy_rotating_reconciliations (cooperative_id, contribution_id);

-- Immutable history of a reconciliation request.
CREATE TABLE legacy_rotating_reconciliation_events (
    id                  UUID            PRIMARY KEY,
    reconciliation_id   UUID            NOT NULL,
    cooperative_id      UUID            NOT NULL,
    actor_user_id       UUID            NOT NULL,
    actor_role          VARCHAR(64)     NOT NULL,
    from_status         VARCHAR(16),
    to_status           VARCHAR(16)     NOT NULL,
    comment             VARCHAR(2000),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_legacy_reconciliation_events_reconciliation
        FOREIGN KEY (reconciliation_id, cooperative_id) REFERENCES legacy_rotating_reconciliations (id, cooperative_id),
    CONSTRAINT fk_legacy_reconciliation_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT chk_legacy_reconciliation_events_to CHECK (to_status IN ('REQUESTED', 'APPROVED', 'DECLINED', 'WITHDRAWN')),
    CONSTRAINT chk_legacy_reconciliation_events_from CHECK (
        from_status IS NULL OR from_status IN ('REQUESTED', 'APPROVED', 'DECLINED', 'WITHDRAWN')
    )
);

CREATE INDEX idx_legacy_reconciliation_events ON legacy_rotating_reconciliation_events (cooperative_id, reconciliation_id, created_at);
