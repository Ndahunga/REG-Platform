package rw.terimbere.csams.modules.report.service;

import java.util.EnumSet;
import java.util.Set;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;

/**
 * Ordinary members may only print their own activity. Officers get cooperative-wide
 * reports that match their permissions — not every type on the menu.
 */
public final class ReportAccessPolicy {

    static final Set<ReportType> MEMBER_SELF_TYPES = EnumSet.of(
            ReportType.CONTRIBUTIONS,
            ReportType.SPECIAL_CONTRIBUTIONS,
            ReportType.LOANS,
            ReportType.REPAYMENTS,
            ReportType.FINES,
            ReportType.FINE_PAYMENTS,
            ReportType.SOCIAL_FUND,
            ReportType.PAYOUTS);

    private ReportAccessPolicy() {}

    public static boolean isSelfScoped(UserPrincipal principal) {
        return principal != null && !CooperativeOfficerRoles.isOfficer(principal);
    }

    public static boolean canExport(UserPrincipal principal, ReportType type) {
        if (principal == null || type == null) {
            return false;
        }
        if (principal.hasRole("SUPER_ADMIN")) {
            return true;
        }
        if (isSelfScoped(principal)) {
            return MEMBER_SELF_TYPES.contains(type);
        }
        return switch (type) {
            case MEMBERS -> principal.hasAuthority("MEMBERSHIP_MANAGE") || principal.hasRole("SECRETARY");
            case CONTRIBUTIONS, SPECIAL_CONTRIBUTIONS -> principal.hasAuthority("CONTRIBUTION_READ");
            case LOANS, REPAYMENTS -> principal.hasAuthority("LOAN_READ");
            case FINES, FINE_PAYMENTS -> principal.hasAuthority("FINE_READ");
            case SOCIAL_FUND -> principal.hasAuthority("SOCIAL_READ");
            case INVESTMENTS -> principal.hasAuthority("INVESTMENT_READ");
            case INCOME, EXPENSES -> principal.hasAuthority("INCOME_EXPENSE_READ");
            case PAYOUTS -> principal.hasAuthority("PAYOUT_READ");
            case FINANCIAL_LEDGER -> principal.hasAuthority("LEDGER_READ");
            case AUDIT_LOGS -> principal.hasAuthority("AUDIT_READ");
            case FULL_FINANCIAL ->
                    principal.hasAuthority("INCOME_EXPENSE_READ")
                            || CooperativeOfficerRoles.isLeadership(principal)
                            || principal.hasRole("ACCOUNTANT");
        };
    }
}
