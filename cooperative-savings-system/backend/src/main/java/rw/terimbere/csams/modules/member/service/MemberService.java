package rw.terimbere.csams.modules.member.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.cooperative.service.CooperativeOnboardingService;
import rw.terimbere.csams.modules.filemanagement.entity.StoredFile;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanService;
import rw.terimbere.csams.modules.payout.entity.PayoutLineStatus;
import rw.terimbere.csams.modules.payout.repository.PayoutLineRepository;
import rw.terimbere.csams.modules.payout.service.PayoutService;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.modules.socialfund.repository.SocialContributionRepository;
import rw.terimbere.csams.modules.socialfund.service.SocialFundService;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.modules.member.dto.AssignAdministratorRequest;
import rw.terimbere.csams.modules.member.dto.MemberDetailResponse;
import rw.terimbere.csams.modules.member.dto.MemberFinancialSummaryResponse;
import rw.terimbere.csams.modules.member.dto.MemberRegisterRequest;
import rw.terimbere.csams.modules.member.dto.MemberResponse;
import rw.terimbere.csams.modules.member.dto.MemberStatusUpdateRequest;
import rw.terimbere.csams.modules.member.dto.MemberUpdateRequest;
import rw.terimbere.csams.shared.utilities.MoneyUtils;
import rw.terimbere.csams.modules.notification.account.AccountNotificationCopy;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
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

@Service
@RequiredArgsConstructor
public class MemberService {

    private static final String ROLE_MEMBER = "MEMBER";
    private static final Set<String> MEMBERSHIP_STATUSES =
            Set.of("ACTIVE", "INACTIVE", "SUSPENDED", "PENDING");
    private static final String PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final PasswordEncoder passwordEncoder;
    private final FileManagementService fileManagementService;
    private final AuditService auditService;
    private final ContributionService contributionService;
    private final ContributionRepository contributionRepository;
    private final SpecialContributionRepository specialContributionRepository;
    private final LoanService loanService;
    private final LoanRepository loanRepository;
    private final FineService fineService;
    private final FineRepository fineRepository;
    private final FinePaymentRepository finePaymentRepository;
    private final SocialFundService socialFundService;
    private final SocialContributionRepository socialContributionRepository;
    private final PayoutService payoutService;
    private final PayoutLineRepository payoutLineRepository;
    private final ShareValuationService shareValuationService;
    private final rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository openingBalanceRepository;
    private final rw.terimbere.csams.modules.share.repository.SharePurchaseRepository sharePurchaseRepository;
    private final NotificationFacade notificationFacade;
    private final CooperativeOnboardingService onboardingService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public MemberResponse register(
            UUID cooperativeId, MemberRegisterRequest request, HttpServletRequest httpRequest) {
        requireCooperativeExists(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireMembershipManage(principal, cooperativeId);

        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsernameIgnoreCaseAndDeletedFalse(username)) {
            throw new ConflictException("Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCaseAndDeletedFalse(email)) {
            throw new ConflictException("Email already exists");
        }

        String nationalId = trimToNull(request.getNationalId());
        if (nationalId != null
                && userRepository.existsByNationalIdAndDeletedFalse(nationalId)) {
            throw new ConflictException("National ID already exists");
        }

        String roleInCoop = CooperativeOfficerRoles.normalize(request.getRoleInCooperative());
        assertCanAssignRole(principal, roleInCoop);

        boolean generated = !StringUtils.hasText(request.getTemporaryPassword());
        String temporaryPassword =
                generated ? generatePassword() : request.getTemporaryPassword();

        Set<Role> roles = new HashSet<>();
        roles.add(requireRole(ROLE_MEMBER));
        String platformRole = CooperativeOfficerRoles.platformRole(roleInCoop);
        if (platformRole != null) {
            roles.add(requireRole(platformRole));
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .phone(trimToNull(request.getPhone()))
                .nationalId(nationalId)
                .address(trimToNull(request.getAddress()))
                .accountStatus(AccountStatus.ACTIVE)
                .roles(roles)
                .build();
        user = userRepository.save(user);

        CooperativeMembership membership = CooperativeMembership.builder()
                .userId(user.getId())
                .cooperativeId(cooperativeId)
                .membershipStatus("ACTIVE")
                .membershipDate(request.getMembershipDate() != null ? request.getMembershipDate() : LocalDate.now())
                .roleInCooperative(roleInCoop)
                .shareCount(0)
                .build();
        membership = membershipRepository.save(membership);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.MEMBER_REGISTER,
                "User",
                user.getId(),
                null,
                "{\"username\":\"" + escape(username) + "\",\"membershipId\":\"" + membership.getId() + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        completeOnboardingIfPresident(cooperativeId, principal.getId(), roleInCoop, httpRequest);

        notifyAccount(
                user.getId(),
                cooperativeId,
                AccountNotificationCopy.WELCOME_TITLE,
                AccountNotificationCopy.welcomeNewMemberBody(schemeName(cooperativeId), roleInCoop));

        MemberResponse response = toResponse(user, membership);
        response.setTemporaryPassword(temporaryPassword);
        return response;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> list(
            UUID cooperativeId, String q, String status, Pageable pageable) {
        requireCooperativeExists(cooperativeId);
        authorizationService.requireMembership(cooperativeId);

        String normalizedStatus = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (normalizedStatus != null && !MEMBERSHIP_STATUSES.contains(normalizedStatus)) {
            throw new ValidationException("Invalid membership status filter");
        }

        Page<CooperativeMembership> page = membershipRepository.searchByCooperative(
                cooperativeId, StringUtils.hasText(q) ? q.trim() : null, normalizedStatus, pageable);

        return PageMapper.toPageResponse(page, membership -> {
            User user = userRepository
                    .findByIdAndDeletedFalse(membership.getUserId())
                    .orElse(null);
            if (user == null) {
                return MemberResponse.builder()
                        .userId(membership.getUserId())
                        .membershipId(membership.getId())
                        .cooperativeId(cooperativeId)
                        .membershipStatus(membership.getMembershipStatus())
                        .membershipDate(membership.getMembershipDate())
                        .roleInCooperative(membership.getRoleInCooperative())
                        .shareCount(ShareAmountCalculator.normalizeShareCount(membership.getShareCount()))
                        .build();
            }
            return toResponse(user, membership);
        });
    }

    @Transactional
    public MemberDetailResponse getDetail(UUID cooperativeId, UUID memberUserId) {
        requireCooperativeExists(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = requireUser(memberUserId);
        return MemberDetailResponse.builder()
                .member(toResponse(user, membership))
                .contributions(List.copyOf(contributionService.recentForMember(cooperativeId, memberUserId)))
                .loans(List.copyOf(loanService.recentForMember(cooperativeId, memberUserId)))
                .fines(List.copyOf(fineService.recentForMember(cooperativeId, memberUserId)))
                .social(List.copyOf(socialFundService.recentForMember(cooperativeId, memberUserId)))
                .payouts(List.copyOf(payoutService.recentForMember(cooperativeId, memberUserId)))
                .build();
    }

    @Transactional(readOnly = true)
    public MemberFinancialSummaryResponse financialSummary(UUID cooperativeId, UUID memberUserId) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        authorizationService.requireMembership(cooperativeId);

        UserPrincipal principal = authorizationService.currentPrincipal();
        boolean self = principal.getId().equals(memberUserId);
        boolean canManageOthers = principal.hasAuthority("MEMBERSHIP_MANAGE")
                || principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);
        if (!self && !canManageOthers) {
            throw new ForbiddenException(
                    "Members may only view their own financial summary unless they have MEMBERSHIP_MANAGE");
        }

        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = requireUser(memberUserId);

        // Lifetime regular contributions = opening balance (cutover) + OuWealth-recorded paid contributions.
        // The opening balance lives in member_opening_balances only; it is never also a Contribution row.
        var opening = openingBalanceRepository.findByCooperativeIdAndMemberUserId(cooperativeId, memberUserId);
        BigDecimal openingContributions = opening
                .map(b -> scaleOrZero(b.getOpeningContributionAmount()))
                .orElse(scaleOrZero(null));
        BigDecimal openingShareCapital =
                opening.map(b -> scaleOrZero(b.getOpeningShareCapitalAmount())).orElse(scaleOrZero(null));
        BigDecimal contributedShareCapital = MoneyUtils.add(
                openingShareCapital,
                scaleOrZero(sharePurchaseRepository.sumApprovedAmountByMember(cooperativeId, memberUserId)));
        BigDecimal regular = MoneyUtils.add(
                openingContributions,
                scaleOrZero(contributionRepository.sumPaidByMember(cooperativeId, memberUserId)));
        BigDecimal special =
                scaleOrZero(specialContributionRepository.sumApprovedAmountByMember(cooperativeId, memberUserId));
        BigDecimal actual = MoneyUtils.add(regular, special);
        BigDecimal expected =
                scaleOrZero(contributionRepository.sumExpectedByMember(cooperativeId, memberUserId));
        BigDecimal outstandingContributions =
                scaleOrZero(contributionRepository.sumOutstandingByMember(cooperativeId, memberUserId));

        EnumSet<LoanStatus> disbursedStatuses =
                EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.CLOSED, LoanStatus.WRITTEN_OFF);
        EnumSet<LoanStatus> openLoanStatuses = EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE);

        BigDecimal loansReceived = scaleOrZero(
                loanRepository.sumPrincipalByMemberAndStatuses(cooperativeId, memberUserId, disbursedStatuses));
        BigDecimal outstandingLoanPrincipal = scaleOrZero(loanRepository.sumOutstandingPrincipalByMemberAndStatuses(
                cooperativeId, memberUserId, openLoanStatuses));
        BigDecimal outstandingLoanInterest = scaleOrZero(loanRepository.sumOutstandingInterestByMemberAndStatuses(
                cooperativeId, memberUserId, openLoanStatuses));
        BigDecimal totalLoanRepayments =
                scaleOrZero(loanRepository.sumTotalRepaidByMember(cooperativeId, memberUserId));

        BigDecimal totalFines = scaleOrZero(
                fineRepository.sumTotalAmountByMemberExcludingCancelled(cooperativeId, memberUserId));
        BigDecimal unpaidFines = scaleOrZero(fineRepository.sumOutstandingByMemberAndStatuses(
                cooperativeId, memberUserId, EnumSet.of(FineStatus.UNPAID, FineStatus.PARTIALLY_PAID)));
        BigDecimal approvedFinePayments =
                scaleOrZero(finePaymentRepository.sumApprovedAmountByMember(cooperativeId, memberUserId));

        BigDecimal socialContributions = scaleOrZero(
                socialContributionRepository.sumApprovedAmountByMember(cooperativeId, memberUserId));

        BigDecimal coopRegular = MoneyUtils.add(
                scaleOrZero(contributionRepository.sumPaidForPeriod(cooperativeId, null, null)),
                scaleOrZero(openingBalanceRepository.sumOpeningContributions(cooperativeId)));
        BigDecimal coopSpecial = scaleOrZero(specialContributionRepository.sumApprovedAmount(cooperativeId));
        BigDecimal coopActual = MoneyUtils.add(coopRegular, coopSpecial);
        BigDecimal contributionPercentage = null;
        if (coopActual.compareTo(BigDecimal.ZERO) > 0) {
            contributionPercentage = actual
                    .multiply(new BigDecimal("100"))
                    .divide(coopActual, 2, RoundingMode.HALF_UP);
        }

        BigDecimal recentPayoutTotal = scaleOrZero(payoutLineRepository.sumPayoutAmountByMemberAndStatuses(
                cooperativeId,
                memberUserId,
                EnumSet.of(PayoutLineStatus.CONFIRMED, PayoutLineStatus.PAID)));

        int sharesHeld = ShareAmountCalculator.ownedShareCount(membership.getShareCount());
        ShareValuationResponse valuation = shareValuationService.calculate(cooperativeId);
        BigDecimal currentShareValue = valuation.getCurrentShareValue();
        BigDecimal totalShareValue = MoneyUtils.multiply(currentShareValue, BigDecimal.valueOf(sharesHeld));

        return MemberFinancialSummaryResponse.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberUserId)
                .memberName(user.getFullName())
                .membershipStatus(membership.getMembershipStatus())
                .membershipDate(membership.getMembershipDate())
                .currency(cooperative.getCurrency())
                .regularContributions(regular)
                .specialContributions(special)
                .actualContributions(actual)
                .expectedContributions(expected)
                .outstandingContributions(outstandingContributions)
                .loansReceived(loansReceived)
                .outstandingLoanPrincipal(outstandingLoanPrincipal)
                .outstandingLoanInterest(outstandingLoanInterest)
                .totalLoanRepayments(totalLoanRepayments)
                .totalFines(totalFines)
                .unpaidFines(unpaidFines)
                .approvedFinePayments(approvedFinePayments)
                .socialContributions(socialContributions)
                .contributionPercentage(contributionPercentage)
                .recentPayoutTotal(recentPayoutTotal)
                .sharesHeld(sharesHeld)
                .currentShareValue(currentShareValue)
                .totalShareValue(totalShareValue)
                .openingContributions(openingContributions)
                .openingCutoverDate(opening.map(b -> b.getCutoverDate()).orElse(null))
                .openingShareCount(opening.map(b -> b.getOpeningShareCount()).orElse(0))
                .openingShareCapital(openingShareCapital)
                .contributedShareCapital(contributedShareCapital)
                .build();
    }

    /**
     * Paginated financial summaries for the admin dashboard member table.
     * Reuses {@link #financialSummary} so formulas stay consistent (single HTTP call; page-sized server work).
     */
    @Transactional(readOnly = true)
    public PageResponse<MemberFinancialSummaryResponse> listFinancialSummaries(
            UUID cooperativeId, String q, Pageable pageable) {
        requireCooperativeExists(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireMembershipManage(principal, cooperativeId);

        PageResponse<MemberResponse> members = list(cooperativeId, q, null, pageable);
        List<MemberFinancialSummaryResponse> rows = members.getContent().stream()
                .map(member -> financialSummary(cooperativeId, member.getUserId()))
                .toList();

        return PageResponse.<MemberFinancialSummaryResponse>builder()
                .content(rows)
                .page(members.getPage())
                .size(members.getSize())
                .totalElements(members.getTotalElements())
                .totalPages(members.getTotalPages())
                .first(members.isFirst())
                .last(members.isLast())
                .build();
    }

    private static BigDecimal scaleOrZero(BigDecimal value) {
        return MoneyUtils.scale(value == null ? BigDecimal.ZERO : value);
    }

    @Transactional
    public MemberResponse update(
            UUID cooperativeId, UUID memberUserId, MemberUpdateRequest request, HttpServletRequest httpRequest) {
        requireCooperativeExists(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireMembershipManage(principal, cooperativeId);

        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = requireUser(memberUserId);
        requireNotSystemSuperAdmin(user);

        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCaseAndDeletedFalseAndIdNot(email, user.getId())) {
            throw new ConflictException("Email already exists");
        }
        if (StringUtils.hasText(request.getUsername())) {
            String username = request.getUsername().trim();
            if (userRepository.existsByUsernameIgnoreCaseAndDeletedFalseAndIdNot(username, user.getId())) {
                throw new ConflictException("Username already exists");
            }
            user.setUsername(username);
        }

        String nationalId = trimToNull(request.getNationalId());
        if (nationalId != null
                && userRepository.existsByNationalIdAndDeletedFalseAndIdNot(nationalId, user.getId())) {
            throw new ConflictException("National ID already exists");
        }

        String previous = "{\"email\":\"" + escape(user.getEmail()) + "\"}";
        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName().trim());
        user.setEmail(email);
        user.setPhone(trimToNull(request.getPhone()));
        user.setNationalId(nationalId);
        user.setAddress(trimToNull(request.getAddress()));
        userRepository.save(user);

        if (request.getMembershipDate() != null) {
            membership.setMembershipDate(request.getMembershipDate());
        }
        String previousRole = CooperativeOfficerRoles.normalize(membership.getRoleInCooperative());
        String nextRole = previousRole;
        if (StringUtils.hasText(request.getRoleInCooperative())) {
            nextRole = CooperativeOfficerRoles.normalize(request.getRoleInCooperative());
            assertCanAssignRole(principal, nextRole);
            membership.setRoleInCooperative(nextRole);
        }
        membershipRepository.save(membership);
        if (StringUtils.hasText(request.getRoleInCooperative())) {
            syncGlobalOfficerRoles(user);
            userRepository.save(user);
        }

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.MEMBER_UPDATE,
                "User",
                user.getId(),
                previous,
                "{\"email\":\"" + escape(user.getEmail()) + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        if (!previousRole.equals(nextRole)) {
            notifyAccount(
                    user.getId(),
                    cooperativeId,
                    AccountNotificationCopy.ROLE_CHANGED_TITLE,
                    AccountNotificationCopy.roleChangedBody(schemeName(cooperativeId), previousRole, nextRole));
        }
        completeOnboardingIfPresident(cooperativeId, principal.getId(), nextRole, httpRequest);
        return toResponse(user, membership);
    }

    @Transactional
    public MemberResponse updateStatus(
            UUID cooperativeId,
            UUID memberUserId,
            MemberStatusUpdateRequest request,
            HttpServletRequest httpRequest) {
        requireCooperativeExists(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireLeadership(principal, cooperativeId);

        if (request.getAccountStatus() == null && !StringUtils.hasText(request.getMembershipStatus())) {
            throw new ValidationException("accountStatus or membershipStatus is required");
        }

        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = requireUser(memberUserId);
        String previous = "{\"accountStatus\":\""
                + user.getAccountStatus()
                + "\",\"membershipStatus\":\""
                + membership.getMembershipStatus()
                + "\"}";

        if (request.getAccountStatus() != null) {
            requireNotSystemSuperAdmin(user);
            if (request.getAccountStatus() == AccountStatus.LOCKED) {
                throw new ValidationException("Use INACTIVE or SUSPENDED for member account status changes");
            }
            user.setAccountStatus(request.getAccountStatus());
            userRepository.save(user);
        }
        if (StringUtils.hasText(request.getMembershipStatus())) {
            String status = request.getMembershipStatus().trim().toUpperCase(Locale.ROOT);
            if (!MEMBERSHIP_STATUSES.contains(status)) {
                throw new ValidationException("Invalid membership status");
            }
            if ("INACTIVE".equals(status) && !"INACTIVE".equalsIgnoreCase(membership.getMembershipStatus())) {
                // Removing a member from active membership has its own rules (a reason, no payout in flight, never
                // oneself) and its own audit record; it must not be possible to bypass them through this endpoint.
                throw rw.terimbere.csams.shared.exceptions.ValidationException.coded(
                        "USE_MEMBER_DEACTIVATE", "Use the deactivate action to make a member inactive");
            }
            membership.setMembershipStatus(status);
            membershipRepository.save(membership);
            syncGlobalOfficerRoles(user);
            userRepository.save(user);
        }

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.MEMBER_STATUS_CHANGE,
                "User",
                user.getId(),
                previous,
                "{\"accountStatus\":\""
                        + user.getAccountStatus()
                        + "\",\"membershipStatus\":\""
                        + membership.getMembershipStatus()
                        + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(user, membership);
    }

    /**
     * Moves one membership to ACTIVE or INACTIVE and records who did it and why. The member's account and every record
     * stay as they are; only this scheme's membership changes (so the member's other schemes are unaffected).
     * Callers have already decided that the change is allowed.
     */
    @Transactional
    public MemberResponse changeMembershipStatus(
            UUID cooperativeId,
            UUID memberUserId,
            String newStatus,
            String reason,
            rw.terimbere.csams.shared.auditing.AuditableAction action,
            HttpServletRequest httpRequest) {
        requireCooperativeExists(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireLeadership(principal, cooperativeId);
        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = requireUser(memberUserId);
        requireNotSystemSuperAdmin(user);
        if (principal.getId().equals(memberUserId)) {
            throw new ForbiddenException("You cannot change your own membership status");
        }
        String previous = "{\"membershipStatus\":\"" + membership.getMembershipStatus() + "\"}";
        membership.setMembershipStatus(newStatus);
        membershipRepository.save(membership);
        syncGlobalOfficerRoles(user);
        userRepository.save(user);
        auditService.record(
                principal.getId(),
                cooperativeId,
                action,
                "User",
                user.getId(),
                previous,
                "{\"membershipStatus\":\"" + newStatus + "\""
                        + (reason == null ? "" : ",\"reason\":\"" + escape(reason) + "\"")
                        + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(user, membership);
    }

    @Transactional
    public MemberResponse uploadProfileImage(
            UUID cooperativeId, UUID memberUserId, MultipartFile file, HttpServletRequest httpRequest) {
        requireCooperativeExists(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        boolean self = principal.getId().equals(memberUserId);
        if (!self) {
            requireMembershipManage(principal, cooperativeId);
        } else {
            authorizationService.requireMembership(cooperativeId);
        }

        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = requireUser(memberUserId);

        StoredFile stored = fileManagementService.storeImage(
                file,
                FileManagementService.CATEGORY_PROFILE_IMAGE,
                "profile-images/" + memberUserId,
                cooperativeId,
                principal.getId(),
                clientIp(httpRequest),
                userAgent(httpRequest));
        user.setProfileImageKey(stored.getStorageKey());
        userRepository.save(user);
        return toResponse(user, membership);
    }

    @Transactional
    public MemberResponse assignAdministrator(
            UUID cooperativeId, AssignAdministratorRequest request, HttpServletRequest httpRequest) {
        requireCooperativeExists(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ForbiddenException("Only SUPER_ADMIN may assign cooperative administrators");
        }

        User user;
        String temporaryPassword = null;
        boolean createdNewUser = false;
        if (request.getUserId() != null) {
            user = requireUser(request.getUserId());
        } else {
            if (!StringUtils.hasText(request.getUsername())
                    || !StringUtils.hasText(request.getEmail())
                    || !StringUtils.hasText(request.getFirstName())
                    || !StringUtils.hasText(request.getLastName())) {
                throw new ValidationException(
                        "username, email, firstName, and lastName are required when creating an admin");
            }
            String username = request.getUsername().trim();
            String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
            if (userRepository.existsByUsernameIgnoreCaseAndDeletedFalse(username)) {
                throw new ConflictException("Username already exists");
            }
            if (userRepository.existsByEmailIgnoreCaseAndDeletedFalse(email)) {
                throw new ConflictException("Email already exists");
            }
            boolean generated = !StringUtils.hasText(request.getTemporaryPassword());
            temporaryPassword = generated ? generatePassword() : request.getTemporaryPassword();
            Set<Role> roles = new HashSet<>();
            roles.add(requireRole(ROLE_MEMBER));
            roles.add(requireRole(CooperativeOfficerRoles.PRESIDENT));
            user = userRepository.save(User.builder()
                    .username(username)
                    .email(email)
                    .passwordHash(passwordEncoder.encode(temporaryPassword))
                    .firstName(request.getFirstName().trim())
                    .lastName(request.getLastName().trim())
                    .phone(trimToNull(request.getPhone()))
                    .accountStatus(AccountStatus.ACTIVE)
                    .roles(roles)
                    .build());
            createdNewUser = true;
        }

        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, user.getId())
                .orElseGet(() -> CooperativeMembership.builder()
                        .userId(user.getId())
                        .cooperativeId(cooperativeId)
                        .membershipStatus("ACTIVE")
                        .membershipDate(LocalDate.now())
                        .build());
        membership.setMembershipStatus("ACTIVE");
        membership.setRoleInCooperative(CooperativeOfficerRoles.PRESIDENT);
        membership = membershipRepository.save(membership);
        syncGlobalOfficerRoles(user);
        userRepository.save(user);

        CooperativeOnboardingState onboardingState =
                onboardingService.completeIfAwaitingPresident(cooperativeId, principal.getId(), httpRequest);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.ROLE_ASSIGN,
                "User",
                user.getId(),
                null,
                "{\"roleInCooperative\":\"PRESIDENT\",\"onboardingState\":\"" + onboardingState + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        String schemeName = schemeName(cooperativeId);
        if (createdNewUser) {
            notifyAccount(
                    user.getId(),
                    cooperativeId,
                    AccountNotificationCopy.WELCOME_TITLE,
                    AccountNotificationCopy.welcomeNewAdministratorBody(schemeName));
        } else {
            notifyAccount(
                    user.getId(),
                    cooperativeId,
                    AccountNotificationCopy.MEMBERSHIP_ADDED_TITLE,
                    AccountNotificationCopy.membershipAddedBody(schemeName, CooperativeOfficerRoles.PRESIDENT));
        }

        MemberResponse response = toResponse(user, membership);
        if (temporaryPassword != null) {
            response.setTemporaryPassword(temporaryPassword);
        }
        return response;
    }

    private void completeOnboardingIfPresident(
            UUID cooperativeId, UUID actorUserId, String roleInCoop, HttpServletRequest httpRequest) {
        if (CooperativeOfficerRoles.PRESIDENT.equals(CooperativeOfficerRoles.normalize(roleInCoop))) {
            onboardingService.completeIfAwaitingPresident(cooperativeId, actorUserId, httpRequest);
        }
    }

    private void requireMembershipManage(UserPrincipal principal, UUID cooperativeId) {
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            authorizationService.requireMembership(cooperativeId);
        }
        if (!principal.hasAuthority("MEMBERSHIP_MANAGE") && !principal.hasAuthority("USER_WRITE")) {
            throw new ForbiddenException("MEMBERSHIP_MANAGE or USER_WRITE required");
        }
    }

    private void requireLeadership(UserPrincipal principal, UUID cooperativeId) {
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            authorizationService.requireMembership(cooperativeId);
        }
        if (!CooperativeOfficerRoles.isLeadership(principal)) {
            throw new ForbiddenException("President or Vice President required to change member status");
        }
    }

    private void assertCanAssignRole(UserPrincipal principal, String roleInCoop) {
        if (!CooperativeOfficerRoles.canAssign(principal, roleInCoop)) {
            throw new ForbiddenException("You cannot assign the " + roleInCoop + " role");
        }
    }

    /**
     * Recomputes the user's global officer roles from <em>all</em> of their ACTIVE cooperative memberships.
     * Authorization of cooperative-scoped requests no longer reads these global roles (it uses the target
     * cooperative's membership role), but they still feed JWT claims and non-scoped code, so a role change
     * in one cooperative must never remove the roles another cooperative relies on.
     */
    private void syncGlobalOfficerRoles(User user) {
        if (user.getRoles() == null) {
            user.setRoles(new HashSet<>());
        }
        user.getRoles().removeIf(role -> CooperativeOfficerRoles.isOfficerRoleCode(role.getCode()));
        user.getRoles().add(requireRole(ROLE_MEMBER));
        for (CooperativeMembership active :
                membershipRepository.findByUserIdAndMembershipStatus(user.getId(), "ACTIVE")) {
            String platformRole = CooperativeOfficerRoles.platformRole(normalizeStoredRole(active));
            if (platformRole != null) {
                user.getRoles().add(requireRole(platformRole));
            }
        }
    }

    private static String normalizeStoredRole(CooperativeMembership membership) {
        try {
            return CooperativeOfficerRoles.normalize(membership.getRoleInCooperative());
        } catch (ValidationException ex) {
            return CooperativeOfficerRoles.MEMBER;
        }
    }

    private void notifyAccount(UUID userId, UUID cooperativeId, String title, String body) {
        notificationFacade.notifyUser(
                userId, cooperativeId, NotificationType.ACCOUNT, title, body, "User", userId);
    }

    private String schemeName(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .map(Cooperative::getName)
                .filter(StringUtils::hasText)
                .orElse("your Saving Scheme");
    }

    /**
     * Cooperative member management edits the global user record. A system SUPER_ADMIN's account (status,
     * identity) is managed only through {@code /api/v1/platform/super-admins}, never by a Saving Scheme.
     */
    private void requireNotSystemSuperAdmin(User target) {
        if (target.getRoleCodes().contains(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ForbiddenException(
                    "System administrator accounts cannot be changed through Saving Scheme member management");
        }
    }

    private void requireCooperativeExists(UUID cooperativeId) {
        cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private CooperativeMembership requireMembership(UUID cooperativeId, UUID userId) {
        return membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership for user", userId));
    }

    private User requireUser(UUID userId) {
        return userRepository
                .findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private Role requireRole(String code) {
        return roleRepository.findByCode(code).orElseThrow(() -> new IllegalStateException("Role missing: " + code));
    }

    private MemberResponse toResponse(User user, CooperativeMembership membership) {
        return MemberResponse.builder()
                .userId(user.getId())
                .membershipId(membership.getId())
                .cooperativeId(membership.getCooperativeId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .nationalId(user.getNationalId())
                .address(user.getAddress())
                .profileImageKey(user.getProfileImageKey())
                .profileImageUrl(fileManagementService.getPublicUrl(user.getProfileImageKey()))
                .accountStatus(user.getAccountStatus())
                .membershipStatus(membership.getMembershipStatus())
                .membershipDate(membership.getMembershipDate())
                .roleInCooperative(membership.getRoleInCooperative())
                .shareCount(ShareAmountCalculator.normalizeShareCount(membership.getShareCount()))
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(PASSWORD_CHARS.charAt(secureRandom.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
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
