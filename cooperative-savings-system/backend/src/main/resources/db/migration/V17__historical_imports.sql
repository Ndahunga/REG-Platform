-- Phase: historical data import tracking (non-financial infrastructure).
-- Does not replace operational financial tables.

CREATE TABLE historical_imports (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID            NOT NULL,
    original_filename   VARCHAR(512),
    storage_key         VARCHAR(1024),
    content_type        VARCHAR(128),
    size_bytes          BIGINT,
    file_hash           VARCHAR(64)     NOT NULL,
    status              VARCHAR(32)     NOT NULL,
    total_rows          INT             NOT NULL DEFAULT 0,
    valid_rows          INT             NOT NULL DEFAULT 0,
    invalid_rows        INT             NOT NULL DEFAULT 0,
    sheet_summary       TEXT,
    reconciliation_summary TEXT,
    error_summary       TEXT,
    uploaded_by         UUID,
    validated_at        TIMESTAMPTZ,
    confirmed_by        UUID,
    confirmed_at        TIMESTAMPTZ,
    cancelled_by        UUID,
    cancelled_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_historical_imports_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_historical_imports_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id),
    CONSTRAINT fk_historical_imports_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users (id),
    CONSTRAINT fk_historical_imports_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id),
    CONSTRAINT chk_historical_imports_status CHECK (status IN (
        'UPLOADED', 'VALIDATING', 'READY', 'INVALID', 'CONFIRMED', 'CANCELLED', 'FAILED'
    )),
    CONSTRAINT chk_historical_imports_rows_nonneg CHECK (
        total_rows >= 0 AND valid_rows >= 0 AND invalid_rows >= 0
    )
);

CREATE INDEX idx_historical_imports_cooperative ON historical_imports (cooperative_id);
CREATE INDEX idx_historical_imports_coop_status ON historical_imports (cooperative_id, status);
CREATE INDEX idx_historical_imports_coop_hash ON historical_imports (cooperative_id, file_hash);

CREATE TABLE historical_import_rows (
    id                      UUID            PRIMARY KEY,
    import_id               UUID            NOT NULL,
    sheet                   VARCHAR(64)     NOT NULL,
    row_number              INT             NOT NULL,
    source_key              VARCHAR(255),
    fingerprint             VARCHAR(64),
    valid                   BOOLEAN         NOT NULL DEFAULT FALSE,
    error_messages          TEXT,
    resulting_entity_type   VARCHAR(64),
    resulting_entity_id     UUID,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_historical_import_rows_import FOREIGN KEY (import_id) REFERENCES historical_imports (id),
    CONSTRAINT uq_historical_import_rows_import_sheet_row UNIQUE (import_id, sheet, row_number),
    CONSTRAINT chk_historical_import_rows_row_number CHECK (row_number > 0)
);

CREATE INDEX idx_historical_import_rows_import ON historical_import_rows (import_id);
CREATE INDEX idx_historical_import_rows_valid ON historical_import_rows (import_id, valid);
CREATE INDEX idx_historical_import_rows_fingerprint ON historical_import_rows (fingerprint);
CREATE INDEX idx_historical_import_rows_source ON historical_import_rows (sheet, source_key);
