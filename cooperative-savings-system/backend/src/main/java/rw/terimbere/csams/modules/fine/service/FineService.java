package rw.terimbere.csams.modules.fine.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.obligation.ContributionDueDates;
import rw.terimbere.csams.modules.contribution.obligation.ContributionObligationService;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fine.dto.FineCreateRequest;
import rw.terimbere.csams.modules.fine.dto.FineGenerateRequest;
import rw.terimbere.csams.modules.fine.dto.FineGenerateResponse;
import rw.terimbere.csams.modules.fine.dto.FinePaymentCreateRequest;
import rw.terimbere.csams.modules.fine.dto.FinePaymentResponse;
import rw.terimbere.csams.modules.fine.dto.FinePaymentReviewRequest;
import rw.terimbere.csams.modules.fine.dto.FineResponse;
import rw.terimbere.csams.modules.fine.dto.FineUpdateRequest;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;
import rw.terimbere.csams.modules.fine.entity.FinePayment;
import rw.terimbere.csams.modules.fine.entity.FinePaymentMethod;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.fine.entity.FineSettings;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.entity.FineType;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.pagination.PageMapper;
import rw.terimbere.csams.shared.utilities.DateRangeValidator;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class FineService {

    private final CreditConversionService creditConversionService;

    private static final Logger log = LoggerFactory.getLogger(FineService.class);

    private final FineRepository fineRepository;
    private final FinePaymentRepository finePaymentRepository;
    private final FineSettingsService fineSettingsService;
    private final FineCalculationService fineCalculationService;
    private final ContributionRepository contributionRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final LedgerService ledgerService;
    private final FileManagementService fileManagementService;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;
    private final ContributionObligationService obligationService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public PageResponse<FineResponse> list(
            UUID cooperativeId, FineStatus status, UUID memberUserId, Pageable pageable) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);

        Page<Fine> page;
        if (status != null && memberUserId != null) {
            page = fineRepository.findByCooperativeIdAndMemberUserIdAndStatus(
                    cooperativeId, memberUserId, status, pageable);
        } else if (status != null) {
            page = fineRepository.findByCooperativeIdAndStatus(cooperativeId, status, pageable);
        } else if (memberUserId != null) {
            page = fineRepository.findByCooperativeIdAndMemberUserId(cooperativeId, memberUserId, pageable);
        } else {
            page = fineRepository.findByCooperativeId(cooperativeId, pageable);
        }
        String currency = cooperative.getCurrency();
        return PageMapper.toPageResponse(page, f -> toResponse(f, currency));
    }

    @Transactional(readOnly = true)
    public PageResponse<FineResponse> myFines(UUID cooperativeId, FineStatus status, Pageable pageable) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        return list(cooperativeId, status, principal.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public FineResponse get(UUID cooperativeId, UUID fineId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return toResponse(requireFine(cooperativeId, fineId), cooperative.getCurrency());
    }

    @Transactional(readOnly = true)
    public List<FineResponse> recentForMember(UUID cooperativeId, UUID memberUserId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        return fineRepository
                .findTop20ByCooperativeIdAndMemberUserIdOrderByIssuedDateDescCreatedAtDesc(
                        cooperativeId, memberUserId)
                .stream()
                .map(f -> toResponse(f, cooperative.getCurrency()))
                .toList();
    }

    @Transactional
    public FineResponse createManual(
            UUID cooperativeId, FineCreateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        requireActiveMember(cooperativeId, request.getMemberUserId());
        FineSettings settings = fineSettingsService.requireSettings(cooperativeId);

        FineCalculationMode mode = request.getCalculationMode() != null
                ? request.getCalculationMode()
                : settings.getFineMode();

        BigDecimal baseAmount;
        BigDecimal dailyIncrement;
        int overdueDays;
        BigDecimal totalAmount;

        if (mode == FineCalculationMode.PROGRESSIVE) {
            baseAmount = MoneyUtils.scaleForStorage(
                    request.getBaseAmount() != null ? request.getBaseAmount() : settings.getBaseFineAmount());
            dailyIncrement = MoneyUtils.scaleForStorage(
                    request.getDailyIncrement() != null
                            ? request.getDailyIncrement()
                            : settings.getDailyIncrement());
            overdueDays = request.getOverdueDays() != null ? request.getOverdueDays() : 0;
            totalAmount = MoneyUtils.scaleForStorage(
                    fineCalculationService.calculateProgressive(baseAmount, dailyIncrement, overdueDays));
            if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                // A manual fine of zero is meaningless (the FIXED branch already rejects it).
                throw new ValidationException("Progressive fine total must be greater than zero");
            }
        } else {
            if (request.getAmount() == null && request.getBaseAmount() == null) {
                throw new ValidationException("amount is required for FIXED fine mode");
            }
            BigDecimal amount = request.getAmount() != null ? request.getAmount() : request.getBaseAmount();
            totalAmount = MoneyUtils.scaleForStorage(amount);
            MoneyUtils.assertPositive(totalAmount);
            baseAmount = totalAmount;
            dailyIncrement = BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE);
            overdueDays = 0;
        }

        LocalDate issuedDate = request.getIssuedDate() == null ? LocalDate.now() : request.getIssuedDate();
        if (request.getDueDate() != null && request.getDueDate().isBefore(issuedDate)) {
            throw new ValidationException("dueDate must be on or after issuedDate");
        }

        Fine fine = Fine.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(request.getMemberUserId())
                .fineType(FineType.MANUAL)
                .calculationMode(mode)
                .sourceContributionId(null)
                .baseAmount(baseAmount)
                .dailyIncrementSnapshot(dailyIncrement)
                .overdueDays(overdueDays)
                .totalAmount(totalAmount)
                .paidAmount(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .outstandingAmount(totalAmount)
                .reason(trimToNull(request.getReason()))
                .notes(trimToNull(request.getNotes()))
                .issuedDate(issuedDate)
                .dueDate(request.getDueDate())
                .status(FineStatus.UNPAID)
                .issuedBy(principal.getId())
                .build();
        fine = fineRepository.save(fine);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.FINE_ISSUE,
                "Fine",
                fine.getId(),
                null,
                "{\"type\":\"MANUAL\",\"totalAmount\":\"" + totalAmount + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                fine.getMemberUserId(),
                cooperativeId,
                NotificationType.FINE,
                "Fine issued",
                "A fine of " + totalAmount + " was issued against your account.",
                "Fine",
                fine.getId());
        return toResponse(fine, cooperative.getCurrency());
    }

    @Transactional
    public FineGenerateResponse generateAutomatic(
            UUID cooperativeId, FineGenerateRequest request, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        Integer year = request == null ? null : request.getYear();
        Integer month = request == null ? null : request.getMonth();
        return generateAutomaticInternal(cooperativeId, year, month, principal.getId(), httpRequest);
    }

    /**
     * The scheduled job. Each Saving Scheme runs in its own transaction, so a failure in one cannot block or roll back
     * the others, and running it again is safe: a contribution is fined at most once (unique source and automatic key).
     */
    public void generateAutomaticForEnabledCooperatives() {
        for (Cooperative cooperative :
                cooperativeRepository.findAllByDeletedFalseAndStatus(CooperativeStatus.ACTIVE)) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    FineSettings settings = fineSettingsService.requireSettings(cooperative.getId());
                    if (settings.isAutoFinesEnabled()) {
                        generateAutomaticInternal(cooperative.getId(), null, null, null, null);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Automatic late fines failed for one Saving Scheme; the others continue: {}", e.getClass().getSimpleName());
            }
        }
    }

    private FineGenerateResponse generateAutomaticInternal(
            UUID cooperativeId,
            Integer year,
            Integer month,
            UUID issuedBy,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        FineSettings settings = fineSettingsService.requireSettings(cooperativeId);
        if (!settings.isAutoFinesEnabled()) {
            throw new BusinessException("Automatic fines are disabled for this cooperative");
        }

        if ((year == null) != (month == null)) {
            throw new ValidationException("year and month must both be provided or both omitted");
        }

        if (year != null) {
            ensureUnpaidPeriodRows(cooperative, year, month);
        } else {
            YearMonth current = ContributionDueDates.currentMonth(clock);
            ensureUnpaidPeriodRowsIfOverdue(cooperative, settings, current);
            ensureUnpaidPeriodRowsIfOverdue(cooperative, settings, current.minusMonths(1));
        }

        List<Contribution> candidates;
        if (year != null) {
            candidates = contributionRepository.findByCooperativeIdAndYearAndMonthAndStatusIn(
                    cooperativeId,
                    year,
                    month,
                    EnumSet.of(ContributionStatus.PENDING, ContributionStatus.PARTIALLY_PAID));
        } else {
            candidates = contributionRepository.findByCooperativeIdAndStatusIn(
                    cooperativeId, EnumSet.of(ContributionStatus.PENDING, ContributionStatus.PARTIALLY_PAID));
        }

        LocalDate today = ContributionDueDates.today(clock);
        int createdCount = 0;
        int skippedDuplicates = 0;
        int skippedNotOverdue = 0;
        List<FineResponse> created = new ArrayList<>();

        for (Contribution contribution : candidates) {
            String automaticKey = automaticSourceKey(cooperativeId, contribution.getId());
            LocalDate dueDate = dueDateOf(cooperative, contribution);
            int overdueDays = fineCalculationService.computeOverdueDays(
                    dueDate, settings.getGraceDays(), today);
            LocalDate graceEnd = dueDate.plusDays(Math.max(0, settings.getGraceDays()));
            if (!today.isAfter(graceEnd)) {
                skippedNotOverdue++;
                continue;
            }
            int shares = Math.max(1, contribution.getShareCount() == null ? 1 : contribution.getShareCount());
            createdCount += chargeCreditConversion(cooperative, settings, contribution, dueDate, today, issuedBy, created);
            if (fineRepository.existsByCooperativeIdAndSourceContributionId(cooperativeId, contribution.getId())
                    || fineRepository.existsByAutomaticSourceKey(automaticKey)) {
                raiseBandedFine(settings, contribution, overdueDays, shares);
                skippedDuplicates++;
                continue;
            }

            BigDecimal base = MoneyUtils.scaleForStorage(settings.getBaseFineAmount());
            BigDecimal dailyIncrement = MoneyUtils.scaleForStorage(settings.getDailyIncrement());
            FineCalculationMode mode = settings.getFineMode() == null
                    ? FineCalculationMode.FIXED
                    : settings.getFineMode();

            BigDecimal total;
            if (mode == FineCalculationMode.PROGRESSIVE) {
                total = MoneyUtils.scaleForStorage(
                        fineCalculationService.calculateProgressive(base, dailyIncrement, overdueDays));
            } else if (mode == FineCalculationMode.BANDED) {
                total = MoneyUtils.scaleForStorage(PenaltyBands.amountFor(settings.getPenaltyBands(), overdueDays));
                base = total;
            } else {
                total = base;
            }
            if (settings.isPerShare() && shares > 1) {
                total = MoneyUtils.scaleForStorage(total.multiply(BigDecimal.valueOf(shares)));
                base = MoneyUtils.scaleForStorage(base.multiply(BigDecimal.valueOf(shares)));
            }
            if (total.compareTo(BigDecimal.ZERO) <= 0) {
                skippedNotOverdue++;
                continue;
            }

            Fine fine = Fine.builder()
                    .cooperativeId(cooperativeId)
                    .memberUserId(contribution.getMemberUserId())
                    .fineType(FineType.AUTOMATIC)
                    .calculationMode(mode)
                    .sourceContributionId(contribution.getId())
                    .automaticSourceKey(automaticKey)
                    .baseAmount(base)
                    .dailyIncrementSnapshot(
                            mode == FineCalculationMode.PROGRESSIVE
                                    ? dailyIncrement
                                    : BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                    .overdueDays(mode == FineCalculationMode.FIXED ? 0 : overdueDays)
                    .totalAmount(total)
                    .paidAmount(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                    .outstandingAmount(total)
                    .reason("Missed contribution for " + contribution.getYear() + "-"
                            + String.format("%02d", contribution.getMonth()))
                    .issuedDate(today)
                    .dueDate(dueDate)
                    .status(FineStatus.UNPAID)
                    .issuedBy(issuedBy)
                    .build();
            fine = fineRepository.save(fine);
            createdCount++;
            created.add(toResponse(fine, cooperative.getCurrency()));

            auditService.record(
                    issuedBy,
                    cooperativeId,
                    AuditableAction.FINE_ISSUE,
                    "Fine",
                    fine.getId(),
                    null,
                    "{\"type\":\"AUTOMATIC\",\"sourceContributionId\":\"" + contribution.getId()
                            + "\",\"totalAmount\":\"" + total + "\"}",
                    clientIp(httpRequest),
                    userAgent(httpRequest));
            notificationFacade.notifyUser(
                    contribution.getMemberUserId(),
                    cooperativeId,
                    NotificationType.FINE,
                    "Late contribution fine",
                    "A fine of " + MoneyUtils.scale(total) + " was applied for missed contribution "
                            + contribution.getYear() + "-" + String.format("%02d", contribution.getMonth()) + ".",
                    "Fine",
                    fine.getId());
        }

        return FineGenerateResponse.builder()
                .createdCount(createdCount)
                .skippedDuplicates(skippedDuplicates)
                .skippedNotOverdue(skippedNotOverdue)
                .created(created)
                .build();
    }

    @Transactional
    public FineResponse updateFine(
            UUID cooperativeId, UUID fineId, FineUpdateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFineConfigurationManager(principal);

        Fine fine = requireFine(cooperativeId, fineId);
        if (fine.getStatus() == FineStatus.PAID
                || fine.getStatus() == FineStatus.WAIVED
                || fine.getStatus() == FineStatus.CANCELLED) {
            throw new BusinessException("Paid, waived, or cancelled fines cannot be edited");
        }
        if (fine.getPaidAmount() != null && fine.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Fines with approved payments cannot be edited");
        }

        String previous = "{\"totalAmount\":\"" + fine.getTotalAmount() + "\",\"status\":\"" + fine.getStatus() + "\"}";
        if (request.getAmount() != null) {
            BigDecimal amount = MoneyUtils.scaleForStorage(request.getAmount());
            MoneyUtils.assertPositive(amount);
            fine.setTotalAmount(amount);
            fine.setBaseAmount(amount);
            fine.setOutstandingAmount(amount);
        }
        if (request.getReason() != null) {
            fine.setReason(trimToNull(request.getReason()));
        }
        if (request.getNotes() != null) {
            fine.setNotes(trimToNull(request.getNotes()));
        }
        if (request.getDueDate() != null) {
            fine.setDueDate(request.getDueDate());
        }
        fine = fineRepository.save(fine);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.FINE_UPDATE,
                "Fine",
                fine.getId(),
                previous,
                "{\"totalAmount\":\"" + fine.getTotalAmount() + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(fine, cooperative.getCurrency());
    }

    @Transactional
    public FineResponse deleteFine(UUID cooperativeId, UUID fineId, HttpServletRequest httpRequest) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        CooperativeOfficerRoles.requireFineConfigurationManager(principal);
        FineResponse cancelled = cancel(cooperativeId, fineId, httpRequest);
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.FINE_DELETE,
                "Fine",
                fineId,
                "{\"status\":\"UNPAID\"}",
                "{\"status\":\"CANCELLED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return cancelled;
    }

    @Transactional
    public FineResponse waive(UUID cooperativeId, UUID fineId, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        Fine fine = requireFine(cooperativeId, fineId);
        if (fine.getStatus() == FineStatus.WAIVED || fine.getStatus() == FineStatus.CANCELLED) {
            throw new BusinessException("Fine is already " + fine.getStatus());
        }
        if (fine.getStatus() == FineStatus.PAID) {
            throw new BusinessException("Paid fines cannot be waived");
        }

        String previous = "{\"status\":\"" + fine.getStatus() + "\"}";
        fine.setStatus(FineStatus.WAIVED);
        fine = fineRepository.save(fine);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.FINE_WAIVE,
                "Fine",
                fine.getId(),
                previous,
                "{\"status\":\"WAIVED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(fine, cooperative.getCurrency());
    }

    @Transactional
    public FineResponse cancel(UUID cooperativeId, UUID fineId, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        Fine fine = requireFine(cooperativeId, fineId);
        if (fine.getStatus() == FineStatus.CANCELLED) {
            throw new BusinessException("Fine is already CANCELLED");
        }
        if (fine.getStatus() == FineStatus.PAID) {
            throw new BusinessException("Paid fines cannot be cancelled");
        }
        if (fine.getPaidAmount() != null && fine.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Fines with approved payments cannot be cancelled");
        }

        String previous = "{\"status\":\"" + fine.getStatus() + "\"}";
        fine.setStatus(FineStatus.CANCELLED);
        fine = fineRepository.save(fine);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.STATUS_CHANGE,
                "Fine",
                fine.getId(),
                previous,
                "{\"status\":\"CANCELLED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(fine, cooperative.getCurrency());
    }

    @Transactional
    public FinePaymentResponse submitPayment(
            UUID cooperativeId, UUID fineId, FinePaymentCreateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        Fine fine = requireFine(cooperativeId, fineId);
        boolean canWrite = principal.hasAuthority("FINE_WRITE")
                || principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);
        if (!canWrite && !fine.getMemberUserId().equals(principal.getId())) {
            throw new ForbiddenException("Members may only submit payments for their own fines");
        }

        if (fine.getStatus() == FineStatus.WAIVED
                || fine.getStatus() == FineStatus.CANCELLED
                || fine.getStatus() == FineStatus.PAID) {
            throw new BusinessException("Cannot submit payment for fine in status " + fine.getStatus());
        }

        BigDecimal amount = MoneyUtils.scaleForStorage(request.getAmount());
        MoneyUtils.assertPositive(amount);

        BigDecimal outstanding = MoneyUtils.scaleForStorage(fine.getOutstandingAmount());
        BigDecimal pending = MoneyUtils.scaleForStorage(
                finePaymentRepository.sumPendingAmountByFineId(fineId) == null
                        ? BigDecimal.ZERO
                        : finePaymentRepository.sumPendingAmountByFineId(fineId));
        BigDecimal available = MoneyUtils.scaleForStorage(outstanding.subtract(pending));
        if (amount.compareTo(available) > 0) {
            throw new BusinessException("Payment amount exceeds outstanding fine balance");
        }

        if (request.getPaymentMethod() == null) {
            throw new ValidationException("paymentMethod is required");
        }
        if (StringUtils.hasText(request.getEvidenceFileKey())) {
            fileManagementService.requireOwnedFile(request.getEvidenceFileKey().trim(), cooperativeId);
        }

        FinePayment payment = FinePayment.builder()
                .fineId(fineId)
                .cooperativeId(cooperativeId)
                .memberUserId(fine.getMemberUserId())
                .amount(amount)
                .paymentDate(request.getPaymentDate())
                .paymentReference(trimToNull(request.getPaymentReference()))
                .paymentMethod(request.getPaymentMethod())
                .paymentMethodDetail(trimToNull(request.getPaymentMethodDetail()))
                .notes(trimToNull(request.getNotes()))
                .evidenceFileKey(trimToNull(request.getEvidenceFileKey()))
                .status(FinePaymentStatus.PENDING)
                .submittedBy(principal.getId())
                .build();
        payment = finePaymentRepository.save(payment);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.FINE_PAYMENT_SUBMIT,
                "FinePayment",
                payment.getId(),
                null,
                "{\"amount\":\"" + amount + "\",\"fineId\":\"" + fineId + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toPaymentResponse(payment, cooperative.getCurrency());
    }

    @Transactional(readOnly = true)
    public List<FinePaymentResponse> listPayments(UUID cooperativeId, UUID fineId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        requireFine(cooperativeId, fineId);
        return finePaymentRepository
                .findByFineIdAndCooperativeIdOrderByCreatedAtDesc(fineId, cooperativeId)
                .stream()
                .map(p -> toPaymentResponse(p, cooperative.getCurrency()))
                .toList();
    }

    @Transactional
    public FinePaymentResponse approvePayment(
            UUID cooperativeId,
            UUID fineId,
            UUID paymentId,
            FinePaymentReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        Fine fine = requireFine(cooperativeId, fineId);
        FinePayment payment = requirePayment(cooperativeId, fineId, paymentId);
        if (payment.getStatus() != FinePaymentStatus.PENDING) {
            throw new BusinessException("Only PENDING fine payments can be approved");
        }
        if (fine.getStatus() == FineStatus.WAIVED || fine.getStatus() == FineStatus.CANCELLED) {
            throw new BusinessException("Cannot approve payment for fine in status " + fine.getStatus());
        }

        BigDecimal amount = MoneyUtils.scaleForStorage(payment.getAmount());
        BigDecimal outstanding = MoneyUtils.scaleForStorage(fine.getOutstandingAmount());
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException("Payment amount exceeds outstanding fine balance");
        }

        payment.setStatus(FinePaymentStatus.APPROVED);
        payment.setReviewedBy(principal.getId());
        payment.setReviewedAt(Instant.now());
        payment.setReviewNotes(request == null ? null : trimToNull(request.getReviewNotes()));
        payment = finePaymentRepository.save(payment);

        BigDecimal newPaid = MoneyUtils.scaleForStorage(fine.getPaidAmount().add(amount));
        BigDecimal newOutstanding = MoneyUtils.scaleForStorage(outstanding.subtract(amount));
        fine.setPaidAmount(newPaid);
        fine.setOutstandingAmount(newOutstanding);
        fine.setStatus(deriveFineStatus(newPaid, fine.getTotalAmount(), newOutstanding));
        fineRepository.save(fine);

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(fine.getMemberUserId())
                .transactionType(LedgerTransactionType.FINE_PAYMENT)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(amount)
                .currency(cooperative.getCurrency())
                .transactionDate(payment.getPaymentDate())
                .reference(payment.getPaymentReference())
                .sourceEntityType(LedgerService.SOURCE_FINE_PAYMENT)
                .sourceEntityId(payment.getId())
                .description("Fine payment approved for fine " + fine.getId())
                .recordedBy(principal.getId())
                .approvedBy(principal.getId())
                .idempotencyKey(LedgerService.finePaymentKey(payment.getId()))
                .build());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.FINE_PAYMENT_APPROVE,
                "FinePayment",
                payment.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"APPROVED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toPaymentResponse(payment, cooperative.getCurrency());
    }

    @Transactional
    public FinePaymentResponse rejectPayment(
            UUID cooperativeId,
            UUID fineId,
            UUID paymentId,
            FinePaymentReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        requireFine(cooperativeId, fineId);
        FinePayment payment = requirePayment(cooperativeId, fineId, paymentId);
        if (payment.getStatus() != FinePaymentStatus.PENDING) {
            throw new BusinessException("Only PENDING fine payments can be rejected");
        }

        payment.setStatus(FinePaymentStatus.REJECTED);
        payment.setReviewedBy(principal.getId());
        payment.setReviewedAt(Instant.now());
        payment.setReviewNotes(request == null ? null : trimToNull(request.getReviewNotes()));
        payment = finePaymentRepository.save(payment);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.FINE_PAYMENT_REJECT,
                "FinePayment",
                payment.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"REJECTED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toPaymentResponse(payment, cooperative.getCurrency());
    }

    @Transactional(readOnly = true)
    public long countTotal(UUID cooperativeId) {
        return fineRepository.countByCooperativeId(cooperativeId);
    }

    @Transactional(readOnly = true)
    public long countUnpaid(UUID cooperativeId) {
        return fineRepository.countByCooperativeIdAndStatusIn(
                cooperativeId, EnumSet.of(FineStatus.UNPAID, FineStatus.PARTIALLY_PAID));
    }

    @Transactional(readOnly = true)
    public long countPaid(UUID cooperativeId) {
        return fineRepository.countByCooperativeIdAndStatusIn(cooperativeId, EnumSet.of(FineStatus.PAID));
    }

    @Transactional(readOnly = true)
    public long countMembersWithOpenFines(UUID cooperativeId) {
        return fineRepository.countDistinctMembersByStatusIn(
                cooperativeId, EnumSet.of(FineStatus.UNPAID, FineStatus.PARTIALLY_PAID));
    }

    @Transactional(readOnly = true)
    public long countPendingPayments(UUID cooperativeId) {
        return finePaymentRepository.countByCooperativeIdAndStatus(cooperativeId, FinePaymentStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public long countPaymentsByStatus(UUID cooperativeId, FinePaymentStatus status) {
        return finePaymentRepository.countByCooperativeIdAndStatus(cooperativeId, status);
    }

    @Transactional(readOnly = true)
    public PageResponse<FinePaymentResponse> listPaymentQueue(
            UUID cooperativeId,
            FinePaymentStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            String q,
            Pageable pageable) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        DateRangeValidator.validateOptional(fromDate, toDate);

        String query = StringUtils.hasText(q) ? q.trim() : null;
        Page<FinePayment> page = finePaymentRepository.findQueuePage(
                cooperativeId, status, fromDate, toDate, query, pageable);

        return PageMapper.toPageResponse(page, payment -> toEnrichedPaymentResponse(payment, cooperative.getCurrency()));
    }

    private FinePaymentResponse toEnrichedPaymentResponse(FinePayment payment, String currency) {
        FinePaymentResponse response = toPaymentResponse(payment, currency);
        userRepository.findById(payment.getMemberUserId()).ifPresent(user -> {
            response.setMemberName(formatName(user));
            response.setUsername(user.getUsername());
        });
        fineRepository.findByIdAndCooperativeId(payment.getFineId(), payment.getCooperativeId()).ifPresent(fine -> {
            response.setFineReason(fine.getReason());
            response.setFineTotalAmount(MoneyUtils.scale(fine.getTotalAmount()));
            response.setFineOutstandingAmount(MoneyUtils.scale(fine.getOutstandingAmount()));
        });
        return response;
    }

    public static FineStatus deriveFineStatus(BigDecimal paid, BigDecimal total, BigDecimal outstanding) {
        if (outstanding != null && outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            return FineStatus.PAID;
        }
        if (paid != null && paid.compareTo(BigDecimal.ZERO) > 0) {
            return FineStatus.PARTIALLY_PAID;
        }
        return FineStatus.UNPAID;
    }

    private Fine requireFine(UUID cooperativeId, UUID fineId) {
        return fineRepository
                .findByIdAndCooperativeId(fineId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Fine", fineId));
    }

    private FinePayment requirePayment(UUID cooperativeId, UUID fineId, UUID paymentId) {
        return finePaymentRepository
                .findByIdAndCooperativeIdAndFineId(paymentId, cooperativeId, fineId)
                .orElseThrow(() -> new ResourceNotFoundException("FinePayment", paymentId));
    }

    private void ensureUnpaidPeriodRowsIfOverdue(
            Cooperative cooperative, FineSettings settings, YearMonth period) {
        LocalDate dueDate = dueDateOfPeriod(cooperative, period.getYear(), period.getMonthValue());
        LocalDate graceEnd = dueDate.plusDays(Math.max(0, settings.getGraceDays()));
        if (!ContributionDueDates.today(clock).isAfter(graceEnd)) {
            return;
        }
        ensureUnpaidPeriodRows(cooperative, period.getYear(), period.getMonthValue());
    }

    /**
     * The due date an obligation is judged against. A snapshotted obligation keeps the due date it was created with, so a
     * later due-day change never moves it; an older row uses the due day in force for its own period (effective-dated),
     * and only a period no setting covers falls back to the scheme's configured day. Short months clamp to their last day.
     */
    private LocalDate dueDateOf(Cooperative cooperative, Contribution contribution) {
        if (contribution.getObligationSnapshotAt() != null && contribution.getDueDate() != null) {
            return contribution.getDueDate();
        }
        return dueDateOfPeriod(cooperative, contribution.getYear(), contribution.getMonth());
    }

    private LocalDate dueDateOfPeriod(Cooperative cooperative, int year, int month) {
        LocalDate effective = obligationService.dueDateFor(cooperative.getId(), year, month);
        return effective != null
                ? effective
                : fineCalculationService.contributionDueDate(year, month, cooperative.getContributionDueDay());
    }

    private void ensureUnpaidPeriodRows(Cooperative cooperative, int year, int month) {
        List<CooperativeMembership> members =
                membershipRepository.findByCooperativeIdAndMembershipStatus(cooperative.getId(), "ACTIVE");
        for (CooperativeMembership membership : members) {
            contributionRepository
                    .findByCooperativeIdAndMemberUserIdAndYearAndMonth(
                            cooperative.getId(), membership.getUserId(), year, month)
                    .orElseGet(() -> {
                        BigDecimal expected = ShareAmountCalculator.expectedMonthly(
                                cooperative.getMonthlyContributionAmount(), membership.getShareCount());
                        Contribution row = Contribution.builder()
                                .cooperativeId(cooperative.getId())
                                .memberUserId(membership.getUserId())
                                .year(year)
                                .month(month)
                                .shareCount(ShareAmountCalculator.normalizeShareCount(membership.getShareCount()))
                                .expectedAmount(expected)
                                .paidAmount(BigDecimal.ZERO)
                                .outstandingAmount(expected)
                                .status(ContributionStatus.PENDING)
                                .ledgerRevision(0)
                                .build();
                        // a row for the current or a future month gets the same obligation snapshot as any other new
                        // row; a past month stays as it always was (no invented snapshot for history)
                        obligationService.applyToNewRow(row, cooperative, membership);
                        return contributionRepository.save(row);
                    });
        }
    }


    /**
     * BANDED mode: while the contribution stays unpaid and the fine untouched, the fine follows the band the days late
     * have reached (it only ever rises). A fine already partly paid is left as it is.
     */
    private void raiseBandedFine(FineSettings settings, Contribution contribution, int overdueDays, int shares) {
        if (settings.getFineMode() != FineCalculationMode.BANDED) {
            return;
        }
        BigDecimal target = PenaltyBands.amountFor(settings.getPenaltyBands(), overdueDays);
        if (settings.isPerShare()) {
            target = target.multiply(BigDecimal.valueOf(shares));
        }
        BigDecimal scaled = MoneyUtils.scaleForStorage(target);
        for (Fine fine : fineRepository.findBySourceContributionId(contribution.getId())) {
            if (fine.getFineType() == FineType.AUTOMATIC
                    && fine.getStatus() == FineStatus.UNPAID
                    && fine.getPaidAmount().signum() == 0
                    && scaled.compareTo(fine.getTotalAmount()) > 0) {
                fine.setBaseAmount(scaled);
                fine.setTotalAmount(scaled);
                fine.setOutstandingAmount(scaled);
                fine.setOverdueDays(overdueDays);
                fineRepository.save(fine);
            }
        }
    }

    /**
     * After {@code creditConversionDays} late, what is still unpaid on the contribution becomes a loan at the monthly
     * rate (see {@link CreditConversionService}); the contribution then counts as paid by that credit.
     */
    private int chargeCreditConversion(
            Cooperative cooperative, FineSettings settings, Contribution contribution, LocalDate dueDate, LocalDate today,
            UUID issuedBy, List<FineResponse> created) {
        return creditConversionService.convertIfDue(cooperative, settings, contribution.getId(), dueDate, today, issuedBy);
    }

    private static String automaticSourceKey(UUID cooperativeId, UUID contributionId) {
        return cooperativeId + ":" + contributionId;
    }

    private void requireActiveMember(UUID cooperativeId, UUID memberUserId) {
        var membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow(() -> new ValidationException("User is not a member of this cooperative"));
        if (!"ACTIVE".equalsIgnoreCase(membership.getMembershipStatus())) {
            throw new BusinessException("Only ACTIVE members can receive fines");
        }
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private FineResponse toResponse(Fine fine, String currency) {
        String memberName = userRepository
                .findById(fine.getMemberUserId())
                .map(this::formatName)
                .orElse(null);
        Integer contributionYear = null;
        Integer contributionMonth = null;
        if (fine.getSourceContributionId() != null) {
            Contribution source = contributionRepository.findById(fine.getSourceContributionId()).orElse(null);
            if (source != null) {
                contributionYear = source.getYear();
                contributionMonth = source.getMonth();
            }
        }
        return FineResponse.builder()
                .id(fine.getId())
                .cooperativeId(fine.getCooperativeId())
                .memberUserId(fine.getMemberUserId())
                .memberName(memberName)
                .fineType(fine.getFineType())
                .calculationMode(fine.getCalculationMode())
                .sourceContributionId(fine.getSourceContributionId())
                .contributionYear(contributionYear)
                .contributionMonth(contributionMonth)
                .baseAmount(MoneyUtils.scale(fine.getBaseAmount()))
                .dailyIncrementSnapshot(MoneyUtils.scale(fine.getDailyIncrementSnapshot()))
                .overdueDays(fine.getOverdueDays())
                .totalAmount(MoneyUtils.scale(fine.getTotalAmount()))
                .paidAmount(MoneyUtils.scale(fine.getPaidAmount()))
                .outstandingAmount(MoneyUtils.scale(fine.getOutstandingAmount()))
                .reason(fine.getReason())
                .notes(fine.getNotes())
                .issuedDate(fine.getIssuedDate())
                .dueDate(fine.getDueDate())
                .status(fine.getStatus())
                .issuedBy(fine.getIssuedBy())
                .currency(currency)
                .createdAt(fine.getCreatedAt())
                .updatedAt(fine.getUpdatedAt())
                .build();
    }

    private FinePaymentResponse toPaymentResponse(FinePayment payment, String currency) {
        return FinePaymentResponse.builder()
                .id(payment.getId())
                .fineId(payment.getFineId())
                .cooperativeId(payment.getCooperativeId())
                .memberUserId(payment.getMemberUserId())
                .amount(MoneyUtils.scale(payment.getAmount()))
                .paymentDate(payment.getPaymentDate())
                .paymentReference(payment.getPaymentReference())
                .paymentMethod(payment.getPaymentMethod())
                .paymentMethodDetail(payment.getPaymentMethodDetail())
                .notes(payment.getNotes())
                .evidenceFileKey(payment.getEvidenceFileKey())
                .status(payment.getStatus())
                .submittedBy(payment.getSubmittedBy())
                .reviewedBy(payment.getReviewedBy())
                .reviewedAt(payment.getReviewedAt())
                .reviewNotes(payment.getReviewNotes())
                .currency(currency)
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    private String formatName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        return (first + " " + last).trim();
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
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

    /**
     * Creates an automatic loan-installment penalty Fine. Returns null when the
     * period key already exists so scheduler retries stay idempotent.
     */
    @Transactional
    public Fine assessLoanInstallmentPenalty(
            Loan loan,
            LoanInstallment installment,
            BigDecimal amount,
            String periodKey,
            LocalDate issuedDate,
            UUID issuedBy) {
        MoneyUtils.assertPositive(amount);
        String key = loanPenaltySourceKey(installment.getId(), periodKey);
        if (fineRepository.existsByAutomaticSourceKey(key)) {
            return null;
        }
        BigDecimal stored = MoneyUtils.scaleForStorage(amount);
        Fine fine = Fine.builder()
                .cooperativeId(loan.getCooperativeId())
                .memberUserId(loan.getMemberUserId())
                .fineType(FineType.AUTOMATIC)
                .calculationMode(FineCalculationMode.FIXED)
                .sourceLoanId(loan.getId())
                .sourceLoanInstallmentId(installment.getId())
                .automaticSourceKey(key)
                .baseAmount(stored)
                .dailyIncrementSnapshot(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .overdueDays(0)
                .totalAmount(stored)
                .paidAmount(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .outstandingAmount(stored)
                .reason("Loan installment " + installment.getInstallmentNumber() + " overdue")
                .issuedDate(issuedDate)
                .status(FineStatus.UNPAID)
                .issuedBy(issuedBy)
                .build();
        return fineRepository.save(fine);
    }

    @Transactional
    public void applyLoanPenaltyPayment(
            Fine fine,
            BigDecimal amount,
            LocalDate paymentDate,
            UUID recordedBy,
            String paymentReference,
            String currency) {
        BigDecimal stored = MoneyUtils.scaleForStorage(amount);
        MoneyUtils.assertPositive(stored);
        BigDecimal outstanding = MoneyUtils.scaleForStorage(fine.getOutstandingAmount());
        if (stored.compareTo(outstanding) > 0) {
            throw new BusinessException("Penalty payment exceeds outstanding fine balance");
        }
        FinePayment payment = FinePayment.builder()
                .fineId(fine.getId())
                .cooperativeId(fine.getCooperativeId())
                .memberUserId(fine.getMemberUserId())
                .amount(stored)
                .paymentDate(paymentDate)
                .paymentReference(trimToNull(paymentReference))
                .paymentMethod(FinePaymentMethod.OTHER)
                .paymentMethodDetail("Loan repayment allocation")
                .status(FinePaymentStatus.APPROVED)
                .submittedBy(recordedBy)
                .reviewedBy(recordedBy)
                .reviewedAt(Instant.now())
                .build();
        payment = finePaymentRepository.save(payment);

        BigDecimal newPaid = MoneyUtils.scaleForStorage(nvl(fine.getPaidAmount()).add(stored));
        BigDecimal newOutstanding = MoneyUtils.scaleForStorage(outstanding.subtract(stored));
        fine.setPaidAmount(newPaid);
        fine.setOutstandingAmount(newOutstanding);
        fine.setStatus(deriveFineStatus(newPaid, fine.getTotalAmount(), newOutstanding));
        fineRepository.save(fine);

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(fine.getCooperativeId())
                .memberUserId(fine.getMemberUserId())
                .transactionType(LedgerTransactionType.FINE_PAYMENT)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(stored)
                .currency(currency)
                .transactionDate(paymentDate)
                .reference(payment.getPaymentReference())
                .sourceEntityType(LedgerService.SOURCE_FINE_PAYMENT)
                .sourceEntityId(payment.getId())
                .description("Loan installment penalty payment")
                .recordedBy(recordedBy)
                .approvedBy(recordedBy)
                .idempotencyKey(LedgerService.finePaymentKey(payment.getId()))
                .build());
    }

    public List<Fine> unpaidLoanInstallmentFines(UUID installmentId) {
        return fineRepository.findBySourceLoanInstallmentIdAndStatusInOrderByIssuedDateAscCreatedAtAsc(
                installmentId, EnumSet.of(FineStatus.UNPAID, FineStatus.PARTIALLY_PAID));
    }

    public static String loanPenaltySourceKey(UUID installmentId, String periodKey) {
        return "LOAN:" + installmentId + ":" + periodKey;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
