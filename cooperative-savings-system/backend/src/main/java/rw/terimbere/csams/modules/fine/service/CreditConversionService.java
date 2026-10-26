package rw.terimbere.csams.modules.fine.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.fine.entity.FineSettings;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanGuaranteeMode;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator;
import rw.terimbere.csams.modules.loan.service.LoanAllocationOrder;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Converts a contribution left unpaid too long into a credit (a loan), as many schemes' rules require: after
 * {@code creditConversionDays} past the due date, what is still unpaid becomes a loan at
 * {@code creditConversionRatePercent} a month over {@code creditConversionTermMonths}, and the contribution counts as
 * paid by that credit. No cash moves: the loan principal and the contribution credit offset each other, so available
 * funds are unchanged, the member's contribution record is complete, and the member now owes a loan with interest.
 * Runs once per contribution.
 */
@Service
@RequiredArgsConstructor
public class CreditConversionService {

    private static final Logger log = LoggerFactory.getLogger(CreditConversionService.class);

    private final ContributionRepository contributionRepository;
    private final ContributionService contributionService;
    private final LoanRepository loanRepository;
    private final LoanInstallmentRepository installmentRepository;
    private final LedgerService ledgerService;
    private final JdbcTemplate jdbc;

    public static String marker(UUID contributionId) {
        return "Credit conversion CC:" + contributionId;
    }

    /** Returns 1 when a contribution was converted, 0 when nothing was due or it was converted before. */
    @Transactional
    public int convertIfDue(Cooperative cooperative, FineSettings settings, UUID contributionId, LocalDate dueDate,
                            LocalDate today, UUID actorId) {
        Integer days = settings.getCreditConversionDays();
        BigDecimal rate = settings.getCreditConversionRatePercent();
        if (days == null || rate == null || today.isBefore(dueDate.plusDays(days))) {
            return 0;
        }
        Long done = jdbc.queryForObject(
                "select count(*) from loans where cooperative_id = ? and purpose like ?",
                Long.class, cooperative.getId(), "%CC:" + contributionId + "%");
        if (done != null && done > 0) {
            return 0;
        }
        Contribution row = contributionRepository.findByIdAndCooperativeIdForUpdate(contributionId, cooperative.getId())
                .orElse(null);
        if (row == null || row.getStatus() == ContributionStatus.WAIVED || row.getStatus() == ContributionStatus.CANCELLED
                || row.getOutstandingAmount() == null || row.getOutstandingAmount().signum() <= 0) {
            return 0;
        }
        BigDecimal amount = MoneyUtils.scaleForStorage(row.getOutstandingAmount());
        int term = settings.getCreditConversionTermMonths() == null || settings.getCreditConversionTermMonths() < 1
                ? 3 : settings.getCreditConversionTermMonths();
        var position = ImportedLoanPositionCalculator.calculate(new ImportedLoanPositionCalculator.Input(
                ImportedLoanPositionCalculator.RepaymentType.MONTHLY,
                amount, rate, today, term, BigDecimal.ZERO, amount, today, LoanRepaymentDateModel.SAME_DAY_OF_MONTH));
        String period = row.getYear() + "-" + String.format(Locale.ROOT, "%02d", row.getMonth());
        Loan loan = loanRepository.save(Loan.builder()
                .cooperativeId(cooperative.getId())
                .memberUserId(row.getMemberUserId())
                .requestedAmount(amount)
                .approvedAmount(amount)
                .principalAmount(MoneyUtils.scaleForStorage(position.principalAmount()))
                .interestRatePercent(rate)
                .interestType(InterestType.FLAT)
                .termMonths(term)
                .interestAmount(MoneyUtils.scaleForStorage(position.totalInterest()))
                .prorataEnabled(position.prorataEnabled())
                .firstPeriodDays(position.firstPeriodDays())
                .equalInstallmentAmount(MoneyUtils.scaleForStorage(position.equalInstallmentAmount()))
                .repaymentDateModel(position.repaymentDateModel())
                .outstandingPrincipal(MoneyUtils.scaleForStorage(position.outstandingPrincipal()))
                .outstandingInterest(MoneyUtils.scaleForStorage(position.outstandingInterest()))
                .totalRepaidPrincipal(MoneyUtils.scaleForStorage(BigDecimal.ZERO))
                .totalRepaidInterest(MoneyUtils.scaleForStorage(BigDecimal.ZERO))
                .requestDate(today)
                .approvalDate(today)
                .disbursementDate(today)
                .dueDate(position.dueDate())
                .status(position.status())
                .guaranteeMode(LoanGuaranteeMode.SELF)
                .purpose("Unpaid contribution " + period + " converted to credit (" + marker(contributionId) + ")")
                .requestedBy(actorId)
                .approvedBy(actorId)
                .disbursedBy(actorId)
                .allocationOrder(LoanAllocationOrder.serialize(LoanAllocationOrder.parse(null)))
                .build());
        List<LoanInstallment> rows = new ArrayList<>();
        for (var item : position.installments()) {
            LoanInstallment inst = LoanInstallment.builder()
                    .loanId(loan.getId())
                    .cooperativeId(loan.getCooperativeId())
                    .installmentNumber(item.installmentNumber())
                    .dueDate(item.dueDate())
                    .openingPrincipalBalance(MoneyUtils.scaleForStorage(item.openingPrincipalBalance()))
                    .principalDue(MoneyUtils.scaleForStorage(item.principalDue()))
                    .interestDue(MoneyUtils.scaleForStorage(item.interestDue()))
                    .scheduledInstallmentAmount(MoneyUtils.scaleForStorage(item.totalDue()))
                    .build();
            inst.refreshStatus(today);
            rows.add(inst);
        }
        installmentRepository.saveAll(rows);
        // Non-cash pair: the loan "pays" the contribution. The disbursement debit and the contribution credit offset.
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperative.getId())
                .memberUserId(row.getMemberUserId())
                .transactionType(LedgerTransactionType.LOAN_DISBURSEMENT)
                .debitAmount(amount)
                .creditAmount(BigDecimal.ZERO)
                .currency(cooperative.getCurrency())
                .transactionDate(today)
                .reference("CC-" + period)
                .sourceEntityType(LedgerService.SOURCE_LOAN)
                .sourceEntityId(loan.getId())
                .description("Unpaid contribution " + period + " converted to credit")
                .recordedBy(actorId)
                .approvedBy(actorId)
                .idempotencyKey(LedgerService.loanDisbursementKey(loan.getId()))
                .build());
        contributionService.applyVerifiedPayment(row, amount, today, "CREDIT-" + loan.getId(), actorId, cooperative.getCurrency());
        log.info("Contribution {} converted to credit loan {}", contributionId, loan.getId());
        return 1;
    }
}
