-- Saving Scheme operating model: ACCUMULATING, ROTATING or HYBRID.
--
-- Foundation only. Nothing in the application changes financial behavior because of this column yet; it records
-- which Ikimina model a scheme operates under so later work can implement model-specific rules.
--
-- Legacy schemes
--   * Existing schemes may already hold contributions, loans, shares and ledger history, and nobody has told the
--     system which model they follow, so they are NOT classified here: operating_model stays NULL and
--     operating_model_unclassified is TRUE. An authorized administrator classifies them once, explicitly.
--   * No financial row is read, changed or deleted.
--
-- New schemes
--   * chk_cooperatives_operating_model_required lets NULL through only for rows flagged unclassified, and the flag
--     defaults to FALSE, so a scheme inserted without a model is rejected by the database itself.
--   * The application also validates the model and never sets the unclassified flag for new schemes.
--
-- Immutability is enforced by the application: the entity column is insert-only (updatable = false) and the one
-- classification statement only matches rows whose model is still NULL.
--
-- Compatible with PostgreSQL (production) and H2 in PostgreSQL mode (tests).

ALTER TABLE cooperatives ADD COLUMN operating_model VARCHAR(32);
ALTER TABLE cooperatives ADD COLUMN operating_model_unclassified BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE cooperatives SET operating_model_unclassified = TRUE;

ALTER TABLE cooperatives ADD CONSTRAINT chk_cooperatives_operating_model
    CHECK (operating_model IS NULL OR operating_model IN ('ACCUMULATING', 'ROTATING', 'HYBRID'));
ALTER TABLE cooperatives ADD CONSTRAINT chk_cooperatives_operating_model_required
    CHECK (operating_model IS NOT NULL OR operating_model_unclassified);
