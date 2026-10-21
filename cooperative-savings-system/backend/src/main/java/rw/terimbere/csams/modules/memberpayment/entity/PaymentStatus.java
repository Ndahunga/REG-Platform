package rw.terimbere.csams.modules.memberpayment.entity;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import rw.terimbere.csams.shared.exceptions.BusinessException;

/**
 * Authoritative verification/posting status of a {@link MemberPayment}. This is deliberately unrelated to
 * {@link ReceiptStatus}: what a receipt image appears to say can never move a payment along this state machine.
 *
 * <pre>
 * SUBMITTED -> UNDER_REVIEW -> VERIFIED -> POSTED -> REVERSED
 *     |             |
 *     +-------------+--------> REJECTED   (only before VERIFIED)
 * </pre>
 *
 * REJECTED and REVERSED are terminal. POSTED can only be entered once, from VERIFIED.
 */
public enum PaymentStatus {
    /** Claim recorded; nothing has been checked. */
    SUBMITTED,
    /** A reviewer has picked the payment up. */
    UNDER_REVIEW,
    /** An independent reviewer verified it and authorized posting. Still not a financial transaction. */
    VERIFIED,
    /** Posted to the books (orchestration lands in a later task). */
    POSTED,
    /** Refused. Releases the duplicate-reference claim. */
    REJECTED,
    /** A posted payment that was undone. Keeps its duplicate-reference claim. */
    REVERSED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(SUBMITTED, Set.of(UNDER_REVIEW, REJECTED));
        ALLOWED.put(UNDER_REVIEW, Set.of(VERIFIED, REJECTED));
        // Once funds are confirmed a payment is never ordinarily rejected: it is posted, or stays VERIFIED and unresolved.
        ALLOWED.put(VERIFIED, Set.of(POSTED));
        ALLOWED.put(POSTED, Set.of(REVERSED));
        ALLOWED.put(REJECTED, Set.of());
        ALLOWED.put(REVERSED, Set.of());
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    /** Throws unless {@code this -> target} is a defined transition. */
    public void requireTransitionTo(PaymentStatus target) {
        if (canTransitionTo(target)) {
            return;
        }
        if (this == POSTED && target == POSTED) {
            throw new BusinessException("PAYMENT_ALREADY_POSTED", "This payment has already been posted");
        }
        throw new BusinessException(
                "INVALID_PAYMENT_TRANSITION", "A " + this + " payment cannot move to " + target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Whether a payment in this status still holds its duplicate-reference claim. */
    public boolean holdsReference() {
        return this != REJECTED;
    }

    /** Statuses that require the allocations to account for the whole payment amount. */
    public boolean requiresFullAllocation() {
        return this == VERIFIED || this == POSTED || this == REVERSED;
    }
}
