-- Phase 9: group payouts, permissions

INSERT INTO permissions (id, code, name, description) VALUES
    ('a0000001-0000-0000-0000-000000000018', 'PAYOUT_READ', 'Read payouts', 'View group payout runs and member payout history'),
    ('a0000001-0000-0000-0000-000000000019', 'PAYOUT_WRITE', 'Manage payouts', 'Preview, confirm, mark paid, and cancel group payouts');

-- SUPER_ADMIN gets all new permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT '11111111-1111-1111-1111-111111111111', id
FROM permissions
WHERE code IN ('PAYOUT_READ', 'PAYOUT_WRITE');

-- COOPERATIVE_ADMIN: both
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000018'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000019');

-- MEMBER: PAYOUT_READ only
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('33333333-3333-3333-3333-333333333333', 'a0000001-0000-0000-0000-000000000018');

CREATE TABLE payout_runs (
    id                              UUID            PRIMARY KEY,
    cooperative_id                  UUID            NOT NULL,
    name                            VARCHAR(255),
    period_from                     DATE            NOT NULL,
    period_to                       DATE            NOT NULL,
    include_regular                 BOOLEAN         NOT NULL,
    include_special                 BOOLEAN         NOT NULL,
    available_fund_snapshot         NUMERIC(19,4)   NOT NULL,
    payout_pool_amount              NUMERIC(19,4)   NOT NULL,
    total_eligible_contributions    NUMERIC(19,4)   NOT NULL,
    currency                        CHAR(3)         NOT NULL DEFAULT 'RWF',
    status                          VARCHAR(32)     NOT NULL,
    confirmed_at                    TIMESTAMPTZ,
    confirmed_by                    UUID,
    paid_at                         TIMESTAMPTZ,
    paid_by                         UUID,
    created_by                      UUID,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                         BIGINT          NOT NULL DEFAULT 0,
    notes                           VARCHAR(2000),
    CONSTRAINT fk_payout_runs_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_payout_runs_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users (id),
    CONSTRAINT fk_payout_runs_paid_by FOREIGN KEY (paid_by) REFERENCES users (id),
    CONSTRAINT fk_payout_runs_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT chk_payout_runs_status CHECK (status IN (
        'DRAFT', 'PREVIEWED', 'CONFIRMED', 'PAID', 'CANCELLED'
    )),
    CONSTRAINT chk_payout_runs_period CHECK (period_from <= period_to),
    CONSTRAINT chk_payout_runs_pool_nonneg CHECK (payout_pool_amount >= 0),
    CONSTRAINT chk_payout_runs_eligible_nonneg CHECK (total_eligible_contributions >= 0),
    CONSTRAINT chk_payout_runs_fund_nonneg CHECK (available_fund_snapshot >= 0),
    CONSTRAINT chk_payout_runs_includes CHECK (include_regular OR include_special)
);

CREATE INDEX idx_payout_runs_cooperative ON payout_runs (cooperative_id);
CREATE INDEX idx_payout_runs_status ON payout_runs (status);
CREATE INDEX idx_payout_runs_coop_status ON payout_runs (cooperative_id, status);
CREATE INDEX idx_payout_runs_period ON payout_runs (cooperative_id, period_from, period_to);

CREATE TABLE payout_lines (
    id                              UUID            PRIMARY KEY,
    payout_run_id                   UUID            NOT NULL,
    cooperative_id                  UUID            NOT NULL,
    member_user_id                  UUID            NOT NULL,
    eligible_contribution_amount    NUMERIC(19,4)   NOT NULL,
    percentage                      NUMERIC(19,8)   NOT NULL,
    payout_amount                   NUMERIC(19,4)   NOT NULL,
    status                          VARCHAR(32)     NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payout_lines_run FOREIGN KEY (payout_run_id) REFERENCES payout_runs (id),
    CONSTRAINT fk_payout_lines_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT fk_payout_lines_member FOREIGN KEY (member_user_id) REFERENCES users (id),
    CONSTRAINT uq_payout_lines_run_member UNIQUE (payout_run_id, member_user_id),
    CONSTRAINT chk_payout_lines_status CHECK (status IN ('PENDING', 'CONFIRMED', 'PAID')),
    CONSTRAINT chk_payout_lines_eligible_nonneg CHECK (eligible_contribution_amount >= 0),
    CONSTRAINT chk_payout_lines_percentage_nonneg CHECK (percentage >= 0),
    CONSTRAINT chk_payout_lines_payout_nonneg CHECK (payout_amount >= 0)
);

CREATE INDEX idx_payout_lines_run ON payout_lines (payout_run_id);
CREATE INDEX idx_payout_lines_cooperative ON payout_lines (cooperative_id);
CREATE INDEX idx_payout_lines_member ON payout_lines (member_user_id);
CREATE INDEX idx_payout_lines_coop_member ON payout_lines (cooperative_id, member_user_id);
