-- Production safety backfill for cooperatives that existed before
-- cooperative subscriptions were introduced.
--
-- V23 creates the subscription tables, but existing cooperatives do not
-- automatically receive a subscription row. Without this backfill they
-- would evaluate as NONE and operational writes would become read-only.
--
-- Give each pre-existing cooperative that has no subscription a fresh
-- four-calendar-month free trial.
--
-- The cooperative UUID is intentionally reused as the subscription UUID
-- for these legacy backfill rows. UUIDs are table-local identifiers, and
-- cooperative_id remains independently protected by its UNIQUE constraint.
--
-- Existing subscription rows are never modified.

INSERT INTO cooperative_subscriptions (
    id,
    cooperative_id,
    status,
    billing_cycle,
    trial_started_at,
    trial_ends_at,
    current_period_started_at,
    current_period_ends_at,
    past_due_until,
    canceled_at,
    created_at,
    updated_at,
    version
)
SELECT
    c.id,
    c.id,
    'TRIAL',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP + INTERVAL '4' MONTH,
    NULL,
    NULL,
    NULL,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
FROM cooperatives c
WHERE NOT EXISTS (
    SELECT 1
    FROM cooperative_subscriptions s
    WHERE s.cooperative_id = c.id
);
