-- Phase 6B: Flutterwave hosted card checkout correlation, MTN payer identity for
-- pending-attempt reuse, and unique provider references.
-- payer_msisdn is PII (normalized MSISDN). Do not log it. Never store PAN/CVV.
--
-- Unique (provider, external_reference): PostgreSQL and H2 (PostgreSQL mode) treat
-- NULL as distinct in unique constraints, so unpaid rows without a reference remain
-- allowed without a partial index (H2 does not support CREATE UNIQUE INDEX ... WHERE).

ALTER TABLE subscription_payments
    ADD COLUMN payer_msisdn VARCHAR(32);

ALTER TABLE subscription_payments
    ADD COLUMN checkout_url VARCHAR(1024);

CREATE UNIQUE INDEX uq_subscription_payments_provider_ext_ref
    ON subscription_payments (provider, external_reference);
