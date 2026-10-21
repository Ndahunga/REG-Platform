-- Phase 6: fine settings, fines, fine payments, and permissions

INSERT INTO permissions (id, code, name, description) VALUES
    ('a0000001-0000-0000-0000-000000000010', 'FINE_READ', 'Read fines', 'View fines and fine payments'),
    ('a0000001-0000-0000-0000-000000000011', 'FINE_WRITE', 'Manage fines', 'Issue, waive, cancel fines and review fine payments');

-- SUPER_ADMIN gets all new permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT '11111111-1111-1111-1111-111111111111', id
FROM permissions
WHERE code IN ('FINE_READ', 'FINE_WRITE');

-- COOPERATIVE_ADMIN: FINE_READ + FINE_WRITE
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000010'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000011');

-- MEMBER: FINE_READ only
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('33333333-3333-3333-3333-333333333333', 'a0000001-0000-0000-0000-000000000010');

CREATE TABLE fine_settings (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    auto_fines_enabled          BOOLEAN         NOT NULL DEFAULT TRUE,
    fine_mode                   VARCHAR(32)     NOT NULL DEFAULT 'FIXED',
    base_fine_amount            NUMERIC(19,4)   NOT NULL DEFAULT 0,
    daily_increment             NUMERIC(19,4)   NOT NULL DEFAULT 0,
    grace_days                  INT             NOT NULL DEFAULT 0,
    currency                    CHAR(3)         NOT NULL DEFAULT 'RWF',
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_fine_settings_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT uq_fine_settings_cooperative UNIQUE (cooperative_id),
    CONSTRAINT chk_fine_settings_mode CHECK (fine_mode IN ('FIXED', 'PROGRESSIVE')),
    CONSTRAINT chk_fine_settings_amounts_nonneg CHECK (
        base_fine_amount >= 0 AND daily_increment >= 0 AND grace_days >= 0
    )
);

CREATE TABLE fines (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    fine_type                   VARCHAR(32)     NOT NULL,
    calculation_mode            VARCHAR(32)     NOT NULL,
    source_contribution_id      UUID,
    base_amount                 NUMERIC(19,4)   NOT NULL,
    daily_increment_snapshot    NUMERIC(19,4)   NOT NULL DEFAULT 0,
    overdue_days                INT             NOT NULL DEFAULT 0,
    total_amount                NUMERIC(19,4)   NOT NULL,
    paid_amount                 NUMERIC(19,4)   NOT NULL DEFAULT 0,
    outstanding_amount          NUMERIC(19,4)   NOT NULL,
    reason                      VARCHAR(2000),
    notes                       VARCHAR(2000),
    issued_date                 DATE            NOT NULL,
    due_date                    DATE,
    status                      VARCHAR(32)     NOT NULL,
    issued_by                   UUID,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_fines_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_fines_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_fines_source_contribution FOREIGN KEY (source_contribution_id) REFERENCES contributions (id),
    CONSTRAINT fk_fines_issued_by FOREIGN KEY (issued_by) REFERENCES users (id),
    CONSTRAINT chk_fines_type CHECK (fine_type IN ('AUTOMATIC', 'MANUAL')),
    CONSTRAINT chk_fines_calc_mode CHECK (calculation_mode IN ('FIXED', 'PROGRESSIVE')),
    CONSTRAINT chk_fines_status CHECK (status IN (
        'UNPAID', 'PARTIALLY_PAID', 'PAID', 'WAIVED', 'CANCELLED'
    )),
    CONSTRAINT chk_fines_amounts_nonneg CHECK (
        base_amount >= 0
        AND daily_increment_snapshot >= 0
        AND overdue_days >= 0
        AND total_amount >= 0
        AND paid_amount >= 0
        AND outstanding_amount >= 0
    )
);

-- Duplicate AUTOMATIC fines for the same contribution are prevented in FineService
-- (H2 lacks portable partial unique indexes; PostgreSQL can add one in a later migration).
CREATE INDEX idx_fines_cooperative ON fines (cooperative_id);
CREATE INDEX idx_fines_member ON fines (member_user_id);
CREATE INDEX idx_fines_status ON fines (status);
CREATE INDEX idx_fines_coop_status ON fines (cooperative_id, status);
CREATE INDEX idx_fines_source_contribution ON fines (source_contribution_id);
CREATE INDEX idx_fines_coop_source ON fines (cooperative_id, source_contribution_id);

CREATE TABLE fine_payments (
    id                          UUID            PRIMARY KEY,
    fine_id                     UUID            NOT NULL,
    cooperative_id              UUID            NOT NULL,
    member_user_id              UUID            NOT NULL,
    amount                      NUMERIC(19,4)   NOT NULL,
    payment_date                DATE            NOT NULL,
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
    CONSTRAINT fk_fine_payments_fine FOREIGN KEY (fine_id) REFERENCES fines (id),
    CONSTRAINT fk_fine_payments_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_fine_payments_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT fk_fine_payments_submitted_by FOREIGN KEY (submitted_by) REFERENCES users (id),
    CONSTRAINT fk_fine_payments_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT chk_fine_payments_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_fine_payments_amount_pos CHECK (amount > 0)
);

CREATE INDEX idx_fine_payments_fine ON fine_payments (fine_id);
CREATE INDEX idx_fine_payments_cooperative ON fine_payments (cooperative_id);
CREATE INDEX idx_fine_payments_member ON fine_payments (member_user_id);
CREATE INDEX idx_fine_payments_status ON fine_payments (status);
CREATE INDEX idx_fine_payments_coop_status ON fine_payments (cooperative_id, status);
