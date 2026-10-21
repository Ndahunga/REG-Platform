package rw.terimbere.csams.modules.memberpayment.verification;

/**
 * The result of checking a payment against trusted evidence. These are NOT payment lifecycle statuses: a payment stays
 * SUBMITTED / UNDER_REVIEW / VERIFIED / POSTED, and an outcome never moves it by itself.
 */
public enum VerificationOutcome {
    /** Authoritative evidence (a cash confirmation by an officer, a statement line or a signed callback) agrees. */
    CONFIRMED,
    /** Evidence exists but the money has not settled, or cash has not yet been confirmed. */
    PENDING,
    /** The trusted record says the transaction failed. */
    FAILED,
    /** No trusted record of this transaction exists in this Saving Scheme. */
    UNMATCHED,
    /** Evidence disagrees: amount, currency, recipient, a transaction already used, or contradicting records. */
    CONFLICTED
}
