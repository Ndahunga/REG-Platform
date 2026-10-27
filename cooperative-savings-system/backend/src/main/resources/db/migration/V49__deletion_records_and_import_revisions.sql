-- A permanent record of every scheme or member deleted for good: who did it, when, why and how many rows went.
-- It has no reference to the scheme or the user (they no longer exist) and is never deleted with them.
CREATE TABLE data_deletion_records (
    id               UUID          PRIMARY KEY,
    kind             VARCHAR(16)   NOT NULL,
    scheme_id        UUID          NOT NULL,
    scheme_name      VARCHAR(255),
    subject_user_id  UUID,
    subject_username VARCHAR(128),
    reason           VARCHAR(500)  NOT NULL,
    rows_deleted     TEXT,
    accounts_deleted INT           NOT NULL DEFAULT 0,
    deleted_by       UUID          NOT NULL,
    deleted_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
ALTER TABLE data_deletion_records ADD CONSTRAINT chk_data_deletion_kind CHECK (kind IN ('SCHEME', 'MEMBER'));
CREATE INDEX idx_data_deletion_records_scheme ON data_deletion_records (scheme_id);

-- A validated migration workbook can be replaced by a corrected one. The earlier upload is kept, marked SUPERSEDED,
-- with a link to the upload that replaced it and the reason given.
ALTER TABLE historical_imports DROP CONSTRAINT chk_historical_imports_status;
ALTER TABLE historical_imports ADD CONSTRAINT chk_historical_imports_status CHECK (status IN
    ('UPLOADED', 'VALIDATING', 'READY', 'INVALID', 'CONFIRMED', 'CANCELLED', 'FAILED', 'SUPERSEDED'));
ALTER TABLE historical_imports ADD COLUMN revision_of_import_id UUID;
ALTER TABLE historical_imports ADD COLUMN superseded_by_import_id UUID;
ALTER TABLE historical_imports ADD COLUMN superseded_at TIMESTAMPTZ;
ALTER TABLE historical_imports ADD COLUMN revision_reason VARCHAR(500);

-- Every upload of the rotation and positions workbook, in order. One is CURRENT; the ones it replaced are SUPERSEDED.
CREATE TABLE opening_workbook_imports (
    id                   UUID          PRIMARY KEY,
    cooperative_id       UUID          NOT NULL REFERENCES cooperatives (id),
    version_number       INT           NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    original_filename    VARCHAR(512),
    file_hash            VARCHAR(64)   NOT NULL,
    storage_key          VARCHAR(1024),
    cycle_id             UUID,
    first_month_year     INT,
    first_month_month    INT,
    summary              TEXT,
    revision_reason      VARCHAR(500),
    supersedes_id        UUID,
    uploaded_by          UUID          NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    superseded_at        TIMESTAMPTZ
);
ALTER TABLE opening_workbook_imports ADD CONSTRAINT chk_opening_workbook_status CHECK (status IN ('CURRENT', 'SUPERSEDED'));
ALTER TABLE opening_workbook_imports ADD CONSTRAINT uq_opening_workbook_version UNIQUE (cooperative_id, version_number);
CREATE INDEX idx_opening_workbook_imports_coop ON opening_workbook_imports (cooperative_id, status);
