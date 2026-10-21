-- Phase 4: monthly contributions, special campaigns, immutable financial ledger

CREATE TABLE contributions (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    member_user_id          UUID            NOT NULL,
    "year"                  INT             NOT NULL,
    "month"                 INT             NOT NULL,
    expected_amount         NUMERIC(19,4)   NOT NULL,
    paid_amount             NUMERIC(19,4)   NOT NULL DEFAULT 0,
    outstanding_amount      NUMERIC(19,4)   NOT NULL,
    payment_date            DATE,
    status                  VARCHAR(32)     NOT NULL,
    payment_reference       VARCHAR(128),
    notes                   VARCHAR(2000),
    recorded_by             UUID,
    ledger_revision         INT             NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_contributions_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_contributions_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_contributions_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT uq_contributions_period UNIQUE (cooperative_id, member_user_id, "year", "month"),
    CONSTRAINT chk_contributions_year CHECK ("year" BETWEEN 2000 AND 2100),
    CONSTRAINT chk_contributions_month CHECK ("month" BETWEEN 1 AND 12),
    CONSTRAINT chk_contributions_status CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'PAID', 'WAIVED', 'CANCELLED')),
    CONSTRAINT chk_contributions_amounts_nonneg CHECK (
        expected_amount >= 0 AND paid_amount >= 0 AND outstanding_amount >= 0
    )
);

CREATE INDEX idx_contributions_cooperative ON contributions (cooperative_id);
CREATE INDEX idx_contributions_member ON contributions (member_user_id);
CREATE INDEX idx_contributions_year_month ON contributions ("year", "month");
CREATE INDEX idx_contributions_status ON contributions (status);
CREATE INDEX idx_contributions_payment_reference ON contributions (payment_reference);

CREATE TABLE special_contribution_campaigns (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    name                    VARCHAR(255)    NOT NULL,
    purpose                 VARCHAR(512),
    description             VARCHAR(2000),
    suggested_amount        NUMERIC(19,4),
    target_amount           NUMERIC(19,4),
    start_date              DATE,
    end_date                DATE,
    status                  VARCHAR(32)     NOT NULL DEFAULT 'DRAFT',
    created_by              UUID,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_special_campaigns_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_special_campaigns_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT chk_special_campaigns_status CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED', 'CANCELLED')),
    CONSTRAINT chk_special_campaigns_amounts_nonneg CHECK (
        (suggested_amount IS NULL OR suggested_amount >= 0)
        AND (target_amount IS NULL OR target_amount >= 0)
    )
);

CREATE INDEX idx_special_campaigns_cooperative ON special_contribution_campaigns (cooperative_id);
CREATE INDEX idx_special_campaigns_status ON special_contribution_campaigns (status);

CREATE TABLE special_contributions (
    id                      UUID            PRIMARY KEY,
    campaign_id             UUID            NOT NULL,
    cooperative_id          UUID            NOT NULL,
    member_user_id          UUID            NOT NULL,
    amount                  NUMERIC(19,4)   NOT NULL,
    contribution_date       DATE            NOT NULL,
    payment_reference       VARCHAR(128),
    notes                   VARCHAR(2000),
    status                  VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    reviewed_by             UUID,
    reviewed_at             TIMESTAMPTZ,
    review_notes            VARCHAR(2000),
    recorded_by             UUID,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_special_contrib_campaign FOREIGN KEY (campaign_id) REFERENCES special_contribution_campaigns (id),
    CONSTRAINT fk_special_contrib_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_special_contrib_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_special_contrib_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT fk_special_contrib_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT chk_special_contrib_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_special_contrib_amount_nonneg CHECK (amount >= 0)
);

CREATE INDEX idx_special_contrib_campaign ON special_contributions (campaign_id);
CREATE INDEX idx_special_contrib_cooperative ON special_contributions (cooperative_id);
CREATE INDEX idx_special_contrib_member ON special_contributions (member_user_id);
CREATE INDEX idx_special_contrib_status ON special_contributions (status);
CREATE INDEX idx_special_contrib_payment_reference ON special_contributions (payment_reference);

CREATE TABLE financial_ledger (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    member_user_id          UUID,
    transaction_type        VARCHAR(64)     NOT NULL,
    debit_amount            NUMERIC(19,4)   NOT NULL DEFAULT 0,
    credit_amount           NUMERIC(19,4)   NOT NULL DEFAULT 0,
    currency                CHAR(3)         NOT NULL DEFAULT 'RWF',
    transaction_date        DATE            NOT NULL,
    reference               VARCHAR(128),
    source_entity_type      VARCHAR(64)     NOT NULL,
    source_entity_id        UUID            NOT NULL,
    description             VARCHAR(2000),
    status                  VARCHAR(32)     NOT NULL DEFAULT 'APPROVED',
    recorded_by             UUID,
    approved_by             UUID,
    reverses_entry_id       UUID,
    idempotency_key         VARCHAR(128)    NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_ledger_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_ledger_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_ledger_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT fk_ledger_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT fk_ledger_reverses FOREIGN KEY (reverses_entry_id) REFERENCES financial_ledger (id),
    CONSTRAINT uq_ledger_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_ledger_status CHECK (status IN ('APPROVED', 'REVERSED')),
    CONSTRAINT chk_ledger_amounts_nonneg CHECK (debit_amount >= 0 AND credit_amount >= 0)
);

CREATE INDEX idx_ledger_cooperative ON financial_ledger (cooperative_id);
CREATE INDEX idx_ledger_member ON financial_ledger (member_user_id);
CREATE INDEX idx_ledger_type ON financial_ledger (transaction_type);
CREATE INDEX idx_ledger_source ON financial_ledger (source_entity_type, source_entity_id);
CREATE INDEX idx_ledger_status ON financial_ledger (status);
CREATE INDEX idx_ledger_transaction_date ON financial_ledger (transaction_date);
