-- Opening available funds are an explicit, user-supplied cash position at the cutover date.
-- They are NOT derived from member contributions, share capital or loans.
ALTER TABLE historical_imports ADD COLUMN opening_available_funds NUMERIC(19,4);
ALTER TABLE historical_imports ADD CONSTRAINT chk_historical_imports_opening_funds_nonneg
    CHECK (opening_available_funds IS NULL OR opening_available_funds >= 0);
