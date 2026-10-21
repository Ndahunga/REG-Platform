package rw.terimbere.csams.modules.membercredit;

/** What happened to a member's credit. A balance is the receipts less everything that took credit away. */
public enum CreditEntryType {
    /** A verified payment was posted with an allocation to member credit. Adds to the balance. */
    RECEIVED,
    /** An officer applied credit to a contribution or a loan. Takes from the balance. */
    APPLIED,
    /** The payment that created a credit was reversed while the credit was still unspent. Takes from the balance. */
    RECEIPT_REVERSED
}
