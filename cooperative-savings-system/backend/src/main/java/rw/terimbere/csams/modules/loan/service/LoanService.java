package rw.terimbere.csams.modules.loan.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.entity.ApprovalAction;
import rw.terimbere.csams.modules.audit.service.ApprovalTrailService;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.notification.service.NotificationWhatsAppService;
import rw.terimbere.csams.modules.notification.whatsapp.NotificationWhatsAppCopy;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.cooperative.service.SchemeOperatingModelGuard;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.dto.LoanApplicationFormResponse;
import rw.terimbere.csams.modules.loan.dto.LoanApproveRequest;
import rw.terimbere.csams.modules.loan.dto.LoanEligibilityResponse;
import rw.terimbere.csams.modules.loan.dto.LoanGuarantorRespondRequest;
import rw.terimbere.csams.modules.loan.dto.LoanGuarantorResponse;
import rw.terimbere.csams.modules.loan.dto.LoanRejectRequest;
import rw.terimbere.csams.modules.loan.dto.LoanRepaymentCreateRequest;
import rw.terimbere.csams.modules.loan.dto.LoanRepaymentPreviewRequest;
import rw.terimbere.csams.modules.loan.dto.LoanRepaymentResponse;
import rw.terimbere.csams.modules.loan.dto.LoanRepaymentReviseRequest;
import rw.terimbere.csams.modules.loan.dto.LoanRequestCreateRequest;
import rw.terimbere.csams.modules.loan.dto.LoanResponse;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleResponse;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanGuaranteeMode;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentComponent;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.modules.loan.entity.LoanSettings;
import rw.terimbere.csams.modules.loan.entity.LoanShareTier;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.repository.LoanShareTierRepository;
import rw.terimbere.csams.modules.loan.support.LoanOverdueRules;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepaymentAllocation;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanInterestWaiver;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentAllocationRepository;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
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
import rw.terimbere.csams.shared.financial.FinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.pagination.PageMapper;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class LoanService {

    private static final int DEFAULT_TERM_MONTHS = 12;
    private static final EnumSet<LoanStatus> OPEN_LOAN_STATUSES = EnumSet.of(
            LoanStatus.PENDING,
            LoanStatus.AWAITING_SECOND_APPROVAL,
            LoanStatus.APPROVED,
            LoanStatus.ACTIVE,
            LoanStatus.OVERDUE);

    private final LoanRepository loanRepository;
    private final LoanShareTierRepository loanShareTierRepository;
    private final LoanInstallmentRepository loanInstallmentRepository;
    private final LoanRepaymentRepository loanRepaymentRepository;
    private final LoanRepaymentAllocationRepository loanRepaymentAllocationRepository;
    private final rw.terimbere.csams.modules.loanrepayment.repository.LoanInterestWaiverRepository loanInterestWaiverRepository;
    private final LoanSettingsService loanSettingsService;
    private final LoanPenaltyService loanPenaltyService;
    private final FineService fineService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final FinancialCalculationService financialCalculationService;
    private final LedgerService ledgerService;
    private final SchemeFundLockService schemeFundLockService;
    private final SchemeOperatingModelGuard operatingModelGuard;
    private final AuditService auditService;
    private final ApprovalTrailService approvalTrailService;
    private final NotificationFacade notificationFacade;
    private final NotificationWhatsAppService notificationWhatsAppService;
    private final LoanGuarantorService loanGuarantorService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final rw.terimbere.csams.modules.contribution.repository.ContributionRepository contributionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshOverdueStatuses(UUID cooperativeId) {
        loanRepository.markOverdue(cooperativeId, today());
    }

    @Transactional
    public LoanResponse requestLoan(
            UUID cooperativeId, LoanRequestCreateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        boolean canWrite = principal.hasAuthority("LOAN_WRITE")
                || principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);
        UUID memberUserId;
        if (canWrite && request.getMemberUserId() != null) {
            memberUserId = request.getMemberUserId();
        } else {
            memberUserId = principal.getId();
            if (request.getMemberUserId() != null && !request.getMemberUserId().equals(principal.getId())) {
                throw new ForbiddenException("Members may only request loans for themselves");
            }
        }

        LoanSettings settings = loanSettingsService.requireSettings(cooperativeId);
        InterestType interestType =
                settings.getInterestType() == null ? InterestType.FLAT : settings.getInterestType();
        LoanSettingsService.rejectReducingInterest(interestType);
        if (!canWrite && !settings.isAllowMemberRequests()) {
            throw new BusinessException("Member loan requests are disabled for this cooperative");
        }

        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow(() -> new ValidationException("User is not a member of this cooperative"));
        if (!"ACTIVE".equalsIgnoreCase(membership.getMembershipStatus())) {
            throw new BusinessException("Only ACTIVE members can receive loans");
        }
        enforceMinMembershipMonths(membership, settings.getMinMembershipMonths());

        BigDecimal amount = MoneyUtils.scaleForStorage(request.getAmount());
        MoneyUtils.assertPositive(amount);
        LoanEligibilityResponse eligibility = evaluateEligibility(cooperativeId, memberUserId, amount, null);
        if (!eligibility.isEligible()) {
            throw new BusinessException(eligibility.getReason());
        }

        LoanGuaranteeMode guaranteeMode = resolveGuaranteeMode(request);

        int termMonths = resolveTermMonths(request.getTermMonths(), settings);
        if (settings.getMaxTermMonths() != null && termMonths > settings.getMaxTermMonths()) {
            throw new BusinessException("Term exceeds maximum allowed months");
        }
        LocalDate requestDate = today();
        LoanRepaymentDateModel model = settings.getRepaymentDateModel() == null
                ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH
                : settings.getRepaymentDateModel();

        Loan loan = Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberUserId)
                .requestedAmount(amount)
                .interestRatePercent(MoneyUtils.scaleForStorage(settings.getInterestRatePercent()))
                .interestType(interestType)
                .termMonths(termMonths)
                .repaymentDateModel(model)
                .prorataEnabled(model == LoanRepaymentDateModel.MONTH_END)
                .outstandingPrincipal(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .outstandingInterest(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .totalRepaidPrincipal(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .totalRepaidInterest(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                .requestDate(requestDate)
                .status(LoanStatus.PENDING)
                .guaranteeMode(guaranteeMode)
                .shareCount(eligibility.getShareCount())
                .sharePercent(eligibility.getSharePercent())
                .maxLoanByShares(
                        eligibility.getMaxLoanByShares() == null
                                ? null
                                : MoneyUtils.scaleForStorage(eligibility.getMaxLoanByShares()))
                .purpose(trimToNull(request.getPurpose()))
                .requestedBy(principal.getId())
                .build();
        applyFlatScheduleTerms(loan, amount, requestDate);
        loan.setApplicationSnapshot(writeSnapshot(buildApplicationForm(
                cooperative, membership, memberUserId, loan, Instant.now())));
        loan = loanRepository.save(loan);

        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_LOAN,
                loan.getId(),
                principal,
                ApprovalAction.SUBMITTED,
                null,
                LoanStatus.PENDING.name(),
                trimToNull(request.getPurpose()));

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.LOAN_REQUEST,
                "Loan",
                loan.getId(),
                null,
                "{\"amount\":\""
                        + amount
                        + "\",\"status\":\"PENDING\",\"guaranteeMode\":\""
                        + guaranteeMode
                        + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationWhatsAppService.notifyOfficers(
                cooperativeId,
                CooperativeOfficerRoles.LOAN_APPROVE,
                NotificationWhatsAppCopy.loanPending(),
                principal.getId());
        if (guaranteeMode == LoanGuaranteeMode.GUARANTOR) {
            if (request.getGuarantorUserId() == null || request.getGuaranteedAmount() == null) {
                throw new ValidationException("A guarantor and guaranteed amount are required for a guaranteed loan");
            }
            loanGuarantorService.assignGuarantor(
                    loan, request.getGuarantorUserId(), request.getGuaranteedAmount(), principal, httpRequest);
        }
        return toResponse(loan, cooperative.getCurrency(), true);
    }

    @Transactional(readOnly = true)
    public LoanApplicationFormResponse applicationPreview(UUID cooperativeId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, principal.getId())
                .orElseThrow(() -> new ValidationException("User is not a member of this cooperative"));
        LoanSettings settings = loanSettingsService.requireSettings(cooperativeId);
        Loan draft = Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(principal.getId())
                .interestRatePercent(settings.getInterestRatePercent())
                .interestType(settings.getInterestType() == null ? InterestType.FLAT : settings.getInterestType())
                .termMonths(resolveTermMonths(null, settings))
                .requestDate(today())
                .build();
        LoanApplicationFormResponse form =
                buildApplicationForm(cooperative, membership, principal.getId(), draft, Instant.now());
        form.setEligibility(evaluateEligibility(cooperativeId, principal.getId(), null, null));
        return form;
    }

    @Transactional(readOnly = true)
    public LoanScheduleResponse previewRepayment(UUID cooperativeId, LoanRepaymentPreviewRequest request) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        LoanSettings settings = loanSettingsService.requireSettings(cooperativeId);
        InterestType interestType =
                settings.getInterestType() == null ? InterestType.FLAT : settings.getInterestType();
        LoanSettingsService.rejectReducingInterest(interestType);
        int termMonths = resolveTermMonths(request.getTermMonths(), settings);
        if (settings.getMaxTermMonths() != null && termMonths > settings.getMaxTermMonths()) {
            throw new BusinessException("Term exceeds maximum allowed months");
        }
        BigDecimal amount = MoneyUtils.scaleForStorage(request.getAmount());
        MoneyUtils.assertPositive(amount);
        LocalDate referenceDate = request.getReferenceDate() == null ? today() : request.getReferenceDate();
        LoanRepaymentDateModel model = settings.getRepaymentDateModel() == null
                ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH
                : settings.getRepaymentDateModel();
        LoanScheduleResponse preview = LoanScheduleCalculator.calculate(
                amount,
                settings.getInterestRatePercent(),
                termMonths,
                model,
                referenceDate);
        preview.setScheduleFinalized(false);
        return preview;
    }

    @Transactional(readOnly = true)
    public LoanEligibilityResponse eligibility(UUID cooperativeId, UUID memberUserId, BigDecimal requestedAmount) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        UUID target = memberUserId;
        boolean canWrite = principal.hasAuthority("LOAN_WRITE")
                || principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);
        if (target == null || !canWrite) {
            target = principal.getId();
        }
        return evaluateEligibility(cooperativeId, target, requestedAmount, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<LoanResponse> list(
            UUID cooperativeId,
            LoanStatus status,
            UUID memberUserId,
            boolean pendingApproval,
            Pageable pageable) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        if (!CooperativeOfficerRoles.isOfficer(principal)) {
            memberUserId = principal.getId();
            pendingApproval = false;
        }

        LocalDate today = today();
        Page<Loan> page;
        if (pendingApproval) {
            page = loanRepository.findByCooperativeIdAndStatusIn(
                    cooperativeId,
                    EnumSet.of(LoanStatus.PENDING, LoanStatus.AWAITING_SECOND_APPROVAL),
                    pageable);
        } else if (status == LoanStatus.OVERDUE && memberUserId != null) {
            page = loanRepository.findCurrentlyOverdueByMember(cooperativeId, memberUserId, today, pageable);
        } else if (status == LoanStatus.OVERDUE) {
            page = loanRepository.findCurrentlyOverdue(cooperativeId, today, pageable);
        } else if (status == LoanStatus.ACTIVE && memberUserId != null) {
            page = loanRepository.findActiveNotCurrentlyOverdueByMember(
                    cooperativeId, memberUserId, today, pageable);
        } else if (status == LoanStatus.ACTIVE) {
            page = loanRepository.findActiveNotCurrentlyOverdue(cooperativeId, today, pageable);
        } else if (status != null && memberUserId != null) {
            page = loanRepository.findByCooperativeIdAndMemberUserIdAndStatus(
                    cooperativeId, memberUserId, status, pageable);
        } else if (status != null) {
            page = loanRepository.findByCooperativeIdAndStatus(cooperativeId, status, pageable);
        } else if (memberUserId != null) {
            page = loanRepository.findByCooperativeIdAndMemberUserId(cooperativeId, memberUserId, pageable);
        } else {
            page = loanRepository.findByCooperativeId(cooperativeId, pageable);
        }
        return PageMapper.toPageResponse(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public PageResponse<LoanResponse> myLoans(UUID cooperativeId, LoanStatus status, Pageable pageable) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        return list(cooperativeId, status, principal.getId(), false, pageable);
    }

    @Transactional(readOnly = true)
    public LoanResponse get(UUID cooperativeId, UUID loanId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        Loan loan = requireLoan(cooperativeId, loanId);
        requireLoanReadAccess(loan);
        return toResponse(loan, null, true);
    }

    @Transactional(readOnly = true)
    public List<LoanResponse> recentForMember(UUID cooperativeId, UUID memberUserId) {
        return loanRepository
                .findTop20ByCooperativeIdAndMemberUserIdOrderByRequestDateDescCreatedAtDesc(
                        cooperativeId, memberUserId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public LoanResponse approve(
            UUID cooperativeId, UUID loanId, LoanApproveRequest request, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        Loan loan = requireLoan(cooperativeId, loanId);
        if (principal.getId().equals(loan.getMemberUserId())) {
            throw new ForbiddenException("A member cannot approve their own loan");
        }
        loanGuarantorService.requireAcceptedIfRequired(loan);
        String previousStatus = loan.getStatus().name();

        if (loan.getStatus() == LoanStatus.PENDING) {
            CooperativeOfficerRoles.requireLoanApprove(principal);
            applyFirstApproval(loan, request, principal);
        } else if (loan.getStatus() == LoanStatus.AWAITING_SECOND_APPROVAL) {
            CooperativeOfficerRoles.requireFundAuthorize(principal);
            if (principal.getId().equals(loan.getFirstApprovedBy())) {
                throw new ForbiddenException("The same person cannot give both loan approvals");
            }
            applySecondApproval(loan, request, principal);
        } else {
            throw new BusinessException("This loan is not waiting for approval");
        }

        loan = loanRepository.save(loan);
        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_LOAN,
                loan.getId(),
                principal,
                ApprovalAction.APPROVED,
                previousStatus,
                loan.getStatus().name(),
                null);
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.APPROVE,
                "Loan",
                loan.getId(),
                "{\"status\":\"" + previousStatus + "\"}",
                "{\"status\":\""
                        + loan.getStatus()
                        + "\",\"approvedAmount\":\""
                        + loan.getApprovedAmount()
                        + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        if (loan.getStatus() == LoanStatus.APPROVED) {
            notificationFacade.notifyUser(
                    loan.getMemberUserId(),
                    cooperativeId,
                    NotificationType.LOAN,
                    "Loan approved",
                    "Your loan request was approved by " + actorLabel(principal) + ".",
                    "Loan",
                    loan.getId());
        }
        return toResponse(loan, null, true);
    }

    @Transactional
    public LoanResponse reject(
            UUID cooperativeId, UUID loanId, LoanRejectRequest request, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        Loan loan = requireLoan(cooperativeId, loanId);
        if (loan.getStatus() == LoanStatus.PENDING) {
            CooperativeOfficerRoles.requireLoanApprove(principal);
        } else if (loan.getStatus() == LoanStatus.AWAITING_SECOND_APPROVAL) {
            CooperativeOfficerRoles.requireFundAuthorize(principal);
            if (principal.getId().equals(loan.getFirstApprovedBy())) {
                throw new ForbiddenException("The same person cannot give both loan approvals");
            }
        } else {
            throw new BusinessException("Only loans awaiting approval can be rejected");
        }
        if (request == null || !StringUtils.hasText(request.getRejectionReason())) {
            throw new ValidationException("Rejection reason is required");
        }

        String previousStatus = loan.getStatus().name();
        loan.setStatus(LoanStatus.REJECTED);
        loan.setRejectionReason(request.getRejectionReason().trim());
        loan.setApprovedBy(principal.getId());
        loan.setApprovalDate(today());
        loan = loanRepository.save(loan);

        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_LOAN,
                loan.getId(),
                principal,
                ApprovalAction.REJECTED,
                previousStatus,
                LoanStatus.REJECTED.name(),
                request.getRejectionReason().trim());
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.REJECT,
                "Loan",
                loan.getId(),
                "{\"status\":\"" + previousStatus + "\"}",
                "{\"status\":\"REJECTED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                loan.getMemberUserId(),
                cooperativeId,
                NotificationType.LOAN,
                "Loan rejected",
                "Your loan request was rejected by " + actorLabel(principal) + ": "
                        + request.getRejectionReason().trim(),
                "Loan",
                loan.getId());
        return toResponse(loan, null, true);
    }

    @Transactional
    public LoanResponse disburse(UUID cooperativeId, UUID loanId, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        // Serialize with every other operation that spends this scheme's funds. The lock comes before the loan is
        // read and before available funds are calculated, so both reflect what earlier transactions committed.
        schemeFundLockService.lockSchemeFund(cooperativeId);
        operatingModelGuard.requireAccumulatingWorkflows(cooperativeId, "Loan disbursement");
        Loan loan = requireLoan(cooperativeId, loanId);
        if (loan.getStatus() != LoanStatus.APPROVED) {
            throw new BusinessException("Only APPROVED loans can be disbursed");
        }

        BigDecimal principalAmount = loan.getApprovedAmount() != null
                ? loan.getApprovedAmount()
                : loan.getRequestedAmount();
        MoneyUtils.assertPositive(principalAmount);

        BigDecimal available = financialCalculationService.calculateAvailableGroupFund(cooperativeId);
        if (available.compareTo(MoneyUtils.scale(principalAmount)) < 0) {
            throw new BusinessException(
                    "INSUFFICIENT_GROUP_FUND",
                    "Available group fund is insufficient for disbursement. Available: "
                            + available
                            + ", required: "
                            + MoneyUtils.scale(principalAmount));
        }

        LocalDate disbursementDate = today();
        finalizeScheduleAtDisbursement(loan, principalAmount, disbursementDate);
        BigDecimal interest = loan.getInterestAmount();
        loan.setPrincipalAmount(MoneyUtils.scaleForStorage(principalAmount));
        loan.setInterestAmount(MoneyUtils.scaleForStorage(interest));
        loan.setOutstandingPrincipal(MoneyUtils.scaleForStorage(principalAmount));
        loan.setOutstandingInterest(MoneyUtils.scaleForStorage(interest));
        loan.setDisbursementDate(disbursementDate);
        loan.setDisbursedBy(principal.getId());
        loan.setStatus(LoanStatus.ACTIVE);
        if (loan.getDueDate() == null && !loanInstallmentRepository.existsByLoanId(loan.getId())) {
            loan.setDueDate(disbursementDate.plusMonths(loan.getTermMonths()));
        }
        loan = loanRepository.save(loan);

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(loan.getMemberUserId())
                .transactionType(LedgerTransactionType.LOAN_DISBURSEMENT)
                .debitAmount(principalAmount)
                .creditAmount(BigDecimal.ZERO)
                .currency(cooperative.getCurrency())
                .transactionDate(disbursementDate)
                .reference("LOAN-" + loan.getId())
                .sourceEntityType(LedgerService.SOURCE_LOAN)
                .sourceEntityId(loan.getId())
                .description("Loan disbursement")
                .recordedBy(principal.getId())
                .approvedBy(principal.getId())
                .idempotencyKey(LedgerService.loanDisbursementKey(loan.getId()))
                .build());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.DISBURSE,
                "Loan",
                loan.getId(),
                "{\"status\":\"APPROVED\"}",
                "{\"status\":\"ACTIVE\",\"principal\":\"" + principalAmount + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                loan.getMemberUserId(),
                cooperativeId,
                NotificationType.LOAN,
                "Loan disbursed",
                "Your loan of " + MoneyUtils.scale(principalAmount) + " has been disbursed.",
                "Loan",
                loan.getId());
        return toResponse(loan);
    }

    @Transactional
    public LoanResponse writeOff(UUID cooperativeId, UUID loanId, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(principal);
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !principal.hasAuthority("LOAN_WRITE")) {
            throw new ForbiddenException("LOAN_WRITE or SUPER_ADMIN required to write off a loan");
        }

        Loan loan = requireLoan(cooperativeId, loanId);
        if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.OVERDUE) {
            throw new BusinessException("Only ACTIVE or OVERDUE loans can be written off");
        }

        // Leave outstanding amounts for reporting; exclude WRITTEN_OFF from dashboard outstanding metric.
        LoanStatus previous = loan.getStatus();
        loan.setStatus(LoanStatus.WRITTEN_OFF);
        loan = loanRepository.save(loan);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.LOAN_WRITE_OFF,
                "Loan",
                loan.getId(),
                "{\"status\":\"" + previous + "\"}",
                "{\"status\":\"WRITTEN_OFF\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(loan);
    }

    @Transactional
    public LoanRepaymentResponse recordRepayment(
            UUID cooperativeId, UUID loanId, LoanRepaymentCreateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        loanRepository.markOverdue(cooperativeId, today());

        // Row lock: a repayment, a combined-payment posting or any other change to this loan cannot interleave.
        Loan loan = loanRepository
                .findByIdAndCooperativeIdForUpdate(loanId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
        BigDecimal amount = MoneyUtils.scaleForStorage(request.getAmount());
        MoneyUtils.assertPositive(amount);
        LoanRepayment repayment = applyRepayment(
                cooperative,
                loan,
                amount,
                request.getPaymentDate(),
                trimToNull(request.getPaymentReference()),
                trimToNull(request.getNotes()),
                request.getAllocateInterestFirst(),
                principal.getId());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.REPAY,
                "LoanRepayment",
                repayment.getId(),
                null,
                "{\"loanId\":\"" + loanId + "\",\"amount\":\"" + repayment.getAmountTotal() + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toRepaymentResponse(repayment);
    }

    /**
     * The one place a repayment is applied to a loan: validates the loan's status and outstanding amount, lets the loan
     * engine compute the principal/interest/penalty split, updates the loan and its installments, and appends the ledger
     * entries. Used unchanged by the direct repayment route and by verified combined-payment posting, so there is a
     * single loan repayment calculator. The caller must hold the loan row lock and an open transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoanRepayment applyRepayment(
            Cooperative cooperative,
            Loan loan,
            BigDecimal amount,
            java.time.LocalDate paymentDate,
            String reference,
            String notes,
            Boolean allocateInterestFirst,
            UUID actorId) {
        return applyRepayment(cooperative, loan, amount, paymentDate, reference, notes, allocateInterestFirst, actorId, null, null);
    }

    private LoanRepayment applyRepayment(
            Cooperative cooperative,
            Loan loan,
            BigDecimal amount,
            java.time.LocalDate paymentDate,
            String reference,
            String notes,
            Boolean allocateInterestFirst,
            UUID actorId,
            UUID revisionOfId,
            String revisionReason) {
        if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.OVERDUE) {
            throw new BusinessException("Repayments are only allowed on ACTIVE or OVERDUE loans");
        }
        MoneyUtils.assertPositive(amount);

        if (isScheduleBased(loan)) {
            loanPenaltyService.evaluate(loan, today(), actorId);
            loan = requireLoan(cooperative.getId(), loan.getId());
        }

        BigDecimal outstandingPrincipal = loan.getOutstandingPrincipal() == null
                ? BigDecimal.ZERO
                : loan.getOutstandingPrincipal();
        BigDecimal outstandingInterest = loan.getOutstandingInterest() == null
                ? BigDecimal.ZERO
                : loan.getOutstandingInterest();
        BigDecimal outstandingPenalty = loan.getOutstandingPenalty() == null
                ? BigDecimal.ZERO
                : loan.getOutstandingPenalty();
        BigDecimal totalOutstanding = MoneyUtils.add(
                MoneyUtils.add(MoneyUtils.scale(outstandingPrincipal), MoneyUtils.scale(outstandingInterest)),
                MoneyUtils.scale(outstandingPenalty));

        if (MoneyUtils.scale(amount).compareTo(totalOutstanding) > 0) {
            throw new BusinessException(
                    "REPAYMENT_EXCEEDS_OUTSTANDING",
                    "Repayment amount exceeds outstanding principal + interest + penalty (" + totalOutstanding + ")");
        }

        boolean interestFirst = allocateInterestFirst == null || allocateInterestFirst;
        BigDecimal interestPortion;
        BigDecimal principalPortion;
        BigDecimal penaltyPortion = BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE);
        LoanScheduleAllocator.PaymentSplit split = null;
        List<LoanInstallment> installments = List.of();
        boolean scheduled = isScheduleBased(loan) && loanInstallmentRepository.existsByLoanId(loan.getId());

        // Interest only for the months the money was used: repaying early never pays the interest of unused months.
        EarlyRepaymentInterest.Settlement settlement = EarlyRepaymentInterest.settlement(
                loan, paymentDate, outstandingPrincipal, outstandingInterest, outstandingPenalty, scheduled);
        boolean early = settlement.interestWaived().signum() > 0;
        if (early && MoneyUtils.scale(amount).compareTo(settlement.total()) > 0) {
            throw new BusinessException(
                    "REPAYMENT_EXCEEDS_SETTLEMENT",
                    "Repaying this loan in full on " + paymentDate + " takes " + settlement.total() + " (principal "
                            + settlement.principal() + " + interest for " + settlement.monthsCharged() + " month(s) "
                            + settlement.interestToPay() + "); the interest of the months not used is not owed");
        }
        boolean settles = early && MoneyUtils.scale(amount).compareTo(settlement.total()) == 0;
        BigDecimal interestCap = early ? settlement.interestToPay() : null;
        BigDecimal waived = MoneyUtils.scaleForStorage(settles ? settlement.interestWaived() : BigDecimal.ZERO);
        List<LoanInterestWaiver> waivers = new ArrayList<>();

        if (scheduled) {
            installments = loanInstallmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loan.getId());
            if (settles) {
                // the interest of the unused months comes off the last instalments first
                BigDecimal left = waived;
                for (int i = installments.size() - 1; i >= 0 && left.signum() > 0; i--) {
                    LoanInstallment installment = installments.get(i);
                    BigDecimal take = installment.remainingInterest().min(left);
                    if (take.signum() <= 0) continue;
                    installment.setInterestDue(MoneyUtils.scaleForStorage(installment.getInterestDue().subtract(take)));
                    left = left.subtract(take);
                    waivers.add(LoanInterestWaiver.builder()
                            .installmentId(installment.getId())
                            .loanId(loan.getId())
                            .cooperativeId(loan.getCooperativeId())
                            .amount(MoneyUtils.scaleForStorage(take))
                            .build());
                }
            }
            // a payment never takes interest of months not yet used: paying ahead goes to the principal
            split = LoanScheduleAllocator.allocatePayment(
                    installments,
                    MoneyUtils.scale(amount),
                    LoanAllocationOrder.parse(loan.getAllocationOrder()),
                    today(),
                    settles ? null : interestCap);
            principalPortion = split.principalPortion();
            interestPortion = split.interestPortion();
            penaltyPortion = split.penaltyPortion();
            loanInstallmentRepository.saveAll(installments);
        } else {
            BigDecimal remaining = amount;
            BigDecimal interestDueNow = interestCap == null ? outstandingInterest : interestCap.min(outstandingInterest);
            if (interestFirst) {
                interestPortion = remaining.min(interestDueNow);
                remaining = remaining.subtract(interestPortion);
                principalPortion = remaining.min(outstandingPrincipal);
            } else {
                principalPortion = remaining.min(outstandingPrincipal);
                remaining = remaining.subtract(principalPortion);
                interestPortion = remaining.min(interestDueNow);
            }
            if (settles) {
                waivers.add(LoanInterestWaiver.builder()
                        .loanId(loan.getId())
                        .cooperativeId(loan.getCooperativeId())
                        .amount(waived)
                        .build());
            }
            interestPortion = MoneyUtils.scaleForStorage(interestPortion);
            principalPortion = MoneyUtils.scaleForStorage(principalPortion);
        }
        BigDecimal amountTotal = MoneyUtils.scaleForStorage(
                principalPortion.add(interestPortion).add(penaltyPortion));

        LoanRepayment repayment = LoanRepayment.builder()
                .loanId(loan.getId())
                .cooperativeId(cooperative.getId())
                .memberUserId(loan.getMemberUserId())
                .paymentDate(paymentDate)
                .amountTotal(amountTotal)
                .principalPortion(principalPortion)
                .interestPortion(interestPortion)
                .penaltyPortion(penaltyPortion)
                .paymentReference(reference)
                .notes(notes)
                .recordedBy(actorId)
                .revisionOfId(revisionOfId)
                .revisionReason(revisionReason)
                .interestWaived(waived)
                .build();
        repayment = loanRepaymentRepository.save(repayment);
        persistAllocations(repayment, split);
        for (LoanInterestWaiver waiver : waivers) {
            waiver.setRepaymentId(repayment.getId());
        }
        loanInterestWaiverRepository.saveAll(waivers);
        if (waived.signum() > 0 && loan.getInterestAmount() != null) {
            loan.setInterestAmount(MoneyUtils.scaleForStorage(loan.getInterestAmount().subtract(waived)));
        }

        loan.setOutstandingPrincipal(
                MoneyUtils.scaleForStorage(outstandingPrincipal.subtract(principalPortion)));
        loan.setOutstandingInterest(
                MoneyUtils.scaleForStorage(outstandingInterest.subtract(interestPortion).subtract(waived)));
        loan.setOutstandingPenalty(
                MoneyUtils.scaleForStorage(outstandingPenalty.subtract(penaltyPortion)));
        loan.setTotalRepaidPrincipal(MoneyUtils.scaleForStorage(
                (loan.getTotalRepaidPrincipal() == null ? BigDecimal.ZERO : loan.getTotalRepaidPrincipal())
                        .add(principalPortion)));
        loan.setTotalRepaidInterest(MoneyUtils.scaleForStorage(
                (loan.getTotalRepaidInterest() == null ? BigDecimal.ZERO : loan.getTotalRepaidInterest())
                        .add(interestPortion)));
        loan.setTotalRepaidPenalty(MoneyUtils.scaleForStorage(
                (loan.getTotalRepaidPenalty() == null ? BigDecimal.ZERO : loan.getTotalRepaidPenalty())
                        .add(penaltyPortion)));

        if (MoneyUtils.isZero(MoneyUtils.scale(loan.getOutstandingPrincipal()))
                && MoneyUtils.isZero(MoneyUtils.scale(loan.getOutstandingInterest()))
                && MoneyUtils.isZero(MoneyUtils.scale(loan.getOutstandingPenalty()))) {
            loan.setStatus(LoanStatus.CLOSED);
        } else if (loan.getStatus() == LoanStatus.OVERDUE
                && loan.getDueDate() != null
                && !loan.getDueDate().isBefore(today())) {
            loan.setStatus(LoanStatus.ACTIVE);
        }
        loanRepository.save(loan);
        applyPenaltyPaymentsToFines(installments, split, repayment, actorId, cooperative.getCurrency());

        if (principalPortion.compareTo(BigDecimal.ZERO) > 0) {
            ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                    .cooperativeId(cooperative.getId())
                    .memberUserId(loan.getMemberUserId())
                    .transactionType(LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT)
                    .debitAmount(BigDecimal.ZERO)
                    .creditAmount(principalPortion)
                    .currency(cooperative.getCurrency())
                    .transactionDate(paymentDate)
                    .reference(repayment.getPaymentReference())
                    .sourceEntityType(LedgerService.SOURCE_LOAN_REPAYMENT)
                    .sourceEntityId(repayment.getId())
                    .description("Loan principal repayment")
                    .recordedBy(actorId)
                    .approvedBy(actorId)
                    .idempotencyKey(LedgerService.loanPrincipalRepaymentKey(repayment.getId()))
                    .build());
        }
        if (interestPortion.compareTo(BigDecimal.ZERO) > 0) {
            ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                    .cooperativeId(cooperative.getId())
                    .memberUserId(loan.getMemberUserId())
                    .transactionType(LedgerTransactionType.LOAN_INTEREST_PAYMENT)
                    .debitAmount(BigDecimal.ZERO)
                    .creditAmount(interestPortion)
                    .currency(cooperative.getCurrency())
                    .transactionDate(paymentDate)
                    .reference(repayment.getPaymentReference())
                    .sourceEntityType(LedgerService.SOURCE_LOAN_REPAYMENT)
                    .sourceEntityId(repayment.getId())
                    .description("Loan interest payment")
                    .recordedBy(actorId)
                    .approvedBy(actorId)
                    .idempotencyKey(LedgerService.loanInterestRepaymentKey(repayment.getId()))
                    .build());
        }

        return repayment;
    }

    /**
     * Corrects a repayment recorded with a mistake (wrong amount, date or reference). The wrong repayment is given back
     * to the loan exactly as {@link #reverseRepayment} does (it stays on record, marked reversed) and the corrected one
     * is recorded in its place, pointing to it with the reason. Repayments that came from a member payment or a member
     * credit are corrected through those (reverse the payment), not here.
     */
    @Transactional
    public LoanRepaymentResponse reviseRepayment(
            UUID cooperativeId,
            UUID loanId,
            UUID repaymentId,
            LoanRepaymentReviseRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        String reason = trimToNull(request.getReason());
        if (reason == null || reason.length() < 5) {
            throw new BusinessException("REVISION_REASON_REQUIRED", "Say why the repayment is revised (at least 5 characters)");
        }
        schemeFundLockService.lockSchemeFund(cooperativeId);
        Loan loan = loanRepository
                .findByIdAndCooperativeIdForUpdate(loanId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
        LoanRepayment original = loanRepaymentRepository
                .findById(repaymentId)
                .filter(r -> r.getCooperativeId().equals(cooperativeId) && r.getLoanId().equals(loanId))
                .orElseThrow(() -> new ResourceNotFoundException("Loan repayment", repaymentId));
        if (loanRepaymentRepository.countMemberPaymentPostings(repaymentId) > 0
                || loanRepaymentRepository.countMemberCreditApplications(repaymentId) > 0) {
            throw new BusinessException(
                    "REPAYMENT_FROM_PAYMENT",
                    "This repayment was posted from a member payment; correct it by reversing that payment");
        }
        BigDecimal amount = MoneyUtils.scaleForStorage(request.getAmount());
        MoneyUtils.assertPositive(amount);
        LoanRepaymentResponse before = toRepaymentResponse(original);

        reverseRepayment(loan, original, principal.getId());
        LoanRepayment revised = applyRepayment(
                cooperative,
                loan,
                amount,
                request.getPaymentDate() != null ? request.getPaymentDate() : original.getPaymentDate(),
                request.getPaymentReference() != null ? trimToNull(request.getPaymentReference()) : original.getPaymentReference(),
                request.getNotes() != null ? trimToNull(request.getNotes()) : original.getNotes(),
                request.getAllocateInterestFirst(),
                principal.getId(),
                original.getId(),
                reason);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.LOAN_REPAYMENT_REVISION,
                "LoanRepayment",
                revised.getId(),
                "{\"repaymentId\":\"" + original.getId() + "\",\"amount\":\"" + before.getAmountTotal()
                        + "\",\"paymentDate\":\"" + before.getPaymentDate() + "\"}",
                "{\"repaymentId\":\"" + revised.getId() + "\",\"amount\":\"" + revised.getAmountTotal()
                        + "\",\"paymentDate\":\"" + revised.getPaymentDate() + "\",\"reason\":"
                        + jsonString(reason) + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toRepaymentResponse(revised);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    /** What repaying the whole loan on {@code date} (default today) takes: interest only for the months used. */
    @Transactional(readOnly = true)
    public EarlyRepaymentInterest.Settlement settlementQuote(UUID cooperativeId, UUID loanId, LocalDate date) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        Loan loan = requireLoan(cooperativeId, loanId);
        requireLoanReadAccess(loan);
        boolean scheduled = isScheduleBased(loan) && loanInstallmentRepository.existsByLoanId(loan.getId());
        return EarlyRepaymentInterest.settlement(
                loan, date == null ? today() : date, loan.getOutstandingPrincipal(), loan.getOutstandingInterest(),
                loan.getOutstandingPenalty(), scheduled);
    }

    @Transactional(readOnly = true)
    public List<LoanRepaymentResponse> listRepayments(UUID cooperativeId, UUID loanId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        requireLoanReadAccess(requireLoan(cooperativeId, loanId));
        List<LoanRepaymentResponse> rows = loanRepaymentRepository
                .findByLoanIdAndCooperativeIdOrderByPaymentDateDescCreatedAtDesc(loanId, cooperativeId)
                .stream()
                .map(this::toRepaymentResponse)
                .map(r -> {
                    r.setRevisable(!r.isReversed()
                            && loanRepaymentRepository.countMemberPaymentPostings(r.getId()) == 0
                            && loanRepaymentRepository.countMemberCreditApplications(r.getId()) == 0);
                    return r;
                })
                .toList();
        java.util.Map<UUID, LoanRepaymentResponse> byId = new java.util.HashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        for (LoanRepaymentResponse r : rows) {
            if (r.getRevisionOfId() != null && byId.containsKey(r.getRevisionOfId())) {
                byId.get(r.getRevisionOfId()).setRevisedById(r.getId());
            }
        }
        return rows;
    }

    /** What giving one repayment back to a loan restored, and where the loan stood before and after. */
    public record RepaymentReversal(
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal penalty,
            BigDecimal principalBefore,
            BigDecimal principalAfter,
            BigDecimal interestBefore,
            BigDecimal interestAfter,
            BigDecimal penaltyBefore,
            BigDecimal penaltyAfter,
            LoanStatus statusBefore,
            LoanStatus statusAfter,
            UUID principalLedgerEntryId,
            UUID interestLedgerEntryId) {}

    /**
     * Gives a repayment back to its loan: the loan owes again exactly what the repayment had taken (principal, interest and
     * penalty), the instalments it paid are reopened, a loan it closed is reopened, its ledger credits are taken back with
     * REVERSAL entries, and the repayment is flagged reversed. The repayment itself is never edited or deleted. The caller
     * holds the scheme fund lock and the loan row lock, in an open transaction.
     *
     * <p>It is refused, never forced, when it could not leave the loan consistent: when a later repayment of the same
     * loan is still in force (reverse that first), when the loan was written off or is not yet running, when the
     * repayment paid a penalty (those are settled through fines and need their own reversal), or when the loan no longer
     * holds what the repayment put into it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RepaymentReversal reverseRepayment(Loan loan, LoanRepayment repayment, UUID actorId) {
        if (repayment.isReversed()) {
            throw new BusinessException("REPAYMENT_ALREADY_REVERSED", "This repayment was already reversed");
        }
        if (!repayment.getLoanId().equals(loan.getId()) || !repayment.getCooperativeId().equals(loan.getCooperativeId())) {
            throw new BusinessException("REVERSAL_BLOCKED_LATER_CHANGES", "The repayment does not belong to this loan");
        }
        LoanStatus before = loan.getStatus();
        if (before != LoanStatus.ACTIVE && before != LoanStatus.OVERDUE && before != LoanStatus.CLOSED) {
            throw new BusinessException(
                    "REVERSAL_BLOCKED_LOAN_STATE", "A repayment can only be reversed on an active, overdue or closed loan");
        }
        BigDecimal principal = MoneyUtils.scaleForStorage(repayment.getPrincipalPortion());
        BigDecimal interest = MoneyUtils.scaleForStorage(repayment.getInterestPortion());
        BigDecimal penalty = MoneyUtils.scaleForStorage(
                repayment.getPenaltyPortion() == null ? BigDecimal.ZERO : repayment.getPenaltyPortion());
        if (penalty.signum() > 0) {
            throw new BusinessException(
                    "REVERSAL_BLOCKED_PENALTY",
                    "This repayment paid a penalty, which is settled through fines; it cannot be reversed automatically");
        }
        if (loanRepaymentRepository.existsLaterActiveRepayment(loan.getId(), repayment.getId(), repayment.getCreatedAt())) {
            throw new BusinessException(
                    "REVERSAL_BLOCKED_LATER_REPAYMENT",
                    "A later repayment of this loan is still in force; reverse that one first");
        }

        BigDecimal principalBefore = MoneyUtils.scaleForStorage(nz(loan.getOutstandingPrincipal()));
        BigDecimal interestBefore = MoneyUtils.scaleForStorage(nz(loan.getOutstandingInterest()));
        BigDecimal penaltyBefore = MoneyUtils.scaleForStorage(nz(loan.getOutstandingPenalty()));
        BigDecimal repaidPrincipal = MoneyUtils.scaleForStorage(nz(loan.getTotalRepaidPrincipal()));
        BigDecimal repaidInterest = MoneyUtils.scaleForStorage(nz(loan.getTotalRepaidInterest()));
        if (repaidPrincipal.compareTo(principal) < 0 || repaidInterest.compareTo(interest) < 0) {
            throw new BusinessException(
                    "REVERSAL_BLOCKED_LATER_CHANGES", "The loan no longer shows that much repaid, so this repayment cannot be given back");
        }
        BigDecimal principalAfter = MoneyUtils.scaleForStorage(principalBefore.add(principal));
        BigDecimal ceiling = loan.getPrincipalAmount() != null ? loan.getPrincipalAmount() : loan.getApprovedAmount();
        if (ceiling != null && principalAfter.compareTo(MoneyUtils.scaleForStorage(ceiling)) > 0) {
            throw new BusinessException(
                    "REVERSAL_BLOCKED_LATER_CHANGES", "Giving this repayment back would leave more principal than the loan was for");
        }

        // the instalments this repayment paid are reopened exactly as it paid them
        List<LoanRepaymentAllocation> paid = loanRepaymentAllocationRepository.findByRepaymentIdOrderByCreatedAtAsc(repayment.getId());
        List<LoanInterestWaiver> waivers = loanInterestWaiverRepository.findByRepaymentId(repayment.getId());
        BigDecimal waived = MoneyUtils.scaleForStorage(nz(repayment.getInterestWaived()));
        if (!paid.isEmpty() || waivers.stream().anyMatch(w -> w.getInstallmentId() != null)) {
            Map<UUID, LoanInstallment> installments = new HashMap<>();
            for (LoanInstallment i : loanInstallmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loan.getId())) {
                installments.put(i.getId(), i);
            }
            // the interest of unused months it waived is owed again
            for (LoanInterestWaiver w : waivers) {
                LoanInstallment installment = w.getInstallmentId() == null ? null : installments.get(w.getInstallmentId());
                if (installment != null) {
                    installment.setInterestDue(MoneyUtils.scaleForStorage(nz(installment.getInterestDue()).add(w.getAmount())));
                }
            }
            for (LoanRepaymentAllocation a : paid) {
                LoanInstallment installment = installments.get(a.getInstallmentId());
                if (installment == null) {
                    throw new BusinessException("REVERSAL_BLOCKED_LATER_CHANGES", "An instalment this repayment paid no longer exists");
                }
                BigDecimal amount = MoneyUtils.scaleForStorage(a.getAmount());
                switch (a.getComponent()) {
                    case PRINCIPAL -> installment.setPrincipalPaid(takeBack(installment.getPrincipalPaid(), amount));
                    case INTEREST -> installment.setInterestPaid(takeBack(installment.getInterestPaid(), amount));
                    case PENALTY -> throw new BusinessException(
                            "REVERSAL_BLOCKED_PENALTY", "This repayment paid a penalty; it cannot be reversed automatically");
                }
            }
            LocalDate asOf = today();
            installments.values().forEach(i -> i.refreshStatus(asOf));
            loanInstallmentRepository.saveAll(installments.values());
        }

        loan.setOutstandingPrincipal(principalAfter);
        loan.setOutstandingInterest(MoneyUtils.scaleForStorage(interestBefore.add(interest).add(waived)));
        if (waived.signum() > 0 && loan.getInterestAmount() != null) {
            loan.setInterestAmount(MoneyUtils.scaleForStorage(loan.getInterestAmount().add(waived)));
        }
        loan.setOutstandingPenalty(penaltyBefore);
        loan.setTotalRepaidPrincipal(MoneyUtils.scaleForStorage(repaidPrincipal.subtract(principal)));
        loan.setTotalRepaidInterest(MoneyUtils.scaleForStorage(repaidInterest.subtract(interest)));
        if (before == LoanStatus.CLOSED) {
            boolean late = loan.getDueDate() != null && loan.getDueDate().isBefore(today());
            loan.setStatus(late ? LoanStatus.OVERDUE : LoanStatus.ACTIVE);
        }
        loanRepository.saveAndFlush(loan);

        UUID principalLedger = null;
        UUID interestLedger = null;
        if (principal.signum() > 0) {
            principalLedger = reverseLedger(repayment, LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT, "PRINCIPAL", actorId);
        }
        if (interest.signum() > 0) {
            interestLedger = reverseLedger(repayment, LedgerTransactionType.LOAN_INTEREST_PAYMENT, "INTEREST", actorId);
        }
        repayment.setReversedAt(java.time.Instant.now());
        repayment.setReversedBy(actorId);
        loanRepaymentRepository.saveAndFlush(repayment);
        return new RepaymentReversal(
                principal, interest, penalty, principalBefore, principalAfter, interestBefore, loan.getOutstandingInterest(),
                penaltyBefore, penaltyBefore, before, loan.getStatus(), principalLedger, interestLedger);
    }

    private UUID reverseLedger(LoanRepayment repayment, LedgerTransactionType type, String component, UUID actorId) {
        return ledgerService
                .reverseApprovedCredit(
                        LedgerService.SOURCE_LOAN_REPAYMENT,
                        repayment.getId(),
                        type,
                        LedgerService.loanRepaymentReversalKey(repayment.getId(), component),
                        actorId,
                        "Reversal of the loan " + component.toLowerCase(Locale.ROOT) + " repayment")
                .map(rw.terimbere.csams.modules.ledger.entity.LedgerEntry::getId)
                .orElseThrow(() -> new BusinessException(
                        "LEDGER_REFERENCE_MISSING", "The ledger credit of this repayment could not be found to reverse"));
    }

    private static BigDecimal takeBack(BigDecimal paid, BigDecimal amount) {
        BigDecimal now = MoneyUtils.scaleForStorage(nz(paid));
        if (now.compareTo(amount) < 0) {
            throw new BusinessException("REVERSAL_BLOCKED_LATER_CHANGES", "An instalment holds less than this repayment paid into it");
        }
        return MoneyUtils.scaleForStorage(now.subtract(amount));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional(readOnly = true)
    public BigDecimal sumOutstandingPrincipalActiveOverdue(UUID cooperativeId) {
        BigDecimal sum = loanRepository.sumOutstandingPrincipalByStatuses(
                cooperativeId, EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE));
        return MoneyUtils.scale(sum == null ? BigDecimal.ZERO : sum);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumDisbursedPrincipal(UUID cooperativeId) {
        BigDecimal sum = loanRepository.sumPrincipalByStatuses(
                cooperativeId,
                EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.CLOSED, LoanStatus.WRITTEN_OFF));
        return MoneyUtils.scale(sum == null ? BigDecimal.ZERO : sum);
    }

    @Transactional(readOnly = true)
    public long countOverdue(UUID cooperativeId) {
        return loanRepository.countByCooperativeIdAndStatus(cooperativeId, LoanStatus.OVERDUE);
    }

    private void enforceMinMembershipMonths(CooperativeMembership membership, int minMonths) {
        if (minMonths <= 0) {
            return;
        }
        LocalDate membershipDate = membership.getMembershipDate();
        if (membershipDate == null) {
            throw new BusinessException("Membership date is required to validate loan eligibility");
        }
        long months = ChronoUnit.MONTHS.between(membershipDate, today());
        if (months < minMonths) {
            throw new BusinessException(
                    "Member must have at least " + minMonths + " months of membership to request a loan");
        }
    }

    private LoanEligibilityResponse evaluateEligibility(
            UUID cooperativeId, UUID memberUserId, BigDecimal requestedAmount, UUID excludeLoanId) {
        List<Loan> openLoans = loanRepository.findByCooperativeIdAndMemberUserIdAndStatusIn(
                cooperativeId, memberUserId, OPEN_LOAN_STATUSES);
        BigDecimal existingAmount = BigDecimal.ZERO;
        BigDecimal repaid = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        // principal still owed on loans running, plus loans requested or approved but not yet paid out: it uses up the
        // member's limit
        BigDecimal limitUsed = BigDecimal.ZERO;
        for (Loan open : openLoans) {
            if (excludeLoanId != null && excludeLoanId.equals(open.getId())) {
                continue;
            }
            if (open.getStatus() == LoanStatus.ACTIVE || open.getStatus() == LoanStatus.OVERDUE) {
                limitUsed = limitUsed.add(open.getOutstandingPrincipal() == null ? BigDecimal.ZERO : open.getOutstandingPrincipal());
            } else {
                BigDecimal pending = open.getApprovedAmount() != null ? open.getApprovedAmount() : open.getRequestedAmount();
                limitUsed = limitUsed.add(pending == null ? BigDecimal.ZERO : pending);
            }
            BigDecimal principal = open.getPrincipalAmount() != null
                    ? open.getPrincipalAmount()
                    : open.getApprovedAmount() != null ? open.getApprovedAmount() : open.getRequestedAmount();
            existingAmount = existingAmount.add(principal == null ? BigDecimal.ZERO : principal);
            repaid = repaid.add(open.getTotalRepaidPrincipal() == null
                    ? BigDecimal.ZERO
                    : open.getTotalRepaidPrincipal());
            repaid = repaid.add(open.getTotalRepaidInterest() == null
                    ? BigDecimal.ZERO
                    : open.getTotalRepaidInterest());
            outstanding = outstanding.add(open.getOutstandingPrincipal() == null
                    ? BigDecimal.ZERO
                    : open.getOutstandingPrincipal());
            outstanding = outstanding.add(open.getOutstandingInterest() == null
                    ? BigDecimal.ZERO
                    : open.getOutstandingInterest());
            if (open.getStatus() == LoanStatus.PENDING
                    || open.getStatus() == LoanStatus.AWAITING_SECOND_APPROVAL
                    || open.getStatus() == LoanStatus.APPROVED) {
                outstanding = outstanding.add(
                        principal == null || principal.compareTo(BigDecimal.ZERO) == 0
                                ? (open.getRequestedAmount() == null ? BigDecimal.ZERO : open.getRequestedAmount())
                                : BigDecimal.ZERO);
            }
        }
        // A member may hold more than one loan: each goes through the usual approval, and together they stay within the
        // member's limit (by shares and by the scheme's maximum).
        boolean eligible = true;
        String reason = "Member is eligible for a new loan";

        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElse(null);
        int shareCount = membership == null
                ? ShareAmountCalculator.DEFAULT_SHARE_COUNT
                : ShareAmountCalculator.normalizeShareCount(membership.getShareCount());
        Number totalSharesNumber = membershipRepository.sumShareCountByCooperativeIdAndActiveStatus(cooperativeId);
        long totalShares = totalSharesNumber == null ? 0L : totalSharesNumber.longValue();
        BigDecimal sharePercent = LoanShareLimitCalculator.sharePercent(shareCount, totalShares);
        List<LoanShareTier> tiers =
                loanShareTierRepository.findByCooperativeIdOrderByMinSharePercentDesc(cooperativeId);
        BigDecimal maxByShares = LoanShareLimitCalculator.matchingMaxLoan(sharePercent, tiers).orElse(null);
        LoanSettings settings = loanSettingsService.requireSettings(cooperativeId);
        BigDecimal effectiveMax = settings.getMaxLoanAmount();
        if (!tiers.isEmpty() && maxByShares == null && eligible) {
            eligible = false;
            reason = "Member share percentage does not meet any configured loan level";
            effectiveMax = BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE);
        } else if (maxByShares != null) {
            effectiveMax = effectiveMax == null ? maxByShares : effectiveMax.min(maxByShares);
        }
        if (settings.getMaxLoanPerShare() != null) {
            // Loans are granted by shares: the limit is the amount per share times the shares the member holds, less
            // what the member still owes on loans in progress.
            BigDecimal perShareMax = settings.getMaxLoanPerShare().multiply(BigDecimal.valueOf(shareCount));
            effectiveMax = effectiveMax == null ? perShareMax : effectiveMax.min(perShareMax);
            if (maxByShares == null) {
                maxByShares = perShareMax;
            }
        }
        BigDecimal fullLimit = effectiveMax;
        if (effectiveMax != null && limitUsed.signum() > 0) {
            effectiveMax = effectiveMax.subtract(limitUsed).max(BigDecimal.ZERO);
            if (eligible && effectiveMax.signum() <= 0) {
                eligible = false;
                reason = "Member has reached the loan limit (" + MoneyUtils.scale(fullLimit) + ") with "
                        + MoneyUtils.scale(limitUsed) + " still owed or requested on other loans";
            }
        }
        if (eligible && settings.isBlockWhenContributionOverdue() && hasOverdueContribution(cooperativeId, memberUserId)) {
            eligible = false;
            reason = "Member has a contribution past its due date; it must be paid before a new loan";
        }
        if (eligible && requestedAmount != null && effectiveMax != null
                && MoneyUtils.scaleForStorage(requestedAmount).compareTo(MoneyUtils.scaleForStorage(effectiveMax))
                        > 0) {
            eligible = false;
            reason = limitUsed.signum() > 0
                    ? "Requested amount exceeds what is left of this member's loan limit: " + MoneyUtils.scale(effectiveMax)
                            + " (limit " + MoneyUtils.scale(fullLimit) + " less " + MoneyUtils.scale(limitUsed)
                            + " still owed or requested on other loans)"
                    : maxByShares != null
                    ? "Requested amount exceeds the loan level for this member's share percentage (maximum "
                            + MoneyUtils.scale(effectiveMax)
                            + ")"
                    : "Requested amount exceeds maximum loan amount";
        }

        return LoanEligibilityResponse.builder()
                .memberUserId(memberUserId)
                .eligible(eligible)
                .reason(reason)
                .existingLoanAmount(MoneyUtils.scale(existingAmount))
                .amountAlreadyRepaid(MoneyUtils.scale(repaid))
                .outstandingBalance(MoneyUtils.scale(outstanding))
                .requestedAmount(requestedAmount == null ? null : MoneyUtils.scale(requestedAmount))
                .shareCount(shareCount)
                .totalShares(totalShares)
                .sharePercent(sharePercent)
                .maxLoanByShares(maxByShares == null ? null : MoneyUtils.scale(maxByShares))
                .maxEligibleAmount(effectiveMax == null ? null : MoneyUtils.scale(effectiveMax))
                .build();
    }

    private boolean hasOverdueContribution(UUID cooperativeId, UUID memberUserId) {
        LocalDate today = today();
        return contributionRepository.findByCooperativeIdAndMemberUserIdOrderByYearDescMonthDesc(cooperativeId, memberUserId).stream()
                .anyMatch(c -> c.getStatus() != rw.terimbere.csams.modules.contribution.entity.ContributionStatus.WAIVED
                        && c.getStatus() != rw.terimbere.csams.modules.contribution.entity.ContributionStatus.CANCELLED
                        && c.getOutstandingAmount() != null && c.getOutstandingAmount().signum() > 0
                        && c.getDueDate() != null && c.getDueDate().isBefore(today));
    }

    private static LoanGuaranteeMode resolveGuaranteeMode(LoanRequestCreateRequest request) {
        if (request.getGuaranteeMode() != null) {
            return request.getGuaranteeMode();
        }
        return request.getGuarantorUserId() != null ? LoanGuaranteeMode.GUARANTOR : LoanGuaranteeMode.SELF;
    }

    private int resolveTermMonths(Integer requested, LoanSettings settings) {
        if (requested != null) {
            return requested;
        }
        if (settings.getMaxTermMonths() != null) {
            return Math.min(DEFAULT_TERM_MONTHS, settings.getMaxTermMonths());
        }
        return DEFAULT_TERM_MONTHS;
    }

    private void applyFlatScheduleTerms(Loan loan, BigDecimal principal, LocalDate referenceDate) {
        if (loan.getInterestType() == InterestType.REDUCING) {
            loan.setInterestAmount(LoanInterestCalculator.computeInterest(
                    principal, loan.getInterestRatePercent(), loan.getInterestType()));
            return;
        }
        LoanSettings settings = loanSettingsService.requireSettings(loan.getCooperativeId());
        LoanRepaymentDateModel model = settings.getRepaymentDateModel() == null
                ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH
                : settings.getRepaymentDateModel();
        loan.setRepaymentDateModel(model);
        loan.setProrataEnabled(model == LoanRepaymentDateModel.MONTH_END);
        LoanScheduleResponse schedule = LoanScheduleCalculator.calculate(
                principal,
                loan.getInterestRatePercent(),
                loan.getTermMonths(),
                model,
                referenceDate);
        loan.setInterestAmount(MoneyUtils.scaleForStorage(schedule.getTotalInterest()));
        loan.setEqualInstallmentAmount(MoneyUtils.scaleForStorage(schedule.getEqualInstallmentAmount()));
        loan.setFirstPeriodDays(schedule.getFirstPeriodDays());
    }

    private void finalizeScheduleAtDisbursement(
            Loan loan, BigDecimal principalAmount, LocalDate disbursementDate) {
        if (loan.getInterestType() == InterestType.REDUCING) {
            if (loan.getInterestAmount() == null) {
                loan.setInterestAmount(LoanInterestCalculator.computeInterest(
                        principalAmount, loan.getInterestRatePercent(), loan.getInterestType()));
            }
            return;
        }
        if (!isScheduleBased(loan) && loan.getInterestAmount() != null) {
            return;
        }
        LoanSettings settings = loanSettingsService.requireSettings(loan.getCooperativeId());
        snapshotRepaymentPolicy(loan, settings);
        LoanRepaymentDateModel model = loan.getRepaymentDateModel() == null
                ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH
                : loan.getRepaymentDateModel();
        loan.setProrataEnabled(model == LoanRepaymentDateModel.MONTH_END);
        LoanScheduleResponse schedule = LoanScheduleCalculator.calculate(
                principalAmount,
                loan.getInterestRatePercent(),
                loan.getTermMonths(),
                model,
                disbursementDate);
        schedule.setScheduleFinalized(true);
        loan.setInterestAmount(MoneyUtils.scaleForStorage(schedule.getTotalInterest()));
        loan.setEqualInstallmentAmount(MoneyUtils.scaleForStorage(schedule.getEqualInstallmentAmount()));
        loan.setFirstPeriodDays(schedule.getFirstPeriodDays());
        persistInstallments(loan, schedule);
        if (!schedule.getInstallments().isEmpty()) {
            loan.setDueDate(schedule.getInstallments().get(schedule.getInstallments().size() - 1).getDueDate());
        }
    }

    private void snapshotRepaymentPolicy(Loan loan, LoanSettings settings) {
        loan.setRepaymentDateModel(settings.getRepaymentDateModel() == null
                ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH
                : settings.getRepaymentDateModel());
        loan.setLoanPenaltyEnabled(settings.isLateFeeEnabled());
        loan.setPenaltyType(settings.getPenaltyType());
        loan.setPenaltyRateOrAmount(settings.getPenaltyRateOrAmount());
        loan.setPenaltyFrequency(settings.getPenaltyFrequency());
        loan.setGracePeriodDays(settings.getGracePeriodDays());
        loan.setAllocationOrder(LoanAllocationOrder.serialize(
                LoanAllocationOrder.parse(settings.getAllocationOrder())));
    }

    private void persistInstallments(Loan loan, LoanScheduleResponse schedule) {
        Loan saved = loanRepository.save(loan);
        if (loanInstallmentRepository.existsByLoanId(saved.getId())) {
            return;
        }
        UUID loanId = saved.getId();
        UUID cooperativeId = saved.getCooperativeId();
        List<LoanInstallment> rows = schedule.getInstallments().stream()
                .map(item -> LoanInstallment.builder()
                        .loanId(loanId)
                        .cooperativeId(cooperativeId)
                        .installmentNumber(item.getInstallmentNumber())
                        .dueDate(item.getDueDate())
                        .openingPrincipalBalance(MoneyUtils.scaleForStorage(item.getOpeningPrincipalBalance()))
                        .principalDue(MoneyUtils.scaleForStorage(item.getPrincipalComponent()))
                        .interestDue(MoneyUtils.scaleForStorage(item.getInterestComponent()))
                        .penaltyDue(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                        .principalPaid(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                        .interestPaid(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                        .penaltyPaid(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE))
                        .scheduledInstallmentAmount(MoneyUtils.scaleForStorage(item.getScheduledInstallmentAmount()))
                        .status(item.getStatus())
                        .build())
                .toList();
        for (LoanInstallment row : rows) {
            row.refreshStatus(today());
        }
        loanInstallmentRepository.saveAll(rows);
    }

    private boolean isScheduleBased(Loan loan) {
        if (loan.getInterestType() == InterestType.REDUCING) {
            return false;
        }
        return loan.getEqualInstallmentAmount() != null
                || (loan.getId() != null && loanInstallmentRepository.existsByLoanId(loan.getId()));
    }

    private LoanScheduleResponse buildScheduleForLoan(Loan loan) {
        if (!isScheduleBased(loan)) {
            return null;
        }
        if (loan.getId() != null && loanInstallmentRepository.existsByLoanId(loan.getId())) {
            List<LoanInstallment> installments =
                    loanInstallmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loan.getId());
            return toScheduleResponse(loan, installments, true);
        }
        BigDecimal principal = loan.getPrincipalAmount() != null
                ? loan.getPrincipalAmount()
                : loan.getApprovedAmount() != null ? loan.getApprovedAmount() : loan.getRequestedAmount();
        if (principal == null || principal.compareTo(BigDecimal.ZERO) <= 0 || loan.getTermMonths() <= 0) {
            return null;
        }
        LocalDate start = loan.getDisbursementDate() != null
                ? loan.getDisbursementDate()
                : loan.getApprovalDate() != null ? loan.getApprovalDate() : loan.getRequestDate();
        if (start == null) {
            start = today();
        }
        LoanRepaymentDateModel model = loan.getRepaymentDateModel() == null
                ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH
                : loan.getRepaymentDateModel();
        LoanScheduleResponse schedule = LoanScheduleCalculator.calculate(
                principal,
                loan.getInterestRatePercent(),
                loan.getTermMonths(),
                model,
                start);
        schedule.setScheduleFinalized(false);
        return schedule;
    }

    private LoanScheduleResponse toScheduleResponse(
            Loan loan, List<LoanInstallment> installments, boolean finalized) {
        List<rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse> rows = installments.stream()
                .map(this::toInstallmentResponse)
                .toList();
        BigDecimal totalInterest = rows.stream()
                .map(rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse::getInterestComponent)
                .reduce(BigDecimal.ZERO, MoneyUtils::add);
        BigDecimal totalRepayment = rows.stream()
                .map(rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse::getScheduledInstallmentAmount)
                .reduce(BigDecimal.ZERO, MoneyUtils::add);
        return LoanScheduleResponse.builder()
                .principal(scaleOrNull(loan.getPrincipalAmount() != null
                        ? loan.getPrincipalAmount()
                        : loan.getApprovedAmount()))
                .monthlyInterestRatePercent(loan.getInterestRatePercent() == null
                        ? null
                        : MoneyUtils.scale(loan.getInterestRatePercent()))
                .numberOfInstallments(rows.size())
                .repaymentDateModel(loan.getRepaymentDateModel())
                .prorataEnabled(loan.isProrataEnabled())
                .firstPeriodDays(loan.getFirstPeriodDays())
                .regularMonthlyInterest(rows.size() > 1 ? rows.get(1).getInterestComponent() : null)
                .firstPeriodInterest(rows.isEmpty() ? null : rows.get(0).getInterestComponent())
                .totalInterest(totalInterest)
                .totalRepayment(totalRepayment)
                .equalInstallmentAmount(scaleOrNull(loan.getEqualInstallmentAmount()))
                .scheduleFinalized(finalized)
                .installments(rows)
                .build();
    }

    private rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse toInstallmentResponse(
            LoanInstallment installment) {
        installment.refreshStatus(today());
        return rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse.builder()
                .id(installment.getId())
                .installmentNumber(installment.getInstallmentNumber())
                .dueDate(installment.getDueDate())
                .openingPrincipalBalance(scaleOrNull(installment.getOpeningPrincipalBalance()))
                .paymentAmount(scaleOrNull(installment.totalDue()))
                .scheduledInstallmentAmount(scaleOrNull(installment.getScheduledInstallmentAmount()))
                .principalComponent(scaleOrNull(installment.getPrincipalDue()))
                .interestComponent(scaleOrNull(installment.getInterestDue()))
                .penaltyDue(scaleOrNull(installment.getPenaltyDue()))
                .remainingPrincipal(scaleOrNull(installment.remainingPrincipal()))
                .status(installment.getStatus())
                .amountPaid(scaleOrNull(installment.totalPaid()))
                .balance(scaleOrNull(installment.remainingAmount()))
                .principalPaid(scaleOrNull(installment.getPrincipalPaid()))
                .interestPaid(scaleOrNull(installment.getInterestPaid()))
                .penaltyPaid(scaleOrNull(installment.getPenaltyPaid()))
                .remainingAmount(scaleOrNull(installment.remainingAmount()))
                .build();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Ordinary members may read only their own loan. Officers and Super Admin
     * may read any loan in a cooperative they are allowed to access.
     */
    void requireLoanReadAccess(Loan loan) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(loan.getCooperativeId());
        if (CooperativeOfficerRoles.isOfficer(principal)) {
            return;
        }
        if (principal.getId() != null && principal.getId().equals(loan.getMemberUserId())) {
            return;
        }
        throw new ForbiddenException();
    }

    private Loan requireLoan(UUID cooperativeId, UUID loanId) {
        return loanRepository
                .findByIdAndCooperativeId(loanId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private void applyFirstApproval(Loan loan, LoanApproveRequest request, UserPrincipal principal) {
        LoanSettings settings = loanSettingsService.requireSettings(loan.getCooperativeId());
        BigDecimal approvedAmount = request != null && request.getApprovedAmount() != null
                ? MoneyUtils.scaleForStorage(request.getApprovedAmount())
                : loan.getRequestedAmount();
        MoneyUtils.assertPositive(approvedAmount);
        LoanEligibilityResponse eligibility = evaluateEligibility(
                loan.getCooperativeId(), loan.getMemberUserId(), approvedAmount, loan.getId());
        if (eligibility.getMaxEligibleAmount() != null
                && approvedAmount.compareTo(MoneyUtils.scaleForStorage(eligibility.getMaxEligibleAmount())) > 0) {
            throw new BusinessException(
                    eligibility.getReason() == null
                            ? "Approved amount exceeds the loan level for this member's shares"
                            : eligibility.getReason());
        }
        if (request != null && request.getTermMonths() != null) {
            int term = request.getTermMonths();
            if (settings.getMaxTermMonths() != null && term > settings.getMaxTermMonths()) {
                throw new BusinessException("Term exceeds maximum allowed months");
            }
            loan.setTermMonths(term);
        }
        loan.setApprovedAmount(approvedAmount);
        applyFlatScheduleTerms(loan, approvedAmount, today());
        loan.setFirstApprovedBy(principal.getId());
        loan.setFirstApprovedAt(Instant.now());
        loan.setFirstApproverRole(CooperativeOfficerRoles.displayRole(principal));
        loan.setStatus(LoanStatus.AWAITING_SECOND_APPROVAL);
    }

    private void applySecondApproval(Loan loan, LoanApproveRequest request, UserPrincipal principal) {
        if (request != null && request.getApprovedAmount() != null) {
            MoneyUtils.assertPositive(request.getApprovedAmount());
            loan.setApprovedAmount(MoneyUtils.scaleForStorage(request.getApprovedAmount()));
            applyFlatScheduleTerms(loan, loan.getApprovedAmount(), today());
        }
        loan.setApprovedBy(principal.getId());
        loan.setApprovalDate(today());
        loan.setStatus(LoanStatus.APPROVED);
    }

    private LoanResponse toResponse(Loan loan) {
        return toResponse(loan, null, false);
    }

    private LoanResponse toResponse(Loan loan, String ignoredCurrency) {
        return toResponse(loan, ignoredCurrency, false);
    }

    private LoanResponse toResponse(Loan loan, String ignoredCurrency, boolean includeHistory) {
        String memberName = userRepository
                .findByIdAndDeletedFalse(loan.getMemberUserId())
                .map(this::formatName)
                .orElse(null);
        LoanApplicationFormResponse form = parseSnapshot(loan.getApplicationSnapshot());
        if (form == null) {
            form = buildApplicationFormFromLoan(loan);
        }
        LoanScheduleResponse schedule = buildScheduleForLoan(loan);
        return LoanResponse.builder()
                .id(loan.getId())
                .cooperativeId(loan.getCooperativeId())
                .memberUserId(loan.getMemberUserId())
                .memberName(memberName)
                .requestedAmount(scaleOrNull(loan.getRequestedAmount()))
                .approvedAmount(scaleOrNull(loan.getApprovedAmount()))
                .principalAmount(scaleOrNull(loan.getPrincipalAmount()))
                .interestRatePercent(
                        loan.getInterestRatePercent() == null
                                ? null
                                : MoneyUtils.scale(loan.getInterestRatePercent()))
                .interestType(loan.getInterestType())
                .termMonths(loan.getTermMonths())
                .interestAmount(scaleOrNull(loan.getInterestAmount()))
                .prorataEnabled(loan.isProrataEnabled())
                .firstPeriodDays(loan.getFirstPeriodDays())
                .regularMonthlyInterest(schedule == null ? null : scaleOrNull(schedule.getRegularMonthlyInterest()))
                .firstPeriodInterest(schedule == null ? null : scaleOrNull(schedule.getFirstPeriodInterest()))
                .totalRepayment(schedule == null ? null : scaleOrNull(schedule.getTotalRepayment()))
                .equalInstallmentAmount(scaleOrNull(loan.getEqualInstallmentAmount()))
                .repaymentDateModel(loan.getRepaymentDateModel())
                .loanPenaltyEnabled(loan.isLoanPenaltyEnabled())
                .penaltyType(loan.getPenaltyType())
                .penaltyRateOrAmount(scaleOrNull(loan.getPenaltyRateOrAmount()))
                .penaltyFrequency(loan.getPenaltyFrequency())
                .gracePeriodDays(loan.getGracePeriodDays())
                .allocationOrder(loan.getAllocationOrder())
                .scheduleFinalized(schedule != null && schedule.isScheduleFinalized())
                .repaymentSchedule(schedule == null ? List.of() : schedule.getInstallments())
                .outstandingPrincipal(scaleOrNull(loan.getOutstandingPrincipal()))
                .outstandingInterest(scaleOrNull(loan.getOutstandingInterest()))
                .outstandingPenalty(scaleOrNull(loan.getOutstandingPenalty()))
                .totalRepaidPrincipal(scaleOrNull(loan.getTotalRepaidPrincipal()))
                .totalRepaidInterest(scaleOrNull(loan.getTotalRepaidInterest()))
                .totalRepaidPenalty(scaleOrNull(loan.getTotalRepaidPenalty()))
                .requestDate(loan.getRequestDate())
                .approvalDate(loan.getApprovalDate())
                .disbursementDate(loan.getDisbursementDate())
                .dueDate(loan.getDueDate())
                .status(LoanOverdueRules.effectiveStatus(loan, today()))
                .guaranteeMode(loan.getGuaranteeMode() == null ? LoanGuaranteeMode.SELF : loan.getGuaranteeMode())
                .shareCount(loan.getShareCount())
                .sharePercent(loan.getSharePercent())
                .maxLoanByShares(scaleOrNull(loan.getMaxLoanByShares()))
                .purpose(loan.getPurpose())
                .rejectionReason(loan.getRejectionReason())
                .requestedBy(loan.getRequestedBy())
                .approvedBy(loan.getApprovedBy())
                .disbursedBy(loan.getDisbursedBy())
                .firstApprovedBy(loan.getFirstApprovedBy())
                .firstApprovedAt(loan.getFirstApprovedAt())
                .firstApproverRole(loan.getFirstApproverRole())
                .applicationForm(form)
                .eligibility(evaluateEligibility(
                        loan.getCooperativeId(), loan.getMemberUserId(), loan.getRequestedAmount(), loan.getId()))
                .guarantor(loanGuarantorService.findForLoan(loan.getId()))
                .approvalHistory(
                        includeHistory
                                ? approvalTrailService.list(
                                        loan.getCooperativeId(),
                                        ApprovalTrailService.ENTITY_LOAN,
                                        loan.getId())
                                : List.of())
                .createdAt(loan.getCreatedAt())
                .updatedAt(loan.getUpdatedAt())
                .build();
    }

    private LoanApplicationFormResponse buildApplicationFormFromLoan(Loan loan) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(loan.getCooperativeId())
                .orElse(null);
        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(loan.getCooperativeId(), loan.getMemberUserId())
                .orElse(null);
        return buildApplicationForm(
                cooperative,
                membership,
                loan.getMemberUserId(),
                loan,
                loan.getCreatedAt());
    }

    private LoanApplicationFormResponse buildApplicationForm(
            Cooperative cooperative,
            CooperativeMembership membership,
            UUID memberUserId,
            Loan loan,
            Instant submittedAt) {
        User member = userRepository.findByIdAndDeletedFalse(memberUserId).orElse(null);
        return LoanApplicationFormResponse.builder()
                .cooperativeId(cooperative == null ? loan.getCooperativeId() : cooperative.getId())
                .cooperativeName(cooperative == null ? null : cooperative.getName())
                .currency(cooperative == null ? null : cooperative.getCurrency())
                .memberUserId(memberUserId)
                .memberFullName(member == null ? null : formatName(member))
                .username(member == null ? null : member.getUsername())
                .email(member == null ? null : member.getEmail())
                .phone(member == null ? null : member.getPhone())
                .nationalId(member == null ? null : member.getNationalId())
                .address(member == null ? null : member.getAddress())
                .membershipDate(membership == null ? null : membership.getMembershipDate())
                .membershipStatus(membership == null ? null : membership.getMembershipStatus())
                .roleInCooperative(membership == null ? null : membership.getRoleInCooperative())
                .requestedAmount(scaleOrNull(loan.getRequestedAmount()))
                .purpose(loan.getPurpose())
                .termMonths(loan.getTermMonths() == 0 ? null : loan.getTermMonths())
                .interestRatePercent(
                        loan.getInterestRatePercent() == null
                                ? null
                                : MoneyUtils.scale(loan.getInterestRatePercent()))
                .interestType(loan.getInterestType())
                .requestDate(loan.getRequestDate())
                .submittedAt(submittedAt)
                .build();
    }

    private String writeSnapshot(LoanApplicationFormResponse form) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cooperativeId", form.getCooperativeId());
            payload.put("cooperativeName", form.getCooperativeName());
            payload.put("currency", form.getCurrency());
            payload.put("memberUserId", form.getMemberUserId());
            payload.put("memberFullName", form.getMemberFullName());
            payload.put("username", form.getUsername());
            payload.put("email", form.getEmail());
            payload.put("phone", form.getPhone());
            payload.put("nationalId", form.getNationalId());
            payload.put("address", form.getAddress());
            payload.put("membershipDate", form.getMembershipDate());
            payload.put("membershipStatus", form.getMembershipStatus());
            payload.put("roleInCooperative", form.getRoleInCooperative());
            payload.put("requestedAmount", form.getRequestedAmount());
            payload.put("purpose", form.getPurpose());
            payload.put("termMonths", form.getTermMonths());
            payload.put("interestRatePercent", form.getInterestRatePercent());
            payload.put("interestType", form.getInterestType());
            payload.put("requestDate", form.getRequestDate());
            payload.put("submittedAt", form.getSubmittedAt());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to snapshot loan application", ex);
        }
    }

    private LoanApplicationFormResponse parseSnapshot(String snapshot) {
        if (!StringUtils.hasText(snapshot)) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot, LoanApplicationFormResponse.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private void persistAllocations(LoanRepayment repayment, LoanScheduleAllocator.PaymentSplit split) {
        if (split == null || split.lines() == null || split.lines().isEmpty()) {
            return;
        }
        List<LoanRepaymentAllocation> rows = split.lines().stream()
                .filter(line -> line.installmentId() != null && line.amount().compareTo(BigDecimal.ZERO) > 0)
                .map(line -> LoanRepaymentAllocation.builder()
                        .repaymentId(repayment.getId())
                        .installmentId(line.installmentId())
                        .loanId(repayment.getLoanId())
                        .cooperativeId(repayment.getCooperativeId())
                        .component(line.component())
                        .amount(MoneyUtils.scaleForStorage(line.amount()))
                        .build())
                .toList();
        if (!rows.isEmpty()) {
            loanRepaymentAllocationRepository.saveAll(rows);
        }
    }

    private void applyPenaltyPaymentsToFines(
            List<LoanInstallment> installments,
            LoanScheduleAllocator.PaymentSplit split,
            LoanRepayment repayment,
            UUID recordedBy,
            String currency) {
        if (split == null || split.lines() == null) {
            return;
        }
        for (LoanScheduleAllocator.AllocationLine line : split.lines()) {
            if (line.component() != LoanRepaymentComponent.PENALTY
                    || line.installmentId() == null
                    || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal remaining = MoneyUtils.scale(line.amount());
            for (Fine fine : fineService.unpaidLoanInstallmentFines(line.installmentId())) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal take = remaining.min(MoneyUtils.scale(fine.getOutstandingAmount()));
                if (take.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                fineService.applyLoanPenaltyPayment(
                        fine, take, repayment.getPaymentDate(), recordedBy, repayment.getPaymentReference(), currency);
                remaining = MoneyUtils.scale(remaining.subtract(take));
            }
        }
    }

    public record ScheduleExport(byte[] content, String contentType, String filename) {}

    @Transactional(readOnly = true)
    public ScheduleExport exportSchedule(
            UUID cooperativeId, UUID loanId, String format, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        Loan loan = requireLoan(cooperativeId, loanId);
        requireLoanReadAccess(loan);
        LoanScheduleResponse schedule = buildScheduleForLoan(loan);
        if (schedule == null) {
            throw new BusinessException("This loan does not have an amortization schedule");
        }
        String memberName = userRepository
                .findByIdAndDeletedFalse(loan.getMemberUserId())
                .map(this::formatName)
                .orElse("");
        List<List<Object>> summary = List.of(nullableRow(
                memberName,
                schedulePrincipal(loan),
                scaleOrNull(loan.getInterestRatePercent()),
                loan.getRepaymentDateModel() == null ? "" : loan.getRepaymentDateModel().name(),
                loan.getDisbursementDate(),
                loan.getTermMonths(),
                scaleOrNull(loan.getInterestAmount()),
                schedule.getTotalRepayment()));
        List<List<Object>> rows = new ArrayList<>();
        List<rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse> installments =
                schedule.getInstallments() == null ? List.of() : schedule.getInstallments();
        for (var item : installments) {
            rows.add(nullableRow(
                    item.getInstallmentNumber(),
                    item.getDueDate(),
                    item.getOpeningPrincipalBalance(),
                    item.getPrincipalComponent(),
                    item.getInterestComponent(),
                    item.getPenaltyDue(),
                    item.getPaymentAmount(),
                    item.getPrincipalPaid(),
                    item.getInterestPaid(),
                    item.getPenaltyPaid(),
                    item.getAmountPaid(),
                    item.getRemainingAmount(),
                    item.getStatus() == null ? "" : item.getStatus().name()));
        }
        rw.terimbere.csams.modules.report.dto.ReportHeaderMeta header =
                rw.terimbere.csams.modules.report.dto.ReportHeaderMeta.builder()
                        .cooperativeName(cooperative.getName())
                        .reportTitle("Repayment Schedule")
                        .selectedPeriod(loan.getDisbursementDate() == null
                                ? "Preview"
                                : loan.getDisbursementDate().toString())
                        .generatedAt(Instant.now())
                        .generatedBy(actorLabel(authorizationService.currentPrincipal()))
                        .currency(cooperative.getCurrency())
                        .build();
        List<rw.terimbere.csams.modules.report.dto.ReportSheetData> sheets = List.of(
                rw.terimbere.csams.modules.report.dto.ReportSheetData.builder()
                        .sheetName("Loan")
                        .headers(List.of(
                                "Member",
                                "Principal",
                                "Monthly Rate",
                                "Repayment Model",
                                "Disbursement Date",
                                "Term",
                                "Contractual Interest",
                                "Contractual Repayment"))
                        .rows(summary)
                        .build(),
                rw.terimbere.csams.modules.report.dto.ReportSheetData.builder()
                        .sheetName("Schedule")
                        .headers(List.of(
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
                        .build());
        boolean excel = format != null && format.equalsIgnoreCase("xlsx");
        byte[] content = excel
                ? rw.terimbere.csams.modules.report.export.ExcelReportWriter.write(header, sheets)
                : rw.terimbere.csams.modules.report.export.PdfReportWriter.write(header, sheets);
        String filename = scheduleDocumentFilename(memberName, excel);
        String contentType = excel
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "application/pdf";
        return new ScheduleExport(content, contentType, filename);
    }

    /** List.of rejects null cells; preview/export rows can contain missing dates or money. */
    private static List<Object> nullableRow(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static BigDecimal schedulePrincipal(Loan loan) {
        if (loan.getPrincipalAmount() != null) {
            return scaleOrNull(loan.getPrincipalAmount());
        }
        if (loan.getApprovedAmount() != null) {
            return scaleOrNull(loan.getApprovedAmount());
        }
        return scaleOrNull(loan.getRequestedAmount());
    }

    static String scheduleDocumentFilename(String memberName, boolean excel) {
        String slug = slugMemberName(memberName);
        String base = slug.isEmpty() ? "repayment-schedule" : "repayment-schedule-" + slug;
        return base + (excel ? ".xlsx" : ".pdf");
    }

    private static String slugMemberName(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        String slug = name.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug;
    }

    private LoanRepaymentResponse toRepaymentResponse(LoanRepayment repayment) {
        return LoanRepaymentResponse.builder()
                .id(repayment.getId())
                .loanId(repayment.getLoanId())
                .cooperativeId(repayment.getCooperativeId())
                .memberUserId(repayment.getMemberUserId())
                .paymentDate(repayment.getPaymentDate())
                .amountTotal(MoneyUtils.scale(repayment.getAmountTotal()))
                .principalPortion(MoneyUtils.scale(repayment.getPrincipalPortion()))
                .interestPortion(MoneyUtils.scale(repayment.getInterestPortion()))
                .penaltyPortion(MoneyUtils.scale(
                        repayment.getPenaltyPortion() == null ? BigDecimal.ZERO : repayment.getPenaltyPortion()))
                .paymentReference(repayment.getPaymentReference())
                .notes(repayment.getNotes())
                .recordedBy(repayment.getRecordedBy())
                .createdAt(repayment.getCreatedAt())
                .reversed(repayment.isReversed())
                .reversedAt(repayment.getReversedAt())
                .revisionOfId(repayment.getRevisionOfId())
                .revisionReason(repayment.getRevisionReason())
                .interestWaived(MoneyUtils.scale(repayment.getInterestWaived() == null ? BigDecimal.ZERO : repayment.getInterestWaived()))
                .build();
    }

    private String formatName(User user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }

    private String actorLabel(UserPrincipal principal) {
        String role = CooperativeOfficerRoles.displayRole(principal);
        return userRepository
                .findByIdAndDeletedFalse(principal.getId())
                .map(this::formatName)
                .filter(StringUtils::hasText)
                .map(name -> name + " (" + role + ")")
                .orElse(role);
    }

    private static BigDecimal scaleOrNull(BigDecimal value) {
        return value == null ? null : MoneyUtils.scale(value);
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
}
