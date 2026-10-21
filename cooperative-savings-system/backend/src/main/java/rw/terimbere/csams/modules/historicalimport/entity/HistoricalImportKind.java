package rw.terimbere.csams.modules.historicalimport.entity;

/** What an opening-balance workbook carries into a Saving Scheme. */
public enum HistoricalImportKind {
    /** The two-sheet workbook: members and share capital (Member Registration, Share Capital Registration). */
    OPENING_BALANCE,
    /** The separate one-sheet workbook of loans still outstanding at the cutover (Loan Registration). */
    OPENING_LOANS
}
