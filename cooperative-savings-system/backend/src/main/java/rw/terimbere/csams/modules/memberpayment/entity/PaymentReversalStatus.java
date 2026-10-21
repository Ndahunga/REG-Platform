package rw.terimbere.csams.modules.memberpayment.entity;

/**
 * REQUESTED until a different authorized officer approves (EXECUTED, with the compensating accounting) or declines
 * it, or until the requester withdraws it (WITHDRAWN, no financial movement).
 */
public enum PaymentReversalStatus {
    REQUESTED,
    EXECUTED,
    DECLINED,
    WITHDRAWN
}
