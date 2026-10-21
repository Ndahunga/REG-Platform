package rw.terimbere.csams.modules.memberpayment.entity;

/**
 * What the content of a receipt appears to indicate. This is an advisory content classification, not proof of
 * settlement, and it is stored separately from {@link PaymentStatus}. Nothing in the payment workflow reads it
 * to verify, authorize or post a payment.
 */
public enum ReceiptStatus {
    SUCCESS_INDICATED,
    FAILED_INDICATED,
    PENDING_INDICATED,
    UNKNOWN
}
