-- Phase 4: multi-year historical transaction import (two-sheet workbook).
--
-- A batch is uploaded and validated (a dry run that posts nothing), then approved by a different officer with fund
-- authority, and only then posted in ONE transaction. Posting is final: nothing is edited or deleted afterwards, and
-- a duplicate of an already posted row is refused by the database (posted_key). Contributions are recorded as historical
-- obligations WITHOUT an obligation snapshot, so their rotation attribution stays unresolved and they can never become
-- verified rotating payout eligibility. Loans are accepted only when fully closed within the history.

CREATE TABLE historical_ledger_imports (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    file_name                   VARCHAR(255)    NOT NULL,
    file_sha256                 VARCHAR(64)     NOT NULL,
    -- the file hash while the batch is live; NULL once cancelled, so the same file can be uploaded again
    active_key                  VARCHAR(64),
    status                      VARCHAR(16)     NOT NULL,
    currency                    VARCHAR(3),
    history_end_date            DATE,
    opening_balance             NUMERIC(19,4),
    declared_closing_balance    NUMERIC(19,4),
    computed_closing_balance    NUMERIC(19,4),
    balance_variance            NUMERIC(19,4),
    total_rows                  INT             NOT NULL DEFAULT 0,
    valid_rows                  INT             NOT NULL DEFAULT 0,
    error_rows                  INT             NOT NULL DEFAULT 0,
    error_count                 INT             NOT NULL DEFAULT 0,
    summary                     TEXT,
    uploaded_by                 UUID            NOT NULL,
    uploaded_at                 TIMESTAMPTZ     NOT NULL,
    approved_by                 UUID,
    approved_at                 TIMESTAMPTZ,
    variance_acknowledged       BOOLEAN         NOT NULL DEFAULT FALSE,
    variance_note               VARCHAR(1000),
    cancelled_by                UUID,
    cancelled_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_hist_ledger_imports_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_hist_ledger_imports_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id),
    CONSTRAINT fk_hist_ledger_imports_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT fk_hist_ledger_imports_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id),
    -- the same file is one live batch per scheme: an upload repeated by mistake returns it instead of making another
    CONSTRAINT uq_hist_ledger_imports_active UNIQUE (cooperative_id, active_key),
    CONSTRAINT uq_hist_ledger_imports_id_scope UNIQUE (id, cooperative_id),
    CONSTRAINT chk_hist_ledger_imports_status CHECK (status IN ('PREVIEWED', 'POSTED', 'CANCELLED')),
    CONSTRAINT chk_hist_ledger_imports_shape CHECK (
        (status = 'PREVIEWED' AND approved_by IS NULL AND approved_at IS NULL AND cancelled_by IS NULL AND active_key IS NOT NULL)
        OR (status = 'POSTED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND cancelled_by IS NULL AND active_key IS NOT NULL)
        OR (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND active_key IS NULL)
    ),
    -- independence: whoever uploaded a batch cannot approve it
    CONSTRAINT chk_hist_ledger_imports_independent CHECK (approved_by IS NULL OR approved_by <> uploaded_by),
    CONSTRAINT chk_hist_ledger_imports_counts CHECK (total_rows >= 0 AND valid_rows >= 0 AND error_rows >= 0 AND error_count >= 0)
);

CREATE INDEX idx_hist_ledger_imports_scheme ON historical_ledger_imports (cooperative_id, uploaded_at);

CREATE TABLE historical_ledger_rows (
    id                      UUID            PRIMARY KEY,
    import_id               UUID            NOT NULL,
    cooperative_id          UUID            NOT NULL,
    sheet                   VARCHAR(16)     NOT NULL,
    row_number              INT             NOT NULL,
    row_type                VARCHAR(40)     NOT NULL,
    username                VARCHAR(100),
    member_user_id          UUID,
    event_date              DATE,
    amount                  NUMERIC(19,4),
    original_reference      VARCHAR(128),
    period_year             INT,
    period_month            INT,
    loan_reference          VARCHAR(64),
    operating_model         VARCHAR(16),
    description             VARCHAR(500),
    setting_key             VARCHAR(40),
    setting_value           VARCHAR(100),
    fingerprint             VARCHAR(64),
    valid                   BOOLEAN         NOT NULL,
    errors                  TEXT,
    -- the fingerprint once posted: a posted row can never be posted again, by any batch of the scheme
    posted_key              VARCHAR(64),
    ledger_entry_id         UUID,
    contribution_id         UUID,
    -- the authoritative operational record this row created (CONTRIBUTION, FINE_PAYMENT, INCOME_EXPENSE), if any
    operational_type        VARCHAR(32),
    operational_id          UUID,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_hist_ledger_rows_import
        FOREIGN KEY (import_id, cooperative_id) REFERENCES historical_ledger_imports (id, cooperative_id),
    CONSTRAINT uq_hist_ledger_rows_posted UNIQUE (cooperative_id, posted_key),
    CONSTRAINT chk_hist_ledger_rows_sheet CHECK (sheet IN ('MEMBERS', 'TRANSACTIONS')),
    CONSTRAINT chk_hist_ledger_rows_posted CHECK (posted_key IS NULL OR valid)
);

CREATE INDEX idx_hist_ledger_rows_import ON historical_ledger_rows (cooperative_id, import_id, sheet, row_number);

CREATE TABLE historical_ledger_import_events (
    id              UUID            PRIMARY KEY,
    import_id       UUID            NOT NULL,
    cooperative_id  UUID            NOT NULL,
    actor_user_id   UUID            NOT NULL,
    actor_role      VARCHAR(64)     NOT NULL,
    from_status     VARCHAR(16),
    to_status       VARCHAR(16)     NOT NULL,
    comment         VARCHAR(1000),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_hist_ledger_events_import
        FOREIGN KEY (import_id, cooperative_id) REFERENCES historical_ledger_imports (id, cooperative_id),
    CONSTRAINT fk_hist_ledger_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT chk_hist_ledger_events_to CHECK (to_status IN ('PREVIEWED', 'POSTED', 'CANCELLED'))
);

CREATE INDEX idx_hist_ledger_events ON historical_ledger_import_events (cooperative_id, import_id, created_at);
