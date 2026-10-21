package rw.terimbere.csams.shared.financial;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Fallback stub when no ledger-backed implementation is present.
 * Phase 4 provides {@link LedgerFinancialCalculationService} as {@code @Primary}.
 */
@Service
@ConditionalOnMissingBean(LedgerFinancialCalculationService.class)
public class StubFinancialCalculationService implements FinancialCalculationService {

    @Override
    public BigDecimal calculateAvailableGroupFund(UUID cooperativeId) {
        return BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE);
    }
}
