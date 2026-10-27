package rw.terimbere.csams.modules.historicalimport.entity;

public enum HistoricalImportStatus {
    UPLOADED,
    VALIDATING,
    READY,
    INVALID,
    CONFIRMED,
    CANCELLED,
    FAILED,
    /** Replaced by a corrected upload; kept as the record of what was imported before. */
    SUPERSEDED
}
