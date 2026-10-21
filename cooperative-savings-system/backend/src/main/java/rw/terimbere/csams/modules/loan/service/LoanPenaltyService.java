package rw.terimbere.csams.modules.loan.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanPenaltyFrequency;
import rw.terimbere.csams.modules.loan.entity.LoanPenaltyType;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Assesses loan-installment penalties using the policy snapshotted on the loan.
 *
 * <p>Penalty accounting is the Fine receivable. {@code LoanInstallment.penaltyDue}
 * mirrors assessed fines so the schedule and ShareValuation stay consistent.
 *
 * <p>{@code PERCENTAGE_OF_OUTSTANDING_LOAN_BALANCE} uses outstanding principal —
 * the same basis as share-valuation {@code outstandingLoans}.
 */
@Service
@RequiredArgsConstructor
public class LoanPenaltyService {

    private final LoanRepository loanRepository;
    private final LoanInstallmentRepository installmentRepository;
    private final FineService fineService;
    private final Clock clock;

    @Transactional
    public void evaluateAllActiveLoans() {
        LocalDate today = LocalDate.now(clock);
        for (Loan loan : loanRepository.findByStatusIn(EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE))) {
            evaluate(loan, today, null);
        }
    }

    @Transactional
    public List<LoanInstallment> evaluate(Loan loan, LocalDate asOf, UUID recordedBy) {
        List<LoanInstallment> installments = installmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loan.getId());
        if (installments.isEmpty()) {
            return installments;
        }
        LocalDate today = asOf == null ? LocalDate.now(clock) : asOf;
        for (LoanInstallment installment : installments) {
            if (loan.isLoanPenaltyEnabled() && installment.getStatus() != rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus.PAID) {
                assessInstallment(loan, installment, today, recordedBy);
            }
            installment.refreshStatus(today);
        }
        installmentRepository.saveAll(installments);
        refreshLoanPenaltyTotals(loan, installments);
        return installments;
    }

    private void assessInstallment(Loan loan, LoanInstallment installment, LocalDate asOf, UUID recordedBy) {
        if (installment.getDueDate() == null) {
            return;
        }
        int grace = loan.getGracePeriodDays() == null ? 0 : Math.max(0, loan.getGracePeriodDays());
        LocalDate firstEligible = installment.getDueDate().plusDays(grace).plusDays(1);
        if (asOf.isBefore(firstEligible)) {
            return;
        }
        LoanPenaltyFrequency frequency =
                loan.getPenaltyFrequency() == null ? LoanPenaltyFrequency.ONE_TIME : loan.getPenaltyFrequency();
        for (String periodKey : periodKeys(frequency, firstEligible, asOf)) {
            BigDecimal amount = computePenaltyAmount(loan, installment);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Fine created = fineService.assessLoanInstallmentPenalty(
                    loan, installment, amount, periodKey, asOf, recordedBy);
            if (created != null) {
                installment.setPenaltyDue(MoneyUtils.add(nvl(installment.getPenaltyDue()), amount));
            }
        }
    }

    private List<String> periodKeys(LoanPenaltyFrequency frequency, LocalDate firstEligible, LocalDate asOf) {
        List<String> keys = new ArrayList<>();
        if (frequency == LoanPenaltyFrequency.ONE_TIME) {
            keys.add("ONCE");
            return keys;
        }
        if (frequency == LoanPenaltyFrequency.DAILY) {
            LocalDate cursor = firstEligible;
            while (!cursor.isAfter(asOf)) {
                keys.add(cursor.toString());
                cursor = cursor.plusDays(1);
            }
            return keys;
        }
        YearMonth start = YearMonth.from(firstEligible);
        YearMonth end = YearMonth.from(asOf);
        YearMonth cursor = start;
        while (!cursor.isAfter(end)) {
            keys.add(cursor.toString());
            cursor = cursor.plusMonths(1);
        }
        if (keys.isEmpty()) {
            keys.add(start.toString());
        }
        return keys;
    }

    /**
     * Outstanding-loan-balance basis is outstanding principal (share-valuation
     * outstandingLoans). Overdue-installment basis is remaining contractual
     * principal + interest, excluding already-assessed penalty.
     */
    private BigDecimal computePenaltyAmount(Loan loan, LoanInstallment installment) {
        LoanPenaltyType type = loan.getPenaltyType() == null ? LoanPenaltyType.FIXED_AMOUNT : loan.getPenaltyType();
        BigDecimal rateOrAmount = nvl(loan.getPenaltyRateOrAmount());
        return switch (type) {
            case FIXED_AMOUNT -> MoneyUtils.scale(rateOrAmount);
            case PERCENTAGE_OF_OVERDUE_INSTALLMENT -> MoneyUtils.percentage(
                    MoneyUtils.add(installment.remainingPrincipal(), installment.remainingInterest()), rateOrAmount);
            case PERCENTAGE_OF_OUTSTANDING_LOAN_BALANCE -> MoneyUtils.percentage(
                    nvl(loan.getOutstandingPrincipal()), rateOrAmount);
        };
    }

    private void refreshLoanPenaltyTotals(Loan loan, List<LoanInstallment> installments) {
        BigDecimal outstanding = BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE);
        BigDecimal repaid = BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE);
        for (LoanInstallment installment : installments) {
            outstanding = MoneyUtils.add(outstanding, installment.remainingPenalty());
            repaid = MoneyUtils.add(repaid, nvl(installment.getPenaltyPaid()));
        }
        loan.setOutstandingPenalty(MoneyUtils.scaleForStorage(outstanding));
        loan.setTotalRepaidPenalty(MoneyUtils.scaleForStorage(repaid));
        loanRepository.save(loan);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE) : MoneyUtils.scale(value);
    }
}
