-- Share purchases (imigabane) with Accountant/President approval.
-- Compatible with PostgreSQL (production) and H2 PostgreSQL mode (tests).
-- Existing membership share_count values are preserved; only the allowed range and
-- insert default change. New members start at 0 and buy their first share.

ALTER TABLE cooperative_memberships DROP CONSTRAINT chk_membership_share_count;
ALTER TABLE cooperative_memberships ADD CONSTRAINT chk_membership_share_count
    CHECK (share_count >= 0 AND share_count <= 1000);
ALTER TABLE cooperative_memberships ALTER COLUMN share_count SET DEFAULT 0;

ALTER TABLE cooperative_settings ADD COLUMN base_share_price NUMERIC(19,4);

CREATE TABLE share_purchases (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    member_user_id          UUID            NOT NULL,
    number_of_shares        INT             NOT NULL,
    price_per_share         NUMERIC(19,4)   NOT NULL,
    total_amount            NUMERIC(19,4)   NOT NULL,
    status                  VARCHAR(32)     NOT NULL,
    requested_by            UUID            NOT NULL,
    requested_at            TIMESTAMPTZ     NOT NULL,
    reviewed_by             UUID,
    reviewed_at             TIMESTAMPTZ,
    rejection_reason        VARCHAR(2000),
    notes                   VARCHAR(2000),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_share_purchases_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_share_purchases_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_share_purchases_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_share_purchases_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT chk_share_purchases_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_share_purchases_shares CHECK (number_of_shares >= 1 AND number_of_shares <= 1000),
    CONSTRAINT chk_share_purchases_price CHECK (price_per_share >= 0),
    CONSTRAINT chk_share_purchases_amount CHECK (total_amount >= 0)
);

CREATE INDEX idx_share_purchases_cooperative ON share_purchases (cooperative_id);
CREATE INDEX idx_share_purchases_member ON share_purchases (member_user_id);
CREATE INDEX idx_share_purchases_status ON share_purchases (cooperative_id, status);
CREATE INDEX idx_share_purchases_reviewed_at ON share_purchases (cooperative_id, reviewed_at);
