-- Controlled reversal of POSTED contribution-only payments (Phase 2, workstream A2).
--
-- Additive only. A reversal is a request that a DIFFERENT authorized officer approves; approval executes the
-- compensating accounting in the same transaction. Original payments, allocations, posting rows and ledger entries are
-- never updated or deleted: the compensation is recorded in new rows (member_payment_posting_reversals) and, in the
-- ledger, by the existing REVERSAL entry type that the contribution accounting already uses.

CREATE TABLE member_payment_reversals (
    id                  UUID            PRIMARY KEY,
    payment_id          UUID            NOT NULL,
    cooperative_id      UUID            NOT NULL,
    member_user_id      UUID            NOT NULL,
    status              VARCHAR(16)     NOT NULL,
    reason              VARCHAR(2000)   NOT NULL,
    requested_by        UUID            NOT NULL,
    requested_at        TIMESTAMPTZ     NOT NULL,
    decided_by          UUID,
    decided_at          TIMESTAMPTZ,
    decision_note       VARCHAR(2000),
    -- payment id while the request is open / once it was executed: UNIQUE makes a second open request, or a second
    -- execution, impossible. NULL keys never collide, so declined requests do not block a new one.
    open_key            UUID,
    executed_key        UUID,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_payment_reversals_payment
        FOREIGN KEY (payment_id, cooperative_id, member_user_id)
        REFERENCES member_payments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_payment_reversals_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_payment_reversals_decided_by FOREIGN KEY (decided_by) REFERENCES users (id),
    CONSTRAINT uq_payment_reversals_open UNIQUE (open_key),
    CONSTRAINT uq_payment_reversals_executed UNIQUE (executed_key),
    CONSTRAINT uq_payment_reversals_id_scope UNIQUE (id, cooperative_id),
    CONSTRAINT chk_payment_reversals_status CHECK (status IN ('REQUESTED', 'EXECUTED', 'DECLINED')),
    CONSTRAINT chk_payment_reversals_shape CHECK (
        (status = 'REQUESTED'
            AND decided_by IS NULL AND decided_at IS NULL
            AND open_key = payment_id AND executed_key IS NULL)
        OR (status = 'EXECUTED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND open_key IS NULL AND executed_key = payment_id)
        OR (status = 'DECLINED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_note IS NOT NULL
            AND open_key IS NULL AND executed_key IS NULL)
    ),
    -- Independence: neither the payer nor the requester may decide, and the payer may not request.
    CONSTRAINT chk_payment_reversals_independent CHECK (
        requested_by <> member_user_id
        AND (decided_by IS NULL OR (decided_by <> requested_by AND decided_by <> member_user_id))
    )
);

CREATE INDEX idx_payment_reversals_scheme_status ON member_payment_reversals (cooperative_id, status);

-- What a reversal took back from each posted contribution allocation (one row per posting, ever).
CREATE TABLE member_payment_posting_reversals (
    id                          UUID            PRIMARY KEY,
    reversal_id                 UUID            NOT NULL,
    posting_id                  UUID            NOT NULL,
    payment_id                  UUID            NOT NULL,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    contribution_id             UUID            NOT NULL,
    period_year                 INT             NOT NULL,
    period_month                INT             NOT NULL,
    amount                      NUMERIC(19,4)   NOT NULL,
    paid_before                 NUMERIC(19,4)   NOT NULL,
    paid_after                  NUMERIC(19,4)   NOT NULL,
    rotating_amount             NUMERIC(19,4),
    retained_amount             NUMERIC(19,4),
    rotation_period_id          UUID,
    original_recipient_user_id  UUID,
    ledger_entry_id             UUID,
    reversed_by                 UUID            NOT NULL,
    reversed_at                 TIMESTAMPTZ     NOT NULL,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_posting_reversals_reversal
        FOREIGN KEY (reversal_id, cooperative_id) REFERENCES member_payment_reversals (id, cooperative_id),
    CONSTRAINT fk_posting_reversals_posting FOREIGN KEY (posting_id) REFERENCES member_payment_postings (id),
    CONSTRAINT fk_posting_reversals_payment
        FOREIGN KEY (payment_id, cooperative_id, member_user_id)
        REFERENCES member_payments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_posting_reversals_contribution
        FOREIGN KEY (contribution_id, cooperative_id, member_user_id)
        REFERENCES contributions (id, cooperative_id, member_user_id),
    CONSTRAINT fk_posting_reversals_period
        FOREIGN KEY (rotation_period_id, cooperative_id) REFERENCES rotation_periods (id, cooperative_id),
    CONSTRAINT fk_posting_reversals_ledger FOREIGN KEY (ledger_entry_id) REFERENCES financial_ledger (id),
    CONSTRAINT fk_posting_reversals_by FOREIGN KEY (reversed_by) REFERENCES users (id),
    CONSTRAINT uq_posting_reversals_posting UNIQUE (posting_id),
    CONSTRAINT chk_posting_reversals_amount CHECK (amount > 0),
    CONSTRAINT chk_posting_reversals_paid CHECK (
        paid_before >= 0 AND paid_after >= 0 AND paid_after = paid_before - amount
    ),
    CONSTRAINT chk_posting_reversals_split CHECK (
        (rotating_amount IS NULL AND retained_amount IS NULL)
        OR (rotating_amount IS NOT NULL AND retained_amount IS NOT NULL
            AND rotating_amount >= 0 AND retained_amount >= 0
            AND rotating_amount + retained_amount = amount)
    )
);

CREATE INDEX idx_posting_reversals_period
    ON member_payment_posting_reversals (cooperative_id, rotation_period_id);
