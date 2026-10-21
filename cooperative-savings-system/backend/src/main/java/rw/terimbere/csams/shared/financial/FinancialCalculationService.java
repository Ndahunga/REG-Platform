package rw.terimbere.csams.shared.financial;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Financial calculation contracts used across modules.
 * Full ledger-backed implementations arrive in later phases.
 */
public interface FinancialCalculationService {

    /**
     * Calculates the available group fund balance for a cooperative.
     *
     * @param cooperativeId cooperative identifier
     * @return available fund amount scaled for money use
     */
    BigDecimal calculateAvailableGroupFund(UUID cooperativeId);
}
