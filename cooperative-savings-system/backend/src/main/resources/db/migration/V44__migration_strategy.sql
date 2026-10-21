-- One explicit migration strategy per Saving Scheme, so the same history can never be counted twice.
--
--   OPENING_BALANCE               members' balances are carried in as opening balances at a cutover date; the history
--                                 before that date is NOT imported as transactions.
--   DETAILED_TRANSACTION_HISTORY  the transaction history is imported and posted to the ledger; no opening balances.
--
-- A scheme has at most one row (UNIQUE), so two importers racing to claim a strategy cannot both succeed. The
-- application refuses the importer that does not match the strategy and refuses a change once anything was imported.
-- Existing schemes are backfilled from what they already imported; a scheme that imported BOTH ways is kept as
-- OPENING_BALANCE and flagged needs_review so that no further import is accepted until a person has reconciled it.

-- What an opening-balance import carries: members and share balances (OPENING_BALANCE), or the loans still outstanding
-- at the cutover (OPENING_LOANS, a separate workbook so the member workbook stays two sheets). Existing imports are
-- all OPENING_BALANCE.
ALTER TABLE historical_imports ADD COLUMN import_kind VARCHAR(20) NOT NULL DEFAULT 'OPENING_BALANCE';
ALTER TABLE historical_imports ADD CONSTRAINT chk_historical_imports_kind CHECK (import_kind IN ('OPENING_BALANCE', 'OPENING_LOANS'));

CREATE TABLE cooperative_migration_strategies (
    id              UUID            PRIMARY KEY,
    cooperative_id  UUID            NOT NULL,
    strategy        VARCHAR(40)     NOT NULL,
    cutover_date    DATE,
    source          VARCHAR(20)     NOT NULL,
    needs_review    BOOLEAN         NOT NULL DEFAULT FALSE,
    set_by          UUID,
    -- how a needs_review flag was resolved: by whom, when and why (never silently cleared)
    resolved_by     UUID,
    resolved_at     TIMESTAMPTZ,
    resolution_note VARCHAR(1000),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_migration_strategy_cooperative FOREIGN KEY (cooperative_id) REFERENCES cooperatives (id),
    CONSTRAINT uq_migration_strategy_cooperative UNIQUE (cooperative_id),
    CONSTRAINT chk_migration_strategy_value CHECK (strategy IN ('OPENING_BALANCE', 'DETAILED_TRANSACTION_HISTORY')),
    CONSTRAINT chk_migration_strategy_source CHECK (source IN ('EXPLICIT', 'FIRST_IMPORT', 'BACKFILL')),
    CONSTRAINT fk_migration_strategy_resolved_by FOREIGN KEY (resolved_by) REFERENCES users (id),
    CONSTRAINT chk_migration_strategy_resolution CHECK (
        needs_review = TRUE OR resolved_by IS NULL OR (resolved_at IS NOT NULL AND resolution_note IS NOT NULL))
);

-- The row id is the cooperative id (one row per scheme), which avoids a database-specific UUID function.
INSERT INTO cooperative_migration_strategies (id, cooperative_id, strategy, cutover_date, source, needs_review)
SELECT o.cooperative_id, o.cooperative_id, 'OPENING_BALANCE', o.cutover_date, 'BACKFILL',
       EXISTS (SELECT 1 FROM historical_ledger_imports l WHERE l.cooperative_id = o.cooperative_id AND l.status = 'POSTED')
FROM (SELECT cooperative_id, MAX(cutover_date) AS cutover_date FROM historical_imports
      WHERE status = 'CONFIRMED' GROUP BY cooperative_id) o;

INSERT INTO cooperative_migration_strategies (id, cooperative_id, strategy, cutover_date, source, needs_review)
SELECT l.cooperative_id, l.cooperative_id, 'DETAILED_TRANSACTION_HISTORY', NULL, 'BACKFILL', FALSE
FROM (SELECT DISTINCT cooperative_id FROM historical_ledger_imports WHERE status = 'POSTED') l
WHERE NOT EXISTS (SELECT 1 FROM cooperative_migration_strategies s WHERE s.cooperative_id = l.cooperative_id);
