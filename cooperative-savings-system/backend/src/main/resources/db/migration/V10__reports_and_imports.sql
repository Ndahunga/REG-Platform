-- Phase 10: contribution Excel imports (report exports audited via AuditService EXPORT — no export table)

CREATE TABLE contribution_imports (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID            NOT NULL,
    "year"              INT             NOT NULL,
    "month"             INT             NOT NULL,
    original_filename   VARCHAR(512),
    storage_key         VARCHAR(1024),
    content_type        VARCHAR(128),
    size_bytes          BIGINT,
    status              VARCHAR(32)     NOT NULL,
    total_rows          INT             NOT NULL DEFAULT 0,
    valid_rows          INT             NOT NULL DEFAULT 0,
    invalid_rows        INT             NOT NULL DEFAULT 0,
    uploaded_by         UUID,
    confirmed_by        UUID,
    confirmed_at        TIMESTAMPTZ,
    error_summary       TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_contribution_imports_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_contribution_imports_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id),
    CONSTRAINT fk_contribution_imports_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users (id),
    CONSTRAINT chk_contribution_imports_status CHECK (status IN (
        'UPLOADED', 'VALIDATED', 'CONFIRMED', 'CANCELLED', 'FAILED'
    )),
    CONSTRAINT chk_contribution_imports_year CHECK ("year" BETWEEN 2000 AND 2100),
    CONSTRAINT chk_contribution_imports_month CHECK ("month" BETWEEN 1 AND 12),
    CONSTRAINT chk_contribution_imports_rows_nonneg CHECK (
        total_rows >= 0 AND valid_rows >= 0 AND invalid_rows >= 0
    )
);

CREATE INDEX idx_contribution_imports_cooperative ON contribution_imports (cooperative_id);
CREATE INDEX idx_contribution_imports_coop_status ON contribution_imports (cooperative_id, status);
CREATE INDEX idx_contribution_imports_coop_period ON contribution_imports (cooperative_id, "year", "month");

CREATE TABLE contribution_import_rows (
    id                  UUID            PRIMARY KEY,
    import_id           UUID            NOT NULL,
    row_number          INT             NOT NULL,
    username            VARCHAR(64),
    member_name         VARCHAR(255),
    amount              NUMERIC(19,4),
    payment_date        DATE,
    reference           VARCHAR(128),
    notes               VARCHAR(2000),
    valid               BOOLEAN         NOT NULL DEFAULT FALSE,
    error_messages      TEXT,
    member_user_id      UUID,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contribution_import_rows_import FOREIGN KEY (import_id) REFERENCES contribution_imports (id),
    CONSTRAINT fk_contribution_import_rows_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT uq_contribution_import_rows_import_row UNIQUE (import_id, row_number),
    CONSTRAINT chk_contribution_import_rows_row_number CHECK (row_number > 0)
);

CREATE INDEX idx_contribution_import_rows_import ON contribution_import_rows (import_id);
CREATE INDEX idx_contribution_import_rows_valid ON contribution_import_rows (import_id, valid);
