-- Loan limit per share held (e.g. 300,000 per share: 2 shares may borrow 600,000), and whether a member with an
-- overdue contribution may borrow. Both optional; existing schemes keep their behaviour.
ALTER TABLE loan_settings ADD COLUMN max_loan_per_share NUMERIC(19,4);
ALTER TABLE loan_settings ADD CONSTRAINT chk_loan_settings_per_share CHECK (max_loan_per_share IS NULL OR max_loan_per_share > 0);
ALTER TABLE loan_settings ADD COLUMN block_when_contribution_overdue BOOLEAN NOT NULL DEFAULT FALSE;

-- Late-contribution penalties by bands of days late, optionally per share, and conversion of a contribution left
-- unpaid too long into a charge of a percentage per month (e.g. after 30 days, 2% a month on what is still unpaid).
ALTER TABLE fine_settings DROP CONSTRAINT chk_fine_settings_mode;
ALTER TABLE fine_settings ADD CONSTRAINT chk_fine_settings_mode CHECK (fine_mode IN ('FIXED', 'PROGRESSIVE', 'BANDED'));
ALTER TABLE fines DROP CONSTRAINT chk_fines_calc_mode;
ALTER TABLE fines ADD CONSTRAINT chk_fines_calc_mode CHECK (calculation_mode IN ('FIXED', 'PROGRESSIVE', 'BANDED'));
-- "1-5:2000;6-15:5000;16-30:10000": days late (after grace) to amount. The last band applies beyond its range.
ALTER TABLE fine_settings ADD COLUMN penalty_bands VARCHAR(500);
ALTER TABLE fine_settings ADD COLUMN per_share BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fine_settings ADD COLUMN credit_conversion_days INT;
ALTER TABLE fine_settings ADD COLUMN credit_conversion_rate_percent NUMERIC(8,4);
-- The converted contribution becomes a loan repaid over this many months (default 3).
ALTER TABLE fine_settings ADD COLUMN credit_conversion_term_months INT CHECK (credit_conversion_term_months IS NULL OR credit_conversion_term_months BETWEEN 1 AND 60);
ALTER TABLE fine_settings ADD CONSTRAINT chk_fine_settings_conversion CHECK (
    (credit_conversion_days IS NULL AND credit_conversion_rate_percent IS NULL)
    OR (credit_conversion_days > 0 AND credit_conversion_rate_percent > 0 AND credit_conversion_rate_percent <= 100));

-- Opening positions that the member and share-capital workbook cannot carry: money the scheme owes a member at the
-- cutover (e.g. a contribution paid in advance or held for them), and income earned before the cutover that was not
-- yet shared out (it is shared equally per share like any other income).
ALTER TABLE member_opening_balances ADD COLUMN opening_owed_to_member NUMERIC(19,4) NOT NULL DEFAULT 0;
ALTER TABLE member_opening_balances ADD CONSTRAINT chk_member_opening_owed_nonneg CHECK (opening_owed_to_member >= 0);
CREATE TABLE scheme_opening_positions (
    cooperative_id     UUID          PRIMARY KEY REFERENCES cooperatives (id),
    unallocated_income NUMERIC(19,4) NOT NULL DEFAULT 0,
    updated_by         UUID,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- A scheme may change its operating model (e.g. from Accumulating to Hybrid) from a future month. The change is
-- scheduled, can be cancelled before it applies, and is applied when its month begins.
CREATE TABLE operating_model_changes (
    id               UUID         PRIMARY KEY,
    cooperative_id   UUID         NOT NULL REFERENCES cooperatives (id),
    from_model       VARCHAR(32)  NOT NULL,
    to_model         VARCHAR(32)  NOT NULL CHECK (to_model IN ('ACCUMULATING', 'ROTATING', 'HYBRID')),
    effective_year   INT          NOT NULL,
    effective_month  INT          NOT NULL CHECK (effective_month BETWEEN 1 AND 12),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('SCHEDULED', 'APPLIED', 'CANCELLED')),
    reason           VARCHAR(1000),
    requested_by     UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    applied_at       TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ
);
CREATE INDEX idx_operating_model_changes_coop ON operating_model_changes (cooperative_id, status);
