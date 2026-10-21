package rw.terimbere.csams.modules.socialfund.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.entity.ApprovalAction;
import rw.terimbere.csams.modules.audit.service.ApprovalTrailService;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.socialfund.dto.SocialContributionCreateRequest;
import rw.terimbere.csams.modules.socialfund.dto.SocialContributionResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialDisbursementCreateRequest;
import rw.terimbere.csams.modules.socialfund.dto.SocialDisbursementResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialFundReportResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialFundSummaryResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialReviewRequest;
import rw.terimbere.csams.modules.socialfund.entity.SocialContribution;
import rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursement;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursementStatus;
import rw.terimbere.csams.modules.socialfund.repository.SocialContributionRepository;
import rw.terimbere.csams.modules.socialfund.repository.SocialDisbursementRepository;
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
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class SocialFundService {

    private final SocialContributionRepository contributionRepository;
    private final SocialDisbursementRepository disbursementRepository;
    private final SocialFundBalanceService balanceService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final ApprovalTrailService approvalTrailService;
    private final NotificationFacade notificationFacade;

    @Transactional(readOnly = true)
    public SocialFundSummaryResponse summary(UUID cooperativeId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return buildSummary(cooperative);
    }

    @Transactional(readOnly = true)
    public PageResponse<SocialContributionResponse> listContributions(
            UUID cooperativeId, SocialContributionStatus status, UUID memberUserId, Pageable pageable) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);

        Page<SocialContribution> page;
        if (status != null && memberUserId != null) {
            page = contributionRepository.findByCooperativeIdAndMemberUserIdAndStatus(
                    cooperativeId, memberUserId, status, pageable);
        } else if (status != null) {
            page = contributionRepository.findByCooperativeIdAndStatus(cooperativeId, status, pageable);
        } else if (memberUserId != null) {
            page = contributionRepository.findByCooperativeIdAndMemberUserId(
                    cooperativeId, memberUserId, pageable);
        } else {
            page = contributionRepository.findByCooperativeId(cooperativeId, pageable);
        }
        String currency = cooperative.getCurrency();
        return PageMapper.toPageResponse(page, c -> toContributionResponse(c, currency));
    }

    @Transactional(readOnly = true)
    public List<SocialContributionResponse> myContributions(UUID cooperativeId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        return contributionRepository
                .findByCooperativeIdAndMemberUserIdOrderByContributionDateDescCreatedAtDesc(
                        cooperativeId, principal.getId())
                .stream()
                .map(c -> toContributionResponse(c, cooperative.getCurrency()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SocialContributionResponse> recentForMember(UUID cooperativeId, UUID memberUserId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        return contributionRepository
                .findTop20ByCooperativeIdAndMemberUserIdOrderByContributionDateDescCreatedAtDesc(
                        cooperativeId, memberUserId)
                .stream()
                .map(c -> toContributionResponse(c, cooperative.getCurrency()))
                .toList();
    }

    @Transactional
    public SocialContributionResponse submitContribution(
            UUID cooperativeId, SocialContributionCreateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        UUID memberUserId = request.getMemberUserId() == null ? principal.getId() : request.getMemberUserId();
        requireActiveMember(cooperativeId, memberUserId);

        if (!principal.getId().equals(memberUserId)
                && !principal.hasAuthority("SOCIAL_WRITE")
                && !principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ForbiddenException("Cannot submit social contributions for another member");
        }

        MoneyUtils.assertPositive(request.getAmount());
        SocialContribution contribution = SocialContribution.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberUserId)
                .amount(MoneyUtils.scaleForStorage(request.getAmount()))
                .contributionDate(
                        request.getContributionDate() == null ? LocalDate.now() : request.getContributionDate())
                .paymentReference(trimToNull(request.getPaymentReference()))
                .notes(trimToNull(request.getNotes()))
                .evidenceFileKey(trimToNull(request.getEvidenceFileKey()))
                .status(SocialContributionStatus.PENDING)
                .submittedBy(principal.getId())
                .build();
        contribution = contributionRepository.save(contribution);

        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_SOCIAL_CONTRIBUTION,
                contribution.getId(),
                principal,
                ApprovalAction.SUBMITTED,
                null,
                SocialContributionStatus.PENDING.name(),
                trimToNull(request.getNotes()));
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SOCIAL_CONTRIBUTION_SUBMIT,
                "SocialContribution",
                contribution.getId(),
                null,
                "{\"amount\":\"" + contribution.getAmount() + "\",\"memberUserId\":\"" + memberUserId + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toContributionResponse(contribution, cooperative.getCurrency());
    }

    @Transactional
    public SocialContributionResponse approveContribution(
            UUID cooperativeId,
            UUID contributionId,
            SocialReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        SocialContribution contribution = requireContribution(cooperativeId, contributionId);
        if (contribution.getStatus() != SocialContributionStatus.PENDING) {
            throw new BusinessException("Only PENDING social contributions can be approved");
        }
        if (principal.getId().equals(contribution.getMemberUserId())) {
            throw new ForbiddenException("You cannot approve your own social contribution");
        }

        contribution.setStatus(SocialContributionStatus.APPROVED);
        contribution.setReviewedBy(principal.getId());
        contribution.setReviewedAt(Instant.now());
        contribution.setReviewNotes(request == null ? null : trimToNull(request.getReviewNotes()));
        contribution = contributionRepository.save(contribution);

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(contribution.getMemberUserId())
                .transactionType(LedgerTransactionType.SOCIAL_CONTRIBUTION)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(contribution.getAmount())
                .currency(cooperative.getCurrency())
                .transactionDate(contribution.getContributionDate())
                .reference(contribution.getPaymentReference())
                .sourceEntityType(LedgerService.SOURCE_SOCIAL_CONTRIBUTION)
                .sourceEntityId(contribution.getId())
                .description("Social contribution approved")
                .recordedBy(principal.getId())
                .approvedBy(principal.getId())
                .idempotencyKey(LedgerService.socialContributionKey(contribution.getId()))
                .build());

        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_SOCIAL_CONTRIBUTION,
                contribution.getId(),
                principal,
                ApprovalAction.APPROVED,
                SocialContributionStatus.PENDING.name(),
                SocialContributionStatus.APPROVED.name(),
                request == null ? null : trimToNull(request.getReviewNotes()));

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SOCIAL_CONTRIBUTION_APPROVE,
                "SocialContribution",
                contribution.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"APPROVED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toContributionResponse(contribution, cooperative.getCurrency());
    }

    @Transactional
    public SocialContributionResponse rejectContribution(
            UUID cooperativeId,
            UUID contributionId,
            SocialReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        SocialContribution contribution = requireContribution(cooperativeId, contributionId);
        if (contribution.getStatus() != SocialContributionStatus.PENDING) {
            throw new BusinessException("Only PENDING social contributions can be rejected");
        }
        if (principal.getId().equals(contribution.getMemberUserId())) {
            throw new ForbiddenException("You cannot reject your own social contribution");
        }
        if (request == null || !StringUtils.hasText(request.getReviewNotes())) {
            throw new ValidationException("Rejection reason is required");
        }

        contribution.setStatus(SocialContributionStatus.REJECTED);
        contribution.setReviewedBy(principal.getId());
        contribution.setReviewedAt(Instant.now());
        contribution.setReviewNotes(trimToNull(request.getReviewNotes()));
        contribution = contributionRepository.save(contribution);

        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_SOCIAL_CONTRIBUTION,
                contribution.getId(),
                principal,
                ApprovalAction.REJECTED,
                SocialContributionStatus.PENDING.name(),
                SocialContributionStatus.REJECTED.name(),
                request.getReviewNotes().trim());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SOCIAL_CONTRIBUTION_REJECT,
                "SocialContribution",
                contribution.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"REJECTED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toContributionResponse(contribution, cooperative.getCurrency());
    }

    @Transactional(readOnly = true)
    public PageResponse<SocialDisbursementResponse> listDisbursements(
            UUID cooperativeId,
            SocialDisbursementStatus status,
            UUID beneficiaryMemberUserId,
            Pageable pageable) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);

        Page<SocialDisbursement> page;
        if (status != null && beneficiaryMemberUserId != null) {
            page = disbursementRepository.findByCooperativeIdAndBeneficiaryMemberUserIdAndStatus(
                    cooperativeId, beneficiaryMemberUserId, status, pageable);
        } else if (status != null) {
            page = disbursementRepository.findByCooperativeIdAndStatus(cooperativeId, status, pageable);
        } else if (beneficiaryMemberUserId != null) {
            page = disbursementRepository.findByCooperativeIdAndBeneficiaryMemberUserId(
                    cooperativeId, beneficiaryMemberUserId, pageable);
        } else {
            page = disbursementRepository.findByCooperativeId(cooperativeId, pageable);
        }
        String currency = cooperative.getCurrency();
        return PageMapper.toPageResponse(page, d -> toDisbursementResponse(d, currency));
    }

    @Transactional
    public SocialDisbursementResponse requestDisbursement(
            UUID cooperativeId, SocialDisbursementCreateRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        requireActiveMember(cooperativeId, request.getBeneficiaryMemberUserId());
        MoneyUtils.assertPositive(request.getAmount());

        SocialDisbursement disbursement = SocialDisbursement.builder()
                .cooperativeId(cooperativeId)
                .beneficiaryMemberUserId(request.getBeneficiaryMemberUserId())
                .amount(MoneyUtils.scaleForStorage(request.getAmount()))
                .disbursementDate(
                        request.getDisbursementDate() == null ? LocalDate.now() : request.getDisbursementDate())
                .reason(request.getReason().trim())
                .notes(trimToNull(request.getNotes()))
                .evidenceFileKey(trimToNull(request.getEvidenceFileKey()))
                .status(SocialDisbursementStatus.PENDING)
                .requestedBy(principal.getId())
                .build();
        disbursement = disbursementRepository.save(disbursement);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SOCIAL_DISBURSEMENT_REQUEST,
                "SocialDisbursement",
                disbursement.getId(),
                null,
                "{\"amount\":\""
                        + disbursement.getAmount()
                        + "\",\"beneficiaryMemberUserId\":\""
                        + disbursement.getBeneficiaryMemberUserId()
                        + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toDisbursementResponse(disbursement, cooperative.getCurrency());
    }

    @Transactional
    public SocialDisbursementResponse approveDisbursement(
            UUID cooperativeId,
            UUID disbursementId,
            SocialReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(principal);

        SocialDisbursement disbursement = requireDisbursement(cooperativeId, disbursementId);
        if (disbursement.getStatus() == SocialDisbursementStatus.APPROVED) {
            return toDisbursementResponse(disbursement, cooperative.getCurrency());
        }
        if (disbursement.getStatus() != SocialDisbursementStatus.PENDING) {
            throw new BusinessException("Only PENDING social disbursements can be approved");
        }

        BigDecimal amount = MoneyUtils.scale(disbursement.getAmount());
        BigDecimal available = balanceService.calculateBalance(cooperativeId);
        if (available.compareTo(amount) < 0) {
            throw new BusinessException(
                    "INSUFFICIENT_SOCIAL_FUND",
                    "Social fund balance is insufficient for disbursement. Available: "
                            + available
                            + ", required: "
                            + amount);
        }

        disbursement.setStatus(SocialDisbursementStatus.APPROVED);
        disbursement.setReviewedBy(principal.getId());
        disbursement.setReviewedAt(Instant.now());
        disbursement.setReviewNotes(request == null ? null : trimToNull(request.getReviewNotes()));
        disbursement = disbursementRepository.save(disbursement);

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(disbursement.getBeneficiaryMemberUserId())
                .transactionType(LedgerTransactionType.SOCIAL_DISBURSEMENT)
                .debitAmount(disbursement.getAmount())
                .creditAmount(BigDecimal.ZERO)
                .currency(cooperative.getCurrency())
                .transactionDate(disbursement.getDisbursementDate())
                .reference("SOCIAL-DISB-" + disbursement.getId())
                .sourceEntityType(LedgerService.SOURCE_SOCIAL_DISBURSEMENT)
                .sourceEntityId(disbursement.getId())
                .description("Social disbursement approved: " + disbursement.getReason())
                .recordedBy(principal.getId())
                .approvedBy(principal.getId())
                .idempotencyKey(LedgerService.socialDisbursementKey(disbursement.getId()))
                .build());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SOCIAL_DISBURSEMENT_APPROVE,
                "SocialDisbursement",
                disbursement.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"APPROVED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                disbursement.getBeneficiaryMemberUserId(),
                cooperativeId,
                NotificationType.SOCIAL,
                "Social fund disbursement approved",
                "Your social fund disbursement of "
                        + MoneyUtils.scale(disbursement.getAmount())
                        + " was approved.",
                "SocialDisbursement",
                disbursement.getId());
        return toDisbursementResponse(disbursement, cooperative.getCurrency());
    }

    @Transactional
    public SocialDisbursementResponse rejectDisbursement(
            UUID cooperativeId,
            UUID disbursementId,
            SocialReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        SocialDisbursement disbursement = requireDisbursement(cooperativeId, disbursementId);
        if (disbursement.getStatus() != SocialDisbursementStatus.PENDING) {
            throw new BusinessException("Only PENDING social disbursements can be rejected");
        }

        disbursement.setStatus(SocialDisbursementStatus.REJECTED);
        disbursement.setReviewedBy(principal.getId());
        disbursement.setReviewedAt(Instant.now());
        disbursement.setReviewNotes(request == null ? null : trimToNull(request.getReviewNotes()));
        disbursement = disbursementRepository.save(disbursement);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SOCIAL_DISBURSEMENT_REJECT,
                "SocialDisbursement",
                disbursement.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"REJECTED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toDisbursementResponse(disbursement, cooperative.getCurrency());
    }

    @Transactional
    public SocialDisbursementResponse cancelDisbursement(
            UUID cooperativeId, UUID disbursementId, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        SocialDisbursement disbursement = requireDisbursement(cooperativeId, disbursementId);
        if (disbursement.getStatus() != SocialDisbursementStatus.PENDING) {
            throw new BusinessException("Only PENDING social disbursements can be cancelled");
        }

        disbursement.setStatus(SocialDisbursementStatus.CANCELLED);
        disbursement.setReviewedBy(principal.getId());
        disbursement.setReviewedAt(Instant.now());
        disbursement = disbursementRepository.save(disbursement);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.OTHER,
                "SocialDisbursement",
                disbursement.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"CANCELLED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toDisbursementResponse(disbursement, cooperative.getCurrency());
    }

    @Transactional(readOnly = true)
    public SocialFundReportResponse report(UUID cooperativeId, LocalDate from, LocalDate to) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);

        if (from == null || to == null) {
            throw new ValidationException("from and to dates are required");
        }
        if (to.isBefore(from)) {
            throw new ValidationException("to must be on or after from");
        }

        List<SocialContributionResponse> contributions = contributionRepository
                .findApprovedInPeriod(cooperativeId, from, to)
                .stream()
                .map(c -> toContributionResponse(c, cooperative.getCurrency()))
                .toList();
        List<SocialDisbursementResponse> disbursements = disbursementRepository
                .findApprovedInPeriod(cooperativeId, from, to)
                .stream()
                .map(d -> toDisbursementResponse(d, cooperative.getCurrency()))
                .toList();

        return SocialFundReportResponse.builder()
                .from(from)
                .to(to)
                .summary(buildSummary(cooperative))
                .approvedContributions(contributions)
                .approvedDisbursements(disbursements)
                .build();
    }

    @Transactional(readOnly = true)
    public long countPendingApprovals(UUID cooperativeId) {
        long pendingContributions =
                contributionRepository.countByCooperativeIdAndStatus(cooperativeId, SocialContributionStatus.PENDING);
        long pendingDisbursements =
                disbursementRepository.countByCooperativeIdAndStatus(cooperativeId, SocialDisbursementStatus.PENDING);
        return pendingContributions + pendingDisbursements;
    }

    private SocialFundSummaryResponse buildSummary(Cooperative cooperative) {
        UUID cooperativeId = cooperative.getId();
        BigDecimal totalContributions = balanceService.sumApprovedContributions(cooperativeId);
        BigDecimal totalDisbursements = balanceService.sumApprovedDisbursements(cooperativeId);
        return SocialFundSummaryResponse.builder()
                .balance(MoneyUtils.subtract(totalContributions, totalDisbursements))
                .totalApprovedContributions(totalContributions)
                .totalApprovedDisbursements(totalDisbursements)
                .pendingContributions(contributionRepository.countByCooperativeIdAndStatus(
                        cooperativeId, SocialContributionStatus.PENDING))
                .pendingDisbursements(disbursementRepository.countByCooperativeIdAndStatus(
                        cooperativeId, SocialDisbursementStatus.PENDING))
                .currency(cooperative.getCurrency())
                .build();
    }

    private SocialContribution requireContribution(UUID cooperativeId, UUID contributionId) {
        return contributionRepository
                .findByIdAndCooperativeId(contributionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("SocialContribution", contributionId));
    }

    private SocialDisbursement requireDisbursement(UUID cooperativeId, UUID disbursementId) {
        return disbursementRepository
                .findByIdAndCooperativeId(disbursementId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("SocialDisbursement", disbursementId));
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private void requireActiveMember(UUID cooperativeId, UUID memberUserId) {
        var membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow(() -> new ValidationException("Member is not part of this cooperative"));
        if (!"ACTIVE".equalsIgnoreCase(membership.getMembershipStatus())) {
            throw new BusinessException("Only ACTIVE members can participate in the social fund");
        }
    }

    private SocialContributionResponse toContributionResponse(SocialContribution c, String currency) {
        return SocialContributionResponse.builder()
                .id(c.getId())
                .cooperativeId(c.getCooperativeId())
                .memberUserId(c.getMemberUserId())
                .memberName(userName(c.getMemberUserId()))
                .amount(MoneyUtils.scale(c.getAmount()))
                .contributionDate(c.getContributionDate())
                .paymentReference(c.getPaymentReference())
                .notes(c.getNotes())
                .evidenceFileKey(c.getEvidenceFileKey())
                .status(c.getStatus())
                .submittedBy(c.getSubmittedBy())
                .submittedByName(userName(c.getSubmittedBy()))
                .reviewedBy(c.getReviewedBy())
                .reviewedByName(userName(c.getReviewedBy()))
                .reviewedAt(c.getReviewedAt())
                .reviewNotes(c.getReviewNotes())
                .approvalHistory(
                        c.getId() == null
                                ? List.of()
                                : approvalTrailService.list(
                                        c.getCooperativeId(),
                                        ApprovalTrailService.ENTITY_SOCIAL_CONTRIBUTION,
                                        c.getId()))
                .currency(currency)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private String userName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findByIdAndDeletedFalse(userId).map(User::getFullName).orElse(null);
    }

    private SocialDisbursementResponse toDisbursementResponse(SocialDisbursement d, String currency) {
        return SocialDisbursementResponse.builder()
                .id(d.getId())
                .cooperativeId(d.getCooperativeId())
                .beneficiaryMemberUserId(d.getBeneficiaryMemberUserId())
                .amount(MoneyUtils.scale(d.getAmount()))
                .disbursementDate(d.getDisbursementDate())
                .reason(d.getReason())
                .notes(d.getNotes())
                .evidenceFileKey(d.getEvidenceFileKey())
                .status(d.getStatus())
                .requestedBy(d.getRequestedBy())
                .reviewedBy(d.getReviewedBy())
                .reviewedAt(d.getReviewedAt())
                .reviewNotes(d.getReviewNotes())
                .currency(currency)
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
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
