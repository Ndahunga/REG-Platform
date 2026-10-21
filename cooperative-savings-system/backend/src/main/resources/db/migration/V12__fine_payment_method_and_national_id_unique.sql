-- V12: Fine payment method + national ID uniqueness (nullable-safe)
-- Does not modify historical Flyway V1-V11.
-- Compatible with PostgreSQL (production) and H2 PostgreSQL mode (tests).
-- Note: UNIQUE allows multiple NULL national_id values. Soft-deleted rows that retain
-- a national_id still occupy the unique slot (intentional identity reuse prevention).

ALTER TABLE fine_payments ADD COLUMN payment_method VARCHAR(32);
ALTER TABLE fine_payments ADD COLUMN payment_method_detail VARCHAR(255);

CREATE UNIQUE INDEX ux_users_national_id ON users (national_id);
