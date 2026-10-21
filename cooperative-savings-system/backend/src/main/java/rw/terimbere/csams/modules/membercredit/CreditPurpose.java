package rw.terimbere.csams.modules.membercredit;

/**
 * Why a member's excess money is being held. It decides what the credit may later be applied to: advance contributions
 * go to contribution periods only, a loan prepayment to its own loan only, and unapplied credit to either. Excess money
 * is never income and never silently becomes a contribution or a repayment.
 */
public enum CreditPurpose {
    ADVANCE_CONTRIBUTION,
    LOAN_PREPAYMENT,
    UNAPPLIED_CREDIT
}
