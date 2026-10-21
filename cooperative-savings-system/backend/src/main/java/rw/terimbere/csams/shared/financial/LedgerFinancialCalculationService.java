package rw.terimbere.csams.shared.financial;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Ledger-backed financial calculations (Phase 9 formula).
 *
 * <pre>
 * availableGroupFund =
 *   approvedContributionCredits (REGULAR + SPECIAL)
 *   + approvedFineIncome (FINE_PAYMENT)
 *   + otherApprovedIncome (OTHER_INCOME)
 *   + returnedInvestmentCapital (INVESTMENT_CAPITAL_RETURN)
 *   + approved SHARE_PURCHASE credits (cash in; part of availableFunds / total Ikimina value)
 *   + availableInterest
 *   + openingAvailableFunds (explicit cash at the migration cutover; 0 when not migrated)
 *   + principal repaid after the cutover on migrated loans
 *   − outstandingLoanPrincipal (ACTIVE/OVERDUE, loans issued in OuWealth only)
 *   − investmentOutflows (INVESTMENT_OUTFLOW debits)
 *   − generalExpenses (GENERAL_EXPENSE)
 *   − memberPayouts (MEMBER_PAYOUT debits)
 *
 * availableInterest =
 *   loanInterestEarned + investmentProfit − interestExpenses
 * </pre>
 *
 * <p>Investment outflows are ledger INVESTMENT_OUTFLOW debits (not remaining capital). Using
 * outflows − capital returns keeps LOSS_RECORDED from restoring the fund (remaining is zeroed
 * without a capital-return credit). Dashboard {@code investmentCapital} still reports remaining
 * capital on ACTIVE/PARTIALLY_RETURNED investments.
 *
 * <p>Social fund credits/debits are excluded.
 */
@Service
@Primary
@RequiredArgsConstructor
public class LedgerFinancialCalculationService implements FinancialCalculationService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository loanRepository;
    private final InvestmentRepository investmentRepository;
    private final MemberOpeningBalanceRepository openingBalanceRepository;
    private final rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository historicalImportRepository;
    private final rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository loanRepaymentRepository;

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateAvailableGroupFund(UUID cooperativeId) {
        BigDecimal contributionCredits = sumApprovedContributionCredits(cooperativeId);
        BigDecimal fineIncome = sumApprovedCreditsByType(cooperativeId, LedgerTransactionType.FINE_PAYMENT);
        BigDecimal otherIncome = sumApprovedCreditsByType(cooperativeId, LedgerTransactionType.OTHER_INCOME);
        BigDecimal returnedCapital =
                sumApprovedCreditsByType(cooperativeId, LedgerTransactionType.INVESTMENT_CAPITAL_RETURN);
        BigDecimal sharePurchases = sumApprovedCreditsByType(cooperativeId, LedgerTransactionType.SHARE_PURCHASE);
        BigDecimal availableInterest = calculateAvailableInterest(cooperativeId);

        BigDecimal credits = MoneyUtils.add(
                MoneyUtils.add(
                        MoneyUtils.add(contributionCredits, fineIncome),
                        MoneyUtils.add(otherIncome, returnedCapital)),
                MoneyUtils.add(sharePurchases, availableInterest));

        // Loans issued inside OuWealth reduce cash. Loans imported by the migration do not: the explicit
        // cutover cash is already net of them. Principal repaid on them after the cutover is cash coming in.
        // WRITTEN_OFF loans stay in this set: a write-off is an accounting status change, not a cash receipt, so the
        // unrecovered principal that was lent out must keep reducing available cash.
        BigDecimal outstandingPrincipal = loanRepository.sumOutstandingPrincipalExcludingImported(
                cooperativeId, EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.WRITTEN_OFF));
        BigDecimal outstanding =
                MoneyUtils.scale(outstandingPrincipal == null ? BigDecimal.ZERO : outstandingPrincipal);
        BigDecimal opening = MoneyUtils.add(
                sumOpeningAvailableFunds(cooperativeId), sumPrincipalRepaidOnImportedLoans(cooperativeId));
        credits = MoneyUtils.add(credits, opening);

        BigDecimal investmentOutflows =
                sumApprovedDebitsByType(cooperativeId, LedgerTransactionType.INVESTMENT_OUTFLOW);
        BigDecimal generalExpenses = sumApprovedDebitsByType(cooperativeId, LedgerTransactionType.GENERAL_EXPENSE);
        BigDecimal memberPayouts = sumApprovedDebitsByType(cooperativeId, LedgerTransactionType.MEMBER_PAYOUT);

        return MoneyUtils.subtract(
                MoneyUtils.subtract(
                        MoneyUtils.subtract(MoneyUtils.subtract(credits, outstanding), investmentOutflows),
                        generalExpenses),
                memberPayouts);
    }

    /** Opening regular contributions through the migration cutover (balance, not a transaction). */
    @Transactional(readOnly = true)
    public BigDecimal sumOpeningContributions(UUID cooperativeId) {
        BigDecimal contributions = openingBalanceRepository.sumOpeningContributions(cooperativeId);
        return MoneyUtils.scale(contributions == null ? BigDecimal.ZERO : contributions);
    }


    /**
     * Explicit cash position at the migration cutover, as entered by the Saving Scheme. It is never derived
     * from member contributions, share capital or loans: those are lifetime member facts, not cash.
     */
    @Transactional(readOnly = true)
    public BigDecimal sumOpeningAvailableFunds(UUID cooperativeId) {
        BigDecimal funds = historicalImportRepository.sumOpeningAvailableFunds(
                cooperativeId, rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus.CONFIRMED);
        return MoneyUtils.scale(funds == null ? BigDecimal.ZERO : funds);
    }

    /** Principal repaid after the cutover on migrated loans: cash received that the cutover cash excludes. */
    @Transactional(readOnly = true)
    public BigDecimal sumPrincipalRepaidOnImportedLoans(UUID cooperativeId) {
        BigDecimal repaid = loanRepaymentRepository.sumPrincipalRepaidOnImportedLoans(cooperativeId);
        return MoneyUtils.scale(repaid == null ? BigDecimal.ZERO : repaid);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateAvailableInterest(UUID cooperativeId) {
        BigDecimal loanInterest =
                sumApprovedCreditsByType(cooperativeId, LedgerTransactionType.LOAN_INTEREST_PAYMENT);
        BigDecimal investmentProfit =
                sumApprovedCreditsByType(cooperativeId, LedgerTransactionType.INVESTMENT_PROFIT);
        BigDecimal interestExpenses =
                sumApprovedDebitsByType(cooperativeId, LedgerTransactionType.INTEREST_EXPENSE);
        return MoneyUtils.subtract(MoneyUtils.add(loanInterest, investmentProfit), interestExpenses);
    }

    /** Remaining capital still deployed in active investments (dashboard metric). */
    @Transactional(readOnly = true)
    public BigDecimal sumActiveInvestmentCapital(UUID cooperativeId) {
        BigDecimal remaining = investmentRepository.sumRemainingCapitalByStatuses(
                cooperativeId, EnumSet.of(InvestmentStatus.ACTIVE, InvestmentStatus.PARTIALLY_RETURNED));
        return MoneyUtils.scale(remaining == null ? BigDecimal.ZERO : remaining);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumApprovedContributionCredits(UUID cooperativeId) {
        BigDecimal credits = ledgerEntryRepository.sumApprovedCredits(
                cooperativeId,
                EnumSet.of(
                        LedgerTransactionType.REGULAR_CONTRIBUTION,
                        LedgerTransactionType.SPECIAL_CONTRIBUTION));
        return MoneyUtils.scale(credits == null ? BigDecimal.ZERO : credits);
    }

    /**
     * Contribution + fine credits only (legacy helper). Prefer {@link #calculateAvailableGroupFund}.
     */
    @Transactional(readOnly = true)
    public BigDecimal sumApprovedGroupFundCredits(UUID cooperativeId) {
        BigDecimal credits = ledgerEntryRepository.sumApprovedCredits(
                cooperativeId,
                EnumSet.of(
                        LedgerTransactionType.REGULAR_CONTRIBUTION,
                        LedgerTransactionType.SPECIAL_CONTRIBUTION,
                        LedgerTransactionType.FINE_PAYMENT));
        return MoneyUtils.scale(credits == null ? BigDecimal.ZERO : credits);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumApprovedCreditsByType(UUID cooperativeId, LedgerTransactionType type) {
        BigDecimal credits = ledgerEntryRepository.sumApprovedCredits(cooperativeId, EnumSet.of(type));
        return MoneyUtils.scale(credits == null ? BigDecimal.ZERO : credits);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumApprovedDebitsByType(UUID cooperativeId, LedgerTransactionType type) {
        BigDecimal debits = ledgerEntryRepository.sumApprovedDebits(cooperativeId, EnumSet.of(type));
        return MoneyUtils.scale(debits == null ? BigDecimal.ZERO : debits);
    }
}
