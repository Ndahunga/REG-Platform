package rw.terimbere.csams.modules.onboarding.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.contribution.obligation.ContributionDueDayService;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.auth.dto.AuthResult;
import rw.terimbere.csams.modules.auth.service.AuthService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.account.AccountNotificationCopy;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.onboarding.dto.PublicOnboardingCooperativeRequest;
import rw.terimbere.csams.modules.onboarding.dto.PublicOnboardingCreatorRequest;
import rw.terimbere.csams.modules.onboarding.dto.PublicOnboardingRequest;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionInitialization;
import rw.terimbere.csams.modules.subscription.service.SubscriptionService;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;

/**
 * Public customer self-onboarding (Flow A). Super Admin create (Flow B) stays on
 * {@code CooperativeService}. Never assigns {@code SUPER_ADMIN}.
 */
@Service
@RequiredArgsConstructor
public class PublicOnboardingService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final SubscriptionService subscriptionService;
    private final AuthService authService;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;
    private final SchemeFundLockService schemeFundLockService;
    private final ContributionDueDayService contributionDueDayService;

    @Transactional
    public AuthResult signup(PublicOnboardingRequest request, HttpServletRequest httpRequest) {
        PublicOnboardingCreatorRequest creator = request.getCreator();
        PublicOnboardingCooperativeRequest cooperativeReq = request.getCooperative();

        String username = creator.getUsername().trim();
        String email = creator.getEmail().trim().toLowerCase(Locale.ROOT);
        String registrationNumber =
                CooperativeFieldRules.normalizeRegistrationNumber(cooperativeReq.getRegistrationNumber());

        if (userRepository.existsByUsernameIgnoreCaseAndDeletedFalse(username)) {
            throw new ConflictException("Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCaseAndDeletedFalse(email)) {
            throw new ConflictException("Email already exists");
        }
        if (registrationNumber != null
                && cooperativeRepository.existsByRegistrationNumberIgnoreCaseAndDeletedFalse(registrationNumber)) {
            throw new ConflictException("Registration number already in use");
        }

        Set<Role> roles = new HashSet<>();
        roles.add(requireRole(CooperativeOfficerRoles.MEMBER));
        roles.add(requireRole(CooperativeOfficerRoles.PRESIDENT));

        User user = userRepository.save(User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(creator.getPassword()))
                .firstName(creator.getFirstName().trim())
                .lastName(creator.getLastName().trim())
                .phone(normalizeOptionalPhone(creator.getPhone()))
                .accountStatus(AccountStatus.ACTIVE)
                .failedLoginAttempts(0)
                .roles(roles)
                .build());

        String ip = clientIp(httpRequest);
        String userAgent = httpRequest == null ? null : httpRequest.getHeader(HttpHeaders.USER_AGENT);
        auditService.record(
                user.getId(),
                null,
                AuditableAction.CREATE,
                "User",
                user.getId(),
                null,
                "{\"onboarding\":true,\"role\":\"PRESIDENT\",\"username\":\"" + escape(username) + "\"}",
                ip,
                userAgent);

        Cooperative cooperative = cooperativeRepository.save(Cooperative.builder()
                .name(cooperativeReq.getName().trim())
                .description(trimToNull(cooperativeReq.getDescription()))
                .registrationNumber(registrationNumber)
                .contactEmail(cooperativeReq.getContactEmail().trim().toLowerCase(Locale.ROOT))
                .contactPhone(CooperativeFieldRules.normalizePhone(cooperativeReq.getContactPhone()))
                .address(trimToNull(cooperativeReq.getAddress()))
                .currency(CooperativeFieldRules.CURRENCY_RWF)
                .financialYearStartMonth(
                        cooperativeReq.getFinancialYearStartMonth() != null
                                ? cooperativeReq.getFinancialYearStartMonth()
                                : 1)
                .monthlyContributionAmount(
                        cooperativeReq.getMonthlyContributionAmount() != null
                                ? cooperativeReq.getMonthlyContributionAmount()
                                : BigDecimal.ZERO)
                .contributionDueDay(
                        cooperativeReq.getContributionDueDay() != null
                                ? cooperativeReq.getContributionDueDay()
                                : 1)
                .registrationDate(cooperativeReq.getRegistrationDate())
                .status(CooperativeStatus.ACTIVE)
                .onboardingState(CooperativeOnboardingState.COMPLETE)
                .operatingModel(cooperativeReq.resolvedOperatingModel())
                .createdBy(user.getId())
                .build());
        schemeFundLockService.initializeForNewScheme(cooperative.getId());
        contributionDueDayService.seedForNewScheme(
                cooperative.getId(),
                cooperativeReq.getContributionDueDay() != null ? cooperativeReq.getContributionDueDay() : 1,
                user.getId());

        auditService.record(
                user.getId(),
                cooperative.getId(),
                AuditableAction.COOPERATIVE_CREATE,
                "Cooperative",
                cooperative.getId(),
                null,
                "{\"name\":\"" + escape(cooperative.getName())
                        + "\",\"onboardingState\":\"COMPLETE\",\"subscriptionInitialization\":\"START_TRIAL\"}",
                ip,
                userAgent);

        CooperativeMembership membership = membershipRepository.save(CooperativeMembership.builder()
                .userId(user.getId())
                .cooperativeId(cooperative.getId())
                .membershipStatus("ACTIVE")
                .membershipDate(LocalDate.now())
                .roleInCooperative(CooperativeOfficerRoles.PRESIDENT)
                .build());

        auditService.record(
                user.getId(),
                cooperative.getId(),
                AuditableAction.ROLE_ASSIGN,
                "User",
                user.getId(),
                null,
                "{\"roleInCooperative\":\"PRESIDENT\",\"onboardingState\":\"COMPLETE\",\"membershipId\":\""
                        + membership.getId() + "\"}",
                ip,
                userAgent);

        subscriptionService.initializeForCooperative(
                cooperative.getId(), SubscriptionInitialization.START_TRIAL, user.getId());

        notificationFacade.notifyUser(
                user.getId(),
                cooperative.getId(),
                NotificationType.ACCOUNT,
                AccountNotificationCopy.WELCOME_TITLE,
                AccountNotificationCopy.welcomeNewAdministratorBody(cooperative.getName()),
                "User",
                user.getId());

        return authService.issueSession(user, httpRequest);
    }

    private Role requireRole(String code) {
        return roleRepository
                .findByCode(code)
                .orElseThrow(() -> new IllegalStateException("Role missing: " + code));
    }

    private static String normalizeOptionalPhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        return CooperativeFieldRules.normalizePhone(phone);
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
}
