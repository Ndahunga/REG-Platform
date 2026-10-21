-- Member-side receipt analysis and the scheme's designated payment accounts.
--
-- Additive only. Two new kinds of record:
--
--   * scheme_payment_accounts: the account(s) a Saving Scheme asks its members to pay into. A receipt whose recipient
--     can be read is compared with these; nothing is ever verified or posted from the comparison.
--   * receipt_analyses: what the system read from a receipt BEFORE the payment was submitted, kept per uploader and
--     file content. It is advisory: it can stop a submission that is plainly failed or pending, or ask for a manual
--     review, but it never confirms, verifies or posts anything.
--
-- member_payments records which analysis the submission was checked against and whether the payer asked for a manual
-- review, so the officer can see it and the rule cannot be bypassed by skipping the analysis step.

CREATE TABLE scheme_payment_accounts (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID            NOT NULL,
    channel             VARCHAR(32)     NOT NULL,
    account_identifier  VARCHAR(64)     NOT NULL,
    account_label       VARCHAR(100),
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by          UUID            NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_scheme_payment_accounts_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_scheme_payment_accounts_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_scheme_payment_accounts UNIQUE (cooperative_id, channel, account_identifier),
    CONSTRAINT chk_scheme_payment_accounts_channel CHECK (channel IN ('MTN_MOMO', 'AIRTEL_MONEY', 'BANK_TRANSFER', 'OTHER')),
    CONSTRAINT chk_scheme_payment_accounts_identifier CHECK (CHAR_LENGTH(account_identifier) >= 6)
);

CREATE INDEX idx_scheme_payment_accounts_cooperative ON scheme_payment_accounts (cooperative_id, active);

CREATE TABLE receipt_analyses (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID            NOT NULL,
    uploaded_by         UUID            NOT NULL,
    file_storage_key    VARCHAR(1024)   NOT NULL,
    file_sha256         VARCHAR(64)     NOT NULL,
    content_type        VARCHAR(64),
    engine              VARCHAR(64),
    reading_status      VARCHAR(16)     NOT NULL,
    failure_code        VARCHAR(64),
    receipt_status      VARCHAR(32),
    mean_confidence     NUMERIC(5,4),
    extracted_amount    NUMERIC(19,4),
    extracted_currency  VARCHAR(3),
    extracted_reference VARCHAR(128),
    reference_confidence NUMERIC(5,4),
    extracted_paid_at   TIMESTAMPTZ,
    extracted_sender    VARCHAR(64),
    extracted_recipient VARCHAR(64),
    extracted_provider  VARCHAR(32),
    amount_ambiguous    BOOLEAN         NOT NULL DEFAULT FALSE,
    amount_candidates   VARCHAR(500),
    recipient_match     VARCHAR(16)     NOT NULL DEFAULT 'UNKNOWN',
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_receipt_analyses_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_receipt_analyses_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id),
    -- one analysis per uploader and file content: analysing the same receipt again returns the stored result
    CONSTRAINT uq_receipt_analyses_file UNIQUE (cooperative_id, uploaded_by, file_sha256),
    CONSTRAINT chk_receipt_analyses_reading CHECK (reading_status IN ('COMPLETED', 'FAILED', 'UNAVAILABLE', 'UNSUPPORTED')),
    CONSTRAINT chk_receipt_analyses_receipt_status CHECK (
        receipt_status IS NULL OR receipt_status IN ('SUCCESS_INDICATED', 'FAILED_INDICATED', 'PENDING_INDICATED', 'UNKNOWN')
    ),
    CONSTRAINT chk_receipt_analyses_completed CHECK (reading_status <> 'COMPLETED' OR receipt_status IS NOT NULL),
    CONSTRAINT chk_receipt_analyses_confidence CHECK (mean_confidence IS NULL OR (mean_confidence >= 0 AND mean_confidence <= 1)),
    CONSTRAINT chk_receipt_analyses_amount CHECK (extracted_amount IS NULL OR extracted_amount >= 0),
    CONSTRAINT chk_receipt_analyses_recipient CHECK (recipient_match IN ('MATCH', 'MISMATCH', 'UNKNOWN'))
);

CREATE INDEX idx_receipt_analyses_cooperative ON receipt_analyses (cooperative_id, uploaded_by, created_at);

ALTER TABLE member_payments ADD COLUMN receipt_analysis_id UUID;
ALTER TABLE member_payments ADD COLUMN receipt_review_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE member_payments ADD COLUMN receipt_recipient_match VARCHAR(16);
ALTER TABLE member_payments ADD COLUMN receipt_amount_ambiguous BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE member_payments
    ADD CONSTRAINT fk_member_payments_receipt_analysis FOREIGN KEY (receipt_analysis_id) REFERENCES receipt_analyses (id);
ALTER TABLE member_payments
    ADD CONSTRAINT chk_member_payments_recipient_match CHECK (
        receipt_recipient_match IS NULL OR receipt_recipient_match IN ('MATCH', 'MISMATCH', 'UNKNOWN')
    );
-- a payment asks for a manual review only when it carries an analysis that says one is needed
ALTER TABLE member_payments
    ADD CONSTRAINT chk_member_payments_review_needs_analysis CHECK (
        receipt_review_requested = FALSE OR receipt_analysis_id IS NOT NULL
    );

ALTER TABLE payment_receipt_extractions ADD COLUMN amount_ambiguous BOOLEAN NOT NULL DEFAULT FALSE;
