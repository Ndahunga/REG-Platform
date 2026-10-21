-- Contractual terms for FLAT monthly equal-installment schedules.
-- Existing loans keep stored interest_amount; new columns stay null/false.

ALTER TABLE loans
    ADD COLUMN prorata_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE loans
    ADD COLUMN first_period_days INT;

ALTER TABLE loans
    ADD COLUMN equal_installment_amount NUMERIC(19,4);

ALTER TABLE loans
    ADD CONSTRAINT chk_loans_first_period_days
    CHECK (first_period_days IS NULL OR (first_period_days >= 0 AND first_period_days <= 30));

ALTER TABLE loans
    ADD CONSTRAINT chk_loans_equal_installment_nonneg
    CHECK (equal_installment_amount IS NULL OR equal_installment_amount >= 0);
