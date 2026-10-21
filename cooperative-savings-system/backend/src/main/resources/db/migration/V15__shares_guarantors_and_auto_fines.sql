-- Member share counts (imigabane), contribution period snapshots,
-- automatic-fine uniqueness, and loan guarantors (kwishingira).
-- Compatible with PostgreSQL (production) and H2 PostgreSQL mode (tests).

ALTER TABLE cooperative_memberships ADD COLUMN share_count INTEGER DEFAULT 1;
UPDATE cooperative_memberships SET share_count = 1 WHERE share_count IS NULL;
ALTER TABLE cooperative_memberships ALTER COLUMN share_count SET NOT NULL;
ALTER TABLE cooperative_memberships ADD CONSTRAINT chk_membership_share_count
    CHECK (share_count >= 1 AND share_count <= 1000);

ALTER TABLE contributions ADD COLUMN share_count INTEGER;
UPDATE contributions SET share_count = 1 WHERE share_count IS NULL;

ALTER TABLE fines ADD COLUMN automatic_source_key VARCHAR(80);
CREATE UNIQUE INDEX ux_fines_automatic_source_key ON fines (automatic_source_key);

CREATE TABLE loan_guarantors (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    loan_id                 UUID            NOT NULL,
    guarantor_user_id       UUID            NOT NULL,
    guaranteed_amount       NUMERIC(19,4)   NOT NULL,
    status                  VARCHAR(32)     NOT NULL,
    requested_by            UUID            NOT NULL,
    requested_at            TIMESTAMPTZ     NOT NULL,
    responded_at            TIMESTAMPTZ,
    response_comment        VARCHAR(2000),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_loan_guarantors_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_loan_guarantors_loan FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT fk_loan_guarantors_guarantor FOREIGN KEY (guarantor_user_id) REFERENCES users (id),
    CONSTRAINT fk_loan_guarantors_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT uq_loan_guarantors_loan UNIQUE (loan_id),
    CONSTRAINT chk_loan_guarantors_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT chk_loan_guarantors_amount CHECK (guaranteed_amount > 0)
);
