-- Phase 5: loan settings, loans, and repayments

CREATE TABLE loan_settings (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    interest_rate_percent       NUMERIC(8,4)    NOT NULL,
    interest_type               VARCHAR(32)     NOT NULL DEFAULT 'FLAT',
    max_loan_amount             NUMERIC(19,4),
    max_term_months             INT,
    min_membership_months       INT             NOT NULL DEFAULT 0,
    allow_member_requests       BOOLEAN         NOT NULL DEFAULT TRUE,
    late_fee_enabled            BOOLEAN         NOT NULL DEFAULT FALSE,
    currency                    CHAR(3)         NOT NULL DEFAULT 'RWF',
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_loan_settings_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT uq_loan_settings_cooperative UNIQUE (cooperative_id),
    CONSTRAINT chk_loan_settings_interest_type CHECK (interest_type IN ('FLAT', 'REDUCING')),
    CONSTRAINT chk_loan_settings_rate_nonneg CHECK (interest_rate_percent >= 0),
    CONSTRAINT chk_loan_settings_max_amount_nonneg CHECK (max_loan_amount IS NULL OR max_loan_amount >= 0),
    CONSTRAINT chk_loan_settings_max_term_nonneg CHECK (max_term_months IS NULL OR max_term_months > 0),
    CONSTRAINT chk_loan_settings_min_membership_nonneg CHECK (min_membership_months >= 0)
);

CREATE TABLE loans (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    requested_amount            NUMERIC(19,4)   NOT NULL,
    approved_amount             NUMERIC(19,4),
    principal_amount            NUMERIC(19,4),
    interest_rate_percent       NUMERIC(8,4)    NOT NULL,
    interest_type               VARCHAR(32)     NOT NULL,
    term_months                 INT             NOT NULL,
    interest_amount             NUMERIC(19,4),
    outstanding_principal       NUMERIC(19,4)   NOT NULL DEFAULT 0,
    outstanding_interest        NUMERIC(19,4)   NOT NULL DEFAULT 0,
    total_repaid_principal      NUMERIC(19,4)   NOT NULL DEFAULT 0,
    total_repaid_interest       NUMERIC(19,4)   NOT NULL DEFAULT 0,
    request_date                DATE            NOT NULL,
    approval_date               DATE,
    disbursement_date           DATE,
    due_date                    DATE,
    status                      VARCHAR(32)     NOT NULL,
    purpose                     VARCHAR(2000),
    rejection_reason            VARCHAR(2000),
    requested_by                UUID,
    approved_by                 UUID,
    disbursed_by                UUID,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_loans_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_loans_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_loans_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_loans_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT fk_loans_disbursed_by FOREIGN KEY (disbursed_by) REFERENCES users (id),
    CONSTRAINT chk_loans_status CHECK (status IN (
        'PENDING', 'APPROVED', 'ACTIVE', 'OVERDUE', 'REJECTED', 'CLOSED', 'WRITTEN_OFF'
    )),
    CONSTRAINT chk_loans_interest_type CHECK (interest_type IN ('FLAT', 'REDUCING')),
    CONSTRAINT chk_loans_amounts_nonneg CHECK (
        requested_amount >= 0
        AND (approved_amount IS NULL OR approved_amount >= 0)
        AND (principal_amount IS NULL OR principal_amount >= 0)
        AND interest_rate_percent >= 0
        AND term_months > 0
        AND (interest_amount IS NULL OR interest_amount >= 0)
        AND outstanding_principal >= 0
        AND outstanding_interest >= 0
        AND total_repaid_principal >= 0
        AND total_repaid_interest >= 0
    )
);

CREATE INDEX idx_loans_cooperative ON loans (cooperative_id);
CREATE INDEX idx_loans_member ON loans (member_user_id);
CREATE INDEX idx_loans_status ON loans (status);
CREATE INDEX idx_loans_due_date ON loans (due_date);
CREATE INDEX idx_loans_coop_status ON loans (cooperative_id, status);

CREATE TABLE loan_repayments (
    id                          UUID            PRIMARY KEY,
    loan_id                     UUID            NOT NULL,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    payment_date                DATE            NOT NULL,
    amount_total                NUMERIC(19,4)   NOT NULL,
    principal_portion           NUMERIC(19,4)   NOT NULL,
    interest_portion            NUMERIC(19,4)   NOT NULL,
    payment_reference           VARCHAR(128),
    notes                       VARCHAR(2000),
    recorded_by                 UUID,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_loan_repayments_loan FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT fk_loan_repayments_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_loan_repayments_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_loan_repayments_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT chk_loan_repayments_amounts_nonneg CHECK (
        amount_total >= 0 AND principal_portion >= 0 AND interest_portion >= 0
    ),
    CONSTRAINT chk_loan_repayments_split CHECK (
        principal_portion + interest_portion = amount_total
    )
);

CREATE INDEX idx_loan_repayments_loan ON loan_repayments (loan_id);
CREATE INDEX idx_loan_repayments_cooperative ON loan_repayments (cooperative_id);
CREATE INDEX idx_loan_repayments_member ON loan_repayments (member_user_id);
CREATE INDEX idx_loan_repayments_payment_date ON loan_repayments (payment_date);
