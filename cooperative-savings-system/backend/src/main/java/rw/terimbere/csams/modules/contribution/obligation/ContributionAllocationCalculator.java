package rw.terimbere.csams.modules.contribution.obligation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, decimal-safe split of a contribution between its ROTATING and RETAINED portions. No floating point, no state,
 * no ledger: these are obligation components and allocation arithmetic, not posted money.
 *
 * <p><b>Rounding rule (documented, HALF_UP).</b> The rotating portion is {@code amount x bps / 10000} rounded HALF_UP to
 * the whole currency unit (RWF has no minor unit). The retained portion is the exact remainder, so
 * {@code rotating + retained == amount} always. HALF_UP is the rounding mode the rest of the system already uses
 * ({@code MoneyUtils.ROUNDING}); only the scale differs (whole RWF here, versus 2 to 4 decimals for stored amounts).
 * A 100% rotating split (bps = 10000) is exact and never rounded. The rotating portion is capped at the amount, so the
 * retained portion can never be negative even for a fractional amount.
 *
 * <p><b>Partial payments.</b> Rounding each payment separately would let the pieces drift away from the obligation's own
 * split. Instead the rotating share is a function of the CUMULATIVE amount paid, and each payment receives the
 * difference between two cumulative targets ({@link #delta}). The deltas therefore always add up to exactly the split
 * of the total paid, whatever the order, size or number of payments, and a full payment lands exactly on the
 * obligation's rotating and retained portions.
 */
public final class ContributionAllocationCalculator {

    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private ContributionAllocationCalculator() {}

    public record Split(BigDecimal rotating, BigDecimal retained) {
        public BigDecimal total() {
            return rotating.add(retained);
        }
    }

    /** The split of {@code amount}: rotating rounded HALF_UP to a whole unit, retained the exact remainder. */
    public static Split split(BigDecimal amount, int rotatingBps) {
        requireNonNegative(amount, "amount");
        requireBps(rotatingBps);
        BigDecimal rotating;
        if (rotatingBps == 10_000) {
            rotating = amount;
        } else {
            rotating = amount.multiply(BigDecimal.valueOf(rotatingBps)).divide(TEN_THOUSAND, 0, RoundingMode.HALF_UP);
            if (rotating.compareTo(amount) > 0) {
                rotating = amount;
            }
        }
        return of(rotating, amount);
    }

    /**
     * What a payment of {@code payment} adds to the rotating and retained portions when {@code alreadyPaid} has already
     * been attributed to the same obligation: the difference of the two cumulative targets, never negative and always
     * totalling exactly the payment.
     */
    public static Split delta(BigDecimal alreadyPaid, BigDecimal payment, int rotatingBps) {
        requireNonNegative(alreadyPaid, "alreadyPaid");
        requireNonNegative(payment, "payment");
        BigDecimal before = split(alreadyPaid, rotatingBps).rotating();
        BigDecimal after = split(alreadyPaid.add(payment), rotatingBps).rotating();
        BigDecimal rotating = after.subtract(before).max(BigDecimal.ZERO).min(payment);
        return of(rotating, payment);
    }

    /**
     * Exact split by amounts: {@code amount x rotatingUnit / unit}, HALF_UP to a whole unit, capped at the amount. Used
     * when a scheme states its split in money (e.g. 50,000 rotated of 70,000 per share), which basis points cannot hold
     * exactly. A full payment of n shares lands exactly on n x rotatingUnit.
     */
    public static Split splitByUnits(BigDecimal amount, BigDecimal rotatingUnit, BigDecimal unit) {
        requireNonNegative(amount, "amount");
        requireUnits(rotatingUnit, unit);
        BigDecimal rotating;
        if (rotatingUnit.compareTo(unit) == 0) {
            rotating = amount;
        } else {
            rotating = amount.multiply(rotatingUnit).divide(unit, 0, RoundingMode.HALF_UP);
            if (rotating.compareTo(amount) > 0) {
                rotating = amount;
            }
        }
        return of(rotating.setScale(Math.max(amount.scale(), 0), RoundingMode.UNNECESSARY), amount);
    }

    /** {@link #delta} for an amount-based split: the difference of two cumulative targets, so pieces never drift. */
    public static Split deltaByUnits(BigDecimal alreadyPaid, BigDecimal payment, BigDecimal rotatingUnit, BigDecimal unit) {
        requireNonNegative(alreadyPaid, "alreadyPaid");
        requireNonNegative(payment, "payment");
        BigDecimal before = splitByUnits(alreadyPaid, rotatingUnit, unit).rotating();
        BigDecimal after = splitByUnits(alreadyPaid.add(payment), rotatingUnit, unit).rotating();
        BigDecimal rotating = after.subtract(before).max(BigDecimal.ZERO).min(payment);
        return of(rotating.setScale(Math.max(payment.scale(), 0), RoundingMode.HALF_UP), payment);
    }

    private static void requireUnits(BigDecimal rotatingUnit, BigDecimal unit) {
        if (rotatingUnit == null || unit == null || rotatingUnit.signum() <= 0 || unit.signum() <= 0
                || rotatingUnit.compareTo(unit) > 0) {
            throw new IllegalArgumentException("Rotating unit must be positive and not above the contribution unit");
        }
    }

    private static Split of(BigDecimal rotating, BigDecimal total) {
        // Both parts keep the scale of the amount they came from, so rotating + retained is exactly the amount.
        int scale = Math.max(total.scale(), 0);
        return new Split(rotating.setScale(scale, RoundingMode.UNNECESSARY), total.subtract(rotating).setScale(scale, RoundingMode.UNNECESSARY));
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireBps(int bps) {
        if (bps < 0 || bps > 10_000) {
            throw new IllegalArgumentException("Basis points must be between 0 and 10000");
        }
    }
}
