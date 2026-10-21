-- Phase 7: social fund (separate from group funds), contributions, disbursements, settings

INSERT INTO permissions (id, code, name, description) VALUES
    ('a0000001-0000-0000-0000-000000000012', 'SOCIAL_READ', 'Read social fund', 'View social fund balance, contributions, and disbursements'),
    ('a0000001-0000-0000-0000-000000000013', 'SOCIAL_WRITE', 'Manage social fund', 'Approve/reject social contributions and manage disbursements');

-- SUPER_ADMIN gets all new permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT '11111111-1111-1111-1111-111111111111', id
FROM permissions
WHERE code IN ('SOCIAL_READ', 'SOCIAL_WRITE');

-- COOPERATIVE_ADMIN: SOCIAL_READ + SOCIAL_WRITE
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000012'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000013');

-- MEMBER: SOCIAL_READ only
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('33333333-3333-3333-3333-333333333333', 'a0000001-0000-0000-0000-000000000012');

CREATE TABLE social_fund_settings (
    id                              UUID            PRIMARY KEY,
    cooperative_id                  UUID            NOT NULL,
    suggested_contribution_amount   NUMERIC(19,4)   NOT NULL DEFAULT 0,
    enabled                         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_social_fund_settings_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT uq_social_fund_settings_cooperative UNIQUE (cooperative_id),
    CONSTRAINT chk_social_fund_settings_amount_nonneg CHECK (suggested_contribution_amount >= 0)
);

CREATE TABLE social_contributions (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    amount                      NUMERIC(19,4)   NOT NULL,
    contribution_date           DATE            NOT NULL,
    payment_reference           VARCHAR(128),
    notes                       VARCHAR(2000),
    evidence_file_key           VARCHAR(512),
    status                      VARCHAR(32)     NOT NULL,
    submitted_by                UUID,
    reviewed_by                 UUID,
    reviewed_at                 TIMESTAMPTZ,
    review_notes                VARCHAR(2000),
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_social_contributions_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_social_contributions_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_social_contributions_submitted_by FOREIGN KEY (submitted_by) REFERENCES users (id),
    CONSTRAINT fk_social_contributions_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT chk_social_contributions_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_social_contributions_amount_pos CHECK (amount > 0)
);

CREATE INDEX idx_social_contributions_cooperative ON social_contributions (cooperative_id);
CREATE INDEX idx_social_contributions_member ON social_contributions (member_user_id);
CREATE INDEX idx_social_contributions_status ON social_contributions (status);
CREATE INDEX idx_social_contributions_coop_status ON social_contributions (cooperative_id, status);

CREATE TABLE social_disbursements (
    id                              UUID            PRIMARY KEY,
    cooperative_id                  UUID            NOT NULL,
    beneficiary_member_user_id      UUID            NOT NULL,
    amount                          NUMERIC(19,4)   NOT NULL,
    disbursement_date               DATE            NOT NULL,
    reason                          VARCHAR(2000)   NOT NULL,
    notes                           VARCHAR(2000),
    evidence_file_key               VARCHAR(512),
    status                          VARCHAR(32)     NOT NULL,
    requested_by                    UUID,
    reviewed_by                     UUID,
    reviewed_at                     TIMESTAMPTZ,
    review_notes                    VARCHAR(2000),
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_social_disbursements_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_social_disbursements_beneficiary FOREIGN KEY (beneficiary_member_user_id) REFERENCES users (id),
    CONSTRAINT fk_social_disbursements_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_social_disbursements_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT chk_social_disbursements_status CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'
    )),
    CONSTRAINT chk_social_disbursements_amount_pos CHECK (amount > 0)
);

CREATE INDEX idx_social_disbursements_cooperative ON social_disbursements (cooperative_id);
CREATE INDEX idx_social_disbursements_beneficiary ON social_disbursements (beneficiary_member_user_id);
CREATE INDEX idx_social_disbursements_status ON social_disbursements (status);
CREATE INDEX idx_social_disbursements_coop_status ON social_disbursements (cooperative_id, status);
