-- A recorded loan repayment can be revised (for example 53,000 typed instead of 530,000). The wrong repayment is given
-- back to the loan (reversed, kept for the record) and a corrected repayment is recorded that points to it, with the
-- reason. Nothing is edited or deleted.
ALTER TABLE loan_repayments ADD COLUMN revision_of_id UUID;
ALTER TABLE loan_repayments ADD COLUMN revision_reason VARCHAR(500);
CREATE INDEX idx_loan_repayments_revision_of ON loan_repayments (revision_of_id);
