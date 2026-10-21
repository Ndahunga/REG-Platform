-- Scheme fund locks: one lock row per Saving Scheme.
--
-- Operations that spend scheme funds (loan disbursement, payout confirmation, investment activation) take a
-- row lock (SELECT ... FOR UPDATE) on their scheme's row before they read available funds, so two of them can no
-- longer both pass the funds check against the same balance. The row carries no data: it exists only to be locked,
-- and nothing else references it, so locking it never blocks foreign-key inserts into other tables.
--
-- Additive only. No financial row, balance or ledger entry is read, changed or deleted. Compatible with PostgreSQL
-- (production) and H2 in PostgreSQL mode (tests): no triggers, no partial indexes.
--
-- Initialization
--   * Existing schemes: backfilled below, once, in this migration.
--   * New schemes: the application inserts the row in the same transaction as the scheme (CooperativeService.create
--     and PublicOnboardingService.signup). The primary key makes a duplicate impossible, and the lock service fails
--     loudly, never creating a row on demand, if a scheme has none.

CREATE TABLE scheme_fund_locks (
    cooperative_id  UUID            PRIMARY KEY,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_scheme_fund_locks_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id)
);

INSERT INTO scheme_fund_locks (cooperative_id)
SELECT id FROM cooperatives;
