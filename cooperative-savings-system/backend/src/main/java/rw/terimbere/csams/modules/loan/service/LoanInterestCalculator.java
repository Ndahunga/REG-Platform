package rw.terimbere.csams.modules.loan.service;

import java.math.BigDecimal;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Interest helpers. FLAT is fully supported.
 *
 * <p>REDUCING historically used the same simple percentage as FLAT. New REDUCING settings/loans are
 * blocked until the client amortization rule is confirmed; this method still reads existing REDUCING
 * loans without changing their stored formula. See {@code documentation/development/reducing-interest-pending.md}.
 */
public final class LoanInterestCalculator {

    private LoanInterestCalculator() {}

    /**
     * One period of FLAT interest: {@code principal × rate/100}.
     * New FLAT loans use {@link LoanScheduleCalculator} to multiply this by the term
     * (with optional first-period prorata). REDUCING remains a legacy one-period path.
     */
    public static BigDecimal computeInterest(BigDecimal principal, BigDecimal ratePercent, InterestType type) {
        BigDecimal p = MoneyUtils.scaleForStorage(principal == null ? BigDecimal.ZERO : principal);
        BigDecimal rate = ratePercent == null ? BigDecimal.ZERO : ratePercent;
        if (type == InterestType.REDUCING) {
            // Legacy path for already-created REDUCING loans — formula unchanged on purpose.
            return MoneyUtils.scaleForStorage(MoneyUtils.percentage(p, rate));
        }
        // FLAT (default)
        return MoneyUtils.scaleForStorage(MoneyUtils.percentage(p, rate));
    }
}
