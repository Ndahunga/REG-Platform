-- Optional guarantor (own loan vs guaranteed loan) and President-configured
-- share-percentage loan levels. Compatible with PostgreSQL and H2 PostgreSQL mode.

ALTER TABLE loans ADD COLUMN guarantee_mode VARCHAR(32) DEFAULT 'SELF';
UPDATE loans SET guarantee_mode = 'SELF' WHERE guarantee_mode IS NULL;
ALTER TABLE loans ALTER COLUMN guarantee_mode SET NOT NULL;
ALTER TABLE loans ADD CONSTRAINT chk_loans_guarantee_mode
    CHECK (guarantee_mode IN ('SELF', 'GUARANTOR'));

ALTER TABLE loans ADD COLUMN share_count INTEGER;
ALTER TABLE loans ADD COLUMN share_percent NUMERIC(9,4);
ALTER TABLE loans ADD COLUMN max_loan_by_shares NUMERIC(19,4);

CREATE TABLE loan_share_tiers (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    min_share_percent       NUMERIC(9,4)    NOT NULL,
    max_loan_amount         NUMERIC(19,4)   NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_loan_share_tiers_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT uq_loan_share_tiers_coop_percent UNIQUE (cooperative_id, min_share_percent),
    CONSTRAINT chk_loan_share_tiers_percent CHECK (min_share_percent > 0 AND min_share_percent <= 100),
    CONSTRAINT chk_loan_share_tiers_amount CHECK (max_loan_amount > 0)
);
