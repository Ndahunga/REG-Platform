-- Member payment foundation: payment aggregate, allocations, loan-repayment breakdown, status history.
--
-- Additive only. A payment here is a *claim* by or for a member that money was paid. It is NOT a financial
-- transaction: nothing in these tables feeds the ledger, balances, reports or dashboards yet, and no row is
-- backfilled from historical contributions or loan repayments. Posting orchestration is a later task.
--
-- Compatible with PostgreSQL (production) and H2 in PostgreSQL mode (tests): no partial indexes, no triggers.
--
-- Duplicate-reference strategy
--   * A transaction reference is only unique *within one Saving Scheme and one payment method*; the same text
--     from MTN MoMo and from a bank is not the same transaction, and two schemes never share a namespace.
--   * reference_key = payment_method || '|' || normalized_reference. It is held (NOT NULL) by every payment that
--     is still a live claim: SUBMITTED, UNDER_REVIEW, VERIFIED, POSTED and REVERSED. It is released (NULL) only
--     when the payment is REJECTED, so a corrected resubmission with the same reference is possible. A REVERSED
--     payment keeps its key so a receipt that was already posted and undone cannot be reused silently.
--   * UNIQUE (cooperative_id, reference_key) enforces this portably: NULL keys never collide.
--   * Method OTHER is ambiguous by nature and is treated conservatively (same key space), never merged with others.

-- Lets payment allocations reference a loan only if it belongs to the same Saving Scheme AND the same member.
ALTER TABLE loans
    ADD CONSTRAINT uq_loans_id_cooperative_member UNIQUE (id, cooperative_id, member_user_id);

CREATE TABLE member_payments (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    member_user_id          UUID            NOT NULL,
    amount                  NUMERIC(19,4)   NOT NULL,
    allocated_amount        NUMERIC(19,4)   NOT NULL DEFAULT 0,
    currency                VARCHAR(3)      NOT NULL,
    payment_date            DATE            NOT NULL,
    payment_method          VARCHAR(32)     NOT NULL,
    transaction_reference   VARCHAR(128),
    normalized_reference    VARCHAR(128),
    reference_key           VARCHAR(170),
    evidence_file_key       VARCHAR(512),
    idempotency_key         VARCHAR(128),
    notes                   VARCHAR(2000),
    status                  VARCHAR(32)     NOT NULL,

    submitted_by            UUID            NOT NULL,
    submitted_at            TIMESTAMPTZ     NOT NULL,
    review_started_by       UUID,
    review_started_at       TIMESTAMPTZ,
    verified_by             UUID,
    verified_at             TIMESTAMPTZ,
    rejected_by             UUID,
    rejected_at             TIMESTAMPTZ,
    rejection_reason        VARCHAR(2000),
    posted_by               UUID,
    posted_at               TIMESTAMPTZ,
    reversed_by             UUID,
    reversed_at             TIMESTAMPTZ,
    reversal_reason         VARCHAR(2000),

    -- Receipt analysis results. Advisory content classification only; never an input to verification or posting.
    -- All nullable: a payment does not need (or wait for) receipt processing.
    extracted_amount        NUMERIC(19,4),
    extracted_reference     VARCHAR(128),
    receipt_status          VARCHAR(32),
    receipt_confidence      NUMERIC(5,4),
    receipt_analyzed_at     TIMESTAMPTZ,

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_member_payments_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_member_payments_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_member_payments_submitted_by FOREIGN KEY (submitted_by) REFERENCES users (id),
    CONSTRAINT fk_member_payments_review_started_by FOREIGN KEY (review_started_by) REFERENCES users (id),
    CONSTRAINT fk_member_payments_verified_by FOREIGN KEY (verified_by) REFERENCES users (id),
    CONSTRAINT fk_member_payments_rejected_by FOREIGN KEY (rejected_by) REFERENCES users (id),
    CONSTRAINT fk_member_payments_posted_by FOREIGN KEY (posted_by) REFERENCES users (id),
    CONSTRAINT fk_member_payments_reversed_by FOREIGN KEY (reversed_by) REFERENCES users (id),

    -- Target of the composite foreign keys from allocations (same payment, same scheme, same member).
    CONSTRAINT uq_member_payments_id_scope UNIQUE (id, cooperative_id, member_user_id),
    CONSTRAINT uq_member_payments_reference_key UNIQUE (cooperative_id, reference_key),
    CONSTRAINT uq_member_payments_idempotency UNIQUE (cooperative_id, submitted_by, idempotency_key),

    CONSTRAINT chk_member_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_member_payments_allocated_nonneg CHECK (allocated_amount >= 0),
    CONSTRAINT chk_member_payments_method CHECK (payment_method IN (
        'MTN_MOMO', 'AIRTEL_MONEY', 'BANK_TRANSFER', 'CASH', 'OTHER'
    )),
    CONSTRAINT chk_member_payments_status CHECK (status IN (
        'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'POSTED', 'REJECTED', 'REVERSED'
    )),
    CONSTRAINT chk_member_payments_receipt_status CHECK (
        receipt_status IS NULL OR receipt_status IN (
            'SUCCESS_INDICATED', 'FAILED_INDICATED', 'PENDING_INDICATED', 'UNKNOWN'
        )
    ),
    CONSTRAINT chk_member_payments_receipt_confidence CHECK (
        receipt_confidence IS NULL OR (receipt_confidence >= 0 AND receipt_confidence <= 1)
    ),
    CONSTRAINT chk_member_payments_extracted_amount CHECK (extracted_amount IS NULL OR extracted_amount >= 0),

    -- Electronic payments must carry a reference; only cash may omit it.
    CONSTRAINT chk_member_payments_reference_required CHECK (
        payment_method = 'CASH' OR normalized_reference IS NOT NULL
    ),
    CONSTRAINT chk_member_payments_reference_key CHECK (
        reference_key IS NULL OR normalized_reference IS NOT NULL
    ),
    -- A rejected payment releases its reference; every other status keeps holding it.
    CONSTRAINT chk_member_payments_rejected_releases_reference CHECK (
        status <> 'REJECTED' OR reference_key IS NULL
    ),
    CONSTRAINT chk_member_payments_live_holds_reference CHECK (
        status = 'REJECTED' OR normalized_reference IS NULL OR reference_key IS NOT NULL
    ),

    -- Status/field consistency: a status can only exist together with the facts that justify it.
    CONSTRAINT chk_member_payments_review_fields CHECK (
        status NOT IN ('UNDER_REVIEW', 'VERIFIED', 'POSTED', 'REVERSED')
        OR (review_started_by IS NOT NULL AND review_started_at IS NOT NULL)
    ),
    CONSTRAINT chk_member_payments_verified_fields CHECK (
        status NOT IN ('VERIFIED', 'POSTED', 'REVERSED')
        OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)
    ),
    CONSTRAINT chk_member_payments_posted_fields CHECK (
        (status IN ('POSTED', 'REVERSED') AND posted_by IS NOT NULL AND posted_at IS NOT NULL)
        OR (status NOT IN ('POSTED', 'REVERSED') AND posted_at IS NULL)
    ),
    CONSTRAINT chk_member_payments_rejected_fields CHECK (
        (status = 'REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL)
        OR (status <> 'REJECTED' AND rejected_at IS NULL)
    ),
    CONSTRAINT chk_member_payments_reversed_fields CHECK (
        (status = 'REVERSED' AND reversed_by IS NOT NULL AND reversed_at IS NOT NULL AND reversal_reason IS NOT NULL)
        OR (status <> 'REVERSED' AND reversed_at IS NULL)
    ),
    -- Only fully allocated payments may be authorized for posting.
    CONSTRAINT chk_member_payments_fully_allocated CHECK (
        status NOT IN ('VERIFIED', 'POSTED', 'REVERSED') OR allocated_amount = amount
    ),
    -- Maker-checker: neither the payer nor the person who submitted the claim may verify it.
    CONSTRAINT chk_member_payments_verifier_independent CHECK (
        verified_by IS NULL OR (verified_by <> member_user_id AND verified_by <> submitted_by)
    )
);

CREATE INDEX idx_member_payments_cooperative_status ON member_payments (cooperative_id, status);
CREATE INDEX idx_member_payments_member ON member_payments (cooperative_id, member_user_id, created_at);

-- One row per target of a payment. The composite foreign keys make it impossible, at the database level, for an
-- allocation to belong to a different scheme/member than its payment, or to point at another member's loan.
CREATE TABLE member_payment_allocations (
    id                      UUID            PRIMARY KEY,
    payment_id              UUID            NOT NULL,
    cooperative_id          UUID            NOT NULL,
    member_user_id          UUID            NOT NULL,
    allocation_type         VARCHAR(32)     NOT NULL,
    amount                  NUMERIC(19,4)   NOT NULL,
    period_year             INT,
    period_month            INT,
    loan_id                 UUID,
    -- Loan-repayment breakdown. All three are NULL until the split is determined; once set they must reconcile.
    principal_amount        NUMERIC(19,4),
    interest_amount         NUMERIC(19,4),
    penalty_amount          NUMERIC(19,4),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_member_payment_allocations_payment
        FOREIGN KEY (payment_id, cooperative_id, member_user_id)
        REFERENCES member_payments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_payment_allocations_loan
        FOREIGN KEY (loan_id, cooperative_id, member_user_id)
        REFERENCES loans (id, cooperative_id, member_user_id),
    CONSTRAINT uq_member_payment_allocations_period UNIQUE (payment_id, period_year, period_month),
    CONSTRAINT uq_member_payment_allocations_loan UNIQUE (payment_id, loan_id),
    CONSTRAINT chk_member_payment_allocations_type CHECK (allocation_type IN (
        'CONTRIBUTION', 'LOAN_REPAYMENT', 'MEMBER_CREDIT'
    )),
    CONSTRAINT chk_member_payment_allocations_amount CHECK (amount > 0),
    CONSTRAINT chk_member_payment_allocations_shape CHECK (
        (allocation_type = 'CONTRIBUTION'
            AND period_year BETWEEN 2000 AND 2100
            AND period_month BETWEEN 1 AND 12
            AND loan_id IS NULL
            AND principal_amount IS NULL AND interest_amount IS NULL AND penalty_amount IS NULL)
        OR (allocation_type = 'LOAN_REPAYMENT'
            AND loan_id IS NOT NULL
            AND period_year IS NULL AND period_month IS NULL)
        OR (allocation_type = 'MEMBER_CREDIT'
            AND loan_id IS NULL
            AND period_year IS NULL AND period_month IS NULL
            AND principal_amount IS NULL AND interest_amount IS NULL AND penalty_amount IS NULL)
    ),
    -- principal + interest + penalty must equal the allocation amount whenever the breakdown is present.
    CONSTRAINT chk_member_payment_allocations_breakdown CHECK (
        (principal_amount IS NULL AND interest_amount IS NULL AND penalty_amount IS NULL)
        OR (principal_amount IS NOT NULL AND interest_amount IS NOT NULL AND penalty_amount IS NOT NULL
            AND principal_amount >= 0 AND interest_amount >= 0 AND penalty_amount >= 0
            AND principal_amount + interest_amount + penalty_amount = amount)
    )
);

CREATE INDEX idx_member_payment_allocations_payment ON member_payment_allocations (payment_id);
CREATE INDEX idx_member_payment_allocations_loan ON member_payment_allocations (cooperative_id, loan_id);
CREATE INDEX idx_member_payment_allocations_period
    ON member_payment_allocations (cooperative_id, member_user_id, period_year, period_month);

-- Immutable status history. Mirrors approval_events but covers the full payment state set
-- (approval_events only allows SUBMITTED / APPROVED / REJECTED).
CREATE TABLE member_payment_events (
    id                      UUID            PRIMARY KEY,
    payment_id              UUID            NOT NULL,
    cooperative_id          UUID            NOT NULL,
    actor_user_id           UUID            NOT NULL,
    actor_role              VARCHAR(64)     NOT NULL,
    from_status             VARCHAR(32),
    to_status               VARCHAR(32)     NOT NULL,
    comment                 VARCHAR(2000),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_member_payment_events_payment FOREIGN KEY (payment_id) REFERENCES member_payments (id),
    CONSTRAINT fk_member_payment_events_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_member_payment_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT chk_member_payment_events_from CHECK (from_status IS NULL OR from_status IN (
        'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'POSTED', 'REJECTED', 'REVERSED'
    )),
    CONSTRAINT chk_member_payment_events_to CHECK (to_status IN (
        'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'POSTED', 'REJECTED', 'REVERSED'
    ))
);

CREATE INDEX idx_member_payment_events_payment
    ON member_payment_events (cooperative_id, payment_id, created_at);
