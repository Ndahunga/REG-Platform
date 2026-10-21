package rw.terimbere.csams.security;

import java.util.Locale;
import java.util.Set;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Cooperative officer roles (Ikimina committee). Distinct from platform {@code SUPER_ADMIN}.
 *
 * <p>{@code COOPERATIVE_ADMIN} is a legacy alias for {@code PRESIDENT}.
 *
 * <p>The principal-based helpers below ({@code isOfficer}, {@code isLeadership}, {@code requireFundAuthorize},
 * ...) are cooperative-aware only because, for cooperative-scoped requests, {@link CooperativeScopeFilter}
 * replaces the principal's roles/permissions with those of its membership in the target cooperative
 * (see {@link CooperativeAccessResolver}). Do not call them with an unscoped principal to authorize
 * work on a specific cooperative.
 */
public final class CooperativeOfficerRoles {

    public static final String MEMBER = "MEMBER";
    public static final String PRESIDENT = "PRESIDENT";
    public static final String VICE_PRESIDENT = "VICE_PRESIDENT";
    public static final String SECRETARY = "SECRETARY";
    public static final String ACCOUNTANT = "ACCOUNTANT";
    public static final String LOAN_OFFICER = "LOAN_OFFICER";
    public static final String LEGACY_ADMIN = "COOPERATIVE_ADMIN";

    public static final String LOAN_APPROVE = "LOAN_APPROVE";
    public static final String FUND_AUTHORIZE = "FUND_AUTHORIZE";

    public static final Set<String> MEMBERSHIP_ROLES = Set.of(
            MEMBER, PRESIDENT, VICE_PRESIDENT, SECRETARY, ACCOUNTANT, LOAN_OFFICER, LEGACY_ADMIN);

    public static final Set<String> OFFICER_ROLE_CODES =
            Set.of(PRESIDENT, VICE_PRESIDENT, SECRETARY, ACCOUNTANT, LOAN_OFFICER, LEGACY_ADMIN);

    public static final Set<String> LEADERSHIP_ROLE_CODES =
            Set.of(PRESIDENT, VICE_PRESIDENT, LEGACY_ADMIN);

    public static final Set<String> BILLING_MANAGER_ROLE_CODES =
            Set.of(PRESIDENT, VICE_PRESIDENT, ACCOUNTANT, LEGACY_ADMIN);

    /** Officers who may see member-identifying contribution/fine leaderboards. */
    public static final Set<String> MEMBER_INSIGHTS_FINANCE_ROLE_CODES = BILLING_MANAGER_ROLE_CODES;

    /** Officers who may see overdue-loan follow-up lists (includes loan officer). */
    public static final Set<String> MEMBER_INSIGHTS_LOAN_ROLE_CODES =
            Set.of(PRESIDENT, VICE_PRESIDENT, ACCOUNTANT, LOAN_OFFICER, LEGACY_ADMIN);

    private CooperativeOfficerRoles() {}

    public static String normalize(String roleInCooperative) {
        if (roleInCooperative == null || roleInCooperative.isBlank()) {
            return MEMBER;
        }
        String code = roleInCooperative.trim().toUpperCase(Locale.ROOT);
        if (LEGACY_ADMIN.equals(code)) {
            return PRESIDENT;
        }
        if (!MEMBERSHIP_ROLES.contains(code)) {
            throw new ValidationException(
                    "roleInCooperative must be MEMBER, PRESIDENT, VICE_PRESIDENT, SECRETARY, ACCOUNTANT, or LOAN_OFFICER");
        }
        return code;
    }

    public static String platformRole(String normalizedMembershipRole) {
        if (normalizedMembershipRole == null || MEMBER.equals(normalizedMembershipRole)) {
            return null;
        }
        return normalizedMembershipRole;
    }

    public static boolean isOfficerRoleCode(String roleCode) {
        return roleCode != null && OFFICER_ROLE_CODES.contains(roleCode);
    }

    public static boolean canManageBilling(UserPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return true;
        }
        return BILLING_MANAGER_ROLE_CODES.stream().anyMatch(principal::hasRole);
    }

    public static boolean canViewMemberFinanceInsights(UserPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return true;
        }
        return MEMBER_INSIGHTS_FINANCE_ROLE_CODES.stream().anyMatch(principal::hasRole);
    }

    public static boolean canViewMemberLoanInsights(UserPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return true;
        }
        return MEMBER_INSIGHTS_LOAN_ROLE_CODES.stream().anyMatch(principal::hasRole);
    }

    public static boolean canViewAnyMemberInsights(UserPrincipal principal) {
        return canViewMemberFinanceInsights(principal) || canViewMemberLoanInsights(principal);
    }

    public static void requireMemberInsightsAccess(UserPrincipal principal) {
        if (!canViewAnyMemberInsights(principal)) {
            throw new ForbiddenException("Member insights are restricted to Saving Scheme officers");
        }
    }

    public static void requireBillingManager(UserPrincipal principal) {
        if (!canManageBilling(principal)) {
            throw new ForbiddenException("Subscription payments are managed by Saving Scheme leadership");
        }
    }

    public static boolean isLeadership(UserPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return true;
        }
        return LEADERSHIP_ROLE_CODES.stream().anyMatch(principal::hasRole);
    }

    public static boolean isOfficer(UserPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return true;
        }
        return OFFICER_ROLE_CODES.stream().anyMatch(principal::hasRole);
    }

    public static boolean canAssign(UserPrincipal actor, String normalizedTargetRole) {
        if (actor == null) {
            return false;
        }
        if (actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return true;
        }
        if (MEMBER.equals(normalizedTargetRole)) {
            return actor.hasAuthority("MEMBERSHIP_MANAGE");
        }
        if (!isLeadership(actor)) {
            return false;
        }
        return !PRESIDENT.equals(normalizedTargetRole);
    }

    public static void requireLoanApprove(UserPrincipal principal) {
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || principal.hasAuthority(LOAN_APPROVE)) {
            return;
        }
        throw new ForbiddenException("Loan committee approval is required");
    }

    public static void requireFineConfigurationManager(UserPrincipal principal) {
        requirePresident(principal, "Only the President can manage the fine configuration");
    }

    public static void requirePresident(UserPrincipal principal, String message) {
        if (principal == null) {
            throw new ForbiddenException(message);
        }
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || principal.hasRole(PRESIDENT)
                || principal.hasRole(LEGACY_ADMIN)) {
            return;
        }
        throw new ForbiddenException(message);
    }

    public static void requireFundAuthorize(UserPrincipal principal) {
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || principal.hasAuthority(FUND_AUTHORIZE)) {
            return;
        }
        throw new ForbiddenException(
                "A President or Vice President must co-sign this fund movement");
    }

    /** Human-readable cooperative role for notifications. Never returns raw enum tokens. */
    public static String displayLabel(String roleInCooperative) {
        String code = roleInCooperative == null ? MEMBER : roleInCooperative.trim().toUpperCase(Locale.ROOT);
        if (LEGACY_ADMIN.equals(code)) {
            code = PRESIDENT;
        }
        return switch (code) {
            case MEMBER -> "Member";
            case PRESIDENT -> "President";
            case VICE_PRESIDENT -> "Vice President";
            case SECRETARY -> "Secretary";
            case ACCOUNTANT -> "Treasurer";
            case LOAN_OFFICER -> "Loan Officer";
            default -> humanizeToken(code);
        };
    }

    private static String humanizeToken(String token) {
        if (token.isBlank()) {
            return "Member";
        }
        String[] parts = token.replace('-', '_').split("_");
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return label.isEmpty() ? "Member" : label.toString();
    }

    /** Highest cooperative office for electronic approval records. */
    public static String displayRole(UserPrincipal principal) {
        if (principal == null) {
            return MEMBER;
        }
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return CooperativeAuthorizationService.SUPER_ADMIN;
        }
        if (principal.hasRole(PRESIDENT) || principal.hasRole(LEGACY_ADMIN)) {
            return PRESIDENT;
        }
        if (principal.hasRole(VICE_PRESIDENT)) {
            return VICE_PRESIDENT;
        }
        if (principal.hasRole(SECRETARY)) {
            return SECRETARY;
        }
        if (principal.hasRole(ACCOUNTANT)) {
            return ACCOUNTANT;
        }
        if (principal.hasRole(LOAN_OFFICER)) {
            return LOAN_OFFICER;
        }
        return MEMBER;
    }
}
