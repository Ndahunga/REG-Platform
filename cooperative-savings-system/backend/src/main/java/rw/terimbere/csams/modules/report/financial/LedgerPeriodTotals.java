package rw.terimbere.csams.modules.report.financial;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/** Approved ledger credit/debit totals for a cooperative period (main fund). */
@Value
@Builder
public class LedgerPeriodTotals {
    Map<LedgerTransactionType, BigDecimal> creditsByType;
    Map<LedgerTransactionType, BigDecimal> debitsByType;
    BigDecimal openingLedgerBalance;
    BigDecimal closingLedgerBalance;
    BigDecimal periodCredits;
    BigDecimal periodDebits;

    public BigDecimal credit(LedgerTransactionType type) {
        return MoneyUtils.scale(creditsByType.getOrDefault(type, BigDecimal.ZERO));
    }

    public BigDecimal debit(LedgerTransactionType type) {
        return MoneyUtils.scale(debitsByType.getOrDefault(type, BigDecimal.ZERO));
    }

    public BigDecimal netCashMovement() {
        return MoneyUtils.subtract(periodCredits, periodDebits);
    }

    public static LedgerPeriodTotals empty() {
        return LedgerPeriodTotals.builder()
                .creditsByType(Collections.emptyMap())
                .debitsByType(Collections.emptyMap())
                .openingLedgerBalance(MoneyUtils.scale(BigDecimal.ZERO))
                .closingLedgerBalance(MoneyUtils.scale(BigDecimal.ZERO))
                .periodCredits(MoneyUtils.scale(BigDecimal.ZERO))
                .periodDebits(MoneyUtils.scale(BigDecimal.ZERO))
                .build();
    }

    public static Map<LedgerTransactionType, BigDecimal> newTypeMap() {
        return new EnumMap<>(LedgerTransactionType.class);
    }
}
