package rw.terimbere.csams.modules.loan.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.loan.entity.LoanShareTier;

public final class LoanShareLimitCalculator {

    public static final int PERCENT_SCALE = 4;

    private LoanShareLimitCalculator() {}

    public static BigDecimal sharePercent(int memberShareCount, long totalShares) {
        int shares = ShareAmountCalculator.normalizeShareCount(memberShareCount);
        if (totalShares <= 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(shares)
                .multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(totalShares), PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Highest configured minimum share % that the member still meets.
     * Example: 4% → 20,000 and 2% → 3,000; a member with 3% receives 3,000.
     */
    public static Optional<BigDecimal> matchingMaxLoan(BigDecimal sharePercent, List<LoanShareTier> tiers) {
        if (sharePercent == null || tiers == null || tiers.isEmpty()) {
            return Optional.empty();
        }
        LoanShareTier best = null;
        for (LoanShareTier tier : tiers) {
            if (tier == null || tier.getMinSharePercent() == null || tier.getMaxLoanAmount() == null) {
                continue;
            }
            if (sharePercent.compareTo(tier.getMinSharePercent()) >= 0) {
                if (best == null || tier.getMinSharePercent().compareTo(best.getMinSharePercent()) > 0) {
                    best = tier;
                }
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.getMaxLoanAmount());
    }
}
