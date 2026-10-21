package rw.terimbere.csams.modules.memberpayment.entity;

/** How a posted amount was attributed for model-specific reporting. */
public enum PostingAttribution {
    /** ROTATING or HYBRID obligation: split by the period's own snapshot, recipient is the original one. */
    ATTRIBUTED,
    /** Snapshotted retained-only (accumulating) scheme: everything is retained. */
    ACCUMULATING,
    /** Historical row without a snapshot: plain contribution accounting, nothing invented. */
    UNATTRIBUTED,
    /** Loan repayment. */
    NOT_APPLICABLE
}
