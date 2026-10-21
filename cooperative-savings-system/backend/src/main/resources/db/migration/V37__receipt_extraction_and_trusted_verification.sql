-- Receipt extraction, trusted transactions and verification outcomes (Phase 3).
--
-- Additive only. Three separate kinds of record, deliberately kept apart:
--
--   * payment_receipt_extractions: what OCR READ from an uploaded receipt. Advisory evidence to look at; never proof.
--   * trusted_transactions: authoritative records of money that really moved (an imported bank or mobile-money
--     statement line recorded by an officer, or a signed provider callback). Only these can establish CONFIRMED.
--   * payment_verification_checks: the immutable history of verification OUTCOMES (CONFIRMED, PENDING, FAILED,
--     UNMATCHED, CONFLICTED). They are not payment lifecycle statuses: SUBMITTED/UNDER_REVIEW/VERIFIED/POSTED are
--     unchanged, and nothing here verifies or posts a payment by itself.

ALTER TABLE member_payments
    ADD CONSTRAINT uq_member_payments_id_cooperative UNIQUE (id, cooperative_id);

CREATE TABLE payment_receipt_extractions (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    payment_id              UUID            NOT NULL,
    file_storage_key        VARCHAR(1024)   NOT NULL,
    file_sha256             VARCHAR(64)     NOT NULL,
    content_type            VARCHAR(64),
    engine                  VARCHAR(64),
    status                  VARCHAR(16)     NOT NULL,
    failure_code            VARCHAR(64),
    attempts                INT             NOT NULL DEFAULT 1,
    raw_text                VARCHAR(8000),
    mean_confidence         NUMERIC(5,4),
    extracted_reference     VARCHAR(128),
    extracted_amount        NUMERIC(19,4),
    extracted_currency      VARCHAR(3),
    extracted_paid_at       TIMESTAMPTZ,
    extracted_sender        VARCHAR(64),
    extracted_recipient     VARCHAR(64),
    extracted_provider      VARCHAR(32),
    extracted_status        VARCHAR(32),
    field_confidences       VARCHAR(2000),
    duplicate_of_payment_id UUID,
    created_by              UUID            NOT NULL,
    reviewed_by             UUID,
    reviewed_at             TIMESTAMPTZ,
    review_note             VARCHAR(2000),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_receipt_extractions_payment
        FOREIGN KEY (payment_id, cooperative_id) REFERENCES member_payments (id, cooperative_id),
    CONSTRAINT fk_receipt_extractions_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_receipt_extractions_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
    -- one extraction per payment and file content: processing the same receipt again returns it, never a second copy
    CONSTRAINT uq_receipt_extractions_payment_file UNIQUE (payment_id, file_sha256),
    CONSTRAINT chk_receipt_extractions_status CHECK (status IN ('COMPLETED', 'FAILED', 'UNAVAILABLE', 'UNSUPPORTED')),
    CONSTRAINT chk_receipt_extractions_completed CHECK (status <> 'COMPLETED' OR engine IS NOT NULL),
    CONSTRAINT chk_receipt_extractions_attempts CHECK (attempts >= 1),
    CONSTRAINT chk_receipt_extractions_confidence CHECK (mean_confidence IS NULL OR (mean_confidence >= 0 AND mean_confidence <= 1)),
    CONSTRAINT chk_receipt_extractions_amount CHECK (extracted_amount IS NULL OR extracted_amount >= 0),
    CONSTRAINT chk_receipt_extractions_review CHECK (
        (reviewed_by IS NULL AND reviewed_at IS NULL) OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

CREATE INDEX idx_receipt_extractions_scheme ON payment_receipt_extractions (cooperative_id, file_sha256);

-- Authoritative records of money that actually moved.
CREATE TABLE trusted_transactions (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    source                  VARCHAR(32)     NOT NULL,
    channel                 VARCHAR(32)     NOT NULL,
    transaction_reference   VARCHAR(128)    NOT NULL,
    normalized_reference    VARCHAR(128)    NOT NULL,
    amount                  NUMERIC(19,4)   NOT NULL,
    currency                VARCHAR(3)      NOT NULL,
    transacted_at           TIMESTAMPTZ,
    sender                  VARCHAR(64),
    recipient               VARCHAR(64),
    provider_status         VARCHAR(16)     NOT NULL,
    event_id                VARCHAR(128),
    batch_id                UUID,
    recorded_by             UUID,
    recorded_at             TIMESTAMPTZ     NOT NULL,
    consumed_by_payment_id  UUID,
    consumed_at             TIMESTAMPTZ,
    conflicted_at           TIMESTAMPTZ,
    conflict_detail         VARCHAR(500),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_trusted_transactions_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_trusted_transactions_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT fk_trusted_transactions_consumed
        FOREIGN KEY (consumed_by_payment_id, cooperative_id) REFERENCES member_payments (id, cooperative_id),
    -- one record per scheme, channel and reference: a repeated import or callback is the same transaction
    CONSTRAINT uq_trusted_transactions_reference UNIQUE (cooperative_id, channel, normalized_reference),
    -- a transaction can be consumed by one payment only, and a payment consumes one transaction only
    CONSTRAINT uq_trusted_transactions_consumed UNIQUE (consumed_by_payment_id),
    CONSTRAINT uq_trusted_transactions_event UNIQUE (cooperative_id, source, event_id),
    CONSTRAINT uq_trusted_transactions_id_scope UNIQUE (id, cooperative_id),
    CONSTRAINT chk_trusted_transactions_source CHECK (
        source IN ('BANK_STATEMENT_IMPORT', 'MOBILE_MONEY_STATEMENT_IMPORT', 'PROVIDER_CALLBACK')
    ),
    CONSTRAINT chk_trusted_transactions_channel CHECK (channel IN ('MTN_MOMO', 'AIRTEL_MONEY', 'BANK_TRANSFER', 'OTHER')),
    CONSTRAINT chk_trusted_transactions_status CHECK (provider_status IN ('SUCCESS', 'FAILED', 'PENDING')),
    CONSTRAINT chk_trusted_transactions_amount CHECK (amount > 0),
    -- an import is recorded by an identified officer; a callback by the system with an event id, never by a person
    CONSTRAINT chk_trusted_transactions_provenance CHECK (
        (source = 'PROVIDER_CALLBACK' AND recorded_by IS NULL AND event_id IS NOT NULL)
        OR (source <> 'PROVIDER_CALLBACK' AND recorded_by IS NOT NULL)
    ),
    -- a later record that disagrees with the stored one marks the transaction conflicted; it never overwrites it
    CONSTRAINT chk_trusted_transactions_conflict CHECK (
        (conflicted_at IS NULL AND conflict_detail IS NULL) OR (conflicted_at IS NOT NULL AND conflict_detail IS NOT NULL)
    ),
    CONSTRAINT chk_trusted_transactions_consumed CHECK (
        (consumed_by_payment_id IS NULL AND consumed_at IS NULL)
        OR (consumed_by_payment_id IS NOT NULL AND consumed_at IS NOT NULL)
    )
);

CREATE INDEX idx_trusted_transactions_scheme ON trusted_transactions (cooperative_id, recorded_at);

-- Immutable history of verification outcomes of a payment. The latest row is the current outcome.
CREATE TABLE payment_verification_checks (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    payment_id              UUID            NOT NULL,
    method                  VARCHAR(32)     NOT NULL,
    outcome                 VARCHAR(16)     NOT NULL,
    source                  VARCHAR(32)     NOT NULL,
    trusted_transaction_id  UUID,
    reason_codes            VARCHAR(500),
    detail                  VARCHAR(1000),
    checked_by              UUID,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_verification_checks_payment
        FOREIGN KEY (payment_id, cooperative_id) REFERENCES member_payments (id, cooperative_id),
    CONSTRAINT fk_verification_checks_transaction
        FOREIGN KEY (trusted_transaction_id, cooperative_id) REFERENCES trusted_transactions (id, cooperative_id),
    CONSTRAINT fk_verification_checks_checked_by FOREIGN KEY (checked_by) REFERENCES users (id),
    CONSTRAINT chk_verification_checks_method CHECK (method IN (
        'CASH_RECEIPT_CONFIRMED', 'MOBILE_MONEY_RECONCILED', 'BANK_RECONCILED', 'PROVIDER_CONFIRMED'
    )),
    CONSTRAINT chk_verification_checks_outcome CHECK (outcome IN ('CONFIRMED', 'PENDING', 'FAILED', 'UNMATCHED', 'CONFLICTED')),
    CONSTRAINT chk_verification_checks_source CHECK (source IN (
        'MANUAL_CASH_CONFIRMATION', 'BANK_STATEMENT_IMPORT', 'MOBILE_MONEY_STATEMENT_IMPORT', 'PROVIDER_CALLBACK',
        'RECEIPT_OCR', 'REFERENCE_MATCH'
    )),
    -- Only authoritative evidence can establish CONFIRMED: never OCR, never a reference match, never a screenshot.
    CONSTRAINT chk_verification_checks_confirmed_source CHECK (
        outcome <> 'CONFIRMED' OR source IN (
            'MANUAL_CASH_CONFIRMATION', 'BANK_STATEMENT_IMPORT', 'MOBILE_MONEY_STATEMENT_IMPORT', 'PROVIDER_CALLBACK')
    ),
    -- A confirmation is either an officer's own cash confirmation, or backed by a trusted transaction record.
    CONSTRAINT chk_verification_checks_confirmed_basis CHECK (
        outcome <> 'CONFIRMED'
        OR (source = 'MANUAL_CASH_CONFIRMATION' AND checked_by IS NOT NULL AND trusted_transaction_id IS NULL
            AND method = 'CASH_RECEIPT_CONFIRMED')
        OR (source <> 'MANUAL_CASH_CONFIRMATION' AND trusted_transaction_id IS NOT NULL
            AND method <> 'CASH_RECEIPT_CONFIRMED')
    ),
    CONSTRAINT chk_verification_checks_manual CHECK (source <> 'MANUAL_CASH_CONFIRMATION' OR checked_by IS NOT NULL)
);

CREATE INDEX idx_verification_checks_payment ON payment_verification_checks (cooperative_id, payment_id, created_at);
CREATE INDEX idx_verification_checks_outcome ON payment_verification_checks (cooperative_id, outcome);
