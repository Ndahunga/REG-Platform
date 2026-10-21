-- Reversal of a posted payment that contains a loan repayment.
--
-- A loan repayment is never edited or deleted. Reversing it gives the loan back exactly what the repayment took
-- (principal, interest and penalty owed, instalment amounts, the CLOSED status), takes the ledger credits back with
-- REVERSAL entries, and leaves both the original repayment and a new immutable record of what was given back:
--
--   * loan_repayments.reversed_at / reversed_by flag the repayment once, so no total or report counts it again;
--   * member_payment_loan_reversals records, per reversed posting, the amounts restored and the loan's position before
--     and after, linked to the payment reversal that caused it.

ALTER TABLE loan_repayments ADD COLUMN reversed_at TIMESTAMPTZ;
ALTER TABLE loan_repayments ADD COLUMN reversed_by UUID;
ALTER TABLE loan_repayments
    ADD CONSTRAINT fk_loan_repayments_reversed_by FOREIGN KEY (reversed_by) REFERENCES users (id);
ALTER TABLE loan_repayments
    ADD CONSTRAINT chk_loan_repayments_reversed CHECK ((reversed_at IS NULL) = (reversed_by IS NULL));

CREATE TABLE member_payment_loan_reversals (
    id                              UUID            PRIMARY KEY,
    reversal_id                     UUID            NOT NULL,
    posting_id                      UUID            NOT NULL,
    payment_id                      UUID            NOT NULL,
    cooperative_id                  UUID            NOT NULL,
    member_user_id                  UUID            NOT NULL,
    loan_id                         UUID            NOT NULL,
    loan_repayment_id               UUID            NOT NULL,
    amount                          NUMERIC(19,4)   NOT NULL,
    principal_amount                NUMERIC(19,4)   NOT NULL,
    interest_amount                 NUMERIC(19,4)   NOT NULL,
    penalty_amount                  NUMERIC(19,4)   NOT NULL,
    outstanding_principal_before    NUMERIC(19,4)   NOT NULL,
    outstanding_principal_after     NUMERIC(19,4)   NOT NULL,
    outstanding_interest_before     NUMERIC(19,4)   NOT NULL,
    outstanding_interest_after      NUMERIC(19,4)   NOT NULL,
    outstanding_penalty_before      NUMERIC(19,4)   NOT NULL,
    outstanding_penalty_after       NUMERIC(19,4)   NOT NULL,
    loan_status_before              VARCHAR(32)     NOT NULL,
    loan_status_after               VARCHAR(32)     NOT NULL,
    principal_ledger_entry_id       UUID,
    interest_ledger_entry_id        UUID,
    reversed_by                     UUID            NOT NULL,
    reversed_at                     TIMESTAMPTZ     NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_loan_reversals_reversal
        FOREIGN KEY (reversal_id, cooperative_id) REFERENCES member_payment_reversals (id, cooperative_id),
    CONSTRAINT fk_loan_reversals_posting FOREIGN KEY (posting_id) REFERENCES member_payment_postings (id),
    CONSTRAINT fk_loan_reversals_payment
        FOREIGN KEY (payment_id, cooperative_id, member_user_id)
        REFERENCES member_payments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_loan_reversals_loan
        FOREIGN KEY (loan_id, cooperative_id, member_user_id)
        REFERENCES loans (id, cooperative_id, member_user_id),
    CONSTRAINT fk_loan_reversals_repayment
        FOREIGN KEY (loan_repayment_id, cooperative_id, member_user_id)
        REFERENCES loan_repayments (id, cooperative_id, member_user_id),
    CONSTRAINT fk_loan_reversals_principal_ledger FOREIGN KEY (principal_ledger_entry_id) REFERENCES financial_ledger (id),
    CONSTRAINT fk_loan_reversals_interest_ledger FOREIGN KEY (interest_ledger_entry_id) REFERENCES financial_ledger (id),
    CONSTRAINT fk_loan_reversals_by FOREIGN KEY (reversed_by) REFERENCES users (id),
    -- one reversal per posting and per repayment, ever
    CONSTRAINT uq_loan_reversals_posting UNIQUE (posting_id),
    CONSTRAINT uq_loan_reversals_repayment UNIQUE (loan_repayment_id),
    CONSTRAINT chk_loan_reversals_amounts CHECK (
        amount > 0
        AND principal_amount >= 0 AND interest_amount >= 0 AND penalty_amount >= 0
        AND principal_amount + interest_amount + penalty_amount = amount
    ),
    -- the loan owes exactly what the repayment had taken, no more and no less
    CONSTRAINT chk_loan_reversals_restored CHECK (
        outstanding_principal_after = outstanding_principal_before + principal_amount
        AND outstanding_interest_after = outstanding_interest_before + interest_amount
        AND outstanding_penalty_after = outstanding_penalty_before + penalty_amount
    )
);

CREATE INDEX idx_loan_reversals_loan ON member_payment_loan_reversals (cooperative_id, loan_id);
