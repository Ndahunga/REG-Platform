-- Scheme expenses (bank charges, meetings, meals, transport...) recorded with their supporting document. The document is
-- read on upload and the amount it shows is compared with the amount recorded; bank charges are supported by the bank
-- statement line instead.
ALTER TABLE income_expense_transactions ADD COLUMN expense_type VARCHAR(40);
ALTER TABLE income_expense_transactions ADD COLUMN evidence_check VARCHAR(24);
ALTER TABLE income_expense_transactions ADD COLUMN evidence_amount_read NUMERIC(19,4);
ALTER TABLE income_expense_transactions ADD COLUMN evidence_note VARCHAR(500);
ALTER TABLE income_expense_transactions ADD COLUMN evidence_file_name VARCHAR(255);
ALTER TABLE income_expense_transactions ADD COLUMN statement_line_id UUID;
CREATE INDEX idx_income_expense_statement_line ON income_expense_transactions (statement_line_id);
