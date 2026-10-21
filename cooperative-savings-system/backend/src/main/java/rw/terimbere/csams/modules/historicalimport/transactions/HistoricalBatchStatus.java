package rw.terimbere.csams.modules.historicalimport.transactions;

public enum HistoricalBatchStatus {
    /** Validated (a dry run): nothing financial exists yet. */
    PREVIEWED,
    /** Approved by an independent authorizer and posted. Final. */
    POSTED,
    CANCELLED
}
