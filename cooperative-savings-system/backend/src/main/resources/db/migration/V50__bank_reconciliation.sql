-- Bank and mobile-money statements uploaded by an officer, line by line, and what each line was reconciled with.
CREATE TABLE bank_statements (
    id                UUID          PRIMARY KEY,
    cooperative_id    UUID          NOT NULL REFERENCES cooperatives (id),
    source            VARCHAR(16)   NOT NULL,
    account_label     VARCHAR(128),
    original_filename VARCHAR(512),
    file_hash         VARCHAR(64)   NOT NULL,
    storage_key       VARCHAR(1024),
    period_from       DATE,
    period_to         DATE,
    opening_balance   NUMERIC(19,4),
    closing_balance   NUMERIC(19,4),
    total_in          NUMERIC(19,4)  NOT NULL DEFAULT 0,
    total_out         NUMERIC(19,4)  NOT NULL DEFAULT 0,
    line_count        INT           NOT NULL DEFAULT 0,
    new_lines         INT           NOT NULL DEFAULT 0,
    parse_warnings    TEXT,
    uploaded_by       UUID          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
ALTER TABLE bank_statements ADD CONSTRAINT chk_bank_statements_source CHECK (source IN ('BANK', 'MOBILE_MONEY'));
CREATE INDEX idx_bank_statements_coop ON bank_statements (cooperative_id, created_at);

CREATE TABLE bank_statement_lines (
    id                   UUID          PRIMARY KEY,
    cooperative_id       UUID          NOT NULL REFERENCES cooperatives (id),
    statement_id         UUID          NOT NULL REFERENCES bank_statements (id),
    line_number          INT           NOT NULL,
    transaction_date     DATE          NOT NULL,
    value_date           DATE,
    narration            VARCHAR(1000) NOT NULL,
    reference            VARCHAR(128),
    direction            VARCHAR(4)    NOT NULL,
    amount               NUMERIC(19,4) NOT NULL,
    balance              NUMERIC(19,4),
    -- The same line in an overlapping statement is recognised and not counted twice.
    fingerprint          VARCHAR(64)   NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    category             VARCHAR(32),
    suggestion           TEXT,
    suggestion_score     INT,
    note                 VARCHAR(500),
    decided_by           UUID,
    decided_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
ALTER TABLE bank_statement_lines ADD CONSTRAINT chk_bank_lines_direction CHECK (direction IN ('IN', 'OUT'));
ALTER TABLE bank_statement_lines ADD CONSTRAINT chk_bank_lines_amount CHECK (amount > 0);
ALTER TABLE bank_statement_lines ADD CONSTRAINT chk_bank_lines_status
    CHECK (status IN ('MATCHED', 'SUGGESTED', 'CONFIRMED', 'EXPLAINED', 'UNMATCHED'));
ALTER TABLE bank_statement_lines ADD CONSTRAINT uq_bank_lines_fingerprint UNIQUE (cooperative_id, fingerprint);
CREATE INDEX idx_bank_lines_statement ON bank_statement_lines (statement_id, line_number);
CREATE INDEX idx_bank_lines_status ON bank_statement_lines (cooperative_id, status);

-- What a line was reconciled with: one or more member payments or ledger entries whose amounts add up to the line.
CREATE TABLE bank_statement_matches (
    id                     UUID          PRIMARY KEY,
    cooperative_id         UUID          NOT NULL REFERENCES cooperatives (id),
    line_id                UUID          NOT NULL REFERENCES bank_statement_lines (id),
    payment_id             UUID          REFERENCES member_payments (id),
    ledger_entry_id        UUID          REFERENCES financial_ledger (id),
    trusted_transaction_id UUID,
    amount                 NUMERIC(19,4) NOT NULL,
    how                    VARCHAR(16)   NOT NULL,
    matched_by             UUID,
    matched_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
ALTER TABLE bank_statement_matches ADD CONSTRAINT chk_bank_matches_target CHECK (
    (payment_id IS NOT NULL AND ledger_entry_id IS NULL) OR (payment_id IS NULL AND ledger_entry_id IS NOT NULL));
ALTER TABLE bank_statement_matches ADD CONSTRAINT chk_bank_matches_how CHECK (how IN ('AUTOMATIC', 'OFFICER'));
-- A payment or a ledger entry is reconciled with one statement line only.
ALTER TABLE bank_statement_matches ADD CONSTRAINT uq_bank_matches_payment UNIQUE (payment_id);
ALTER TABLE bank_statement_matches ADD CONSTRAINT uq_bank_matches_ledger UNIQUE (ledger_entry_id);
CREATE INDEX idx_bank_matches_line ON bank_statement_matches (line_id);
