-- Early repayment: interest is charged only for the months the money was used (a started month counts as a whole
-- month, at least one month). When a member settles before the end of the term, the interest of the months not used
-- is waived. The repayment records how much was waived, and for scheduled loans which instalments it was taken from,
-- so a reversal can put it back exactly.
ALTER TABLE loan_repayments ADD COLUMN interest_waived NUMERIC(19,4) NOT NULL DEFAULT 0;

CREATE TABLE loan_interest_waivers (
    id                  UUID            PRIMARY KEY,
    repayment_id        UUID            NOT NULL,
    installment_id      UUID,
    loan_id             UUID            NOT NULL,
    cooperative_id      UUID            NOT NULL,
    amount              NUMERIC(19,4)   NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_interest_waiver_repayment FOREIGN KEY (repayment_id) REFERENCES loan_repayments (id),
    CONSTRAINT fk_interest_waiver_installment FOREIGN KEY (installment_id) REFERENCES loan_installments (id),
    CONSTRAINT fk_interest_waiver_loan FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT chk_interest_waiver_amount_pos CHECK (amount > 0)
);
CREATE INDEX idx_interest_waiver_repayment ON loan_interest_waivers (repayment_id);
