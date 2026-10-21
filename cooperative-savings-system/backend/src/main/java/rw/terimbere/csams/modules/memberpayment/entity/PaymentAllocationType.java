package rw.terimbere.csams.modules.memberpayment.entity;

public enum PaymentAllocationType {
    /** A monthly contribution period (year + month) of the paying member. */
    CONTRIBUTION,
    /** Repayment of one of the paying member's loans, optionally split into principal / interest / penalty. */
    LOAN_REPAYMENT,
    /** Money held for the member that is not yet applied to a specific obligation. */
    MEMBER_CREDIT
}
