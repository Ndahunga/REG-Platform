-- Rotating and retained money kept apart IN THE LEDGER.
--
-- A hybrid scheme's contribution is one cash movement with two purposes: the rotating part is owed to the period's
-- recipient, the retained part is member capital. Until now the split lived only on the obligation and on the payment
-- posting rows, so the ledger could not say, on its own, how much of its contribution cash is rotating and how much is
-- retained. Each contribution credit (and the reversal that takes it back) now carries both parts.
--
-- The split is NOT extra money. rotating_amount + retained_amount is exactly the entry's own amount (a CHECK enforces
-- it), so summing the two parts can never exceed, or duplicate, the cash of the entry. Entries written before this
-- migration keep both columns NULL (unclassified); the reconciliation derives their split from the source contribution
-- and reports them as such instead of guessing in SQL.

ALTER TABLE financial_ledger ADD COLUMN rotating_amount NUMERIC(19,4);
ALTER TABLE financial_ledger ADD COLUMN retained_amount NUMERIC(19,4);

ALTER TABLE financial_ledger ADD CONSTRAINT chk_ledger_split_complete
    CHECK ((rotating_amount IS NULL AND retained_amount IS NULL)
        OR (rotating_amount IS NOT NULL AND retained_amount IS NOT NULL));
ALTER TABLE financial_ledger ADD CONSTRAINT chk_ledger_split_nonneg
    CHECK (rotating_amount IS NULL OR (rotating_amount >= 0 AND retained_amount >= 0));
ALTER TABLE financial_ledger ADD CONSTRAINT chk_ledger_split_total
    CHECK (rotating_amount IS NULL OR rotating_amount + retained_amount = debit_amount + credit_amount);
