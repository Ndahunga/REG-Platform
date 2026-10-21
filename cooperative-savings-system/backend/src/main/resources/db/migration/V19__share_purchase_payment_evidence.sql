-- Payment evidence for share purchases, matching contribution submit fields.
-- payment_date / payment_reference / evidence_file_key are the same model as contributions.
-- Existing pending/approved rows (if any in non-prod) get a placeholder payment date.

ALTER TABLE share_purchases ADD COLUMN payment_date DATE;
ALTER TABLE share_purchases ADD COLUMN payment_reference VARCHAR(128);
ALTER TABLE share_purchases ADD COLUMN evidence_file_key VARCHAR(512);

UPDATE share_purchases SET payment_date = DATE '1970-01-01' WHERE payment_date IS NULL;

ALTER TABLE share_purchases ALTER COLUMN payment_date SET NOT NULL;
