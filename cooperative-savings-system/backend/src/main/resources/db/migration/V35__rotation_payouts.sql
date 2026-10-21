-- Controlled rotation payouts (Phase 2, workstreams B-D).
--
-- A rotating entitlement is DERIVED, never stored: for a rotation period it is the rotating part of what was actually
-- collected on the obligations bound to that period (cumulative-target split of their paid amounts), minus what was
-- really disbursed. These tables record only the payout operations themselves.
--
-- A payout is requested by one officer, approved by a different one, and becomes financially complete only when an
-- officer confirms the ACTUAL disbursement with a method and a reference; that confirmation writes the ledger debit.
-- Rows are never deleted, and each state needs the facts that justify it.

CREATE TABLE rotation_payouts (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    rotation_period_id      UUID            NOT NULL,
    recipient_user_id       UUID            NOT NULL,
    amount                  NUMERIC(19,4)   NOT NULL,
    -- What the system computed when the payout was requested (audit of why the amount is what it is).
    entitlement_at_request  NUMERIC(19,4)   NOT NULL,
    available_at_request    NUMERIC(19,4)   NOT NULL,
    status                  VARCHAR(16)     NOT NULL,
    idempotency_key         VARCHAR(128),
    notes                   VARCHAR(2000),

    requested_by            UUID            NOT NULL,
    requested_at            TIMESTAMPTZ     NOT NULL,
    approved_by             UUID,
    approved_at             TIMESTAMPTZ,
    rejected_by             UUID,
    rejected_at             TIMESTAMPTZ,
    rejection_reason        VARCHAR(2000),
    cancelled_by            UUID,
    cancelled_at            TIMESTAMPTZ,
    cancel_reason           VARCHAR(2000),
    disbursed_by            UUID,
    disbursed_at            TIMESTAMPTZ,
    disbursement_method     VARCHAR(32),
    disbursement_reference  VARCHAR(128),
    disbursement_note       VARCHAR(2000),
    ledger_entry_id         UUID,

    -- The period id while the payout is open (REQUESTED or APPROVED): at most one open payout per period, so a
    -- double click or a second officer cannot create a duplicate or over-commit the same entitlement.
    open_key                UUID,

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_rotation_payouts_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_rotation_payouts_period
        FOREIGN KEY (rotation_period_id, cooperative_id) REFERENCES rotation_periods (id, cooperative_id),
    CONSTRAINT fk_rotation_payouts_recipient
        FOREIGN KEY (recipient_user_id, cooperative_id) REFERENCES cooperative_memberships (user_id, cooperative_id),
    CONSTRAINT fk_rotation_payouts_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_rotation_payouts_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT fk_rotation_payouts_rejected_by FOREIGN KEY (rejected_by) REFERENCES users (id),
    CONSTRAINT fk_rotation_payouts_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id),
    CONSTRAINT fk_rotation_payouts_disbursed_by FOREIGN KEY (disbursed_by) REFERENCES users (id),
    CONSTRAINT fk_rotation_payouts_ledger FOREIGN KEY (ledger_entry_id) REFERENCES financial_ledger (id),
    CONSTRAINT uq_rotation_payouts_open UNIQUE (open_key),
    CONSTRAINT uq_rotation_payouts_idempotency UNIQUE (cooperative_id, requested_by, idempotency_key),
    CONSTRAINT uq_rotation_payouts_ledger UNIQUE (ledger_entry_id),
    CONSTRAINT uq_rotation_payouts_id_scope UNIQUE (id, cooperative_id),

    CONSTRAINT chk_rotation_payouts_amount CHECK (amount > 0),
    CONSTRAINT chk_rotation_payouts_basis CHECK (entitlement_at_request >= 0 AND available_at_request >= 0),
    -- never more than the entitlement the system computed at request time
    CONSTRAINT chk_rotation_payouts_within_entitlement CHECK (amount <= entitlement_at_request),
    CONSTRAINT chk_rotation_payouts_status CHECK (status IN ('REQUESTED', 'APPROVED', 'DISBURSED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_rotation_payouts_method CHECK (
        disbursement_method IS NULL OR disbursement_method IN ('MTN_MOMO', 'AIRTEL_MONEY', 'BANK_TRANSFER', 'CASH', 'OTHER')
    ),
    CONSTRAINT chk_rotation_payouts_open_key CHECK (
        (status IN ('REQUESTED', 'APPROVED') AND open_key = rotation_period_id)
        OR (status NOT IN ('REQUESTED', 'APPROVED') AND open_key IS NULL)
    ),
    CONSTRAINT chk_rotation_payouts_approved_fields CHECK (
        (status IN ('APPROVED', 'DISBURSED') AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR (status NOT IN ('APPROVED', 'DISBURSED') AND (approved_by IS NULL OR status IN ('CANCELLED')))
    ),
    CONSTRAINT chk_rotation_payouts_rejected_fields CHECK (
        (status = 'REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL)
        OR (status <> 'REJECTED' AND rejected_at IS NULL)
    ),
    CONSTRAINT chk_rotation_payouts_cancelled_fields CHECK (
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL)
    ),
    -- A payout is financially complete only with the actual disbursement facts and its ledger entry.
    CONSTRAINT chk_rotation_payouts_disbursed_fields CHECK (
        (status = 'DISBURSED'
            AND disbursed_by IS NOT NULL AND disbursed_at IS NOT NULL
            AND disbursement_method IS NOT NULL AND disbursement_reference IS NOT NULL
            AND ledger_entry_id IS NOT NULL)
        OR (status <> 'DISBURSED'
            AND disbursed_at IS NULL AND disbursement_reference IS NULL AND ledger_entry_id IS NULL)
    ),
    -- Independence: the requester, the approver and the recipient are all different people for the approval, and the
    -- recipient never requests, approves or confirms their own payout.
    CONSTRAINT chk_rotation_payouts_independent CHECK (
        requested_by <> recipient_user_id
        AND (approved_by IS NULL OR (approved_by <> requested_by AND approved_by <> recipient_user_id))
        AND (disbursed_by IS NULL OR disbursed_by <> recipient_user_id)
    )
);

CREATE INDEX idx_rotation_payouts_period ON rotation_payouts (cooperative_id, rotation_period_id, status);
CREATE INDEX idx_rotation_payouts_recipient ON rotation_payouts (cooperative_id, recipient_user_id);

-- Immutable status history (mirrors member_payment_events).
CREATE TABLE rotation_payout_events (
    id              UUID            PRIMARY KEY,
    payout_id       UUID            NOT NULL,
    cooperative_id  UUID            NOT NULL,
    actor_user_id   UUID            NOT NULL,
    actor_role      VARCHAR(64)     NOT NULL,
    from_status     VARCHAR(16),
    to_status       VARCHAR(16)     NOT NULL,
    comment         VARCHAR(2000),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_rotation_payout_events_payout FOREIGN KEY (payout_id, cooperative_id)
        REFERENCES rotation_payouts (id, cooperative_id),
    CONSTRAINT fk_rotation_payout_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT chk_rotation_payout_events_to CHECK (to_status IN ('REQUESTED', 'APPROVED', 'DISBURSED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_rotation_payout_events_from CHECK (
        from_status IS NULL OR from_status IN ('REQUESTED', 'APPROVED', 'DISBURSED', 'REJECTED', 'CANCELLED')
    )
);

CREATE INDEX idx_rotation_payout_events_payout ON rotation_payout_events (cooperative_id, payout_id, created_at);
