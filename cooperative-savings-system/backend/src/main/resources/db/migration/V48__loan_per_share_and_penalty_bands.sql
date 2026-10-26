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
ALTER TABLE fine_settings ADD CONSTRAINT chk_fine_settings_conversion CHECK (
    (credit_conversion_days IS NULL AND credit_conversion_rate_percent IS NULL)
    OR (credit_conversion_days > 0 AND credit_conversion_rate_percent > 0 AND credit_conversion_rate_percent <= 100));
