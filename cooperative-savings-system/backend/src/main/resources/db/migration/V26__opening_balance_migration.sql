-- Simplified Ikimina migration: opening balances at a cutover date.
-- Existing historical_imports / historical_import_rows (legacy 16-sheet format) are untouched:
-- cutover_date and migration_summary stay NULL for them.

ALTER TABLE historical_imports ADD COLUMN cutover_date DATE;
ALTER TABLE historical_imports ADD COLUMN migration_summary TEXT;

CREATE TABLE member_opening_balances (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    opening_contribution_amount NUMERIC(19,4)   NOT NULL DEFAULT 0,
    opening_share_count         INT             NOT NULL DEFAULT 0,
    opening_share_capital_amount NUMERIC(19,4)  NOT NULL DEFAULT 0,
    cutover_date                DATE            NOT NULL,
    historical_import_id        UUID            NOT NULL,
    created_by                  UUID,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_member_opening_balances_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_member_opening_balances_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_member_opening_balances_import FOREIGN KEY (historical_import_id) REFERENCES historical_imports (id),
    CONSTRAINT fk_member_opening_balances_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_member_opening_balances_member UNIQUE (cooperative_id, member_user_id),
    CONSTRAINT chk_member_opening_contribution_nonneg CHECK (opening_contribution_amount >= 0),
    CONSTRAINT chk_member_opening_share_count CHECK (opening_share_count >= 0 AND opening_share_count <= 1000),
    CONSTRAINT chk_member_opening_share_capital_nonneg CHECK (opening_share_capital_amount >= 0)
);

CREATE INDEX idx_member_opening_balances_cooperative ON member_opening_balances (cooperative_id);

-- Loans imported as an opening receivable (no historical ledger/repayment rows exist for them).
ALTER TABLE loans ADD COLUMN opening_import_id UUID;
ALTER TABLE loans ADD CONSTRAINT fk_loans_opening_import
    FOREIGN KEY (opening_import_id) REFERENCES historical_imports (id);
CREATE INDEX idx_loans_opening_import ON loans (opening_import_id);
