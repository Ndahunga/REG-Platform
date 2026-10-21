package rw.terimbere.csams.modules.memberpayment.entity;

/**
 * How the member paid. A transaction reference is only meaningful together with its method, so duplicate
 * detection never treats the same text from two different methods as the same transaction.
 */
public enum PaymentMethod {
    MTN_MOMO,
    AIRTEL_MONEY,
    BANK_TRANSFER,
    CASH,
    /** Unclassified. Kept in its own reference namespace, never merged with another method. */
    OTHER;

    /** Every method except CASH must carry a transaction reference. */
    public boolean requiresReference() {
        return this != CASH;
    }
}
