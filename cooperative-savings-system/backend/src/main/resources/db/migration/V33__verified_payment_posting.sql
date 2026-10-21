-- Verified-only posting of combined member payments (Task 3B-3B).
--
-- Additive only. Nothing here rewrites a ledger entry, a contribution, a loan or an existing payment row.
--
-- 1. Verification evidence: how the receipt of funds was confirmed. Nullable, because payments verified before this
--    migration carry no evidence; such a payment is NOT postable until its evidence is completed through the audited
--    evidence-completion operation. OCR output is never a confirmation method.
-- 2. Posting attempt metadata (attempt count, last failure code and time), written in its own transaction so a failed
--    financial attempt can never leave financial effects behind.
-- 3. member_payment_postings: one immutable row per allocation actually applied. UNIQUE (allocation_id) makes a second
--    posting of the same allocation impossible at the database level.
-- 4. Separation of duties for posting at the database level.

ALTER TABLE member_payments ADD COLUMN verification_method      VARCHAR(32);
ALTER TABLE member_payments ADD COLUMN verification_basis       VARCHAR(1000);
ALTER TABLE member_payments ADD COLUMN verification_reference   VARCHAR(128);
ALTER TABLE member_payments ADD COLUMN evidence_completed_by    UUID;
ALTER TABLE member_payments ADD COLUMN evidence_completed_at    TIMESTAMPTZ;
ALTER TABLE member_payments ADD COLUMN posting_attempts         INT             NOT NULL DEFAULT 0;
ALTER TABLE member_payments ADD COLUMN last_posting_failure_code VARCHAR(64);
ALTER TABLE member_payments ADD COLUMN last_posting_failed_at   TIMESTAMPTZ;

ALTER TABLE member_payments
    ADD CONSTRAINT fk_member_payments_evidence_completed_by
        FOREIGN KEY (evidence_completed_by) REFERENCES users (id);

ALTER TABLE member_payments
    ADD CONSTRAINT chk_member_payments_verification_method CHECK (
        verification_method IS NULL OR verification_method IN (
            'CASH_RECEIPT_CONFIRMED', 'MOBILE_MONEY_RECONCILED', 'BANK_RECONCILED', 'PROVIDER_CONFIRMED'
        )
    );
-- Evidence is all-or-nothing: a method always comes with its written basis, and only cash may omit the reference.
ALTER TABLE member_payments
    ADD CONSTRAINT chk_member_payments_verification_evidence CHECK (
        (verification_method IS NULL AND verification_basis IS NULL AND verification_reference IS NULL)
        OR (verification_method IS NOT NULL AND verification_basis IS NOT NULL
            AND (verification_method = 'CASH_RECEIPT_CONFIRMED' OR verification_reference IS NOT NULL))
    );
ALTER TABLE member_payments
    ADD CONSTRAINT chk_member_payments_attempts_nonneg CHECK (posting_attempts >= 0);
ALTER TABLE member_payments
    ADD CONSTRAINT chk_member_payments_evidence_independent CHECK (
        evidence_completed_by IS NULL
        OR (evidence_completed_by <> member_user_id AND evidence_completed_by <> submitted_by)
    );
-- The poster is a different person from the verifier, the evidence recorder, the payer and the submitter.
ALTER TABLE member_payments
    ADD CONSTRAINT chk_member_payments_poster_independent CHECK (
        posted_by IS NULL
        OR (posted_by <> member_user_id
            AND posted_by <> submitted_by
            AND (verified_by IS NULL OR posted_by <> verified_by)
            AND (evidence_completed_by IS NULL OR posted_by <> evidence_completed_by))
    );

-- Targets of the composite scope foreign keys below.
ALTER TABLE member_payment_allocations
    ADD CONSTRAINT uq_member_payment_allocations_id_payment UNIQUE (id, payment_id);
ALTER TABLE contributions
    ADD CONSTRAINT uq_contributions_id_scope UNIQUE (id, cooperative_id, member_user_id);
ALTER TABLE loan_repayments
    ADD CONSTRAINT uq_loan_repayments_id_scope UNIQUE (id, cooperative_id, member_user_id);

CREATE TABLE member_payment_postings (
    id                          UUID            PRIMARY KEY,
    payment_id                  UUID            NOT NULL,
    allocation_id               UUID            NOT NULL,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    allocation_type             VARCHAR(32)     NOT NULL,
    amount                      NUMERIC(19,4)   NOT NULL,
    payment_date                DATE            NOT NULL,
    posted_by                   UUID            NOT NULL,
    posted_at                   TIMESTAMPTZ     NOT NULL,

    -- CONTRIBUTION target.
    contribution_id             UUID,
    period_year                 INT,
    period_month                INT,
    paid_before                 NUMERIC(19,4),
    paid_after                  NUMERIC(19,4),
    ledger_entry_id             UUID,
    -- How the amount was attributed. ATTRIBUTED = ROTATING/HYBRID split from the period's own snapshot;
    -- ACCUMULATING = snapshotted retained-only scheme; UNATTRIBUTED = historical row without a snapshot (nothing
    -- invented); NOT_APPLICABLE = loan repayment.
    attribution                 VARCHAR(16)     NOT NULL,
    operating_model             VARCHAR(16),
    rotating_bps                INT,
    retained_bps                INT,
    rotating_amount             NUMERIC(19,4),
    retained_amount             NUMERIC(19,4),
    rotation_period_id          UUID,
    original_recipient_user_id  UUID,

    -- LOAN_REPAYMENT target. The breakdown is the one the loan engine actually applied.
    loan_id                     UUID,
    loan_repayment_id           UUID,
    principal_amount            NUMERIC(19,4),
    interest_amount             NUMERIC(19,4),
    penalty_amount              NUMERIC(19,4),

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_member_payment_postings_payment
        FOREIGN KEY (payment_id, cooperative_id, member_user_id)
        REFERENCES member_payments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_payment_postings_allocation
        FOREIGN KEY (allocation_id, payment_id)
        REFERENCES member_payment_allocations (id, payment_id),
    CONSTRAINT fk_member_payment_postings_contribution
        FOREIGN KEY (contribution_id, cooperative_id, member_user_id)
        REFERENCES contributions (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_payment_postings_loan
        FOREIGN KEY (loan_id, cooperative_id, member_user_id)
        REFERENCES loans (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_payment_postings_repayment
        FOREIGN KEY (loan_repayment_id, cooperative_id, member_user_id)
        REFERENCES loan_repayments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_payment_postings_period
        FOREIGN KEY (rotation_period_id, cooperative_id)
        REFERENCES rotation_periods (id, cooperative_id),
    CONSTRAINT fk_member_payment_postings_recipient
        FOREIGN KEY (original_recipient_user_id, cooperative_id)
        REFERENCES cooperative_memberships (user_id, cooperative_id),
    CONSTRAINT fk_member_payment_postings_posted_by FOREIGN KEY (posted_by) REFERENCES users (id),
    CONSTRAINT fk_member_payment_postings_ledger FOREIGN KEY (ledger_entry_id) REFERENCES financial_ledger (id),

    -- One posting per allocation, ever.
    CONSTRAINT uq_member_payment_postings_allocation UNIQUE (allocation_id),

    CONSTRAINT chk_member_payment_postings_type CHECK (allocation_type IN ('CONTRIBUTION', 'LOAN_REPAYMENT')),
    CONSTRAINT chk_member_payment_postings_attribution CHECK (
        attribution IN ('ATTRIBUTED', 'ACCUMULATING', 'UNATTRIBUTED', 'NOT_APPLICABLE')
    ),
    CONSTRAINT chk_member_payment_postings_amount CHECK (amount > 0),
    CONSTRAINT chk_member_payment_postings_nonneg CHECK (
        (paid_before IS NULL OR paid_before >= 0)
        AND (paid_after IS NULL OR paid_after >= 0)
        AND (rotating_amount IS NULL OR rotating_amount >= 0)
        AND (retained_amount IS NULL OR retained_amount >= 0)
        AND (principal_amount IS NULL OR principal_amount >= 0)
        AND (interest_amount IS NULL OR interest_amount >= 0)
        AND (penalty_amount IS NULL OR penalty_amount >= 0)
    ),
    CONSTRAINT chk_member_payment_postings_shape CHECK (
        (allocation_type = 'CONTRIBUTION'
            AND contribution_id IS NOT NULL
            AND period_year BETWEEN 2000 AND 2100
            AND period_month BETWEEN 1 AND 12
            AND paid_before IS NOT NULL AND paid_after IS NOT NULL
            AND paid_after = paid_before + amount
            AND loan_id IS NULL AND loan_repayment_id IS NULL
            AND principal_amount IS NULL AND interest_amount IS NULL AND penalty_amount IS NULL
            AND attribution <> 'NOT_APPLICABLE')
        OR (allocation_type = 'LOAN_REPAYMENT'
            AND loan_id IS NOT NULL AND loan_repayment_id IS NOT NULL
            AND principal_amount IS NOT NULL AND interest_amount IS NOT NULL AND penalty_amount IS NOT NULL
            AND principal_amount + interest_amount + penalty_amount = amount
            AND contribution_id IS NULL AND period_year IS NULL AND period_month IS NULL
            AND rotating_amount IS NULL AND retained_amount IS NULL
            AND rotation_period_id IS NULL AND original_recipient_user_id IS NULL
            AND attribution = 'NOT_APPLICABLE')
    ),
    -- Rotating + retained is exactly the posted contribution amount whenever a split exists, and a split exists
    -- only for ATTRIBUTED / ACCUMULATING rows; UNATTRIBUTED rows carry no invented split or recipient.
    CONSTRAINT chk_member_payment_postings_split CHECK (
        (attribution IN ('ATTRIBUTED', 'ACCUMULATING')
            AND rotating_amount IS NOT NULL AND retained_amount IS NOT NULL
            AND rotating_amount + retained_amount = amount)
        OR (attribution IN ('UNATTRIBUTED', 'NOT_APPLICABLE')
            AND rotating_amount IS NULL AND retained_amount IS NULL
            AND rotating_bps IS NULL AND retained_bps IS NULL
            AND rotation_period_id IS NULL AND original_recipient_user_id IS NULL)
    ),
    CONSTRAINT chk_member_payment_postings_recipient CHECK (
        attribution <> 'ATTRIBUTED'
        OR (rotation_period_id IS NOT NULL AND original_recipient_user_id IS NOT NULL
            AND rotating_bps IS NOT NULL AND retained_bps IS NOT NULL AND rotating_bps + retained_bps = 10000)
    )
);

CREATE INDEX idx_member_payment_postings_payment ON member_payment_postings (cooperative_id, payment_id);
CREATE INDEX idx_member_payment_postings_contribution ON member_payment_postings (cooperative_id, contribution_id);
CREATE INDEX idx_member_payment_postings_loan ON member_payment_postings (cooperative_id, loan_id);
CREATE INDEX idx_member_payment_postings_recipient
    ON member_payment_postings (cooperative_id, original_recipient_user_id, period_year, period_month);
