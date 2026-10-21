package rw.terimbere.csams.modules.rotation.payout;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import rw.terimbere.csams.shared.exceptions.BusinessException;

/**
 * A rotation payout is financially complete only at DISBURSED, which needs an officer's confirmation of the ACTUAL
 * disbursement (method and reference) and writes the ledger debit.
 *
 * <pre>
 * REQUESTED -> APPROVED -> DISBURSED
 *     |            |
 *     +-> REJECTED +-> CANCELLED      (REQUESTED can also be CANCELLED)
 * </pre>
 */
public enum RotationPayoutStatus {
    REQUESTED,
    APPROVED,
    DISBURSED,
    REJECTED,
    CANCELLED;

    private static final Map<RotationPayoutStatus, Set<RotationPayoutStatus>> ALLOWED = new EnumMap<>(RotationPayoutStatus.class);

    static {
        ALLOWED.put(REQUESTED, Set.of(APPROVED, REJECTED, CANCELLED));
        ALLOWED.put(APPROVED, Set.of(DISBURSED, CANCELLED));
        ALLOWED.put(DISBURSED, Set.of());
        ALLOWED.put(REJECTED, Set.of());
        ALLOWED.put(CANCELLED, Set.of());
    }

    public boolean canTransitionTo(RotationPayoutStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public void requireTransitionTo(RotationPayoutStatus target) {
        if (!canTransitionTo(target)) {
            throw new BusinessException(
                    "INVALID_PAYOUT_TRANSITION", "A " + this + " payout cannot move to " + target);
        }
    }

    /** An open payout holds its period: only one may exist at a time. */
    public boolean isOpen() {
        return this == REQUESTED || this == APPROVED;
    }
}
