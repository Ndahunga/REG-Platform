-- TERIMBERE CSAMS Phase 1 foundation schema
-- Money columns use NUMERIC(19,4). Identifiers use UUID.

CREATE TABLE roles (
    id              UUID            PRIMARY KEY,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    description     VARCHAR(512),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_roles_code UNIQUE (code)
);

CREATE TABLE permissions (
    id              UUID            PRIMARY KEY,
    code            VARCHAR(128)    NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    description     VARCHAR(512),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

CREATE TABLE role_permissions (
    role_id         UUID            NOT NULL,
    permission_id   UUID            NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

CREATE TABLE users (
    id                      UUID            PRIMARY KEY,
    username                VARCHAR(64)     NOT NULL,
    email                   VARCHAR(255)    NOT NULL,
    password_hash           VARCHAR(255)    NOT NULL,
    first_name              VARCHAR(128)    NOT NULL,
    last_name               VARCHAR(128)    NOT NULL,
    phone                   VARCHAR(32),
    national_id             VARCHAR(64),
    address                 VARCHAR(512),
    profile_image_key       VARCHAR(512),
    account_status          VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts   INT             NOT NULL DEFAULT 0,
    locked_until            TIMESTAMPTZ,
    last_login_at           TIMESTAMPTZ,
    password_changed_at     TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_account_status CHECK (account_status IN ('ACTIVE', 'INACTIVE', 'LOCKED', 'PENDING'))
);

CREATE TABLE user_roles (
    user_id         UUID            NOT NULL,
    role_id         UUID            NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

CREATE TABLE cooperatives (
    id                              UUID            PRIMARY KEY,
    name                            VARCHAR(255)    NOT NULL,
    description                     VARCHAR(2000),
    registration_number             VARCHAR(128),
    contact_email                   VARCHAR(255),
    contact_phone                   VARCHAR(32),
    address                         VARCHAR(512),
    currency                        CHAR(3)         NOT NULL DEFAULT 'RWF',
    financial_year_start_month      INT             NOT NULL DEFAULT 1,
    monthly_contribution_amount     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    contribution_due_day            INT             NOT NULL DEFAULT 1,
    logo_file_key                   VARCHAR(512),
    status                          VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    registration_date               DATE,
    created_by                      UUID,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted                         BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at                      TIMESTAMPTZ,
    version                         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_cooperatives_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
    CONSTRAINT chk_cooperatives_fy_month CHECK (financial_year_start_month BETWEEN 1 AND 12),
    CONSTRAINT chk_cooperatives_due_day CHECK (contribution_due_day BETWEEN 1 AND 28),
    CONSTRAINT chk_cooperatives_contribution_nonneg CHECK (monthly_contribution_amount >= 0)
);

CREATE TABLE cooperative_memberships (
    id                      UUID            PRIMARY KEY,
    user_id                 UUID            NOT NULL,
    cooperative_id          UUID            NOT NULL,
    membership_status       VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    membership_date         DATE            NOT NULL DEFAULT CURRENT_DATE,
    role_in_cooperative     VARCHAR(64),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_cooperative_memberships_user_coop UNIQUE (user_id, cooperative_id),
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_memberships_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT chk_memberships_status CHECK (membership_status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED', 'PENDING'))
);

CREATE TABLE refresh_tokens (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL,
    token_hash      VARCHAR(255)    NOT NULL,
    expires_at      TIMESTAMPTZ     NOT NULL,
    revoked         BOOLEAN         NOT NULL DEFAULT FALSE,
    replaced_by     UUID,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE TABLE audit_logs (
    id                  UUID            PRIMARY KEY,
    user_id             UUID,
    cooperative_id      UUID,
    action              VARCHAR(64)     NOT NULL,
    entity_type         VARCHAR(128),
    entity_id           UUID,
    previous_values     JSONB,
    new_values          JSONB,
    ip_address          VARCHAR(64),
    user_agent          VARCHAR(512),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE stored_files (
    id                  UUID            PRIMARY KEY,
    cooperative_id      UUID,
    original_filename   VARCHAR(512)    NOT NULL,
    storage_key         VARCHAR(1024)   NOT NULL,
    content_type        VARCHAR(255),
    size_bytes          BIGINT          NOT NULL DEFAULT 0,
    category            VARCHAR(64),
    uploaded_by         UUID,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted             BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_stored_files_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_stored_files_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT chk_stored_files_size CHECK (size_bytes >= 0)
);

-- Indexes
CREATE INDEX idx_users_username ON users (username);
CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_account_status ON users (account_status);
CREATE INDEX idx_users_deleted ON users (deleted);

CREATE INDEX idx_cooperatives_status ON cooperatives (status);
CREATE INDEX idx_cooperatives_deleted ON cooperatives (deleted);
CREATE INDEX idx_cooperatives_registration_number ON cooperatives (registration_number);

CREATE INDEX idx_memberships_cooperative_id ON cooperative_memberships (cooperative_id);
CREATE INDEX idx_memberships_user_id ON cooperative_memberships (user_id);
CREATE INDEX idx_memberships_status ON cooperative_memberships (membership_status);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

CREATE INDEX idx_audit_logs_user_id ON audit_logs (user_id);
CREATE INDEX idx_audit_logs_cooperative_id ON audit_logs (cooperative_id);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);

CREATE INDEX idx_stored_files_cooperative_id ON stored_files (cooperative_id);
CREATE INDEX idx_stored_files_category ON stored_files (category);
CREATE INDEX idx_stored_files_deleted ON stored_files (deleted);

CREATE INDEX idx_role_permissions_permission_id ON role_permissions (permission_id);
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);

-- Seed roles
INSERT INTO roles (id, code, name, description) VALUES
    ('11111111-1111-1111-1111-111111111111', 'SUPER_ADMIN', 'Super Administrator', 'Full system access across all cooperatives'),
    ('22222222-2222-2222-2222-222222222222', 'COOPERATIVE_ADMIN', 'Cooperative Administrator', 'Administers a single cooperative'),
    ('33333333-3333-3333-3333-333333333333', 'MEMBER', 'Member', 'Standard cooperative member');

-- Seed basic permissions
INSERT INTO permissions (id, code, name, description) VALUES
    ('a0000001-0000-0000-0000-000000000001', 'USER_READ', 'Read users', 'View user profiles'),
    ('a0000001-0000-0000-0000-000000000002', 'USER_WRITE', 'Manage users', 'Create and update users'),
    ('a0000001-0000-0000-0000-000000000003', 'ROLE_MANAGE', 'Manage roles', 'Assign roles and permissions'),
    ('a0000001-0000-0000-0000-000000000004', 'COOPERATIVE_READ', 'Read cooperatives', 'View cooperative details'),
    ('a0000001-0000-0000-0000-000000000005', 'COOPERATIVE_WRITE', 'Manage cooperatives', 'Create and update cooperatives'),
    ('a0000001-0000-0000-0000-000000000006', 'MEMBERSHIP_MANAGE', 'Manage memberships', 'Manage cooperative memberships'),
    ('a0000001-0000-0000-0000-000000000007', 'CONTRIBUTION_READ', 'Read contributions', 'View contributions'),
    ('a0000001-0000-0000-0000-000000000008', 'CONTRIBUTION_WRITE', 'Manage contributions', 'Record and adjust contributions'),
    ('a0000001-0000-0000-0000-000000000009', 'LOAN_READ', 'Read loans', 'View loans'),
    ('a0000001-0000-0000-0000-00000000000a', 'LOAN_WRITE', 'Manage loans', 'Approve and manage loans'),
    ('a0000001-0000-0000-0000-00000000000b', 'REPORT_READ', 'Read reports', 'View and export reports'),
    ('a0000001-0000-0000-0000-00000000000c', 'SETTINGS_MANAGE', 'Manage settings', 'Update system and cooperative settings'),
    ('a0000001-0000-0000-0000-00000000000d', 'AUDIT_READ', 'Read audit logs', 'View audit trail'),
    ('a0000001-0000-0000-0000-00000000000e', 'FILE_MANAGE', 'Manage files', 'Upload and delete stored files'),
    ('a0000001-0000-0000-0000-00000000000f', 'LEDGER_READ', 'Read ledger', 'View financial ledger entries');

-- SUPER_ADMIN gets all permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT '11111111-1111-1111-1111-111111111111', id FROM permissions;

-- COOPERATIVE_ADMIN permissions
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000001'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000004'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000005'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000006'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000007'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000008'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-000000000009'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-00000000000a'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-00000000000b'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-00000000000c'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-00000000000d'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-00000000000e'),
    ('22222222-2222-2222-2222-222222222222', 'a0000001-0000-0000-0000-00000000000f');

-- MEMBER permissions
INSERT INTO role_permissions (role_id, permission_id) VALUES
    ('33333333-3333-3333-3333-333333333333', 'a0000001-0000-0000-0000-000000000004'),
    ('33333333-3333-3333-3333-333333333333', 'a0000001-0000-0000-0000-000000000007'),
    ('33333333-3333-3333-3333-333333333333', 'a0000001-0000-0000-0000-000000000009'),
    ('33333333-3333-3333-3333-333333333333', 'a0000001-0000-0000-0000-00000000000b');
