package rw.terimbere.csams.shared.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;

@ExtendWith(MockitoExtension.class)
class LedgerFinancialCalculationServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private LoanRepository loanRepository;

    @Mock
    private InvestmentRepository investmentRepository;

    @Mock
    private MemberOpeningBalanceRepository openingBalanceRepository;

    @Mock
    private rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository historicalImportRepository;

    @Mock
    private rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository loanRepaymentRepository;

    @InjectMocks
    private LedgerFinancialCalculationService service;

    private UUID cooperativeId;

    @BeforeEach
    void setUp() {
        cooperativeId = UUID.randomUUID();
    }

    @Test
    void availableGroupFundAppliesPhase8Formula() {
        when(ledgerEntryRepository.sumApprovedCredits(
                        eq(cooperativeId),
                        eq(EnumSet.of(
                                LedgerTransactionType.REGULAR_CONTRIBUTION,
                                LedgerTransactionType.SPECIAL_CONTRIBUTION))))
                .thenReturn(new BigDecimal("10000.0000"));
        when(ledgerEntryRepository.sumApprovedCredits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.FINE_PAYMENT))))
                .thenReturn(new BigDecimal("300.0000"));
        when(ledgerEntryRepository.sumApprovedCredits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.OTHER_INCOME))))
                .thenReturn(new BigDecimal("200.0000"));
        when(ledgerEntryRepository.sumApprovedCredits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.INVESTMENT_CAPITAL_RETURN))))
                .thenReturn(new BigDecimal("500.0000"));
        when(ledgerEntryRepository.sumApprovedCredits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.SHARE_PURCHASE))))
                .thenReturn(new BigDecimal("250.0000"));
        when(ledgerEntryRepository.sumApprovedCredits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.LOAN_INTEREST_PAYMENT))))
                .thenReturn(new BigDecimal("100.0000"));
        when(ledgerEntryRepository.sumApprovedCredits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.INVESTMENT_PROFIT))))
                .thenReturn(new BigDecimal("50.0000"));
        when(ledgerEntryRepository.sumApprovedDebits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.INTEREST_EXPENSE))))
                .thenReturn(new BigDecimal("20.0000"));
        when(loanRepository.sumOutstandingPrincipalExcludingImported(
                        eq(cooperativeId),
                        eq(EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.WRITTEN_OFF))))
                .thenReturn(new BigDecimal("2000.0000"));
        when(ledgerEntryRepository.sumApprovedDebits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.INVESTMENT_OUTFLOW))))
                .thenReturn(new BigDecimal("1000.0000"));
        when(ledgerEntryRepository.sumApprovedDebits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.GENERAL_EXPENSE))))
                .thenReturn(new BigDecimal("150.0000"));
        when(ledgerEntryRepository.sumApprovedDebits(
                        eq(cooperativeId), eq(EnumSet.of(LedgerTransactionType.MEMBER_PAYOUT))))
                .thenReturn(new BigDecimal("400.0000"));

        // contrib 10000 + fine 300 + other 200 + capitalReturn 500 + sharePurchases 250
        // + availableInterest (100+50-20=130)
        // − outstanding 2000 − outflow 1000 − expense 150 − payouts 400
        // = 7830
        BigDecimal available = service.calculateAvailableGroupFund(cooperativeId);
        assertThat(available).isEqualByComparingTo("7830.00");
        assertThat(service.calculateAvailableInterest(cooperativeId)).isEqualByComparingTo("130.00");
    }

    @Test
    void socialFundTypesAreNotIncludedInAvailableFund() {
        when(ledgerEntryRepository.sumApprovedCredits(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);
        when(ledgerEntryRepository.sumApprovedDebits(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);
        when(loanRepository.sumOutstandingPrincipalExcludingImported(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);

        assertThat(service.calculateAvailableGroupFund(cooperativeId)).isEqualByComparingTo("0.00");
    }


    @Test
    void explicitCutoverCashIsNotReducedByImportedLoansAndIgnoresMemberBalances() {
        when(ledgerEntryRepository.sumApprovedCredits(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);
        when(ledgerEntryRepository.sumApprovedDebits(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);
        when(historicalImportRepository.sumOpeningAvailableFunds(eq(cooperativeId), any()))
                .thenReturn(new BigDecimal("800000.0000"));
        // the 400,000 imported loan is excluded from the deduction: only OuWealth-issued loans reduce cash
        when(loanRepository.sumOutstandingPrincipalExcludingImported(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);

        assertThat(service.calculateAvailableGroupFund(cooperativeId)).isEqualByComparingTo("800000.00");
        // member lifetime balances never feed the cash position
        org.mockito.Mockito.verifyNoInteractions(openingBalanceRepository);
        // the repository query used for deduction is the non-imported one, never the all-loans sum
        org.mockito.Mockito.verify(loanRepository, org.mockito.Mockito.never())
                .sumOutstandingPrincipalByStatuses(any(), any());
    }

    @Test
    void principalRepaidOnImportedLoansAfterCutoverIsCashIn_andLiveLoansStillReduceCash() {
        when(ledgerEntryRepository.sumApprovedCredits(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);
        when(ledgerEntryRepository.sumApprovedDebits(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);
        when(historicalImportRepository.sumOpeningAvailableFunds(eq(cooperativeId), any()))
                .thenReturn(new BigDecimal("800000.0000"));
        when(loanRepaymentRepository.sumPrincipalRepaidOnImportedLoans(cooperativeId))
                .thenReturn(new BigDecimal("100000.0000"));
        when(loanRepository.sumOutstandingPrincipalExcludingImported(eq(cooperativeId), any(Collection.class)))
                .thenReturn(new BigDecimal("30000.0000"));

        // 800,000 + 100,000 repaid principal - 30,000 lent from OuWealth
        assertThat(service.calculateAvailableGroupFund(cooperativeId)).isEqualByComparingTo("870000.00");
    }

    @Test
    void schemeWithoutMigrationKeepsThePreviousFormula() {
        when(ledgerEntryRepository.sumApprovedCredits(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);
        when(ledgerEntryRepository.sumApprovedDebits(eq(cooperativeId), any(Collection.class)))
                .thenReturn(BigDecimal.ZERO);
        when(loanRepository.sumOutstandingPrincipalExcludingImported(eq(cooperativeId), any(Collection.class)))
                .thenReturn(new BigDecimal("2000.0000"));
        assertThat(service.calculateAvailableGroupFund(cooperativeId)).isEqualByComparingTo("-2000.00");
    }
}
