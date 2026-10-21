-- Ikimina repayment-date model, penalty policy, persisted installments, and allocations.

ALTER TABLE loan_settings
    ADD COLUMN repayment_date_model VARCHAR(32) NOT NULL DEFAULT 'SAME_DAY_OF_MONTH';

ALTER TABLE loan_settings
    ADD COLUMN penalty_type VARCHAR(64) NOT NULL DEFAULT 'FIXED_AMOUNT';

ALTER TABLE loan_settings
    ADD COLUMN penalty_rate_or_amount NUMERIC(19,4) NOT NULL DEFAULT 0;

ALTER TABLE loan_settings
    ADD COLUMN penalty_frequency VARCHAR(32) NOT NULL DEFAULT 'ONE_TIME';

ALTER TABLE loan_settings
    ADD COLUMN grace_period_days INT NOT NULL DEFAULT 0;

ALTER TABLE loan_settings
    ADD COLUMN allocation_order VARCHAR(64) NOT NULL DEFAULT 'PENALTY,INTEREST,PRINCIPAL';

ALTER TABLE loan_settings
    ADD CONSTRAINT chk_loan_settings_repayment_model
    CHECK (repayment_date_model IN ('SAME_DAY_OF_MONTH', 'MONTH_END'));

ALTER TABLE loan_settings
    ADD CONSTRAINT chk_loan_settings_penalty_type
    CHECK (penalty_type IN (
        'FIXED_AMOUNT',
        'PERCENTAGE_OF_OVERDUE_INSTALLMENT',
        'PERCENTAGE_OF_OUTSTANDING_LOAN_BALANCE'
    ));

ALTER TABLE loan_settings
    ADD CONSTRAINT chk_loan_settings_penalty_frequency
    CHECK (penalty_frequency IN ('ONE_TIME', 'DAILY', 'MONTHLY'));

ALTER TABLE loan_settings
    ADD CONSTRAINT chk_loan_settings_penalty_nonneg
    CHECK (penalty_rate_or_amount >= 0 AND grace_period_days >= 0);

ALTER TABLE loans
    ADD COLUMN repayment_date_model VARCHAR(32);

ALTER TABLE loans
    ADD COLUMN loan_penalty_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE loans
    ADD COLUMN penalty_type VARCHAR(64);

ALTER TABLE loans
    ADD COLUMN penalty_rate_or_amount NUMERIC(19,4);

ALTER TABLE loans
    ADD COLUMN penalty_frequency VARCHAR(32);

ALTER TABLE loans
    ADD COLUMN grace_period_days INT;

ALTER TABLE loans
    ADD COLUMN allocation_order VARCHAR(64);

ALTER TABLE loans
    ADD COLUMN outstanding_penalty NUMERIC(19,4) NOT NULL DEFAULT 0;

ALTER TABLE loans
    ADD COLUMN total_repaid_penalty NUMERIC(19,4) NOT NULL DEFAULT 0;

ALTER TABLE loan_repayments
    ADD COLUMN penalty_portion NUMERIC(19,4) NOT NULL DEFAULT 0;

ALTER TABLE loan_repayments
    DROP CONSTRAINT chk_loan_repayments_amounts_nonneg;

ALTER TABLE loan_repayments
    ADD CONSTRAINT chk_loan_repayments_amounts_nonneg CHECK (
        amount_total >= 0
        AND principal_portion >= 0
        AND interest_portion >= 0
        AND penalty_portion >= 0
    );

ALTER TABLE loan_repayments
    DROP CONSTRAINT chk_loan_repayments_split;

ALTER TABLE loan_repayments
    ADD CONSTRAINT chk_loan_repayments_split CHECK (
        principal_portion + interest_portion + penalty_portion = amount_total
    );

CREATE TABLE loan_installments (
    id                              UUID            PRIMARY KEY,
    loan_id                         UUID            NOT NULL,
    cooperative_id                  UUID            NOT NULL,
    installment_number              INT             NOT NULL,
    due_date                        DATE            NOT NULL,
    opening_principal_balance       NUMERIC(19,4)   NOT NULL,
    principal_due                   NUMERIC(19,4)   NOT NULL,
    interest_due                    NUMERIC(19,4)   NOT NULL,
    penalty_due                     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    principal_paid                  NUMERIC(19,4)   NOT NULL DEFAULT 0,
    interest_paid                   NUMERIC(19,4)   NOT NULL DEFAULT 0,
    penalty_paid                    NUMERIC(19,4)   NOT NULL DEFAULT 0,
    scheduled_installment_amount    NUMERIC(19,4)   NOT NULL,
    status                          VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_loan_installments_loan FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT fk_loan_installments_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT uq_loan_installments_number UNIQUE (loan_id, installment_number),
    CONSTRAINT chk_loan_installments_status CHECK (status IN (
        'PENDING', 'DUE', 'PARTIALLY_PAID', 'PAID', 'OVERDUE'
    )),
    CONSTRAINT chk_loan_installments_amounts_nonneg CHECK (
        installment_number > 0
        AND opening_principal_balance >= 0
        AND principal_due >= 0
        AND interest_due >= 0
        AND penalty_due >= 0
        AND principal_paid >= 0
        AND interest_paid >= 0
        AND penalty_paid >= 0
        AND scheduled_installment_amount >= 0
    )
);

CREATE INDEX idx_loan_installments_loan ON loan_installments (loan_id);
CREATE INDEX idx_loan_installments_due ON loan_installments (cooperative_id, due_date, status);

CREATE TABLE loan_repayment_allocations (
    id                  UUID            PRIMARY KEY,
    repayment_id        UUID            NOT NULL,
    installment_id      UUID            NOT NULL,
    loan_id             UUID            NOT NULL,
    cooperative_id      UUID            NOT NULL,
    component           VARCHAR(32)     NOT NULL,
    amount              NUMERIC(19,4)   NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_loan_alloc_repayment FOREIGN KEY (repayment_id) REFERENCES loan_repayments (id),
    CONSTRAINT fk_loan_alloc_installment FOREIGN KEY (installment_id) REFERENCES loan_installments (id),
    CONSTRAINT fk_loan_alloc_loan FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT chk_loan_alloc_component CHECK (component IN ('PENALTY', 'INTEREST', 'PRINCIPAL')),
    CONSTRAINT chk_loan_alloc_amount_pos CHECK (amount > 0)
);

CREATE INDEX idx_loan_alloc_repayment ON loan_repayment_allocations (repayment_id);
CREATE INDEX idx_loan_alloc_installment ON loan_repayment_allocations (installment_id);

ALTER TABLE fines
    ADD COLUMN source_loan_id UUID;

ALTER TABLE fines
    ADD COLUMN source_loan_installment_id UUID;

ALTER TABLE fines
    ADD CONSTRAINT fk_fines_source_loan FOREIGN KEY (source_loan_id) REFERENCES loans (id);

ALTER TABLE fines
    ADD CONSTRAINT fk_fines_source_loan_installment FOREIGN KEY (source_loan_installment_id) REFERENCES loan_installments (id);

CREATE INDEX idx_fines_source_loan ON fines (source_loan_id);
CREATE INDEX idx_fines_source_loan_installment ON fines (source_loan_installment_id);
