package rw.terimbere.csams.modules.specialcontribution.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialCampaignRequest;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialCampaignResponse;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialCampaignStatusUpdateRequest;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialContributionResponse;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialContributionReviewRequest;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialContributionSubmitRequest;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialCampaignStatus;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContribution;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionCampaign;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionCampaignRepository;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class SpecialContributionService {

    private final SpecialContributionCampaignRepository campaignRepository;
    private final SpecialContributionRepository specialContributionRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final LedgerService ledgerService;
    private final AuditService auditService;

    @Transactional
    public SpecialCampaignResponse createCampaign(
            UUID cooperativeId, SpecialCampaignRequest request, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        validateCampaignDates(request.getStartDate(), request.getEndDate());

        SpecialContributionCampaign campaign = SpecialContributionCampaign.builder()
                .cooperativeId(cooperativeId)
                .name(request.getName().trim())
                .purpose(trimToNull(request.getPurpose()))
                .description(trimToNull(request.getDescription()))
                .suggestedAmount(scaleNullable(request.getSuggestedAmount()))
                .targetAmount(scaleNullable(request.getTargetAmount()))
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .status(SpecialCampaignStatus.DRAFT)
                .createdBy(principal.getId())
                .build();
        campaign = campaignRepository.save(campaign);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SPECIAL_CAMPAIGN_CREATE,
                "SpecialContributionCampaign",
                campaign.getId(),
                null,
                "{\"name\":\"" + escape(campaign.getName()) + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toCampaignResponse(campaign);
    }

    @Transactional(readOnly = true)
    public List<SpecialCampaignResponse> listCampaigns(UUID cooperativeId, SpecialCampaignStatus status) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        List<SpecialContributionCampaign> campaigns = status == null
                ? campaignRepository.findByCooperativeIdOrderByCreatedAtDesc(cooperativeId)
                : campaignRepository.findByCooperativeIdAndStatus(cooperativeId, status);
        return campaigns.stream().map(this::toCampaignResponse).toList();
    }

    @Transactional(readOnly = true)
    public SpecialCampaignResponse getCampaign(UUID cooperativeId, UUID campaignId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return toCampaignResponse(requireCampaign(cooperativeId, campaignId));
    }

    @Transactional
    public SpecialCampaignResponse updateCampaign(
            UUID cooperativeId, UUID campaignId, SpecialCampaignRequest request, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        SpecialContributionCampaign campaign = requireCampaign(cooperativeId, campaignId);
        if (campaign.getStatus() == SpecialCampaignStatus.CANCELLED) {
            throw new BusinessException("Cannot update a cancelled campaign");
        }
        validateCampaignDates(request.getStartDate(), request.getEndDate());

        campaign.setName(request.getName().trim());
        campaign.setPurpose(trimToNull(request.getPurpose()));
        campaign.setDescription(trimToNull(request.getDescription()));
        campaign.setSuggestedAmount(scaleNullable(request.getSuggestedAmount()));
        campaign.setTargetAmount(scaleNullable(request.getTargetAmount()));
        campaign.setStartDate(request.getStartDate());
        campaign.setEndDate(request.getEndDate());
        campaign = campaignRepository.save(campaign);
        return toCampaignResponse(campaign);
    }

    @Transactional
    public SpecialCampaignResponse updateCampaignStatus(
            UUID cooperativeId,
            UUID campaignId,
            SpecialCampaignStatusUpdateRequest request,
            HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        SpecialContributionCampaign campaign = requireCampaign(cooperativeId, campaignId);
        SpecialCampaignStatus next = request.getStatus();
        if (next == null) {
            throw new ValidationException("status is required");
        }
        validateStatusTransition(campaign.getStatus(), next);
        campaign.setStatus(next);
        campaign = campaignRepository.save(campaign);
        return toCampaignResponse(campaign);
    }

    @Transactional
    public SpecialContributionResponse submitContribution(
            UUID cooperativeId,
            UUID campaignId,
            SpecialContributionSubmitRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        SpecialContributionCampaign campaign = requireCampaign(cooperativeId, campaignId);
        if (campaign.getStatus() != SpecialCampaignStatus.ACTIVE) {
            throw new BusinessException("Only ACTIVE campaigns accept contributions");
        }

        UUID memberUserId = request.getMemberUserId() == null ? principal.getId() : request.getMemberUserId();
        if (!membershipRepository.existsByCooperativeIdAndUserId(cooperativeId, memberUserId)) {
            throw new ValidationException("Member is not part of this cooperative");
        }
        if (!principal.getId().equals(memberUserId)
                && !principal.hasAuthority("CONTRIBUTION_WRITE")
                && !principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ValidationException("Cannot submit contributions for another member");
        }

        MoneyUtils.assertPositive(request.getAmount());
        SpecialContribution contribution = SpecialContribution.builder()
                .campaignId(campaignId)
                .cooperativeId(cooperativeId)
                .memberUserId(memberUserId)
                .amount(MoneyUtils.scaleForStorage(request.getAmount()))
                .contributionDate(
                        request.getContributionDate() == null ? LocalDate.now() : request.getContributionDate())
                .paymentReference(trimToNull(request.getPaymentReference()))
                .notes(trimToNull(request.getNotes()))
                .status(SpecialContributionStatus.PENDING)
                .recordedBy(principal.getId())
                .build();
        contribution = specialContributionRepository.save(contribution);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SPECIAL_CONTRIBUTION_SUBMIT,
                "SpecialContribution",
                contribution.getId(),
                null,
                "{\"amount\":\"" + contribution.getAmount() + "\",\"campaignId\":\"" + campaignId + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toContributionResponse(contribution, cooperative);
    }

    @Transactional(readOnly = true)
    public List<SpecialContributionResponse> listContributions(
            UUID cooperativeId, UUID campaignId, SpecialContributionStatus status) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        requireCampaign(cooperativeId, campaignId);

        List<SpecialContribution> list = specialContributionRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId);
        return list.stream()
                .filter(c -> status == null || c.getStatus() == status)
                .map(c -> toContributionResponse(c, cooperative))
                .toList();
    }

    @Transactional
    public SpecialContributionResponse approve(
            UUID cooperativeId,
            UUID campaignId,
            UUID contributionId,
            SpecialContributionReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        SpecialContributionCampaign campaign = requireCampaign(cooperativeId, campaignId);
        if (campaign.getStatus() != SpecialCampaignStatus.ACTIVE) {
            throw new BusinessException("Only ACTIVE campaigns can approve contributions");
        }

        SpecialContribution contribution = requireContribution(cooperativeId, campaignId, contributionId);
        if (contribution.getStatus() != SpecialContributionStatus.PENDING) {
            throw new BusinessException("Only PENDING contributions can be approved");
        }

        contribution.setStatus(SpecialContributionStatus.APPROVED);
        contribution.setReviewedBy(principal.getId());
        contribution.setReviewedAt(Instant.now());
        contribution.setReviewNotes(request == null ? null : trimToNull(request.getReviewNotes()));
        contribution = specialContributionRepository.save(contribution);

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(contribution.getMemberUserId())
                .transactionType(LedgerTransactionType.SPECIAL_CONTRIBUTION)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(contribution.getAmount())
                .currency(cooperative.getCurrency())
                .transactionDate(contribution.getContributionDate())
                .reference(contribution.getPaymentReference())
                .sourceEntityType(LedgerService.SOURCE_SPECIAL_CONTRIBUTION)
                .sourceEntityId(contribution.getId())
                .description("Special contribution approved for campaign " + campaign.getName())
                .recordedBy(principal.getId())
                .approvedBy(principal.getId())
                .idempotencyKey(LedgerService.specialContributionKey(contribution.getId()))
                .build());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SPECIAL_CONTRIBUTION_APPROVE,
                "SpecialContribution",
                contribution.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"APPROVED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toContributionResponse(contribution, cooperative);
    }

    @Transactional
    public SpecialContributionResponse reject(
            UUID cooperativeId,
            UUID campaignId,
            UUID contributionId,
            SpecialContributionReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        requireCampaign(cooperativeId, campaignId);

        SpecialContribution contribution = requireContribution(cooperativeId, campaignId, contributionId);
        if (contribution.getStatus() != SpecialContributionStatus.PENDING) {
            throw new BusinessException("Only PENDING contributions can be rejected");
        }

        contribution.setStatus(SpecialContributionStatus.REJECTED);
        contribution.setReviewedBy(principal.getId());
        contribution.setReviewedAt(Instant.now());
        contribution.setReviewNotes(request == null ? null : trimToNull(request.getReviewNotes()));
        contribution = specialContributionRepository.save(contribution);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SPECIAL_CONTRIBUTION_REJECT,
                "SpecialContribution",
                contribution.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"REJECTED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toContributionResponse(contribution, cooperative);
    }

    private void validateStatusTransition(SpecialCampaignStatus current, SpecialCampaignStatus next) {
        if (current == next) {
            return;
        }
        boolean allowed = switch (current) {
            case DRAFT -> next == SpecialCampaignStatus.ACTIVE || next == SpecialCampaignStatus.CANCELLED;
            case ACTIVE -> next == SpecialCampaignStatus.CLOSED || next == SpecialCampaignStatus.CANCELLED;
            case CLOSED, CANCELLED -> false;
        };
        if (!allowed) {
            throw new BusinessException("Invalid campaign status transition from " + current + " to " + next);
        }
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private SpecialContributionCampaign requireCampaign(UUID cooperativeId, UUID campaignId) {
        return campaignRepository
                .findByIdAndCooperativeId(campaignId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("SpecialContributionCampaign", campaignId));
    }

    private SpecialContribution requireContribution(UUID cooperativeId, UUID campaignId, UUID contributionId) {
        SpecialContribution contribution = specialContributionRepository
                .findByIdAndCooperativeId(contributionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("SpecialContribution", contributionId));
        if (!contribution.getCampaignId().equals(campaignId)) {
            throw new ResourceNotFoundException("SpecialContribution", contributionId);
        }
        return contribution;
    }

    private SpecialCampaignResponse toCampaignResponse(SpecialContributionCampaign c) {
        return SpecialCampaignResponse.builder()
                .id(c.getId())
                .cooperativeId(c.getCooperativeId())
                .name(c.getName())
                .purpose(c.getPurpose())
                .description(c.getDescription())
                .suggestedAmount(c.getSuggestedAmount() == null ? null : MoneyUtils.scale(c.getSuggestedAmount()))
                .targetAmount(c.getTargetAmount() == null ? null : MoneyUtils.scale(c.getTargetAmount()))
                .startDate(c.getStartDate())
                .endDate(c.getEndDate())
                .status(c.getStatus())
                .createdBy(c.getCreatedBy())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private SpecialContributionResponse toContributionResponse(SpecialContribution c, Cooperative cooperative) {
        String name = userRepository
                .findByIdAndDeletedFalse(c.getMemberUserId())
                .map(User::getFullName)
                .orElse(null);
        return SpecialContributionResponse.builder()
                .id(c.getId())
                .campaignId(c.getCampaignId())
                .cooperativeId(c.getCooperativeId())
                .memberUserId(c.getMemberUserId())
                .memberName(name)
                .amount(MoneyUtils.scale(c.getAmount()))
                .contributionDate(c.getContributionDate())
                .paymentReference(c.getPaymentReference())
                .notes(c.getNotes())
                .status(c.getStatus())
                .reviewedBy(c.getReviewedBy())
                .reviewedAt(c.getReviewedAt())
                .reviewNotes(c.getReviewNotes())
                .recordedBy(c.getRecordedBy())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private static void validateCampaignDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ValidationException("endDate must not be before startDate");
        }
    }

    private static BigDecimal scaleNullable(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        MoneyUtils.assertNonNegative(amount);
        return MoneyUtils.scaleForStorage(amount);
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
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
