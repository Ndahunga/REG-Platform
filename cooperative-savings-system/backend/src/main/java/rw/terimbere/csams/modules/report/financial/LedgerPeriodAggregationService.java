package rw.terimbere.csams.modules.report.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Period-scoped ledger aggregation for Full Financial statements.
 *
 * <p>Uses {@code transactionDate} (not createdAt). Social fund types are excluded from main-fund balances and
 * period type maps.
 *
 * <p><b>Period-accurate reversal reporting.</b> A reversed original stays in the period of its own transaction date
 * and its linked REVERSAL appears as the offsetting movement in the period of the reversal's transaction date, so a
 * reversed contribution nets to zero cumulatively without rewriting the period it was received in. Only valid
 * original/reversal pairs are reported; see {@code LedgerEntryRepository.PERIOD_ACCURATE_MOVEMENT} for the exact rule
 * and for how orphaned or duplicate reversal rows are treated. This is a reporting rule only: operational balances
 * (available funds, contribution balances) keep excluding reversed originals.
 */
@Service
@RequiredArgsConstructor
public class LedgerPeriodAggregationService {

    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional(readOnly = true)
    public LedgerPeriodTotals aggregateMainFund(UUID cooperativeId, LocalDate fromDate, LocalDate toDate) {
        if (cooperativeId == null || fromDate == null || toDate == null) {
            return LedgerPeriodTotals.empty();
        }
        EnumSet<LedgerTransactionType> excluded =
                EnumSet.copyOf(FullFinancialLedgerClassifications.SOCIAL_FUND_TYPES);

        Map<LedgerTransactionType, BigDecimal> credits = LedgerPeriodTotals.newTypeMap();
        Map<LedgerTransactionType, BigDecimal> debits = LedgerPeriodTotals.newTypeMap();
        BigDecimal periodCredits = BigDecimal.ZERO;
        BigDecimal periodDebits = BigDecimal.ZERO;

        List<Object[]> rows = ledgerEntryRepository.sumMovementByTypeInPeriod(
                cooperativeId, fromDate, toDate, excluded);
        for (Object[] row : rows) {
            LedgerTransactionType type = (LedgerTransactionType) row[0];
            BigDecimal credit = MoneyUtils.scale(nvl((BigDecimal) row[1]));
            BigDecimal debit = MoneyUtils.scale(nvl((BigDecimal) row[2]));
            credits.put(type, credit);
            debits.put(type, debit);
            periodCredits = MoneyUtils.add(periodCredits, credit);
            periodDebits = MoneyUtils.add(periodDebits, debit);
        }

        BigDecimal opening = MoneyUtils.scale(nvl(ledgerEntryRepository.sumNetMovementBefore(
                cooperativeId, fromDate, excluded)));
        BigDecimal closing = MoneyUtils.scale(nvl(ledgerEntryRepository.sumNetMovementThrough(
                cooperativeId, toDate, excluded)));

        return LedgerPeriodTotals.builder()
                .creditsByType(Map.copyOf(credits))
                .debitsByType(Map.copyOf(debits))
                .openingLedgerBalance(opening)
                .closingLedgerBalance(closing)
                .periodCredits(MoneyUtils.scale(periodCredits))
                .periodDebits(MoneyUtils.scale(periodDebits))
                .build();
    }

    @Transactional(readOnly = true)
    public BigDecimal sumApprovedCreditsInPeriod(
            UUID cooperativeId, LocalDate from, LocalDate to, EnumSet<LedgerTransactionType> types) {
        return MoneyUtils.scale(nvl(ledgerEntryRepository.sumApprovedCreditsInPeriod(cooperativeId, from, to, types)));
    }

    @Transactional(readOnly = true)
    public BigDecimal sumApprovedDebitsInPeriod(
            UUID cooperativeId, LocalDate from, LocalDate to, EnumSet<LedgerTransactionType> types) {
        return MoneyUtils.scale(nvl(ledgerEntryRepository.sumApprovedDebitsInPeriod(cooperativeId, from, to, types)));
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
