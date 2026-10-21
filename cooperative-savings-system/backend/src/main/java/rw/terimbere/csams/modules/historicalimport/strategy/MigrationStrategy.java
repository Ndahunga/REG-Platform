package rw.terimbere.csams.modules.historicalimport.strategy;

/** How a Saving Scheme brings its past into the system. Exactly one is in force per scheme. */
public enum MigrationStrategy {
    /** Members' balances are carried in at a cutover date; the earlier history is not imported as transactions. */
    OPENING_BALANCE,
    /** The transaction history is imported and posted to the ledger; no opening balances are carried in. */
    DETAILED_TRANSACTION_HISTORY
}
