package rw.terimbere.csams.modules.memberpayment.account;

/** Whether the recipient a receipt names is one of the scheme's designated payment accounts. */
public enum RecipientMatch {
    /** The receipt names one of the scheme's active accounts. */
    MATCH,
    /** The receipt names a recipient and the scheme has accounts, but none of them is that recipient. */
    MISMATCH,
    /** The receipt does not name a recipient, or the scheme has not configured any account to compare with. */
    UNKNOWN
}
