package rw.terimbere.csams.modules.cooperative.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.contribution.obligation.ContributionDueDayService;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeCreateRequest;
import rw.terimbere.csams.modules.cooperative.dto.OperatingModelClassificationRequest;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeResponse;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeStatusUpdateRequest;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeSummaryResponse;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeUpdateRequest;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.filemanagement.entity.StoredFile;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.modules.member.service.MemberService;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.subscription.dto.CooperativeSubscriptionResponse;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionInitialization;
import rw.terimbere.csams.modules.subscription.service.SubscriptionService;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.pagination.PageMapper;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;

@Service
@RequiredArgsConstructor
public class CooperativeService {

    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final FileManagementService fileManagementService;
    private final AuditService auditService;
    private final SubscriptionService subscriptionService;
    private final MemberService memberService;
    private final SchemeFundLockService schemeFundLockService;
    private final ContributionDueDayService contributionDueDayService;

    /**
     * Super Admin create: persist cooperative, initialize subscription, optionally assign President.
     * Nested {@code REQUIRED} transactions on {@link SubscriptionService} and {@link MemberService}
     * join this method's transaction so a failed essential step rolls back the whole create.
     */
    @Transactional
    public CooperativeResponse create(CooperativeCreateRequest request, HttpServletRequest httpRequest) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || !principal.hasAuthority("COOPERATIVE_WRITE")) {
            throw new ForbiddenException("Only SUPER_ADMIN with COOPERATIVE_WRITE may create cooperatives");
        }

        validateRegistrationNumber(request.getRegistrationNumber(), null);
        SubscriptionInitialization initialization = request.resolvedSubscriptionInitialization();
        if (initialization == SubscriptionInitialization.ACTIVE_MANUAL) {
            throw new ValidationException("ACTIVE_MANUAL subscription initialization is not supported yet");
        }

        boolean assignPresident = request.hasPresidentAssignment();
        CooperativeOnboardingState onboardingState = assignPresident
                ? CooperativeOnboardingState.COMPLETE
                : CooperativeOnboardingState.AWAITING_PRESIDENT;

        Cooperative cooperative = Cooperative.builder()
                .name(request.getName().trim())
                .description(trimToNull(request.getDescription()))
                .registrationNumber(CooperativeFieldRules.normalizeRegistrationNumber(request.getRegistrationNumber()))
                .contactEmail(request.getContactEmail().trim().toLowerCase(Locale.ROOT))
                .contactPhone(CooperativeFieldRules.normalizePhone(request.getContactPhone()))
                .address(trimToNull(request.getAddress()))
                .currency(CooperativeFieldRules.CURRENCY_RWF)
                .financialYearStartMonth(
                        request.getFinancialYearStartMonth() != null ? request.getFinancialYearStartMonth() : 1)
                .monthlyContributionAmount(
                        request.getMonthlyContributionAmount() != null
                                ? request.getMonthlyContributionAmount()
                                : BigDecimal.ZERO)
                .contributionDueDay(
                        request.getContributionDueDay() != null ? request.getContributionDueDay() : 1)
                .registrationDate(request.getRegistrationDate())
                .status(CooperativeStatus.ACTIVE)
                .onboardingState(onboardingState)
                .operatingModel(request.resolvedOperatingModel())
                .createdBy(principal.getId())
                .build();

        Cooperative saved = cooperativeRepository.save(cooperative);
        schemeFundLockService.initializeForNewScheme(saved.getId());
        contributionDueDayService.seedForNewScheme(
                saved.getId(),
                request.getContributionDueDay() != null ? request.getContributionDueDay() : 1,
                principal.getId());
        auditService.record(
                principal.getId(),
                saved.getId(),
                AuditableAction.COOPERATIVE_CREATE,
                "Cooperative",
                saved.getId(),
                null,
                "{\"name\":\"" + escape(saved.getName())
                        + "\",\"onboardingState\":\"" + saved.getOnboardingState()
                        + "\",\"operatingModel\":\"" + saved.getOperatingModel()
                        + "\",\"subscriptionInitialization\":\"" + initialization + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        subscriptionService.initializeForCooperative(saved.getId(), initialization, principal.getId());
        if (assignPresident) {
            memberService.assignAdministrator(saved.getId(), request.getPresident(), httpRequest);
        }
        return toResponse(requireCooperative(saved.getId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<CooperativeResponse> list(String q, CooperativeStatus status, Pageable pageable) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        boolean superAdmin = principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);

        Collection<UUID> ids;
        boolean restrictIds;
        if (superAdmin) {
            ids = List.of(UUID.randomUUID()); // unused when restrictIds=false; JPQL needs non-null collection
            restrictIds = false;
        } else {
            ids = activeMembershipCooperativeIds(principal.getId());
            if (ids.isEmpty()) {
                return PageResponse.<CooperativeResponse>builder()
                        .content(List.of())
                        .page(pageable.getPageNumber())
                        .size(pageable.getPageSize())
                        .totalElements(0)
                        .totalPages(0)
                        .first(true)
                        .last(true)
                        .build();
            }
            restrictIds = true;
        }

        Page<Cooperative> page = cooperativeRepository.search(
                StringUtils.hasText(q) ? q.trim() : null, status, restrictIds, ids, pageable);
        return PageMapper.toPageResponse(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<CooperativeSummaryResponse> listMine() {
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return cooperativeRepository.findAllByDeletedFalseAndStatus(CooperativeStatus.ACTIVE).stream()
                    .map(this::toSummary)
                    .toList();
        }
        Set<UUID> ids = activeMembershipCooperativeIds(principal.getId());
        if (ids.isEmpty()) {
            return List.of();
        }
        return cooperativeRepository.findByIdInAndDeletedFalse(ids).stream()
                .filter(c -> c.getStatus() != CooperativeStatus.ARCHIVED)
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public CooperativeResponse getById(UUID id) {
        authorizationService.requireMembership(id);
        return toResponse(requireCooperative(id));
    }

    @Transactional(readOnly = true)
    public CooperativeSubscriptionResponse getSubscription(UUID id) {
        authorizationService.requireMembership(id);
        requireCooperative(id);
        return subscriptionService
                .findByCooperativeId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", id));
    }

    @Transactional
    public CooperativeResponse update(UUID id, CooperativeUpdateRequest request, HttpServletRequest httpRequest) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        Cooperative cooperative = requireCooperative(id);
        requireWriteAccess(principal, id);

        validateRegistrationNumber(request.getRegistrationNumber(), id);

        String previous = "{\"name\":\"" + escape(cooperative.getName()) + "\"}";
        int previousDueDay = cooperative.getContributionDueDay();
        cooperative.setName(request.getName().trim());
        cooperative.setDescription(trimToNull(request.getDescription()));
        cooperative.setRegistrationNumber(CooperativeFieldRules.normalizeRegistrationNumber(request.getRegistrationNumber()));
        cooperative.setContactEmail(request.getContactEmail().trim().toLowerCase(Locale.ROOT));
        cooperative.setContactPhone(CooperativeFieldRules.normalizePhone(request.getContactPhone()));
        cooperative.setAddress(trimToNull(request.getAddress()));
        cooperative.setCurrency(CooperativeFieldRules.CURRENCY_RWF);
        if (request.getFinancialYearStartMonth() != null) {
            cooperative.setFinancialYearStartMonth(request.getFinancialYearStartMonth());
        }
        if (request.getMonthlyContributionAmount() != null) {
            cooperative.setMonthlyContributionAmount(request.getMonthlyContributionAmount());
        }
        if (request.getContributionDueDay() != null) {
            cooperative.setContributionDueDay(request.getContributionDueDay());
        }
        cooperative.setRegistrationDate(request.getRegistrationDate());

        Cooperative saved = cooperativeRepository.save(cooperative);
        if (saved.getContributionDueDay() != previousDueDay) {
            contributionDueDayService.onSchemeEdited(saved.getId(), saved.getContributionDueDay(), principal.getId());
        }
        auditService.record(
                principal.getId(),
                saved.getId(),
                AuditableAction.COOPERATIVE_UPDATE,
                "Cooperative",
                saved.getId(),
                previous,
                "{\"name\":\"" + escape(saved.getName()) + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(saved);
    }

    /**
     * One-time classification of a legacy scheme that has no operating model. Requires the same access as editing the
     * scheme (Super Admin with COOPERATIVE_WRITE, or that scheme's President or Vice President with
     * COOPERATIVE_WRITE), so another scheme's administrator is refused. Atomic: a single UPDATE that only matches a
     * scheme whose model is still unset, so two concurrent classifications cannot overwrite each other and any later
     * change is refused with 409. Switching the model of a scheme that already has one needs a dedicated, separately
     * approved migration workflow that does not exist yet.
     */
    @Transactional
    public CooperativeResponse classifyOperatingModel(
            UUID id, OperatingModelClassificationRequest request, HttpServletRequest httpRequest) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireCooperative(id);
        requireWriteAccess(principal, id);

        SchemeOperatingModel model = SchemeOperatingModel.valueOf(request.getOperatingModel());
        int updated = cooperativeRepository.classifyOperatingModelIfUnset(id, model.name());
        if (updated == 0) {
            SchemeOperatingModel current = cooperativeRepository.findOperatingModelById(id);
            if (current == null) {
                throw new ResourceNotFoundException("Cooperative", id);
            }
            throw new ConflictException(
                    "OPERATING_MODEL_ALREADY_SET",
                    "This saving scheme's operating model is already set and cannot be changed here");
        }
        auditService.record(
                principal.getId(),
                id,
                AuditableAction.COOPERATIVE_OPERATING_MODEL_SET,
                "Cooperative",
                id,
                "{\"operatingModel\":null}",
                "{\"operatingModel\":\"" + model + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(requireCooperative(id));
    }

    @Transactional
    public CooperativeResponse updateStatus(
            UUID id, CooperativeStatusUpdateRequest request, HttpServletRequest httpRequest) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ForbiddenException("Only SUPER_ADMIN may change cooperative status");
        }
        Cooperative cooperative = requireCooperative(id);
        CooperativeStatus previous = cooperative.getStatus();
        cooperative.setStatus(request.getStatus());
        Cooperative saved = cooperativeRepository.save(cooperative);
        auditService.record(
                principal.getId(),
                saved.getId(),
                AuditableAction.COOPERATIVE_STATUS_CHANGE,
                "Cooperative",
                saved.getId(),
                "{\"status\":\"" + previous + "\"}",
                "{\"status\":\"" + saved.getStatus() + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(saved);
    }

    @Transactional
    public CooperativeResponse uploadLogo(UUID id, MultipartFile file, HttpServletRequest httpRequest) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        Cooperative cooperative = requireCooperative(id);
        requireWriteAccess(principal, id);

        StoredFile stored = fileManagementService.storeImage(
                file,
                FileManagementService.CATEGORY_COOPERATIVE_LOGO,
                "cooperative-logos/" + id,
                id,
                principal.getId(),
                clientIp(httpRequest),
                userAgent(httpRequest));
        cooperative.setLogoFileKey(stored.getStorageKey());
        return toResponse(cooperativeRepository.save(cooperative));
    }

    void requireWriteAccess(UserPrincipal principal, UUID cooperativeId) {
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && principal.hasAuthority("COOPERATIVE_WRITE")) {
            return;
        }
        authorizationService.requireMembership(cooperativeId);
        if (!CooperativeOfficerRoles.isLeadership(principal) || !principal.hasAuthority("COOPERATIVE_WRITE")) {
            throw new ForbiddenException("President or Vice President with COOPERATIVE_WRITE required");
        }
    }

    private Cooperative requireCooperative(UUID id) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", id));
    }

    private void validateRegistrationNumber(String registrationNumber, UUID excludeId) {
        String value = CooperativeFieldRules.normalizeRegistrationNumber(registrationNumber);
        if (value == null) {
            return;
        }
        boolean exists = excludeId == null
                ? cooperativeRepository.existsByRegistrationNumberIgnoreCaseAndDeletedFalse(value)
                : cooperativeRepository.existsByRegistrationNumberIgnoreCaseAndDeletedFalseAndIdNot(value, excludeId);
        if (exists) {
            throw new ConflictException("Registration number already in use");
        }
    }

    private Set<UUID> activeMembershipCooperativeIds(UUID userId) {
        return membershipRepository.findByUserIdAndMembershipStatus(userId, "ACTIVE").stream()
                .map(CooperativeMembership::getCooperativeId)
                .collect(Collectors.toSet());
    }

    private CooperativeResponse toResponse(Cooperative c) {
        return CooperativeResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .registrationNumber(c.getRegistrationNumber())
                .contactEmail(c.getContactEmail())
                .contactPhone(c.getContactPhone())
                .address(c.getAddress())
                .currency(c.getCurrency())
                .financialYearStartMonth(c.getFinancialYearStartMonth())
                .monthlyContributionAmount(c.getMonthlyContributionAmount())
                .contributionDueDay(c.getContributionDueDay())
                .logoFileKey(c.getLogoFileKey())
                .logoUrl(fileManagementService.getPublicUrl(c.getLogoFileKey()))
                .status(c.getStatus())
                .onboardingState(c.getOnboardingState())
                .operatingModel(c.getOperatingModel())
                .registrationDate(c.getRegistrationDate())
                .createdBy(c.getCreatedBy())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private CooperativeSummaryResponse toSummary(Cooperative c) {
        return CooperativeSummaryResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .status(c.getStatus())
                .currency(c.getCurrency())
                .logoUrl(fileManagementService.getPublicUrl(c.getLogoFileKey()))
                .operatingModel(c.getOperatingModel())
                .build();
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
