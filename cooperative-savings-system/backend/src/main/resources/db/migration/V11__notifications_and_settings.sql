-- Phase 11: in-app notifications and cooperative settings

CREATE TABLE notifications (
    id                  UUID            PRIMARY KEY,
    user_id             UUID            NOT NULL,
    cooperative_id      UUID,
    type                VARCHAR(64)     NOT NULL,
    title               VARCHAR(255)    NOT NULL,
    body                VARCHAR(2000),
    entity_type         VARCHAR(128),
    entity_id           UUID,
    "read"              BOOLEAN         NOT NULL DEFAULT FALSE,
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT chk_notifications_type CHECK (type IN (
        'ACCOUNT', 'CONTRIBUTION', 'LOAN', 'FINE', 'SOCIAL', 'INVESTMENT', 'PAYOUT', 'SECURITY', 'SYSTEM'
    ))
);

CREATE INDEX idx_notifications_user_read ON notifications (user_id, "read");
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);

CREATE TABLE cooperative_settings (
    id                      UUID            PRIMARY KEY,
    cooperative_id          UUID            NOT NULL,
    timezone                VARCHAR(64)     NOT NULL DEFAULT 'Africa/Kigali',
    locale                  VARCHAR(16)     NOT NULL DEFAULT 'en',
    notify_contributions    BOOLEAN         NOT NULL DEFAULT TRUE,
    notify_loans            BOOLEAN         NOT NULL DEFAULT TRUE,
    notify_fines            BOOLEAN         NOT NULL DEFAULT TRUE,
    notify_payouts          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_cooperative_settings_cooperative UNIQUE (cooperative_id),
    CONSTRAINT fk_cooperative_settings_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id)
);

CREATE INDEX idx_cooperative_settings_cooperative ON cooperative_settings (cooperative_id);

-- Own notifications are authenticated-user scoped (no new permission).
-- SETTINGS_MANAGE and AUDIT_READ already seeded in V1.
