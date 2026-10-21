-- Phase 1: cooperative onboarding state and platform subscription tables.
-- Subscription money is independent of financial_ledger.

-- ---------------------------------------------------------------------------
-- Cooperative onboarding state (NOT operational status)
-- Existing rows: COMPLETE when an active PRESIDENT / legacy COOPERATIVE_ADMIN
-- membership exists; otherwise AWAITING_PRESIDENT.
-- New inserts default to COMPLETE so Phase 1 does not change POST /cooperatives.
-- ---------------------------------------------------------------------------
ALTER TABLE cooperatives
    ADD COLUMN onboarding_state VARCHAR(32) NOT NULL DEFAULT 'COMPLETE';

ALTER TABLE cooperatives
    ADD CONSTRAINT chk_cooperatives_onboarding_state
    CHECK (onboarding_state IN ('COMPLETE', 'AWAITING_PRESIDENT'));

UPDATE cooperatives c
SET onboarding_state = 'AWAITING_PRESIDENT'
WHERE NOT EXISTS (
    SELECT 1
    FROM cooperative_memberships m
    WHERE m.cooperative_id = c.id
      AND UPPER(m.membership_status) = 'ACTIVE'
      AND UPPER(COALESCE(m.role_in_cooperative, '')) IN ('PRESIDENT', 'COOPERATIVE_ADMIN')
);

-- ---------------------------------------------------------------------------
-- One current platform subscription per cooperative
-- ---------------------------------------------------------------------------
CREATE TABLE cooperative_subscriptions (
    id                          UUID            PRIMARY KEY,
    cooperative_id              UUID            NOT NULL,
    status                      VARCHAR(32)     NOT NULL,
    billing_cycle               VARCHAR(32),
    trial_started_at            TIMESTAMPTZ,
    trial_ends_at               TIMESTAMPTZ,
    current_period_started_at   TIMESTAMPTZ,
    current_period_ends_at      TIMESTAMPTZ,
    past_due_until              TIMESTAMPTZ,
    canceled_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_coop_subscriptions_cooperative
        FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT uq_coop_subscriptions_cooperative UNIQUE (cooperative_id),
    CONSTRAINT chk_coop_subscriptions_status CHECK (status IN (
        'NONE', 'TRIAL', 'ACTIVE', 'PAST_DUE', 'EXPIRED', 'CANCELED'
    )),
    CONSTRAINT chk_coop_subscriptions_billing_cycle CHECK (
        billing_cycle IS NULL OR billing_cycle IN ('MONTHLY', 'ANNUAL')
    ),
    CONSTRAINT chk_coop_subscriptions_trial_window CHECK (
        status <> 'TRIAL'
        OR (trial_started_at IS NOT NULL AND trial_ends_at IS NOT NULL)
    )
);

CREATE INDEX idx_coop_subscriptions_status ON cooperative_subscriptions (status);
CREATE INDEX idx_coop_subscriptions_trial_ends ON cooperative_subscriptions (trial_ends_at);

-- ---------------------------------------------------------------------------
-- Platform subscription payments (never posted to financial_ledger)
-- ---------------------------------------------------------------------------
CREATE TABLE subscription_payments (
    id                      UUID            PRIMARY KEY,
    subscription_id         UUID            NOT NULL,
    cooperative_id          UUID            NOT NULL,
    billing_cycle           VARCHAR(32)     NOT NULL,
    payment_channel         VARCHAR(32)     NOT NULL,
    status                  VARCHAR(32)     NOT NULL,
    currency                CHAR(3)         NOT NULL DEFAULT 'RWF',
    amount                  NUMERIC(19,4)   NOT NULL,
    provider                VARCHAR(64),
    external_reference      VARCHAR(128),
    idempotency_key         VARCHAR(128),
    initiated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    paid_at                 TIMESTAMPTZ,
    failed_at               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_subscription_payments_subscription
        FOREIGN KEY (subscription_id) REFERENCES cooperative_subscriptions (id),
    CONSTRAINT fk_subscription_payments_cooperative
        FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT chk_subscription_payments_billing_cycle CHECK (
        billing_cycle IN ('MONTHLY', 'ANNUAL')
    ),
    CONSTRAINT chk_subscription_payments_channel CHECK (
        payment_channel IN ('MTN_MOMO', 'CARD')
    ),
    CONSTRAINT chk_subscription_payments_status CHECK (
        status IN ('PENDING', 'SUCCESS', 'FAILED', 'CANCELED')
    ),
    CONSTRAINT chk_subscription_payments_amount_pos CHECK (amount > 0),
    CONSTRAINT uq_subscription_payments_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_subscription_payments_subscription ON subscription_payments (subscription_id);
CREATE INDEX idx_subscription_payments_cooperative ON subscription_payments (cooperative_id);
CREATE INDEX idx_subscription_payments_status ON subscription_payments (status);
CREATE INDEX idx_subscription_payments_coop_status ON subscription_payments (cooperative_id, status);
CREATE INDEX idx_subscription_payments_provider_ref
    ON subscription_payments (provider, external_reference);
