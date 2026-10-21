package rw.terimbere.csams.modules.rotation.reconciliation;

public enum LegacyReconciliationStatus {
    /** Awaiting an independent decision. At most one per obligation. */
    REQUESTED,
    /** Confirmed by an independent authorizer: the amount is now verified and payout-eligible. */
    APPROVED,
    DECLINED,
    WITHDRAWN
}
