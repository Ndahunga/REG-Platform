package rw.terimbere.csams.modules.contribution.obligation;

import java.math.BigDecimal;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.obligation.ContributionAllocationCalculator.Split;

/**
 * The rotating/retained split of one stored obligation, using the basis recorded on THAT row: the exact amount basis
 * (rotating unit of the contribution unit) when the row carries it, otherwise its basis points. Callers must check that
 * the row carries a split at all ({@link #hasSplit}).
 */
public final class ContributionSplits {

    private ContributionSplits() {}

    public static boolean hasSplit(Contribution row) {
        return row.getRotatingBpsSnapshot() != null;
    }

    public static boolean usesUnits(Contribution row) {
        return row.getRotatingUnitSnapshot() != null
                && row.getUnitAmountSnapshot() != null
                && row.getUnitAmountSnapshot().signum() > 0
                && row.getRotatingUnitSnapshot().compareTo(row.getUnitAmountSnapshot()) <= 0;
    }

    public static Split split(Contribution row, BigDecimal amount) {
        if (usesUnits(row)) {
            return ContributionAllocationCalculator.splitByUnits(amount, row.getRotatingUnitSnapshot(), row.getUnitAmountSnapshot());
        }
        return ContributionAllocationCalculator.split(amount, row.getRotatingBpsSnapshot());
    }

    public static Split delta(Contribution row, BigDecimal alreadyPaid, BigDecimal payment) {
        if (usesUnits(row)) {
            return ContributionAllocationCalculator.deltaByUnits(
                    alreadyPaid, payment, row.getRotatingUnitSnapshot(), row.getUnitAmountSnapshot());
        }
        return ContributionAllocationCalculator.delta(alreadyPaid, payment, row.getRotatingBpsSnapshot());
    }
}
