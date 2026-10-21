package rw.terimbere.csams.modules.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.security.UserPrincipal;

class ReportAccessPolicyTest {

    @Test
    void memberCannotExportCooperativeWideReports() {
        UserPrincipal member = principal("MEMBER", Set.of("REPORT_READ", "CONTRIBUTION_READ", "LOAN_READ"));

        assertThat(ReportAccessPolicy.isSelfScoped(member)).isTrue();
        assertThat(ReportAccessPolicy.canExport(member, ReportType.CONTRIBUTIONS)).isTrue();
        assertThat(ReportAccessPolicy.canExport(member, ReportType.LOANS)).isTrue();
        assertThat(ReportAccessPolicy.canExport(member, ReportType.FINES)).isTrue();
        assertThat(ReportAccessPolicy.canExport(member, ReportType.FULL_FINANCIAL)).isFalse();
        assertThat(ReportAccessPolicy.canExport(member, ReportType.FINANCIAL_LEDGER)).isFalse();
        assertThat(ReportAccessPolicy.canExport(member, ReportType.INVESTMENTS)).isFalse();
        assertThat(ReportAccessPolicy.canExport(member, ReportType.MEMBERS)).isFalse();
        assertThat(ReportAccessPolicy.canExport(member, ReportType.AUDIT_LOGS)).isFalse();
    }

    @Test
    void loanOfficerDoesNotGetFullFinancialOrInvestments() {
        UserPrincipal officer = principal(
                "LOAN_OFFICER",
                Set.of("REPORT_READ", "CONTRIBUTION_READ", "LOAN_READ", "LEDGER_READ"));

        assertThat(ReportAccessPolicy.isSelfScoped(officer)).isFalse();
        assertThat(ReportAccessPolicy.canExport(officer, ReportType.LOANS)).isTrue();
        assertThat(ReportAccessPolicy.canExport(officer, ReportType.CONTRIBUTIONS)).isTrue();
        assertThat(ReportAccessPolicy.canExport(officer, ReportType.FINANCIAL_LEDGER)).isTrue();
        assertThat(ReportAccessPolicy.canExport(officer, ReportType.FULL_FINANCIAL)).isFalse();
        assertThat(ReportAccessPolicy.canExport(officer, ReportType.INVESTMENTS)).isFalse();
        assertThat(ReportAccessPolicy.canExport(officer, ReportType.MEMBERS)).isFalse();
    }

    @Test
    void accountantGetsFinancialReportsNotMemberRegister() {
        UserPrincipal accountant = principal(
                "ACCOUNTANT",
                Set.of(
                        "REPORT_READ",
                        "CONTRIBUTION_READ",
                        "LOAN_READ",
                        "LEDGER_READ",
                        "INVESTMENT_READ",
                        "INCOME_EXPENSE_READ",
                        "PAYOUT_READ",
                        "FINE_READ",
                        "SOCIAL_READ"));

        assertThat(ReportAccessPolicy.canExport(accountant, ReportType.FULL_FINANCIAL)).isTrue();
        assertThat(ReportAccessPolicy.canExport(accountant, ReportType.INVESTMENTS)).isTrue();
        assertThat(ReportAccessPolicy.canExport(accountant, ReportType.MEMBERS)).isFalse();
        assertThat(ReportAccessPolicy.canExport(accountant, ReportType.AUDIT_LOGS)).isFalse();
    }

    @Test
    void superAdminCanExportEveryType() {
        UserPrincipal admin = principal("SUPER_ADMIN", Set.of());
        for (ReportType type : ReportType.values()) {
            assertThat(ReportAccessPolicy.canExport(admin, type)).isTrue();
        }
    }

    private static UserPrincipal principal(String role, Set<String> permissions) {
        return UserPrincipal.builder()
                .id(UUID.randomUUID())
                .username("tester")
                .password("")
                .roles(Set.of(role))
                .permissions(permissions)
                .cooperativeIds(Set.of())
                .accountNonLocked(true)
                .enabled(true)
                .build();
    }
}
