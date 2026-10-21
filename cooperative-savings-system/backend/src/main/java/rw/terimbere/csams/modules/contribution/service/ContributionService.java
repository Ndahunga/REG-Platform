package rw.terimbere.csams.modules.contribution.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.entity.ApprovalAction;
import rw.terimbere.csams.modules.audit.service.ApprovalTrailService;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.obligation.ContributionObligationService;
import rw.terimbere.csams.modules.contribution.obligation.ContributionObligationService.ObligationView;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.contribution.dto.ContributionBatchRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionLineRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionPeriodPreviewResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionPeriodSummaryResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionReviewRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionSubmitRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionUpdateRequest;
import rw.terimbere.csams.modules.contribution.dto.MonthlyContributionChartPoint;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.obligation.ContributionAllocationCalculator;
import rw.terimbere.csams.modules.contribution.entity.ContributionReviewStatus;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.notification.service.NotificationWhatsAppService;
import rw.terimbere.csams.modules.notification.whatsapp.NotificationWhatsAppCopy;
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
public class ContributionService {

    private final ContributionRepository contributionRepository;
    private final ContributionObligationService obligationService;
    private final rw.terimbere.csams.modules.fundlock.SchemeFundLockService fundLockService;
    private final rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService rotationEntitlementService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final ApprovalTrailService approvalTrailService;
    private final NotificationFacade notificationFacade;
    private final NotificationWhatsAppService notificationWhatsAppService;

    @Transactional(readOnly = true)
    public List<ContributionResponse> getOrBuildPeriodGrid(UUID cooperativeId, int year, int month) {
        validatePeriod(year, month);
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);

        List<CooperativeMembership> activeMembers =
                membershipRepository.findByCooperativeIdAndMembershipStatus(cooperativeId, "ACTIVE");
        Map<UUID, Contribution> existing = contributionRepository
                .findByCooperativeIdAndYearAndMonth(cooperativeId, year, month)
                .stream()
                .collect(Collectors.toMap(Contribution::getMemberUserId, c -> c, (a, b) -> a));
        Map<UUID, String> names = loadMemberNames(
                activeMembers.stream().map(CooperativeMembership::getUserId).toList());

        List<ContributionResponse> rows = new ArrayList<>();
        for (CooperativeMembership membership : activeMembers) {
            BigDecimal expected = expectedAmountFor(cooperative, membership);
            Contribution saved = existing.get(membership.getUserId());
            if (saved != null) {
                rows.add(toResponse(saved, names.get(membership.getUserId()), true, false));
            } else {
                ObligationView projection =
                        obligationService.projectedView(cooperativeId, year, month, expected, BigDecimal.ZERO);
                rows.add(ContributionResponse.builder()
                        .cooperativeId(cooperativeId)
                        .memberUserId(membership.getUserId())
                        .memberName(names.get(membership.getUserId()))
                        .year(year)
                        .month(month)
                        .shareCount(ShareAmountCalculator.normalizeShareCount(membership.getShareCount()))
                        .expectedAmount(MoneyUtils.scale(expected))
                        .paidAmount(MoneyUtils.scale(BigDecimal.ZERO))
                        .outstandingAmount(MoneyUtils.scale(expected))
                        .remainingAmount(MoneyUtils.scale(expected))
                        .status(ContributionStatus.PENDING)
                        .persisted(false)
                        .dueDate(projection.dueDate())
                        .overdue(projection.overdue())
                        .overdueAmount(projection.overdueAmount())
                        .obligationSnapshotted(false)
                        .operatingModel(projection.operatingModel())
                        .rotatingBps(projection.rotatingBps())
                        .retainedBps(projection.retainedBps())
                        .expectedRotatingAmount(projection.expectedRotatingAmount())
                        .expectedRetainedAmount(projection.expectedRetainedAmount())
                        .rotationPeriodId(projection.rotationPeriodId())
                        .build());
            }
        }
        rows.sort(Comparator.comparing(
                r -> r.getMemberName() == null ? "" : r.getMemberName(), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    @Transactional
    public List<ContributionResponse> batchSave(
            UUID cooperativeId,
            int year,
            int month,
            ContributionBatchRequest request,
            HttpServletRequest httpRequest) {
        validatePeriod(year, month);
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        List<ContributionResponse> results = new ArrayList<>();
        for (ContributionLineRequest line : request.getLines()) {
            results.add(upsertLine(cooperative, year, month, line, principal.getId()));
        }

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.CONTRIBUTION_BATCH,
                "ContributionPeriod",
                null,
                null,
                "{\"year\":" + year + ",\"month\":" + month + ",\"lines\":" + request.getLines().size() + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        Map<UUID, String> names = loadMemberNames(
                results.stream().map(ContributionResponse::getMemberUserId).toList());
        return results.stream()
                .map(r -> {
                    r.setMemberName(names.get(r.getMemberUserId()));
                    return r;
                })
                .toList();
    }

    @Transactional
    public ContributionResponse updateSingle(
            UUID cooperativeId,
            UUID contributionId,
            ContributionUpdateRequest request,
            HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        // An obligation bound to a rotation period feeds a recipient's entitlement: take the scheme fund lock first (the
        // order payments, reversals and payouts use), then the row lock.
        Contribution probe = contributionRepository
                .findByIdAndCooperativeId(contributionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Contribution", contributionId));
        if (probe.getRotationPeriodId() != null) {
            fundLockService.lockSchemeFund(cooperativeId);
        }
        Contribution contribution = contributionRepository
                .findByIdAndCooperativeIdForUpdate(contributionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Contribution", contributionId));
        if (contribution.getReviewStatus() == ContributionReviewStatus.PENDING) {
            throw new BusinessException(
                    "This contribution is awaiting Accountant review and cannot be edited until it is approved or rejected");
        }

        String previous = toAuditJson(contribution);
        BigDecimal paidBeforeEdit = contribution.getPaidAmount() == null ? BigDecimal.ZERO : contribution.getPaidAmount();

        ContributionLineRequest line = ContributionLineRequest.builder()
                .memberUserId(contribution.getMemberUserId())
                .paidAmount(request.getPaidAmount() != null ? request.getPaidAmount() : contribution.getPaidAmount())
                .expectedAmount(
                        request.getExpectedAmount() != null
                                ? request.getExpectedAmount()
                                : contribution.getExpectedAmount())
                .paymentDate(
                        request.getPaymentDate() != null ? request.getPaymentDate() : contribution.getPaymentDate())
                .paymentReference(
                        request.getPaymentReference() != null
                                ? request.getPaymentReference()
                                : contribution.getPaymentReference())
                .notes(request.getNotes() != null ? request.getNotes() : contribution.getNotes())
                .status(request.getStatus() != null ? request.getStatus() : null)
                .build();

        applyLine(contribution, requireCooperative(cooperativeId), line, principal.getId());
        requireDistributionsCovered(contribution, paidBeforeEdit);
        contribution = contributionRepository.save(contribution);
        syncLedger(contribution, principal.getId(), requireCooperative(cooperativeId).getCurrency());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.CONTRIBUTION_UPDATE,
                "Contribution",
                contribution.getId(),
                previous,
                toAuditJson(contribution),
                clientIp(httpRequest),
                userAgent(httpRequest));

        String name = userRepository
                .findByIdAndDeletedFalse(contribution.getMemberUserId())
                .map(User::getFullName)
                .orElse(null);
        return toResponse(contribution, name, true);
    }

    @Transactional(readOnly = true)
    public ContributionResponse getById(UUID cooperativeId, UUID contributionId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        Contribution contribution = contributionRepository
                .findByIdAndCooperativeId(contributionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Contribution", contributionId));
        String name = userRepository
                .findByIdAndDeletedFalse(contribution.getMemberUserId())
                .map(User::getFullName)
                .orElse(null);
        return toResponse(contribution, name, true);
    }

    @Transactional(readOnly = true)
    public PageResponse<ContributionResponse> history(
            UUID cooperativeId,
            UUID memberUserId,
            Integer year,
            Integer month,
            ContributionStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        if (year != null) {
            validateYear(year);
        }
        if (month != null) {
            validateMonth(month);
        }
        DateRangeValidator.validateOptional(fromDate, toDate);

        Page<Contribution> page = contributionRepository.search(
                cooperativeId, memberUserId, year, month, status, fromDate, toDate, pageable);
        Map<UUID, String> names = loadMemberNames(
                page.getContent().stream().map(Contribution::getMemberUserId).distinct().toList());
        return PageMapper.toPageResponse(
                page, c -> toResponse(c, names.get(c.getMemberUserId()), true));
    }

    @Transactional(readOnly = true)
    public List<ContributionResponse> getMemberContributionHistory(UUID cooperativeId, UUID memberUserId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        if (!membershipRepository.existsByCooperativeIdAndUserId(cooperativeId, memberUserId)) {
            throw new ResourceNotFoundException("Membership for user", memberUserId);
        }
        String name = userRepository
                .findByIdAndDeletedFalse(memberUserId)
                .map(User::getFullName)
                .orElse(null);
        return contributionRepository
                .findByCooperativeIdAndMemberUserIdOrderByYearDescMonthDesc(cooperativeId, memberUserId)
                .stream()
                .map(c -> toResponse(c, name, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<ContributionResponse> myContributions(
            UUID cooperativeId,
            Integer year,
            Integer month,
            ContributionStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        return history(cooperativeId, principal.getId(), year, month, status, fromDate, toDate, pageable);
    }

    @Transactional(readOnly = true)
    public List<ContributionResponse> getMyContributions(UUID cooperativeId) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        return getMemberContributionHistory(cooperativeId, principal.getId());
    }

    @Transactional
    public ContributionResponse submitMine(
            UUID cooperativeId, ContributionSubmitRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeMembership membership = requireActiveMember(cooperativeId, principal.getId());

        LocalDate paymentDate = request.getPaymentDate();
        int year = request.getYear() != null ? request.getYear() : paymentDate.getYear();
        int month = request.getMonth() != null ? request.getMonth() : paymentDate.getMonthValue();
        validatePeriod(year, month);
        BigDecimal submitted = MoneyUtils.scaleForStorage(request.getAmount());
        MoneyUtils.assertPositive(submitted);

        int shareCount = ShareAmountCalculator.normalizeShareCount(membership.getShareCount());
        BigDecimal expected = expectedAmountFor(cooperative, membership);

        Contribution contribution = contributionRepository
                .findByCooperativeIdAndMemberUserIdAndYearAndMonth(
                        cooperativeId, principal.getId(), year, month)
                .orElseGet(() -> withSnapshot(Contribution.builder()
                        .cooperativeId(cooperativeId)
                        .memberUserId(principal.getId())
                        .year(year)
                        .month(month)
                        .shareCount(shareCount)
                        .expectedAmount(expected)
                        .paidAmount(BigDecimal.ZERO)
                        .outstandingAmount(expected)
                        .status(ContributionStatus.PENDING)
                        .ledgerRevision(0)
                        .build(), cooperative, membership));

        if (contribution.getShareCount() == null) {
            contribution.setShareCount(shareCount);
        }
        if (contribution.getExpectedAmount() == null
                || contribution.getExpectedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            contribution.setExpectedAmount(expected);
        }

        if (contribution.getStatus() == ContributionStatus.WAIVED
                || contribution.getStatus() == ContributionStatus.CANCELLED) {
            throw new BusinessException("This period already has a recorded contribution");
        }
        if (contribution.getReviewStatus() == ContributionReviewStatus.PENDING) {
            throw new BusinessException("A contribution for this period is already awaiting review");
        }

        BigDecimal alreadyPaid = contribution.getPaidAmount() == null
                ? BigDecimal.ZERO
                : MoneyUtils.scaleForStorage(contribution.getPaidAmount());
        BigDecimal remaining = MoneyUtils.scaleForStorage(
                contribution.getExpectedAmount().subtract(alreadyPaid).max(BigDecimal.ZERO));
        if (remaining.compareTo(BigDecimal.ZERO) <= 0
                || contribution.getStatus() == ContributionStatus.PAID) {
            throw new BusinessException("This period's contribution is already fully paid");
        }
        if (submitted.compareTo(remaining) > 0) {
            throw new BusinessException(
                    "Amount paid cannot exceed the remaining amount (" + MoneyUtils.scale(remaining) + ")");
        }

        contribution.setSubmittedAmount(submitted);
        contribution.setPaymentDate(paymentDate);
        contribution.setPaymentReference(trimToNull(request.getPaymentReference()));
        contribution.setEvidenceFileKey(trimToNull(request.getEvidenceFileKey()));
        contribution.setNotes(trimToNull(request.getNotes()));
        contribution.setSubmittedBy(principal.getId());
        contribution.setSubmittedAt(Instant.now());
        contribution.setReviewedBy(null);
        contribution.setReviewedAt(null);
        contribution.setRejectionReason(null);
        contribution.setReviewStatus(ContributionReviewStatus.PENDING);
        contribution.setOutstandingAmount(remaining);
        contribution.setStatus(deriveStatus(contribution.getExpectedAmount(), alreadyPaid));
        contribution = contributionRepository.save(contribution);

        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_CONTRIBUTION,
                contribution.getId(),
                principal,
                ApprovalAction.SUBMITTED,
                null,
                ContributionReviewStatus.PENDING.name(),
                trimToNull(request.getNotes()));
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.CONTRIBUTION_SUBMIT,
                "Contribution",
                contribution.getId(),
                null,
                "{\"status\":\"PENDING\",\"amount\":\"" + submitted + "\",\"year\":" + year + ",\"month\":" + month
                        + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                principal.getId(),
                cooperativeId,
                NotificationType.CONTRIBUTION,
                "Contribution submitted",
                "Your contribution for " + year + "-" + String.format("%02d", month)
                        + " was submitted for Accountant review.",
                "Contribution",
                contribution.getId());
        long pendingCount = contributionRepository.countByCooperativeIdInAndReviewStatus(
                List.of(cooperativeId), ContributionReviewStatus.PENDING);
        notificationWhatsAppService.notifyOfficers(
                cooperativeId,
                "CONTRIBUTION_WRITE",
                NotificationWhatsAppCopy.contributionPending(pendingCount),
                principal.getId());
        return toResponse(contribution, principalName(principal.getId()), true);
    }

    @Transactional(readOnly = true)
    public ContributionPeriodPreviewResponse periodPreview(UUID cooperativeId, Integer year, Integer month) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeMembership membership = requireActiveMember(cooperativeId, principal.getId());

        LocalDate today = LocalDate.now();
        int resolvedYear = year != null ? year : today.getYear();
        int resolvedMonth = month != null ? month : today.getMonthValue();
        validatePeriod(resolvedYear, resolvedMonth);

        int shareCount = ShareAmountCalculator.normalizeShareCount(membership.getShareCount());
        BigDecimal expected = expectedAmountFor(cooperative, membership);
        Contribution existing = contributionRepository
                .findByCooperativeIdAndMemberUserIdAndYearAndMonth(
                        cooperativeId, principal.getId(), resolvedYear, resolvedMonth)
                .orElse(null);

        BigDecimal paid = existing == null || existing.getPaidAmount() == null
                ? BigDecimal.ZERO
                : existing.getPaidAmount();
        BigDecimal pending = existing != null
                        && existing.getReviewStatus() == ContributionReviewStatus.PENDING
                        && existing.getSubmittedAmount() != null
                ? existing.getSubmittedAmount()
                : BigDecimal.ZERO;
        BigDecimal remaining = expected.subtract(paid).max(BigDecimal.ZERO);
        boolean awaitingReview =
                existing != null && existing.getReviewStatus() == ContributionReviewStatus.PENDING;
        boolean canSubmit = remaining.compareTo(BigDecimal.ZERO) > 0 && !awaitingReview
                && (existing == null
                        || (existing.getStatus() != ContributionStatus.WAIVED
                                && existing.getStatus() != ContributionStatus.CANCELLED
                                && existing.getStatus() != ContributionStatus.PAID));

        // The due date comes from the effective-dated due-day setting. A scheme that never configured one has none
        // (null): the legacy column defaults to 1 and would invent an overdue date.
        BigDecimal previewExpected = existing != null ? existing.getExpectedAmount() : expected;
        ObligationView obligation = existing != null
                ? obligationService.viewOf(existing)
                : obligationService.projectedView(cooperativeId, resolvedYear, resolvedMonth, previewExpected, paid);
        LocalDate dueDate = obligation.dueDate();

        return ContributionPeriodPreviewResponse.builder()
                .contributionId(existing == null ? null : existing.getId())
                .cooperativeId(cooperativeId)
                .memberUserId(principal.getId())
                .year(resolvedYear)
                .month(resolvedMonth)
                .shareCount(shareCount)
                .requiredAmount(MoneyUtils.scale(existing != null ? existing.getExpectedAmount() : expected))
                .paidAmount(MoneyUtils.scale(paid))
                .pendingSubmittedAmount(MoneyUtils.scale(pending))
                .remainingAmount(MoneyUtils.scale(
                        existing != null ? existing.getExpectedAmount().subtract(paid).max(BigDecimal.ZERO) : remaining))
                .paymentDate(existing == null ? null : existing.getPaymentDate())
                .dueDate(dueDate)
                .status(existing == null ? ContributionStatus.PENDING : existing.getStatus())
                .reviewStatus(existing == null ? null : existing.getReviewStatus())
                .awaitingReview(awaitingReview)
                .canSubmit(canSubmit)
                .overdue(obligation.overdue())
                .overdueAmount(obligation.overdueAmount())
                .operatingModel(obligation.operatingModel())
                .rotatingBps(obligation.rotatingBps())
                .retainedBps(obligation.retainedBps())
                .expectedRotatingAmount(obligation.expectedRotatingAmount())
                .expectedRetainedAmount(obligation.expectedRetainedAmount())
                .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<ContributionResponse> pendingReview(UUID cooperativeId, Pageable pageable) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        Page<Contribution> page = contributionRepository.findByCooperativeIdAndReviewStatus(
                cooperativeId, ContributionReviewStatus.PENDING, pageable);
        Map<UUID, String> names = loadMemberNames(
                page.getContent().stream().map(Contribution::getMemberUserId).distinct().toList());
        return PageMapper.toPageResponse(page, c -> toResponse(c, names.get(c.getMemberUserId()), true));
    }

    @Transactional
    public ContributionResponse approveSubmission(
            UUID cooperativeId,
            UUID contributionId,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        Contribution contribution = contributionRepository
                .findByIdAndCooperativeIdForUpdate(contributionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Contribution", contributionId));
        if (contribution.getReviewStatus() != ContributionReviewStatus.PENDING) {
            throw new BusinessException("Only PENDING contribution submissions can be approved");
        }
        if (principal.getId().equals(contribution.getMemberUserId())) {
            throw new ForbiddenException("You cannot approve your own contribution");
        }
        BigDecimal increment = contribution.getSubmittedAmount() == null
                ? BigDecimal.ZERO
                : contribution.getSubmittedAmount();
        MoneyUtils.assertPositive(increment);
        BigDecimal alreadyPaid = contribution.getPaidAmount() == null
                ? BigDecimal.ZERO
                : MoneyUtils.scaleForStorage(contribution.getPaidAmount());
        BigDecimal paid = MoneyUtils.scaleForStorage(alreadyPaid.add(increment));
        if (paid.compareTo(contribution.getExpectedAmount()) > 0) {
            throw new BusinessException("Approved amount would exceed the required contribution");
        }

        String previous = contribution.getReviewStatus().name();
        contribution.setPaidAmount(paid);
        contribution.setOutstandingAmount(MoneyUtils.scaleForStorage(
                contribution.getExpectedAmount().subtract(paid).max(BigDecimal.ZERO)));
        contribution.setStatus(deriveStatus(contribution.getExpectedAmount(), paid));
        contribution.setReviewedBy(principal.getId());
        contribution.setReviewedAt(Instant.now());
        contribution.setReviewStatus(ContributionReviewStatus.APPROVED);
        contribution.setRejectionReason(null);
        contribution.setRecordedBy(principal.getId());
        contribution = contributionRepository.save(contribution);
        syncLedger(contribution, principal.getId(), cooperative.getCurrency());

        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_CONTRIBUTION,
                contribution.getId(),
                principal,
                ApprovalAction.APPROVED,
                previous,
                ContributionReviewStatus.APPROVED.name(),
                null);
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.CONTRIBUTION_APPROVE,
                "Contribution",
                contribution.getId(),
                "{\"reviewStatus\":\"" + previous + "\"}",
                "{\"reviewStatus\":\"APPROVED\",\"paidAmount\":\"" + paid + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                contribution.getMemberUserId(),
                cooperativeId,
                NotificationType.CONTRIBUTION,
                "Contribution approved",
                "Your contribution for " + contribution.getYear() + "-"
                        + String.format("%02d", contribution.getMonth())
                        + " was approved by "
                        + actorLabel(principal)
                        + ".",
                "Contribution",
                contribution.getId());
        return toResponse(contribution, principalName(contribution.getMemberUserId()), true);
    }

    @Transactional
    public ContributionResponse rejectSubmission(
            UUID cooperativeId,
            UUID contributionId,
            ContributionReviewRequest request,
            HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        Contribution contribution = contributionRepository
                .findByIdAndCooperativeId(contributionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Contribution", contributionId));
        if (contribution.getReviewStatus() != ContributionReviewStatus.PENDING) {
            throw new BusinessException("Only PENDING contribution submissions can be rejected");
        }
        if (principal.getId().equals(contribution.getMemberUserId())) {
            throw new ForbiddenException("You cannot reject your own contribution");
        }
        if (request == null || !StringUtils.hasText(request.getRejectionReason())) {
            throw new ValidationException("Rejection reason is required");
        }

        String previous = contribution.getReviewStatus().name();
        contribution.setReviewedBy(principal.getId());
        contribution.setReviewedAt(Instant.now());
        contribution.setReviewStatus(ContributionReviewStatus.REJECTED);
        contribution.setRejectionReason(request.getRejectionReason().trim());
        BigDecimal alreadyPaid = contribution.getPaidAmount() == null
                ? BigDecimal.ZERO
                : MoneyUtils.scaleForStorage(contribution.getPaidAmount());
        contribution.setOutstandingAmount(MoneyUtils.scaleForStorage(
                contribution.getExpectedAmount().subtract(alreadyPaid).max(BigDecimal.ZERO)));
        contribution.setStatus(deriveStatus(contribution.getExpectedAmount(), alreadyPaid));
        contribution = contributionRepository.save(contribution);

        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_CONTRIBUTION,
                contribution.getId(),
                principal,
                ApprovalAction.REJECTED,
                previous,
                ContributionReviewStatus.REJECTED.name(),
                request.getRejectionReason().trim());
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.CONTRIBUTION_REJECT,
                "Contribution",
                contribution.getId(),
                "{\"reviewStatus\":\"" + previous + "\"}",
                "{\"reviewStatus\":\"REJECTED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                contribution.getMemberUserId(),
                cooperativeId,
                NotificationType.CONTRIBUTION,
                "Contribution rejected",
                "Your contribution for " + contribution.getYear() + "-"
                        + String.format("%02d", contribution.getMonth())
                        + " was rejected by "
                        + actorLabel(principal)
                        + ": "
                        + request.getRejectionReason().trim(),
                "Contribution",
                contribution.getId());
        return toResponse(contribution, principalName(contribution.getMemberUserId()), true);
    }

    @Transactional(readOnly = true)
    public List<ContributionResponse> recentForMember(UUID cooperativeId, UUID memberUserId) {
        String name = userRepository
                .findByIdAndDeletedFalse(memberUserId)
                .map(User::getFullName)
                .orElse(null);
        return contributionRepository
                .findTop20ByCooperativeIdAndMemberUserIdOrderByYearDescMonthDesc(cooperativeId, memberUserId)
                .stream()
                .map(c -> toResponse(c, name, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public ContributionPeriodSummaryResponse summary(UUID cooperativeId, Integer year, Integer month) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        if (year != null) {
            validateYear(year);
        }
        if (month != null) {
            validateMonth(month);
        }

        List<ContributionResponse> grid;
        if (year != null && month != null) {
            grid = getOrBuildPeriodGrid(cooperativeId, year, month);
        } else {
            grid = contributionRepository
                    .search(cooperativeId, null, year, month, null, null, null, Pageable.unpaged())
                    .getContent()
                    .stream()
                    .map(c -> toResponse(c, null, true))
                    .toList();
        }

        BigDecimal expected = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        long paidCount = 0;
        long pendingCount = 0;
        for (ContributionResponse row : grid) {
            expected = MoneyUtils.add(expected, row.getExpectedAmount());
            paid = MoneyUtils.add(paid, row.getPaidAmount());
            outstanding = MoneyUtils.add(outstanding, row.getOutstandingAmount());
            if (row.getStatus() == ContributionStatus.PAID || row.getStatus() == ContributionStatus.PARTIALLY_PAID) {
                paidCount++;
            }
            if (row.getStatus() == ContributionStatus.PENDING) {
                pendingCount++;
            }
        }

        return ContributionPeriodSummaryResponse.builder()
                .year(year == null ? 0 : year)
                .month(month)
                .expectedTotal(expected)
                .paidTotal(paid)
                .outstandingTotal(outstanding)
                .memberCount(grid.size())
                .paidCount(paidCount)
                .pendingCount(pendingCount)
                .currency(cooperative.getCurrency())
                .build();
    }

    @Transactional(readOnly = true)
    public List<MonthlyContributionChartPoint> monthlyChart(UUID cooperativeId, int year) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        validateYear(year);

        Map<Integer, BigDecimal> byMonth = new HashMap<>();
        for (int m = 1; m <= 12; m++) {
            byMonth.put(m, MoneyUtils.scale(BigDecimal.ZERO));
        }
        for (Object[] row : contributionRepository.sumPaidByMonth(cooperativeId, year)) {
            int month = ((Number) row[0]).intValue();
            BigDecimal total = (BigDecimal) row[1];
            byMonth.put(month, MoneyUtils.scale(total == null ? BigDecimal.ZERO : total));
        }
        List<MonthlyContributionChartPoint> points = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            points.add(MonthlyContributionChartPoint.builder()
                    .month(m)
                    .totalPaid(byMonth.get(m))
                    .build());
        }
        return points;
    }

    /**
     * The rotating funds collected through an obligation may already be disbursed or approved for payout. Lowering its
     * paid amount would then make that distribution inconsistent, so the correction is refused, never forced. Raising it
     * is always allowed (and never creates payout eligibility: only verified postings do).
     */
    private void requireDistributionsCovered(Contribution contribution, BigDecimal paidBeforeEdit) {
        if (contribution.getRotationPeriodId() != null
                && contribution.getPaidAmount().compareTo(paidBeforeEdit) < 0
                && rotationEntitlementService.wouldUndercutDistributions(
                        contribution, contribution.getPaidAmount(), BigDecimal.ZERO)) {
            throw new BusinessException(
                    "ROTATION_DISTRIBUTION_CONSISTENCY",
                    "This contribution cannot be lowered: its rotating funds were already disbursed or approved for payout");
        }
    }

    private ContributionResponse upsertLine(
            Cooperative cooperative,
            int year,
            int month,
            ContributionLineRequest line,
            UUID recordedBy) {
        if (line.getMemberUserId() == null) {
            throw new ValidationException("memberUserId is required");
        }
        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperative.getId(), line.getMemberUserId())
                .orElseThrow(() -> new ValidationException(
                        "Member is not part of this cooperative: " + line.getMemberUserId()));
        BigDecimal expectedDefault = expectedAmountFor(cooperative, membership);

        // An obligation bound to a rotation period feeds a recipient's entitlement, so changing it takes the scheme fund
        // lock first (the same order payments, reversals and payouts use), then the row lock.
        Contribution probe = contributionRepository
                .findByCooperativeIdAndMemberUserIdAndYearAndMonth(cooperative.getId(), line.getMemberUserId(), year, month)
                .orElse(null);
        if (probe != null && probe.getRotationPeriodId() != null) {
            fundLockService.lockSchemeFund(cooperative.getId());
        }
        Contribution contribution = contributionRepository
                .findForUpdate(
                        cooperative.getId(), line.getMemberUserId(), year, month)
                .orElseGet(() -> withSnapshot(Contribution.builder()
                        .cooperativeId(cooperative.getId())
                        .memberUserId(line.getMemberUserId())
                        .year(year)
                        .month(month)
                        .shareCount(ShareAmountCalculator.normalizeShareCount(membership.getShareCount()))
                        .expectedAmount(expectedDefault)
                        .paidAmount(BigDecimal.ZERO)
                        .outstandingAmount(expectedDefault)
                        .status(ContributionStatus.PENDING)
                        .ledgerRevision(0)
                        .build(), cooperative, membership));
        if (contribution.getReviewStatus() == ContributionReviewStatus.PENDING) {
            throw new BusinessException(
                    "This member has a contribution awaiting Accountant review and cannot be edited from the period grid");
        }
        // A waived or cancelled contribution keeps its terminal state. applyLine derives the status from the
        // amounts when none is sent, so without this guard an omitted status would silently reopen it.
        // Re-stating the same terminal status explicitly is the only batch input that leaves it unchanged.
        boolean existing = contribution.getId() != null;
        boolean terminal = contribution.getStatus() == ContributionStatus.WAIVED
                || contribution.getStatus() == ContributionStatus.CANCELLED;
        if (existing && terminal && line.getStatus() != contribution.getStatus()) {
            throw new BusinessException("This member's contribution is "
                    + contribution.getStatus().name().toLowerCase(Locale.ROOT)
                    + " and cannot be changed from the period grid");
        }

        BigDecimal paidBeforeEdit = contribution.getPaidAmount() == null ? BigDecimal.ZERO : contribution.getPaidAmount();
        applyLine(contribution, cooperative, line, recordedBy);
        if (existing) {
            requireDistributionsCovered(contribution, paidBeforeEdit);
        }
        contribution = contributionRepository.save(contribution);
        syncLedger(contribution, recordedBy, cooperative.getCurrency());
        return toResponse(contribution, null, true);
    }

    private void applyLine(
            Contribution contribution, Cooperative cooperative, ContributionLineRequest line, UUID recordedBy) {
        BigDecimal expected = line.getExpectedAmount() != null
                ? MoneyUtils.scaleForStorage(line.getExpectedAmount())
                : contribution.getExpectedAmount() != null
                        ? MoneyUtils.scaleForStorage(contribution.getExpectedAmount())
                        : expectedAmountFor(
                                cooperative,
                                membershipRepository
                                        .findByCooperativeIdAndUserId(
                                                cooperative.getId(), contribution.getMemberUserId())
                                        .orElse(null));
        MoneyUtils.assertNonNegative(expected);

        BigDecimal paid = MoneyUtils.scaleForStorage(
                line.getPaidAmount() == null ? BigDecimal.ZERO : line.getPaidAmount());
        MoneyUtils.assertNonNegative(paid);

        contribution.setExpectedAmount(expected);
        contribution.setPaidAmount(paid);
        contribution.setOutstandingAmount(MoneyUtils.scaleForStorage(expected.subtract(paid).max(BigDecimal.ZERO)));
        contribution.setPaymentDate(line.getPaymentDate());
        contribution.setPaymentReference(trimToNull(line.getPaymentReference()));
        contribution.setNotes(trimToNull(line.getNotes()));
        contribution.setRecordedBy(recordedBy);

        if (line.getStatus() == ContributionStatus.WAIVED || line.getStatus() == ContributionStatus.CANCELLED) {
            contribution.setStatus(line.getStatus());
            if (line.getStatus() == ContributionStatus.WAIVED) {
                contribution.setOutstandingAmount(MoneyUtils.scaleForStorage(BigDecimal.ZERO));
            }
        } else {
            contribution.setStatus(deriveStatus(expected, paid));
        }
    }

    /** What applying a verified payment to one contribution did, for the immutable posting record. */
    public record AppliedPayment(Contribution contribution, BigDecimal paidBefore, BigDecimal paidAfter, UUID ledgerEntryId) {}

    /**
     * Applies a verified payment's contribution allocation to a row the caller has already locked. This is the same
     * accounting path as every other paid-amount change: the paid amount rises and {@link #syncLedger} keeps the ledger
     * equal to that paid amount (it reverses the previous total credit and appends the new total), so the payment never
     * creates a second, independent cash credit, and a later officer correction of the same row cannot double-count it.
     * Obligation snapshots and the expected amount are left untouched. Runs inside the caller's posting transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AppliedPayment applyVerifiedPayment(
            Contribution locked,
            BigDecimal amount,
            LocalDate paymentDate,
            String paymentReference,
            UUID actorId,
            String currency) {
        MoneyUtils.assertPositive(amount);
        if (locked.getStatus() == ContributionStatus.WAIVED || locked.getStatus() == ContributionStatus.CANCELLED) {
            throw new BusinessException(
                    "CONTRIBUTION_PERIOD_CLOSED",
                    "The " + locked.getYear() + "-" + String.format(Locale.ROOT, "%02d", locked.getMonth())
                            + " contribution was waived or cancelled and cannot receive a payment");
        }
        BigDecimal expected = MoneyUtils.scaleForStorage(
                locked.getExpectedAmount() == null ? BigDecimal.ZERO : locked.getExpectedAmount());
        BigDecimal before = MoneyUtils.scaleForStorage(
                locked.getPaidAmount() == null ? BigDecimal.ZERO : locked.getPaidAmount());
        BigDecimal remaining = expected.subtract(before).max(BigDecimal.ZERO);
        BigDecimal applied = MoneyUtils.scaleForStorage(amount);
        if (applied.compareTo(remaining) > 0) {
            throw new BusinessException(
                    "ALLOCATION_EXCEEDS_OUTSTANDING",
                    "The " + locked.getYear() + "-" + String.format(Locale.ROOT, "%02d", locked.getMonth())
                            + " contribution only needs " + remaining.toPlainString() + " more");
        }
        BigDecimal after = MoneyUtils.scaleForStorage(before.add(applied));
        locked.setPaidAmount(after);
        locked.setOutstandingAmount(MoneyUtils.scaleForStorage(expected.subtract(after).max(BigDecimal.ZERO)));
        locked.setStatus(deriveStatus(expected, after));
        locked.setPaymentDate(paymentDate);
        locked.setPaymentReference(trimToNull(paymentReference));
        locked.setRecordedBy(actorId);
        Contribution saved = contributionRepository.saveAndFlush(locked);
        syncLedger(saved, actorId, currency);
        UUID ledgerEntryId = ledgerService
                .findLatestApproved(
                        LedgerService.SOURCE_CONTRIBUTION, saved.getId(), LedgerTransactionType.REGULAR_CONTRIBUTION)
                .map(rw.terimbere.csams.modules.ledger.entity.LedgerEntry::getId)
                .orElseThrow(() -> new BusinessException(
                        "LEDGER_REFERENCE_MISSING", "The contribution ledger credit could not be confirmed"));
        return new AppliedPayment(saved, before, after, ledgerEntryId);
    }

    /**
     * Takes back a posted payment's contribution amount from a row the caller has locked: the paid amount falls by the
     * amount, and {@link #syncLedger} keeps the ledger equal to it (the old total credit is reversed with the existing
     * REVERSAL entry and, if anything remains, the new total is appended). Nothing is deleted. The caller proves that
     * the row still holds exactly what the payment left (no later payment or correction) before calling this.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AppliedPayment reversePostedPayment(Contribution locked, BigDecimal amount, UUID actorId, String currency) {
        MoneyUtils.assertPositive(amount);
        if (locked.getStatus() == ContributionStatus.WAIVED || locked.getStatus() == ContributionStatus.CANCELLED) {
            throw new BusinessException(
                    "CONTRIBUTION_PERIOD_CLOSED", "That contribution period was waived or cancelled; reverse it through its own correction");
        }
        BigDecimal before = MoneyUtils.scaleForStorage(locked.getPaidAmount() == null ? BigDecimal.ZERO : locked.getPaidAmount());
        BigDecimal taken = MoneyUtils.scaleForStorage(amount);
        if (taken.compareTo(before) > 0) {
            throw new BusinessException("REVERSAL_BLOCKED_LATER_CHANGES", "The period now holds less than this payment contributed");
        }
        BigDecimal expected = MoneyUtils.scaleForStorage(locked.getExpectedAmount() == null ? BigDecimal.ZERO : locked.getExpectedAmount());
        BigDecimal after = MoneyUtils.scaleForStorage(before.subtract(taken));
        locked.setPaidAmount(after);
        locked.setOutstandingAmount(MoneyUtils.scaleForStorage(expected.subtract(after).max(BigDecimal.ZERO)));
        locked.setStatus(deriveStatus(expected, after));
        locked.setRecordedBy(actorId);
        Contribution saved = contributionRepository.saveAndFlush(locked);
        syncLedger(saved, actorId, currency);
        UUID ledgerEntryId = ledgerService
                .findLatestApproved(LedgerService.SOURCE_CONTRIBUTION, saved.getId(), LedgerTransactionType.REGULAR_CONTRIBUTION)
                .map(rw.terimbere.csams.modules.ledger.entity.LedgerEntry::getId)
                .orElse(null);
        return new AppliedPayment(saved, before, after, ledgerEntryId);
    }

    private void syncLedger(Contribution contribution, UUID recordedBy, String currency) {
        boolean shouldPost = (contribution.getStatus() == ContributionStatus.PAID
                        || contribution.getStatus() == ContributionStatus.PARTIALLY_PAID)
                && contribution.getPaidAmount() != null
                && contribution.getPaidAmount().compareTo(BigDecimal.ZERO) > 0;

        int currentRevision = contribution.getLedgerRevision();
        var currentEntry = ledgerService.findLatestApproved(
                LedgerService.SOURCE_CONTRIBUTION,
                contribution.getId(),
                LedgerTransactionType.REGULAR_CONTRIBUTION);

        if (currentEntry.isPresent()) {
            BigDecimal existingCredit = MoneyUtils.scaleForStorage(currentEntry.get().getCreditAmount());
            if (shouldPost && existingCredit.compareTo(MoneyUtils.scaleForStorage(contribution.getPaidAmount())) == 0) {
                return;
            }
            ledgerService.reverseApprovedCredit(
                    LedgerService.SOURCE_CONTRIBUTION,
                    contribution.getId(),
                    LedgerTransactionType.REGULAR_CONTRIBUTION,
                    LedgerService.contributionReversalKey(contribution.getId(), currentRevision),
                    recordedBy,
                    "Reversal due to contribution correction");
        }

        if (!shouldPost) {
            return;
        }

        int revision = currentRevision + 1;
        contribution.setLedgerRevision(revision);
        contributionRepository.save(contribution);

        var split = ledgerSplit(contribution);
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(contribution.getCooperativeId())
                .memberUserId(contribution.getMemberUserId())
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(contribution.getPaidAmount())
                .rotatingAmount(split == null ? null : split.rotating())
                .retainedAmount(split == null ? null : split.retained())
                .currency(currency)
                .transactionDate(
                        contribution.getPaymentDate() == null ? LocalDate.now() : contribution.getPaymentDate())
                .reference(contribution.getPaymentReference())
                .sourceEntityType(LedgerService.SOURCE_CONTRIBUTION)
                .sourceEntityId(contribution.getId())
                .description("Regular contribution "
                        + contribution.getYear()
                        + "-"
                        + String.format(Locale.ROOT, "%02d", contribution.getMonth()))
                .recordedBy(recordedBy)
                .approvedBy(recordedBy)
                .idempotencyKey(LedgerService.contributionKey(
                        contribution.getId(), LedgerTransactionType.REGULAR_CONTRIBUTION, revision))
                .build());
    }

    /**
     * How the cash of this contribution divides between the period's recipient and member capital, for the ledger. A
     * rotating or hybrid obligation splits by the basis points frozen on it; any other obligation is entirely retained.
     * Null when the model says rotating or hybrid but no split was ever recorded (nothing is guessed: the ledger entry
     * stays unclassified and the reconciliation reports it).
     */
    public static ContributionAllocationCalculator.Split ledgerSplit(Contribution contribution) {
        BigDecimal paid = MoneyUtils.scaleForStorage(contribution.getPaidAmount());
        String model = contribution.getOperatingModelSnapshot();
        boolean rotatingModel = "ROTATING".equals(model) || "HYBRID".equals(model);
        if (!rotatingModel) {
            return new ContributionAllocationCalculator.Split(MoneyUtils.scaleForStorage(BigDecimal.ZERO), paid);
        }
        if (contribution.getRotatingBpsSnapshot() == null || contribution.getRotationPeriodId() == null) {
            return null;
        }
        var split = rw.terimbere.csams.modules.contribution.obligation.ContributionSplits.split(contribution, paid);
        return new ContributionAllocationCalculator.Split(
                MoneyUtils.scaleForStorage(split.rotating()), MoneyUtils.scaleForStorage(split.retained()));
    }

    public static ContributionStatus deriveStatus(BigDecimal expected, BigDecimal paid) {
        if (paid.compareTo(BigDecimal.ZERO) <= 0) {
            return ContributionStatus.PENDING;
        }
        if (paid.compareTo(expected) < 0) {
            return ContributionStatus.PARTIALLY_PAID;
        }
        return ContributionStatus.PAID;
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private Map<UUID, String> loadMemberNames(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(userIds)) {
            if (!user.isDeleted()) {
                names.put(user.getId(), user.getFullName());
            }
        }
        return names;
    }

    private ContributionResponse toResponse(Contribution c, String memberName, boolean persisted) {
        return toResponse(c, memberName, persisted, persisted);
    }

    private ContributionResponse toResponse(
            Contribution c, String memberName, boolean persisted, boolean includeHistory) {
        ObligationView view = obligationService.viewOf(c);
        return ContributionResponse.builder()
                .id(c.getId())
                .cooperativeId(c.getCooperativeId())
                .memberUserId(c.getMemberUserId())
                .memberName(memberName)
                .year(c.getYear())
                .month(c.getMonth())
                .shareCount(ShareAmountCalculator.normalizeShareCount(c.getShareCount()))
                .expectedAmount(MoneyUtils.scale(c.getExpectedAmount()))
                .paidAmount(MoneyUtils.scale(c.getPaidAmount()))
                .outstandingAmount(MoneyUtils.scale(c.getOutstandingAmount()))
                .remainingAmount(MoneyUtils.scale(c.getOutstandingAmount()))
                .paymentDate(c.getPaymentDate())
                .status(c.getStatus())
                .paymentReference(c.getPaymentReference())
                .notes(c.getNotes())
                .recordedBy(c.getRecordedBy())
                .submittedAmount(c.getSubmittedAmount() == null ? null : MoneyUtils.scale(c.getSubmittedAmount()))
                .evidenceFileKey(c.getEvidenceFileKey())
                .submittedBy(c.getSubmittedBy())
                .submittedByName(principalName(c.getSubmittedBy()))
                .submittedAt(c.getSubmittedAt())
                .reviewedBy(c.getReviewedBy())
                .reviewedByName(principalName(c.getReviewedBy()))
                .reviewedAt(c.getReviewedAt())
                .reviewStatus(c.getReviewStatus())
                .rejectionReason(c.getRejectionReason())
                .approvalHistory(
                        includeHistory && c.getId() != null
                                ? approvalTrailService.list(
                                        c.getCooperativeId(),
                                        ApprovalTrailService.ENTITY_CONTRIBUTION,
                                        c.getId())
                                : List.of())
                .persisted(persisted)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .dueDate(view.dueDate())
                .overdue(view.overdue())
                .overdueAmount(view.overdueAmount())
                .obligationSnapshotted(view.snapshotted())
                .operatingModel(view.operatingModel())
                .rotatingBps(view.rotatingBps())
                .retainedBps(view.retainedBps())
                .expectedRotatingAmount(view.expectedRotatingAmount())
                .expectedRetainedAmount(view.expectedRetainedAmount())
                .rotationPeriodId(view.rotationPeriodId())
                .build();
    }

    private CooperativeMembership requireActiveMember(UUID cooperativeId, UUID memberUserId) {
        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow(() -> new ValidationException("User is not a member of this cooperative"));
        if (!"ACTIVE".equalsIgnoreCase(membership.getMembershipStatus())) {
            throw new BusinessException("Only ACTIVE members can submit contributions");
        }
        return membership;
    }

    /** A row being created now: gets its obligation snapshot when its month is the current or a future one. */
    private Contribution withSnapshot(Contribution row, Cooperative cooperative, CooperativeMembership membership) {
        obligationService.applyToNewRow(row, cooperative, membership);
        return row;
    }

    private static BigDecimal expectedAmountFor(Cooperative cooperative, CooperativeMembership membership) {
        return ShareAmountCalculator.expectedMonthly(
                cooperative.getMonthlyContributionAmount(),
                membership == null ? null : membership.getShareCount());
    }

    private String principalName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findByIdAndDeletedFalse(userId).map(User::getFullName).orElse(null);
    }

    private String actorLabel(UserPrincipal principal) {
        String role = CooperativeOfficerRoles.displayRole(principal);
        String name = principalName(principal.getId());
        if (StringUtils.hasText(name)) {
            return name + " (" + role + ")";
        }
        return role;
    }

    private static String toAuditJson(Contribution c) {
        return "{\"id\":\""
                + c.getId()
                + "\",\"paidAmount\":\""
                + c.getPaidAmount()
                + "\",\"status\":\""
                + c.getStatus()
                + "\",\"expectedAmount\":\""
                + c.getExpectedAmount()
                + "\"}";
    }

    private static void validatePeriod(int year, int month) {
        validateYear(year);
        validateMonth(month);
    }

    private static void validateYear(int year) {
        if (year < 2000 || year > 2100) {
            throw new ValidationException("year must be between 2000 and 2100");
        }
    }

    private static void validateMonth(int month) {
        if (month < 1 || month > 12) {
            throw new ValidationException("month must be between 1 and 12");
        }
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
