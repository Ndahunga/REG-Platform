-- Historical valuation snapshots at share-issuance events only (never on GET /valuation).
-- Existing production share_count values are unchanged.

CREATE TABLE share_valuation_snapshots (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    calculated_at           TIMESTAMPTZ     NOT NULL,
    available_funds         NUMERIC(19,4)   NOT NULL,
    outstanding_loans       NUMERIC(19,4)   NOT NULL,
    unpaid_interest         NUMERIC(19,4)   NOT NULL,
    unpaid_penalties        NUMERIC(19,4)   NOT NULL,
    other_assets            NUMERIC(19,4)   NOT NULL,
    liabilities             NUMERIC(19,4)   NOT NULL,
    total_ikimina_value     NUMERIC(19,4)   NOT NULL,
    total_existing_shares   BIGINT          NOT NULL,
    current_share_value     NUMERIC(19,4)   NOT NULL,
    reason                  VARCHAR(32)     NOT NULL,
    source_entity_type      VARCHAR(64),
    source_entity_id        UUID,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_share_valuation_snapshots_coop FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT chk_share_valuation_snapshots_reason CHECK (reason IN ('PURCHASE_PRICING', 'SHARE_ISSUANCE'))
);

CREATE INDEX idx_share_valuation_snapshots_coop ON share_valuation_snapshots (cooperative_id, calculated_at);
CREATE INDEX idx_share_valuation_snapshots_source ON share_valuation_snapshots (source_entity_type, source_entity_id);

ALTER TABLE share_purchases ADD COLUMN valuation_snapshot_id UUID;
ALTER TABLE share_purchases
    ADD CONSTRAINT fk_share_purchases_valuation_snapshot
    FOREIGN KEY (valuation_snapshot_id) REFERENCES share_valuation_snapshots (id);
