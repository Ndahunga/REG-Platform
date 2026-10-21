package rw.terimbere.csams.modules.report.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.entity.AuditLog;
import rw.terimbere.csams.modules.audit.repository.AuditLogRepository;
import rw.terimbere.csams.modules.audit.repository.AuditLogSpecs;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FinePayment;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseTransaction;
import rw.terimbere.csams.modules.incomeexpense.repository.IncomeExpenseTransactionRepository;
import rw.terimbere.csams.modules.investment.entity.Investment;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleResponse;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanScheduleCalculator;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.payout.entity.PayoutLine;
import rw.terimbere.csams.modules.payout.entity.PayoutRun;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;
import rw.terimbere.csams.modules.payout.repository.PayoutLineRepository;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.modules.report.dto.MemberContributionAggregate;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportHeaderMeta;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.modules.report.dto.ReportTypeResponse;
import rw.terimbere.csams.modules.report.export.FullFinancialPdfWriter;
import rw.terimbere.csams.modules.report.export.ReportExporter;
import rw.terimbere.csams.modules.report.export.ReportLabels;
import rw.terimbere.csams.modules.report.financial.FullFinancialReportAssembler;
import rw.terimbere.csams.modules.report.financial.FullFinancialReportModel;
import rw.terimbere.csams.modules.report.financial.MemberContributionScheduleService;
import rw.terimbere.csams.modules.report.support.ContributionObligationPeriod;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.entity.SharePurchase;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.socialfund.entity.SocialContribution;
import rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursement;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursementStatus;
import rw.terimbere.csams.modules.socialfund.repository.SocialContributionRepository;
import rw.terimbere.csams.modules.socialfund.repository.SocialDisbursementRepository;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContribution;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.financial.FinancialCalculationService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final ContributionRepository contributionRepository;
    private final SpecialContributionRepository specialContributionRepository;
    private final LoanRepository loanRepository;
    private final LoanInstallmentRepository loanInstallmentRepository;
    private final LoanRepaymentRepository loanRepaymentRepository;
    private final FineRepository fineRepository;
    private final FinePaymentRepository finePaymentRepository;
    private final SocialContributionRepository socialContributionRepository;
    private final SocialDisbursementRepository socialDisbursementRepository;
    private final InvestmentRepository investmentRepository;
    private final IncomeExpenseTransactionRepository incomeExpenseRepository;
    private final PayoutRunRepository payoutRunRepository;
    private final PayoutLineRepository payoutLineRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditLogRepository auditLogRepository;
    private final FinancialCalculationService financialCalculationService;
    private final ShareValuationService shareValuationService;
    private final SharePurchaseRepository sharePurchaseRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final ReportExporter reportExporter;
    private final FullFinancialReportAssembler fullFinancialReportAssembler;
    private final MemberContributionScheduleService memberContributionScheduleService;
    private final rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository openingBalanceRepository;
    private final PlatformTransactionManager transactionManager;

    @Transactional(readOnly = true)
    public List<ReportTypeResponse> listTypes(UUID cooperativeId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        boolean selfScoped = ReportAccessPolicy.isSelfScoped(principal);
        return Arrays.stream(ReportType.values())
                .filter(t -> ReportAccessPolicy.canExport(principal, t))
                .map(t -> ReportTypeResponse.builder()
                        .code(t.name())
                        .label(t.getLabel())
                        .requiresAuditRead(t.requiresAuditRead())
                        .selfScoped(selfScoped)
                        .build())
                .toList();
    }

    /** Not read-only: export also writes an audit log (Postgres rejects INSERTs in read-only txs). */
    @Transactional
    public ReportBinaryExport export(
            UUID cooperativeId, ReportExportRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        ReportType type = request.getReportType();
        if (type == null) {
            throw new ValidationException("reportType is required");
        }
        if (!ReportAccessPolicy.canExport(principal, type)) {
            throw new ForbiddenException("This report is not available for your role");
        }
        if (ReportAccessPolicy.isSelfScoped(principal)) {
            request.setMemberUserId(principal.getId());
        }
        if (type.requiresAuditRead() && !principal.hasAuthority("AUDIT_READ") && !principal.hasRole("SUPER_ADMIN")) {
            throw new ForbiddenException("AUDIT_READ required for audit log reports");
        }

        ReportTimelineValidator.validate(
                request,
                LocalDate.now(ReportTimelineValidator.ZONE),
                cooperative.getRegistrationDate());

        Instant generatedAt = Instant.now();
        String period = formatPeriod(request);
        ReportHeaderMeta header = ReportHeaderMeta.builder()
                .cooperativeName(cooperative.getName())
                .reportTitle(type.getLabel())
                .selectedPeriod(period)
                .generatedAt(generatedAt)
                .generatedBy(principal.getUsername())
                .currency(cooperative.getCurrency())
                .build();

        byte[] bytes;
        if (type == ReportType.FULL_FINANCIAL) {
            FullFinancialReportModel model = fullFinancialReportAssembler.assemble(
                    cooperative, request, principal.getUsername(), generatedAt);
            bytes = FullFinancialPdfWriter.write(model);
        } else {
            List<ReportSheetData> sheets = buildSheets(cooperativeId, type, request);
            bytes = reportExporter.export(header, sheets);
        }

        try {
            TransactionTemplate auditTx = new TransactionTemplate(transactionManager);
            auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            auditTx.executeWithoutResult(status -> auditService.record(
                    principal.getId(),
                    cooperativeId,
                    AuditableAction.EXPORT,
                    "Report",
                    null,
                    null,
                    "{\"reportType\":\""
                            + type.name()
                            + "\",\"period\":\""
                            + period.replace("\\", "\\\\").replace("\"", "'")
                            + "\",\"bytes\":"
                            + bytes.length
                            + "}",
                    clientIp(httpRequest),
                    userAgent(httpRequest)));
        } catch (RuntimeException ex) {
            log.warn("Report export succeeded but audit log failed for cooperative {}", cooperativeId, ex);
        }

        String filename = sanitizeFilename(cooperative.getName())
                + "_"
                + type.name().toLowerCase(Locale.ROOT)
                + "_"
                + request.getFromDate()
                + "_"
                + request.getToDate()
                + "."
                + reportExporter.fileExtension();

        return new ReportBinaryExport(bytes, reportExporter.contentType(), filename);
    }

    private List<ReportSheetData> buildSheets(UUID cooperativeId, ReportType type, ReportExportRequest request) {
        return switch (type) {
            case MEMBERS -> List.of(membersSheet(cooperativeId, request));
            case CONTRIBUTIONS -> List.of(contributionsSheet(cooperativeId, request));
            case SPECIAL_CONTRIBUTIONS -> List.of(specialContributionsSheet(cooperativeId, request));
            case LOANS -> loanReportSheets(cooperativeId, request);
            case REPAYMENTS -> List.of(repaymentsSheet(cooperativeId, request));
            case FINES -> List.of(finesSheet(cooperativeId, request));
            case FINE_PAYMENTS -> List.of(finePaymentsSheet(cooperativeId, request));
            case SOCIAL_FUND -> socialFundSheets(cooperativeId, request);
            case INVESTMENTS -> List.of(investmentsSheet(cooperativeId, request));
            case INCOME -> List.of(incomeExpenseSheet(cooperativeId, request, true));
            case EXPENSES -> List.of(incomeExpenseSheet(cooperativeId, request, false));
            case PAYOUTS -> payoutSheets(cooperativeId, request);
            case FINANCIAL_LEDGER -> List.of(ledgerSheet(cooperativeId, request));
            case AUDIT_LOGS -> List.of(auditSheet(cooperativeId, request));
            case FULL_FINANCIAL ->
                    throw new ValidationException("FULL_FINANCIAL uses the specialized assembler path");
        };
    }

    private ReportSheetData membersSheet(UUID cooperativeId, ReportExportRequest request) {
        String statusFilter = StringUtils.hasText(request.getStatus()) ? request.getStatus().trim() : null;
        List<CooperativeMembership> memberships = statusFilter == null
                ? membershipRepository.searchByCooperative(cooperativeId, null, null, Pageable.unpaged())
                        .getContent()
                : membershipRepository.findByCooperativeIdAndMembershipStatus(cooperativeId, statusFilter);

        Map<UUID, User> users = loadUsers(
                memberships.stream().map(CooperativeMembership::getUserId).toList());
        ShareValuationResponse valuation = shareValuationService.calculate(cooperativeId);
        Instant periodFrom = periodStart(request.getFromDate());
        Instant periodTo = periodEnd(request.getToDate());
        List<List<Object>> rows = new ArrayList<>();
        int additionalTotal = 0;
        Map<UUID, rw.terimbere.csams.modules.membership.entity.MemberOpeningBalance> openingByMember = new java.util.HashMap<>();
        openingBalanceRepository.findByCooperativeId(cooperativeId).forEach(b -> openingByMember.put(b.getMemberUserId(), b));
        BigDecimal openingContributionTotal = MoneyUtils.scale(BigDecimal.ZERO);
        BigDecimal openingCapitalTotal = MoneyUtils.scale(BigDecimal.ZERO);
        for (CooperativeMembership m : memberships) {
            User u = users.get(m.getUserId());
            if (u == null) {
                continue;
            }
            int owned = ShareAmountCalculator.ownedShareCount(m.getShareCount());
            // Additional Shares = approved SharePurchase rows reviewed in-period only.
            // Historical/imported share_count is an opening balance, not Additional Shares.
            int additional = toInt(sharePurchaseRepository.sumApprovedSharesInPeriod(
                    cooperativeId, m.getUserId(), periodFrom, periodTo));
            int afterPeriod = toInt(sharePurchaseRepository.sumApprovedSharesAfter(
                    cooperativeId, m.getUserId(), periodTo));
            int sharesHeldEnd = Math.max(0, owned - afterPeriod);
            int sharesAtStart = Math.max(0, sharesHeldEnd - additional);
            additionalTotal += additional;
            var openingBalance = openingByMember.get(m.getUserId());
            BigDecimal openingContribution = openingBalance == null
                    ? MoneyUtils.scale(BigDecimal.ZERO)
                    : MoneyUtils.scale(openingBalance.getOpeningContributionAmount());
            BigDecimal openingCapital = openingBalance == null
                    ? MoneyUtils.scale(BigDecimal.ZERO)
                    : MoneyUtils.scale(openingBalance.getOpeningShareCapitalAmount());
            openingContributionTotal = MoneyUtils.add(openingContributionTotal, openingContribution);
            openingCapitalTotal = MoneyUtils.add(openingCapitalTotal, openingCapital);
            BigDecimal memberCurrentOwnershipValue =
                    MoneyUtils.multiply(valuation.getCurrentShareValue(), BigDecimal.valueOf(sharesHeldEnd));
            rows.add(cells(
                    nullToEmpty(u.getUsername()),
                    nullToEmpty(u.getFullName()),
                    nullToEmpty(u.getEmail()),
                    nullToEmpty(u.getPhone()),
                    nullToEmpty(m.getMembershipStatus()),
                    nullToEmpty(m.getRoleInCooperative()),
                    m.getMembershipDate(),
                    sharesAtStart,
                    additional,
                    sharesHeldEnd,
                    MoneyUtils.scale(valuation.getCurrentShareValue()),
                    memberCurrentOwnershipValue,
                    openingContribution,
                    openingCapital));
        }
        return ReportSheetData.builder()
                .sheetName("Members")
                .headers(List.of(
                        "Username",
                        "Full Name",
                        "Email",
                        "Phone",
                        "Status",
                        "Role",
                        "Membership Date",
                        "Shares at Start of Period",
                        "Additional Shares Purchased During Period",
                        "Shares Held at End of Period",
                        "Current Share Value",
                        "Member Current Ownership Value",
                        "Opening Contributions (through cutover)",
                        "Opening Share Capital (through cutover)"))
                .rows(rows)
                .totalsRow(List.of(
                        "TOTAL",
                        rows.size(),
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        additionalTotal,
                        "",
                        "",
                        "",
                        openingContributionTotal,
                        openingCapitalTotal))
                .build();
    }

    private ReportSheetData contributionsSheet(UUID cooperativeId, ReportExportRequest request) {
        ContributionStatus status = parseEnum(ContributionStatus.class, request.getStatus());
        List<Contribution> list = contributionRepository
                .search(
                        cooperativeId,
                        request.getMemberUserId(),
                        request.getYear(),
                        request.getMonth(),
                        status,
                        request.getFromDate(),
                        request.getToDate(),
                        Pageable.unpaged())
                .getContent();
        Map<UUID, String> names = loadMemberNames(
                list.stream().map(Contribution::getMemberUserId).distinct().toList());
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal paidTotal = BigDecimal.ZERO;
        BigDecimal expectedTotal = BigDecimal.ZERO;
        for (Contribution c : list) {
            paidTotal = MoneyUtils.add(paidTotal, nvl(c.getPaidAmount()));
            expectedTotal = MoneyUtils.add(expectedTotal, nvl(c.getExpectedAmount()));
            rows.add(cells(
                    names.getOrDefault(c.getMemberUserId(), ""),
                    c.getYear(),
                    c.getMonth(),
                    MoneyUtils.scale(nvl(c.getExpectedAmount())),
                    MoneyUtils.scale(nvl(c.getPaidAmount())),
                    MoneyUtils.scale(nvl(c.getOutstandingAmount())),
                    c.getStatus() == null ? "" : c.getStatus().name(),
                    c.getPaymentDate(),
                    nullToEmpty(c.getPaymentReference()),
                    nullToEmpty(c.getNotes())));
        }
        return ReportSheetData.builder()
                .sheetName("Contributions")
                .headers(List.of(
                        "Member",
                        "Year",
                        "Month",
                        "Expected",
                        "Paid",
                        "Outstanding",
                        "Status",
                        "Payment Date",
                        "Reference",
                        "Notes"))
                .rows(rows)
                .totalsRow(List.of(
                        "TOTAL",
                        "",
                        "",
                        MoneyUtils.scale(expectedTotal),
                        MoneyUtils.scale(paidTotal),
                        "",
                        "",
                        "",
                        "",
                        ""))
                .build();
    }

    private ReportSheetData specialContributionsSheet(UUID cooperativeId, ReportExportRequest request) {
        SpecialContributionStatus status = parseEnum(SpecialContributionStatus.class, request.getStatus());
        List<SpecialContribution> list = specialContributionRepository.findFiltered(
                cooperativeId, request.getMemberUserId(), status, request.getFromDate(), request.getToDate());
        Map<UUID, String> names = loadMemberNames(
                list.stream().map(SpecialContribution::getMemberUserId).distinct().toList());
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SpecialContribution s : list) {
            total = MoneyUtils.add(total, s.getAmount());
            rows.add(cells(
                    names.getOrDefault(s.getMemberUserId(), ""),
                    s.getCampaignId() == null ? "" : s.getCampaignId().toString(),
                    MoneyUtils.scale(s.getAmount()),
                    s.getContributionDate(),
                    s.getStatus() == null ? "" : s.getStatus().name(),
                    nullToEmpty(s.getPaymentReference()),
                    nullToEmpty(s.getNotes())));
        }
        return ReportSheetData.builder()
                .sheetName("Special Contributions")
                .headers(List.of(
                        "Member", "Campaign Id", "Amount", "Date", "Status", "Reference", "Notes"))
                .rows(rows)
                .totalsRow(List.of("TOTAL", "", MoneyUtils.scale(total), "", "", "", ""))
                .build();
    }

    private List<ReportSheetData> loanReportSheets(UUID cooperativeId, ReportExportRequest request) {
        List<Loan> loans = loadLoans(cooperativeId, request);
        Map<UUID, String> names = loadMemberNames(
                loans.stream().map(Loan::getMemberUserId).distinct().toList());
        return List.of(loansSheet(loans, names), loanInstallmentsSheet(loans, names));
    }

    private List<Loan> loadLoans(UUID cooperativeId, ReportExportRequest request) {
        LoanStatus status = parseEnum(LoanStatus.class, request.getStatus());
        List<Loan> loans;
        if (request.getMemberUserId() != null && status != null) {
            loans = loanRepository
                    .findByCooperativeIdAndMemberUserIdAndStatus(
                            cooperativeId, request.getMemberUserId(), status, Pageable.unpaged())
                    .getContent();
        } else if (request.getMemberUserId() != null) {
            loans = loanRepository
                    .findByCooperativeIdAndMemberUserId(cooperativeId, request.getMemberUserId(), Pageable.unpaged())
                    .getContent();
        } else if (status != null) {
            loans = loanRepository
                    .findByCooperativeIdAndStatus(cooperativeId, status, Pageable.unpaged())
                    .getContent();
        } else {
            loans = loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent();
        }
        if (request.getFromDate() != null || request.getToDate() != null) {
            loans = loans.stream()
                    .filter(l -> inRange(l.getRequestDate(), request.getFromDate(), request.getToDate()))
                    .toList();
        }
        return loans;
    }

    private ReportSheetData loansSheet(List<Loan> loans, Map<UUID, String> names) {
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal principalTotal = BigDecimal.ZERO;
        BigDecimal outstandingTotal = BigDecimal.ZERO;
        for (Loan loan : loans) {
            principalTotal = MoneyUtils.add(principalTotal, nvl(loan.getPrincipalAmount()));
            outstandingTotal = MoneyUtils.add(outstandingTotal, nvl(loan.getOutstandingPrincipal()));
            BigDecimal originalPrincipal = nvl(loan.getPrincipalAmount()).compareTo(BigDecimal.ZERO) > 0
                    ? nvl(loan.getPrincipalAmount())
                    : nvl(loan.getApprovedAmount()).compareTo(BigDecimal.ZERO) > 0
                            ? nvl(loan.getApprovedAmount())
                            : nvl(loan.getRequestedAmount());
            BigDecimal totalInterest = nvl(loan.getInterestAmount());
            BigDecimal totalRepayment = originalPrincipal.compareTo(BigDecimal.ZERO) > 0
                    ? MoneyUtils.add(originalPrincipal, totalInterest)
                    : BigDecimal.ZERO;
            rows.add(cells(
                    names.getOrDefault(loan.getMemberUserId(), ""),
                    MoneyUtils.scale(nvl(loan.getRequestedAmount())),
                    MoneyUtils.scale(nvl(loan.getApprovedAmount())),
                    MoneyUtils.scale(originalPrincipal),
                    MoneyUtils.scale(nvl(loan.getInterestRatePercent())),
                    loan.getTermMonths(),
                    loan.getRepaymentDateModel() == null ? "" : loan.getRepaymentDateModel().name(),
                    loan.isProrataEnabled() ? "Yes" : "No",
                    loan.getFirstPeriodDays() == null ? "" : loan.getFirstPeriodDays(),
                    MoneyUtils.scale(totalInterest),
                    MoneyUtils.scale(totalRepayment),
                    loan.getEqualInstallmentAmount() == null
                            ? ""
                            : MoneyUtils.scale(loan.getEqualInstallmentAmount()),
                    MoneyUtils.scale(nvl(loan.getOutstandingPrincipal())),
                    MoneyUtils.scale(nvl(loan.getOutstandingInterest())),
                    loan.getStatus() == null ? "" : loan.getStatus().name(),
                    loan.getRequestDate(),
                    loan.getDisbursementDate(),
                    loan.getDueDate(),
                    nullToEmpty(loan.getPurpose())));
        }
        return ReportSheetData.builder()
                .sheetName("Loans")
                .headers(List.of(
                        "Member",
                        "Requested",
                        "Approved",
                        "Original Principal",
                        "Monthly Flat Interest Rate",
                        "Repayment Term",
                        "Repayment Date Model",
                        "Prorata",
                        "First Period Days",
                        "Total Interest",
                        "Total Repayment",
                        "Equal Installment Amount",
                        "Outstanding Principal",
                        "Outstanding Interest",
                        "Status",
                        "Request Date",
                        "Disbursement Date",
                        "Due Date",
                        "Purpose"))
                .rows(rows)
                .totalsRow(List.of(
                        "TOTAL",
                        "",
                        "",
                        MoneyUtils.scale(principalTotal),
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        MoneyUtils.scale(outstandingTotal),
                        "",
                        "",
                        "",
                        "",
                        "",
                        ""))
                .build();
    }

    private ReportSheetData loanInstallmentsSheet(List<Loan> loans, Map<UUID, String> names) {
        List<List<Object>> rows = new ArrayList<>();
        for (Loan loan : loans) {
            List<LoanInstallment> persisted = loanInstallmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loan.getId());
            String memberName = names.getOrDefault(loan.getMemberUserId(), "");
            if (!persisted.isEmpty()) {
                for (LoanInstallment installment : persisted) {
                    installment.refreshStatus(LocalDate.now());
                    rows.add(cells(
                            memberName,
                            loan.getId(),
                            installment.getInstallmentNumber(),
                            installment.getDueDate(),
                            installment.getOpeningPrincipalBalance(),
                            installment.getPrincipalDue(),
                            installment.getInterestDue(),
                            installment.getPenaltyDue(),
                            installment.totalDue(),
                            installment.getPrincipalPaid(),
                            installment.getInterestPaid(),
                            installment.getPenaltyPaid(),
                            installment.totalPaid(),
                            installment.remainingAmount(),
                            installment.getStatus() == null ? "" : installment.getStatus().name()));
                }
                continue;
            }
            if (loan.getEqualInstallmentAmount() == null || loan.getDisbursementDate() == null) {
                continue;
            }
            BigDecimal principal = nvl(loan.getPrincipalAmount()).compareTo(BigDecimal.ZERO) > 0
                    ? nvl(loan.getPrincipalAmount())
                    : nvl(loan.getApprovedAmount());
            if (principal.compareTo(BigDecimal.ZERO) <= 0 || loan.getTermMonths() <= 0) {
                continue;
            }
            LoanScheduleResponse schedule = LoanScheduleCalculator.calculate(
                    principal,
                    loan.getInterestRatePercent(),
                    loan.getTermMonths(),
                    loan.getRepaymentDateModel(),
                    loan.getDisbursementDate());
            for (LoanInstallmentResponse installment : schedule.getInstallments()) {
                rows.add(cells(
                        memberName,
                        loan.getId(),
                        installment.getInstallmentNumber(),
                        installment.getDueDate(),
                        installment.getOpeningPrincipalBalance(),
                        installment.getPrincipalComponent(),
                        installment.getInterestComponent(),
                        installment.getPenaltyDue(),
                        installment.getPaymentAmount(),
                        installment.getPrincipalPaid(),
                        installment.getInterestPaid(),
                        installment.getPenaltyPaid(),
                        installment.getAmountPaid(),
                        installment.getRemainingAmount(),
                        installment.getStatus() == null ? "" : installment.getStatus().name()));
            }
        }
        return ReportSheetData.builder()
                .sheetName("Loan Installments")
                .headers(List.of(
                        "Member",
                        "Loan Id",
                        "Installment",
                        "Due Date",
                        "Opening Balance",
                        "Principal Due",
                        "Interest Due",
                        "Penalty Due",
                        "Total Due",
                        "Principal Paid",
                        "Interest Paid",
                        "Penalty Paid",
                        "Total Paid",
                        "Remaining",
                        "Status"))
                .rows(rows)
                .build();
    }

    private ReportSheetData repaymentsSheet(UUID cooperativeId, ReportExportRequest request) {
        List<LoanRepayment> list = loanRepaymentRepository.findFiltered(
                cooperativeId, request.getMemberUserId(), request.getFromDate(), request.getToDate());
        Map<UUID, String> names = loadMemberNames(
                list.stream().map(LoanRepayment::getMemberUserId).distinct().toList());
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LoanRepayment r : list) {
            total = MoneyUtils.add(total, r.getAmountTotal());
            rows.add(cells(
                    names.getOrDefault(r.getMemberUserId(), ""),
                    r.getLoanId() == null ? "" : r.getLoanId().toString(),
                    r.getPaymentDate(),
                    MoneyUtils.scale(r.getAmountTotal()),
                    MoneyUtils.scale(r.getPrincipalPortion()),
                    MoneyUtils.scale(r.getInterestPortion()),
                    nullToEmpty(r.getPaymentReference()),
                    nullToEmpty(r.getNotes())));
        }
        return ReportSheetData.builder()
                .sheetName("Repayments")
                .headers(List.of(
                        "Member",
                        "Loan Id",
                        "Payment Date",
                        "Total",
                        "Principal",
                        "Interest",
                        "Reference",
                        "Notes"))
                .rows(rows)
                .totalsRow(List.of("TOTAL", "", "", MoneyUtils.scale(total), "", "", "", ""))
                .build();
    }

    private ReportSheetData finesSheet(UUID cooperativeId, ReportExportRequest request) {
        FineStatus status = parseEnum(FineStatus.class, request.getStatus());
        List<Fine> fines;
        if (request.getMemberUserId() != null && status != null) {
            fines = fineRepository
                    .findByCooperativeIdAndMemberUserIdAndStatus(
                            cooperativeId, request.getMemberUserId(), status, Pageable.unpaged())
                    .getContent();
        } else if (request.getMemberUserId() != null) {
            fines = fineRepository
                    .findByCooperativeIdAndMemberUserId(cooperativeId, request.getMemberUserId(), Pageable.unpaged())
                    .getContent();
        } else if (status != null) {
            fines = fineRepository
                    .findByCooperativeIdAndStatus(cooperativeId, status, Pageable.unpaged())
                    .getContent();
        } else {
            fines = fineRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent();
        }
        if (request.getFromDate() != null || request.getToDate() != null) {
            fines = fines.stream()
                    .filter(f -> inRange(f.getIssuedDate(), request.getFromDate(), request.getToDate()))
                    .toList();
        }
        Map<UUID, String> names = loadMemberNames(
                fines.stream().map(Fine::getMemberUserId).distinct().toList());
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (Fine fine : fines) {
            total = MoneyUtils.add(total, fine.getTotalAmount());
            outstanding = MoneyUtils.add(outstanding, fine.getOutstandingAmount());
            rows.add(cells(
                    names.getOrDefault(fine.getMemberUserId(), ""),
                    fine.getFineType() == null ? "" : fine.getFineType().name(),
                    MoneyUtils.scale(fine.getTotalAmount()),
                    MoneyUtils.scale(fine.getPaidAmount()),
                    MoneyUtils.scale(fine.getOutstandingAmount()),
                    fine.getStatus() == null ? "" : fine.getStatus().name(),
                    fine.getIssuedDate(),
                    fine.getDueDate(),
                    nullToEmpty(fine.getReason())));
        }
        return ReportSheetData.builder()
                .sheetName("Fines")
                .headers(List.of(
                        "Member",
                        "Type",
                        "Total",
                        "Paid",
                        "Outstanding",
                        "Status",
                        "Issued Date",
                        "Due Date",
                        "Reason"))
                .rows(rows)
                .totalsRow(List.of(
                        "TOTAL",
                        "",
                        MoneyUtils.scale(total),
                        "",
                        MoneyUtils.scale(outstanding),
                        "",
                        "",
                        "",
                        ""))
                .build();
    }

    private ReportSheetData finePaymentsSheet(UUID cooperativeId, ReportExportRequest request) {
        FinePaymentStatus status = parseEnum(FinePaymentStatus.class, request.getStatus());
        List<FinePayment> list = finePaymentRepository.findFiltered(
                cooperativeId, request.getMemberUserId(), status, request.getFromDate(), request.getToDate());
        Map<UUID, String> names = loadMemberNames(
                list.stream().map(FinePayment::getMemberUserId).distinct().toList());
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (FinePayment p : list) {
            total = MoneyUtils.add(total, p.getAmount());
            rows.add(cells(
                    names.getOrDefault(p.getMemberUserId(), ""),
                    p.getFineId() == null ? "" : p.getFineId().toString(),
                    MoneyUtils.scale(p.getAmount()),
                    p.getPaymentDate(),
                    p.getStatus() == null ? "" : p.getStatus().name(),
                    nullToEmpty(p.getPaymentReference()),
                    nullToEmpty(p.getNotes())));
        }
        return ReportSheetData.builder()
                .sheetName("Fine Payments")
                .headers(List.of(
                        "Member", "Fine Id", "Amount", "Payment Date", "Status", "Reference", "Notes"))
                .rows(rows)
                .totalsRow(List.of("TOTAL", "", MoneyUtils.scale(total), "", "", "", ""))
                .build();
    }

    private List<ReportSheetData> socialFundSheets(UUID cooperativeId, ReportExportRequest request) {
        SocialContributionStatus cStatus = parseEnum(SocialContributionStatus.class, request.getStatus());
        List<SocialContribution> contributions;
        if (request.getMemberUserId() != null && cStatus != null) {
            contributions = socialContributionRepository
                    .findByCooperativeIdAndMemberUserIdAndStatus(
                            cooperativeId, request.getMemberUserId(), cStatus, Pageable.unpaged())
                    .getContent();
        } else if (request.getMemberUserId() != null) {
            contributions = socialContributionRepository
                    .findByCooperativeIdAndMemberUserId(
                            cooperativeId, request.getMemberUserId(), Pageable.unpaged())
                    .getContent();
        } else if (cStatus != null) {
            contributions = socialContributionRepository
                    .findByCooperativeIdAndStatus(cooperativeId, cStatus, Pageable.unpaged())
                    .getContent();
        } else {
            contributions = socialContributionRepository
                    .findByCooperativeId(cooperativeId, Pageable.unpaged())
                    .getContent();
        }
        if (request.getFromDate() != null || request.getToDate() != null) {
            contributions = contributions.stream()
                    .filter(c -> inRange(c.getContributionDate(), request.getFromDate(), request.getToDate()))
                    .toList();
        }

        SocialDisbursementStatus dStatus = parseEnum(SocialDisbursementStatus.class, request.getStatus());
        List<SocialDisbursement> disbursements;
        if (request.getMemberUserId() != null && dStatus != null) {
            disbursements = socialDisbursementRepository
                    .findByCooperativeIdAndBeneficiaryMemberUserIdAndStatus(
                            cooperativeId, request.getMemberUserId(), dStatus, Pageable.unpaged())
                    .getContent();
        } else if (request.getMemberUserId() != null) {
            disbursements = socialDisbursementRepository
                    .findByCooperativeIdAndBeneficiaryMemberUserId(
                            cooperativeId, request.getMemberUserId(), Pageable.unpaged())
                    .getContent();
        } else if (dStatus != null) {
            disbursements = socialDisbursementRepository
                    .findByCooperativeIdAndStatus(cooperativeId, dStatus, Pageable.unpaged())
                    .getContent();
        } else {
            disbursements = socialDisbursementRepository
                    .findByCooperativeId(cooperativeId, Pageable.unpaged())
                    .getContent();
        }
        if (request.getFromDate() != null || request.getToDate() != null) {
            disbursements = disbursements.stream()
                    .filter(d -> inRange(d.getDisbursementDate(), request.getFromDate(), request.getToDate()))
                    .toList();
        }

        Map<UUID, String> names = loadMemberNames(new ArrayList<>(
                contributions.stream().map(SocialContribution::getMemberUserId).collect(Collectors.toSet())));
        names.putAll(loadMemberNames(disbursements.stream()
                .map(SocialDisbursement::getBeneficiaryMemberUserId)
                .distinct()
                .toList()));

        List<List<Object>> cRows = new ArrayList<>();
        BigDecimal cTotal = BigDecimal.ZERO;
        for (SocialContribution c : contributions) {
            cTotal = MoneyUtils.add(cTotal, c.getAmount());
            cRows.add(cells(
                    names.getOrDefault(c.getMemberUserId(), ""),
                    MoneyUtils.scale(c.getAmount()),
                    c.getContributionDate(),
                    c.getStatus() == null ? "" : c.getStatus().name(),
                    nullToEmpty(c.getPaymentReference()),
                    nullToEmpty(c.getNotes())));
        }

        List<List<Object>> dRows = new ArrayList<>();
        BigDecimal dTotal = BigDecimal.ZERO;
        for (SocialDisbursement d : disbursements) {
            dTotal = MoneyUtils.add(dTotal, d.getAmount());
            dRows.add(cells(
                    names.getOrDefault(d.getBeneficiaryMemberUserId(), ""),
                    MoneyUtils.scale(d.getAmount()),
                    d.getDisbursementDate(),
                    d.getStatus() == null ? "" : d.getStatus().name(),
                    nullToEmpty(d.getReason()),
                    nullToEmpty(d.getNotes())));
        }

        return List.of(
                ReportSheetData.builder()
                        .sheetName("Social Contributions")
                        .headers(List.of("Member", "Amount", "Date", "Status", "Reference", "Notes"))
                        .rows(cRows)
                        .totalsRow(List.of("TOTAL", MoneyUtils.scale(cTotal), "", "", "", ""))
                        .build(),
                ReportSheetData.builder()
                        .sheetName("Social Disbursements")
                        .headers(List.of("Beneficiary", "Amount", "Date", "Status", "Reason", "Notes"))
                        .rows(dRows)
                        .totalsRow(List.of("TOTAL", MoneyUtils.scale(dTotal), "", "", "", ""))
                        .build());
    }

    private ReportSheetData investmentsSheet(UUID cooperativeId, ReportExportRequest request) {
        InvestmentStatus status = parseEnum(InvestmentStatus.class, request.getStatus());
        List<Investment> investments = status == null
                ? investmentRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent()
                : investmentRepository
                        .findByCooperativeIdAndStatus(cooperativeId, status, Pageable.unpaged())
                        .getContent();
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal amountTotal = BigDecimal.ZERO;
        BigDecimal remainingTotal = BigDecimal.ZERO;
        for (Investment inv : investments) {
            LocalDate filterDate = inv.getActivatedAt() == null
                    ? null
                    : inv.getActivatedAt().atZone(ReportTimelineValidator.ZONE).toLocalDate();
            if (!inRange(filterDate, request.getFromDate(), request.getToDate())) {
                continue;
            }
            amountTotal = MoneyUtils.add(amountTotal, nvl(inv.getAmount()));
            remainingTotal = MoneyUtils.add(remainingTotal, nvl(inv.getRemainingCapital()));
            rows.add(cells(
                    nullToEmpty(inv.getName()),
                    MoneyUtils.scale(nvl(inv.getAmount())),
                    MoneyUtils.scale(nvl(inv.getRemainingCapital())),
                    MoneyUtils.scale(nvl(inv.getTotalCapitalReturned())),
                    MoneyUtils.scale(nvl(inv.getTotalProfitReturned())),
                    inv.getStatus() == null ? "" : inv.getStatus().name(),
                    inv.getExpectedReturnDate(),
                    nullToEmpty(inv.getDescription())));
        }
        return ReportSheetData.builder()
                .sheetName("Investments")
                .headers(List.of(
                        "Name",
                        "Amount",
                        "Remaining Capital",
                        "Capital Returned",
                        "Profit Returned",
                        "Status",
                        "Expected Return Date",
                        "Description"))
                .rows(rows)
                .totalsRow(List.of(
                        "TOTAL",
                        MoneyUtils.scale(amountTotal),
                        MoneyUtils.scale(remainingTotal),
                        "",
                        "",
                        "",
                        "",
                        ""))
                .build();
    }

    private ReportSheetData incomeExpenseSheet(
            UUID cooperativeId, ReportExportRequest request, boolean income) {
        IncomeExpenseCategory categoryFilter = parseEnum(IncomeExpenseCategory.class, request.getStatus());
        var approvalStatus = parseEnum(
                rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseApprovalStatus.class,
                // reuse status field for approval status when not a category
                categoryFilter == null ? request.getStatus() : null);

        List<IncomeExpenseTransaction> all = incomeExpenseRepository
                .findFiltered(
                        cooperativeId,
                        categoryFilter,
                        approvalStatus,
                        request.getFromDate(),
                        request.getToDate(),
                        Pageable.unpaged())
                .getContent();

        List<IncomeExpenseTransaction> filtered = all.stream()
                .filter(t -> income
                        ? t.getCategory() == IncomeExpenseCategory.OTHER_INCOME
                                || (t.getCategory() == IncomeExpenseCategory.ADJUSTMENT
                                        && t.getLedgerEffect() != null
                                        && t.getLedgerEffect().name().contains("CREDIT"))
                        : t.getCategory() == IncomeExpenseCategory.GENERAL_EXPENSE
                                || t.getCategory() == IncomeExpenseCategory.INTEREST_EXPENSE
                                || (t.getCategory() == IncomeExpenseCategory.ADJUSTMENT
                                        && t.getLedgerEffect() != null
                                        && t.getLedgerEffect().name().contains("DEBIT")))
                .toList();

        // If no category/status filter and income/expense toggle: include all matching categories simply
        if (categoryFilter == null && !StringUtils.hasText(request.getStatus())) {
            filtered = all.stream()
                    .filter(t -> income
                            ? t.getCategory() == IncomeExpenseCategory.OTHER_INCOME
                            : t.getCategory() == IncomeExpenseCategory.GENERAL_EXPENSE
                                    || t.getCategory() == IncomeExpenseCategory.INTEREST_EXPENSE)
                    .toList();
        } else if (categoryFilter != null) {
            filtered = all;
        }

        List<List<Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (IncomeExpenseTransaction t : filtered) {
            total = MoneyUtils.add(total, t.getAmount());
            rows.add(cells(
                    t.getCategory() == null ? "" : t.getCategory().name(),
                    MoneyUtils.scale(t.getAmount()),
                    t.getTransactionDate(),
                    t.getApprovalStatus() == null ? "" : t.getApprovalStatus().name(),
                    nullToEmpty(t.getReference()),
                    nullToEmpty(t.getDescription()),
                    nullToEmpty(t.getNotes())));
        }
        return ReportSheetData.builder()
                .sheetName(income ? "Income" : "Expenses")
                .headers(List.of(
                        "Category", "Amount", "Date", "Approval Status", "Reference", "Description", "Notes"))
                .rows(rows)
                .totalsRow(List.of("TOTAL", MoneyUtils.scale(total), "", "", "", "", ""))
                .build();
    }

    private List<ReportSheetData> payoutSheets(UUID cooperativeId, ReportExportRequest request) {
        PayoutRunStatus status = parseEnum(PayoutRunStatus.class, request.getStatus());
        List<PayoutRun> runs = status == null
                ? payoutRunRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent()
                : payoutRunRepository
                        .findByCooperativeIdAndStatus(cooperativeId, status, Pageable.unpaged())
                        .getContent();
        if (request.getFromDate() != null || request.getToDate() != null) {
            runs = runs.stream()
                    .filter(r -> overlaps(r.getPeriodFrom(), r.getPeriodTo(), request.getFromDate(), request.getToDate()))
                    .toList();
        }

        List<List<Object>> runRows = new ArrayList<>();
        BigDecimal poolTotal = BigDecimal.ZERO;
        for (PayoutRun run : runs) {
            poolTotal = MoneyUtils.add(poolTotal, run.getPayoutPoolAmount());
            runRows.add(cells(
                    run.getId() == null ? "" : run.getId().toString(),
                    nullToEmpty(run.getName()),
                    run.getPeriodFrom(),
                    run.getPeriodTo(),
                    MoneyUtils.scale(run.getPayoutPoolAmount()),
                    MoneyUtils.scale(run.getTotalEligibleContributions()),
                    run.getStatus() == null ? "" : run.getStatus().name(),
                    run.getConfirmedAt(),
                    run.getPaidAt()));
        }

        List<List<Object>> lineRows = new ArrayList<>();
        BigDecimal lineTotal = BigDecimal.ZERO;
        for (PayoutRun run : runs) {
            List<PayoutLine> lines =
                    payoutLineRepository.findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(
                            run.getId(), cooperativeId);
            if (request.getMemberUserId() != null) {
                lines = lines.stream()
                        .filter(l -> request.getMemberUserId().equals(l.getMemberUserId()))
                        .toList();
            }
            Map<UUID, String> names = loadMemberNames(
                    lines.stream().map(PayoutLine::getMemberUserId).distinct().toList());
            for (PayoutLine line : lines) {
                lineTotal = MoneyUtils.add(lineTotal, line.getPayoutAmount());
                lineRows.add(cells(
                        run.getId() == null ? "" : run.getId().toString(),
                        names.getOrDefault(line.getMemberUserId(), ""),
                        MoneyUtils.scale(line.getEligibleContributionAmount()),
                        line.getPercentage(),
                        MoneyUtils.scale(line.getPayoutAmount()),
                        line.getStatus() == null ? "" : line.getStatus().name()));
            }
        }

        return List.of(
                ReportSheetData.builder()
                        .sheetName("Payout Runs")
                        .headers(List.of(
                                "Run Id",
                                "Name",
                                "Period From",
                                "Period To",
                                "Pool Amount",
                                "Eligible Contributions",
                                "Status",
                                "Confirmed At",
                                "Paid At"))
                        .rows(runRows)
                        .totalsRow(List.of(
                                "TOTAL", "", "", "", MoneyUtils.scale(poolTotal), "", "", "", ""))
                        .build(),
                ReportSheetData.builder()
                        .sheetName("Payout Lines")
                        .headers(List.of(
                                "Run Id",
                                "Member",
                                "Eligible",
                                "Percentage",
                                "Payout Amount",
                                "Status"))
                        .rows(lineRows)
                        .totalsRow(List.of("TOTAL", "", "", "", MoneyUtils.scale(lineTotal), ""))
                        .build());
    }

    private ReportSheetData ledgerSheet(UUID cooperativeId, ReportExportRequest request) {
        List<LedgerEntry> entries = ledgerEntryRepository
                .findFiltered(
                        cooperativeId,
                        request.getTransactionType(),
                        request.getFromDate(),
                        request.getToDate(),
                        request.getMemberUserId(),
                        null,
                        Pageable.unpaged())
                .getContent();
        if (StringUtils.hasText(request.getStatus())) {
            String status = request.getStatus().trim().toUpperCase(Locale.ROOT);
            entries = entries.stream()
                    .filter(e -> e.getStatus() != null && e.getStatus().name().equals(status))
                    .toList();
        }
        Map<UUID, String> names = loadMemberNames(entries.stream()
                .map(LedgerEntry::getMemberUserId)
                .filter(id -> id != null)
                .distinct()
                .toList());
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            debitTotal = MoneyUtils.add(debitTotal, e.getDebitAmount());
            creditTotal = MoneyUtils.add(creditTotal, e.getCreditAmount());
            rows.add(cells(
                    e.getTransactionDate(),
                    e.getTransactionType() == null ? "" : e.getTransactionType().name(),
                    e.getMemberUserId() == null ? "" : names.getOrDefault(e.getMemberUserId(), ""),
                    MoneyUtils.scale(e.getDebitAmount()),
                    MoneyUtils.scale(e.getCreditAmount()),
                    e.getStatus() == null ? "" : e.getStatus().name(),
                    nullToEmpty(e.getReference()),
                    nullToEmpty(e.getDescription()),
                    nullToEmpty(e.getSourceEntityType())));
        }
        return ReportSheetData.builder()
                .sheetName("Financial Ledger")
                .headers(List.of(
                        "Date",
                        "Type",
                        "Member",
                        "Debit",
                        "Credit",
                        "Status",
                        "Reference",
                        "Description",
                        "Source"))
                .rows(rows)
                .totalsRow(List.of(
                        "TOTAL",
                        "",
                        "",
                        MoneyUtils.scale(debitTotal),
                        MoneyUtils.scale(creditTotal),
                        "",
                        "",
                        "",
                        ""))
                .build();
    }

    private ReportSheetData auditSheet(UUID cooperativeId, ReportExportRequest request) {
        Instant from = request.getFromDate() == null
                ? null
                : request.getFromDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = request.getToDate() == null
                ? null
                : request.getToDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1);
        List<AuditLog> logs = auditLogRepository.findAll(
                AuditLogSpecs.filtered(cooperativeId, null, request.getMemberUserId(), null, from, to),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Map<UUID, String> names = loadMemberNames(logs.stream()
                .map(AuditLog::getUserId)
                .filter(id -> id != null)
                .distinct()
                .toList());
        List<List<Object>> rows = new ArrayList<>();
        for (AuditLog log : logs) {
            rows.add(cells(
                    log.getCreatedAt(),
                    log.getUserId() == null ? "" : names.getOrDefault(log.getUserId(), log.getUserId().toString()),
                    nullToEmpty(log.getAction()),
                    nullToEmpty(ReportLabels.entityType(log.getEntityType())),
                    log.getEntityId() == null ? "" : log.getEntityId().toString(),
                    nullToEmpty(log.getIpAddress())));
        }
        return ReportSheetData.builder()
                .sheetName("Audit Logs")
                .headers(List.of("Created At", "User", "Action", "Entity Type", "Entity Id", "IP"))
                .rows(rows)
                .totalsRow(List.of("TOTAL", rows.size(), "", "", "", ""))
                .build();
    }

    /**
     * Obligation-period aggregates for FULL_FINANCIAL only (Phase D1; delegated).
     *
     * <p>Status rules: CANCELLED excluded; WAIVED expected counted as 0 (matches {@code
     * sumExpectedByMember}); paid/outstanding use stored amounts (waived outstanding is already 0).
     */
    List<MemberContributionAggregate> loadFullFinancialContributionAggregates(
            UUID cooperativeId, ContributionObligationPeriod.Range range) {
        return memberContributionScheduleService.loadAggregates(cooperativeId, range);
    }

    private BigDecimal sumColumn(ReportSheetData sheet, int columnIndex) {
        BigDecimal total = BigDecimal.ZERO;
        if (sheet.getRows() == null) {
            return total;
        }
        for (List<Object> row : sheet.getRows()) {
            if (row != null && row.size() > columnIndex && row.get(columnIndex) instanceof BigDecimal bd) {
                total = MoneyUtils.add(total, bd);
            } else if (row != null && row.size() > columnIndex && row.get(columnIndex) instanceof Number n) {
                total = MoneyUtils.add(total, BigDecimal.valueOf(n.doubleValue()));
            }
        }
        return total;
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private Map<UUID, User> loadUsers(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, User> map = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            if (!user.isDeleted()) {
                map.put(user.getId(), user);
            }
        }
        return map;
    }

    private Map<UUID, String> loadMemberNames(List<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        for (User user : loadUsers(userIds).values()) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    private ReportSheetData sharePurchasesSheet(UUID cooperativeId, ReportExportRequest request) {
        Instant periodFrom = periodStart(request.getFromDate());
        Instant periodTo = periodEnd(request.getToDate());
        List<SharePurchase> purchases = sharePurchaseRepository.findByCooperativeIdAndStatusAndReviewedAtBetween(
                cooperativeId, SharePurchaseStatus.APPROVED, periodFrom, periodTo);
        Map<UUID, String> names = loadMemberNames(
                purchases.stream().map(SharePurchase::getMemberUserId).distinct().toList());
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal amountTotal = BigDecimal.ZERO;
        int shareTotal = 0;
        for (SharePurchase p : purchases) {
            shareTotal += p.getNumberOfShares() == null ? 0 : p.getNumberOfShares();
            amountTotal = MoneyUtils.add(amountTotal, nvl(p.getTotalAmount()));
            rows.add(cells(
                    names.getOrDefault(p.getMemberUserId(), ""),
                    p.getNumberOfShares(),
                    MoneyUtils.scale(nvl(p.getPricePerShare())),
                    MoneyUtils.scale(nvl(p.getTotalAmount())),
                    p.getReviewedAt(),
                    p.getStatus() == null ? "" : p.getStatus().name()));
        }
        return ReportSheetData.builder()
                .sheetName("Additional Shares")
                .headers(List.of(
                        "Member",
                        "Additional Shares",
                        "Price Per Share",
                        "Amount Paid",
                        "Approved At",
                        "Status"))
                .rows(rows)
                .totalsRow(List.of("TOTAL", shareTotal, "", MoneyUtils.scale(amountTotal), "", ""))
                .build();
    }

    private static Instant periodStart(LocalDate fromDate) {
        return (fromDate == null ? LocalDate.of(1970, 1, 1) : fromDate)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }

    private static Instant periodEnd(LocalDate toDate) {
        LocalDate end = toDate == null ? LocalDate.of(2100, 12, 31) : toDate;
        return end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1);
    }

    private static int toInt(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private static String formatPeriod(ReportExportRequest request) {
        String range = request.getFromDate() + " to " + request.getToDate();
        if (request.getYear() != null && request.getMonth() != null) {
            return range
                    + " ("
                    + request.getYear()
                    + "-"
                    + String.format(Locale.ROOT, "%02d", request.getMonth())
                    + ")";
        }
        return range;
    }

    private static boolean inRange(LocalDate value, LocalDate from, LocalDate to) {
        if (value == null) {
            return false;
        }
        if (from != null && value.isBefore(from)) {
            return false;
        }
        if (to != null && value.isAfter(to)) {
            return false;
        }
        return true;
    }

    private static boolean overlaps(LocalDate periodFrom, LocalDate periodTo, LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.MIN : from;
        LocalDate end = to == null ? LocalDate.MAX : to;
        return !periodTo.isBefore(start) && !periodFrom.isAfter(end);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Row helper that allows null cells (unlike {@link List#of}). */
    private static List<Object> cells(Object... values) {
        return Arrays.asList(values);
    }

    private static String sanitizeFilename(String name) {
        if (!StringUtils.hasText(name)) {
            return "cooperative";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }

    public record ReportBinaryExport(byte[] content, String contentType, String filename) {}
}
