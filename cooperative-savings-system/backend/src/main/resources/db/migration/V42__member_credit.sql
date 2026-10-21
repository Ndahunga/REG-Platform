-- Member credit: money a member paid that is not (yet) a contribution or a loan repayment.
--
-- Excess money is classified explicitly, never absorbed as income:
--   ADVANCE_CONTRIBUTION  held for future contribution periods;
--   LOAN_PREPAYMENT       held for one named loan;
--   UNAPPLIED_CREDIT      held without a purpose, usable for either.
--
-- The credit belongs to a member of one Saving Scheme. It is an append-only journal (member_credit_entries): a balance is
-- always the sum of its entries, never a mutable field. A credit is RECEIVED when a verified payment is posted, APPLIED
-- (to a contribution or a loan) by an officer later, and RECEIPT_REVERSED when the payment that created it is reversed
-- while the credit is still unspent. Every entry has its own ledger entry; the ledger types MEMBER_CREDIT_RECEIVED,
-- MEMBER_CREDIT_APPLIED and MEMBER_CREDIT_REVERSED are never income and never part of the group's available funds.

-- 1. A payment allocation can now be a member credit, with its purpose (and the loan for a loan prepayment).
ALTER TABLE member_payment_allocations ADD COLUMN credit_purpose VARCHAR(24);

ALTER TABLE member_payment_allocations DROP CONSTRAINT chk_member_payment_allocations_shape;
ALTER TABLE member_payment_allocations ADD CONSTRAINT chk_member_payment_allocations_shape CHECK (
    (allocation_type = 'CONTRIBUTION'
        AND period_year BETWEEN 2000 AND 2100
        AND period_month BETWEEN 1 AND 12
        AND loan_id IS NULL
        AND credit_purpose IS NULL
        AND principal_amount IS NULL AND interest_amount IS NULL AND penalty_amount IS NULL)
    OR (allocation_type = 'LOAN_REPAYMENT'
        AND loan_id IS NOT NULL
        AND credit_purpose IS NULL
        AND period_year IS NULL AND period_month IS NULL)
    OR (allocation_type = 'MEMBER_CREDIT'
        AND credit_purpose IN ('ADVANCE_CONTRIBUTION', 'LOAN_PREPAYMENT', 'UNAPPLIED_CREDIT')
        AND ((credit_purpose = 'LOAN_PREPAYMENT' AND loan_id IS NOT NULL)
             OR (credit_purpose <> 'LOAN_PREPAYMENT' AND loan_id IS NULL))
        AND period_year IS NULL AND period_month IS NULL
        AND principal_amount IS NULL AND interest_amount IS NULL AND penalty_amount IS NULL)
);

-- 2. A posting row can record the receipt of a member credit.
ALTER TABLE member_payment_postings DROP CONSTRAINT chk_member_payment_postings_type;
ALTER TABLE member_payment_postings ADD CONSTRAINT chk_member_payment_postings_type
    CHECK (allocation_type IN ('CONTRIBUTION', 'LOAN_REPAYMENT', 'MEMBER_CREDIT'));

ALTER TABLE member_payment_postings DROP CONSTRAINT chk_member_payment_postings_shape;
ALTER TABLE member_payment_postings ADD CONSTRAINT chk_member_payment_postings_shape CHECK (
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
    OR (allocation_type = 'MEMBER_CREDIT'
        AND ledger_entry_id IS NOT NULL
        AND contribution_id IS NULL AND period_year IS NULL AND period_month IS NULL
        AND paid_before IS NULL AND paid_after IS NULL
        AND loan_repayment_id IS NULL
        AND principal_amount IS NULL AND interest_amount IS NULL AND penalty_amount IS NULL
        AND rotating_amount IS NULL AND retained_amount IS NULL
        AND rotation_period_id IS NULL AND original_recipient_user_id IS NULL
        AND attribution = 'NOT_APPLICABLE')
);

-- 3. The credit journal.
CREATE TABLE member_credit_entries (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    entry_type                  VARCHAR(16)     NOT NULL,
    purpose                     VARCHAR(24)     NOT NULL,
    loan_id                     UUID,
    amount                      NUMERIC(19,4)   NOT NULL,

    -- RECEIVED: the verified payment allocation that created the credit.
    source_payment_id           UUID,
    source_allocation_id        UUID,
    -- APPLIED: exactly one target.
    applied_contribution_id     UUID,
    applied_loan_repayment_id   UUID,
    -- RECEIPT_REVERSED: the receipt entry it takes back, and the payment reversal that caused it.
    reverses_entry_id           UUID,

    ledger_entry_id             UUID            NOT NULL,
    idempotency_key             VARCHAR(128),
    note                        VARCHAR(500),
    created_by                  UUID            NOT NULL,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_member_credit_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_member_credit_member FOREIGN KEY (member_user_id, cooperative_id)
        REFERENCES cooperative_memberships (user_id, cooperative_id),
    CONSTRAINT fk_member_credit_loan FOREIGN KEY (loan_id, cooperative_id, member_user_id)
        REFERENCES loans (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_credit_payment FOREIGN KEY (source_payment_id, cooperative_id, member_user_id)
        REFERENCES member_payments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_credit_allocation FOREIGN KEY (source_allocation_id)
        REFERENCES member_payment_allocations (id),
    CONSTRAINT fk_member_credit_contribution FOREIGN KEY (applied_contribution_id, cooperative_id, member_user_id)
        REFERENCES contributions (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_credit_repayment FOREIGN KEY (applied_loan_repayment_id, cooperative_id, member_user_id)
        REFERENCES loan_repayments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_member_credit_reverses FOREIGN KEY (reverses_entry_id) REFERENCES member_credit_entries (id),
    CONSTRAINT fk_member_credit_ledger FOREIGN KEY (ledger_entry_id) REFERENCES financial_ledger (id),
    CONSTRAINT fk_member_credit_created_by FOREIGN KEY (created_by) REFERENCES users (id),

    -- one receipt per payment allocation, one reversal per receipt, one journal entry per ledger entry, and an
    -- application is idempotent per scheme and key
    CONSTRAINT uq_member_credit_source_allocation UNIQUE (source_allocation_id),
    CONSTRAINT uq_member_credit_reverses UNIQUE (reverses_entry_id),
    CONSTRAINT uq_member_credit_ledger UNIQUE (ledger_entry_id),
    CONSTRAINT uq_member_credit_idempotency UNIQUE (cooperative_id, idempotency_key),

    CONSTRAINT chk_member_credit_type CHECK (entry_type IN ('RECEIVED', 'APPLIED', 'RECEIPT_REVERSED')),
    CONSTRAINT chk_member_credit_purpose CHECK (purpose IN ('ADVANCE_CONTRIBUTION', 'LOAN_PREPAYMENT', 'UNAPPLIED_CREDIT')),
    CONSTRAINT chk_member_credit_amount CHECK (amount > 0),
    CONSTRAINT chk_member_credit_loan CHECK ((purpose = 'LOAN_PREPAYMENT') = (loan_id IS NOT NULL)),
    CONSTRAINT chk_member_credit_shape CHECK (
        (entry_type = 'RECEIVED'
            AND source_payment_id IS NOT NULL AND source_allocation_id IS NOT NULL
            AND applied_contribution_id IS NULL AND applied_loan_repayment_id IS NULL
            AND reverses_entry_id IS NULL AND idempotency_key IS NULL)
        OR (entry_type = 'APPLIED'
            AND source_payment_id IS NULL AND source_allocation_id IS NULL
            AND reverses_entry_id IS NULL AND idempotency_key IS NOT NULL
            AND ((applied_contribution_id IS NOT NULL AND applied_loan_repayment_id IS NULL)
                 OR (applied_contribution_id IS NULL AND applied_loan_repayment_id IS NOT NULL)))
        OR (entry_type = 'RECEIPT_REVERSED'
            AND source_payment_id IS NOT NULL AND source_allocation_id IS NULL
            AND applied_contribution_id IS NULL AND applied_loan_repayment_id IS NULL
            AND reverses_entry_id IS NOT NULL)
    ),
    -- what a credit may be applied to is fixed by its purpose
    CONSTRAINT chk_member_credit_application CHECK (
        entry_type <> 'APPLIED'
        OR (purpose = 'ADVANCE_CONTRIBUTION' AND applied_contribution_id IS NOT NULL)
        OR (purpose = 'LOAN_PREPAYMENT' AND applied_loan_repayment_id IS NOT NULL)
        OR purpose = 'UNAPPLIED_CREDIT'
    )
);

CREATE INDEX idx_member_credit_member ON member_credit_entries (cooperative_id, member_user_id, purpose, entry_type);
CREATE INDEX idx_member_credit_contribution ON member_credit_entries (cooperative_id, applied_contribution_id);
