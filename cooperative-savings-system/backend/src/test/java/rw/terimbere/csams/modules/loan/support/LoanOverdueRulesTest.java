package rw.terimbere.csams.modules.loan.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;

class LoanOverdueRulesTest {

    private final LocalDate today = LocalDate.of(2026, 9, 19);

    @Test
    void markOverdueAndAnalyticsPredicatesStayAlignedOnActivePastDueBalance() {
        assertThat(LoanOverdueRules.ACTIVE_PAST_DUE_WITH_BALANCE)
                .contains("LoanStatus.ACTIVE")
                .contains("dueDate < :today")
                .contains("(l.outstandingPrincipal + l.outstandingInterest) > 0");
        assertThat(LoanOverdueRules.CURRENTLY_OVERDUE_FOR_ANALYTICS)
                .contains("LoanStatus.OVERDUE")
                .contains("LoanStatus.ACTIVE")
                .contains("dueDate < :today")
                .contains("(l.outstandingPrincipal + l.outstandingInterest) > 0");
        assertThat(LoanOverdueRules.ACTIVE_NOT_CURRENTLY_OVERDUE).contains("LoanStatus.ACTIVE");
    }

    @Test
    void effectiveStatus_mapsActivePastDueWithBalanceToOverdue() {
        Loan loan = baseLoan(LoanStatus.ACTIVE, today.minusDays(1), "1000", "0");
        assertThat(LoanOverdueRules.effectiveStatus(loan, today)).isEqualTo(LoanStatus.OVERDUE);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void effectiveStatus_keepsFutureActiveAndZeroBalanceActive() {
        assertThat(LoanOverdueRules.effectiveStatus(
                        baseLoan(LoanStatus.ACTIVE, today.plusDays(10), "1000", "0"), today))
                .isEqualTo(LoanStatus.ACTIVE);
        assertThat(LoanOverdueRules.effectiveStatus(
                        baseLoan(LoanStatus.ACTIVE, today.minusDays(1), "0", "0"), today))
                .isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void effectiveStatus_keepsPersistedOverdue() {
        assertThat(LoanOverdueRules.effectiveStatus(
                        baseLoan(LoanStatus.OVERDUE, today.minusDays(30), "500", "10"), today))
                .isEqualTo(LoanStatus.OVERDUE);
    }

    private static Loan baseLoan(
            LoanStatus status, LocalDate dueDate, String principal, String interest) {
        return Loan.builder()
                .cooperativeId(UUID.randomUUID())
                .memberUserId(UUID.randomUUID())
                .requestedAmount(new BigDecimal(principal).max(BigDecimal.ONE))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .outstandingPrincipal(new BigDecimal(principal))
                .outstandingInterest(new BigDecimal(interest))
                .requestDate(LocalDate.of(2026, 1, 1))
                .dueDate(dueDate)
                .status(status)
                .build();
    }
}
