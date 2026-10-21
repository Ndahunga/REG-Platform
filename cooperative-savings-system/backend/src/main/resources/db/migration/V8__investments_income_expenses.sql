-- Phase 8: investments, income/expenses, permissions

INSERT INTO permissions (id, code, name, description) VALUES
    ('a0000001-0000-0000-0000-000000000014', 'INVESTMENT_READ', 'Read investments', 'View cooperative investments and returns'),
    ('a0000001-0000-0000-0000-000000000015', 'INVESTMENT_WRITE', 'Manage investments', 'Create, activate, cancel investments and record returns/losses'),
    ('a0000001-0000-0000-0000-000000000016', 'INCOME_EXPENSE_READ', 'Read income and expenses', 'View other income, expenses, and adjustments'),
    ('a0000001-0000-0000-0000-000000000017', 'INCOME_EXPENSE_WRITE', 'Manage income and expenses', 'Record and approve/reject income, expenses, and adjustments');

-- SUPER_ADMIN gets all new permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT '11111111-1111-1111-1111-111111111111', id
FROM permissions
WHERE code IN ('INVESTMENT_READ', 'INVESTMENT_WRITE', 'INCOME_EXPENSE_READ', 'INCOME_EXPENSE_WRITE');

-- COOPERATIVE_ADMIN: all four
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000014'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000015'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000016'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000017');

-- MEMBER: INVESTMENT_READ only (no income/expense access)
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('33333333-3333-3333-3333-333333333333', 'a0000001-0000-0000-0000-000000000014');

CREATE TABLE investments (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    name                        VARCHAR(255)    NOT NULL,
    description                 VARCHAR(2000),
    amount                      NUMERIC(19,4)   NOT NULL,
    expected_return_amount      NUMERIC(19,4),
    expected_return_date        DATE,
    remaining_capital           NUMERIC(19,4)   NOT NULL DEFAULT 0,
    total_capital_returned      NUMERIC(19,4)   NOT NULL DEFAULT 0,
    total_profit_returned       NUMERIC(19,4)   NOT NULL DEFAULT 0,
    status                      VARCHAR(32)     NOT NULL,
    document_file_key           VARCHAR(512),
    activated_at                TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,
    created_by                  UUID,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_investments_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_investments_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT chk_investments_status CHECK (status IN (
        'PLANNED', 'ACTIVE', 'PARTIALLY_RETURNED', 'COMPLETED', 'CANCELLED', 'LOSS_RECORDED'
    )),
    CONSTRAINT chk_investments_amount_pos CHECK (amount > 0),
    CONSTRAINT chk_investments_remaining_nonneg CHECK (remaining_capital >= 0),
    CONSTRAINT chk_investments_returned_nonneg CHECK (
        total_capital_returned >= 0 AND total_profit_returned >= 0
    ),
    CONSTRAINT chk_investments_expected_nonneg CHECK (
        expected_return_amount IS NULL OR expected_return_amount >= 0
    )
);

CREATE INDEX idx_investments_cooperative ON investments (cooperative_id);
CREATE INDEX idx_investments_status ON investments (status);
CREATE INDEX idx_investments_coop_status ON investments (cooperative_id, status);

CREATE TABLE investment_returns (
    id                          UUID            PRIMARY KEY,
    investment_id               UUID            NOT NULL,
    cooperative_id              UUID            NOT NULL,
    return_date                 DATE            NOT NULL,
    capital_portion             NUMERIC(19,4)   NOT NULL DEFAULT 0,
    profit_portion              NUMERIC(19,4)   NOT NULL DEFAULT 0,
    amount_total                NUMERIC(19,4)   NOT NULL,
    notes                       VARCHAR(2000),
    reference                   VARCHAR(128),
    recorded_by                 UUID,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_investment_returns_investment FOREIGN KEY (investment_id) REFERENCES investments (id),
    CONSTRAINT fk_investment_returns_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_investment_returns_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT chk_investment_returns_portions_nonneg CHECK (
        capital_portion >= 0 AND profit_portion >= 0
    ),
    CONSTRAINT chk_investment_returns_total CHECK (
        amount_total = capital_portion + profit_portion AND amount_total > 0
    )
);

CREATE INDEX idx_investment_returns_investment ON investment_returns (investment_id);
CREATE INDEX idx_investment_returns_cooperative ON investment_returns (cooperative_id);

CREATE TABLE income_expense_transactions (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    category                    VARCHAR(32)     NOT NULL,
    amount                      NUMERIC(19,4)   NOT NULL,
    ledger_effect               VARCHAR(16),
    transaction_date            DATE            NOT NULL,
    reference                   VARCHAR(128),
    description                 VARCHAR(2000),
    notes                       VARCHAR(2000),
    supporting_file_key         VARCHAR(512),
    approval_status             VARCHAR(32)     NOT NULL,
    recorded_by                 UUID,
    approved_by                 UUID,
    approved_at                 TIMESTAMPTZ,
    rejection_reason            VARCHAR(2000),
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_income_expense_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_income_expense_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT fk_income_expense_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT chk_income_expense_category CHECK (category IN (
        'OTHER_INCOME', 'GENERAL_EXPENSE', 'INTEREST_EXPENSE', 'ADJUSTMENT'
    )),
    CONSTRAINT chk_income_expense_amount_pos CHECK (amount > 0),
    CONSTRAINT chk_income_expense_status CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_income_expense_ledger_effect CHECK (
        (category = 'ADJUSTMENT' AND ledger_effect IN ('CREDIT', 'DEBIT'))
        OR (category <> 'ADJUSTMENT' AND ledger_effect IS NULL)
    )
);

CREATE INDEX idx_income_expense_cooperative ON income_expense_transactions (cooperative_id);
CREATE INDEX idx_income_expense_category ON income_expense_transactions (category);
CREATE INDEX idx_income_expense_status ON income_expense_transactions (approval_status);
CREATE INDEX idx_income_expense_coop_status ON income_expense_transactions (cooperative_id, approval_status);
CREATE INDEX idx_income_expense_date ON income_expense_transactions (transaction_date);
