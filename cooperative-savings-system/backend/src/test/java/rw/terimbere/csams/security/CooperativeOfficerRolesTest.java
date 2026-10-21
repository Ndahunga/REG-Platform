package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

class CooperativeOfficerRolesTest {

    @Test
    void normalizesLegacyAdminToPresident() {
        assertThat(CooperativeOfficerRoles.normalize("cooperative_admin")).isEqualTo("PRESIDENT");
        assertThat(CooperativeOfficerRoles.normalize("VICE_PRESIDENT")).isEqualTo("VICE_PRESIDENT");
        assertThat(CooperativeOfficerRoles.normalize(null)).isEqualTo("MEMBER");
    }

    @Test
    void displayLabelHumanizesCooperativeRoles() {
        assertThat(CooperativeOfficerRoles.displayLabel("MEMBER")).isEqualTo("Member");
        assertThat(CooperativeOfficerRoles.displayLabel("VICE_PRESIDENT")).isEqualTo("Vice President");
        assertThat(CooperativeOfficerRoles.displayLabel("ACCOUNTANT")).isEqualTo("Treasurer");
        assertThat(CooperativeOfficerRoles.displayLabel("LOAN_OFFICER")).isEqualTo("Loan Officer");
        assertThat(CooperativeOfficerRoles.displayLabel("cooperative_admin")).isEqualTo("President");
        assertThat(CooperativeOfficerRoles.displayLabel(null)).isEqualTo("Member");
    }

    @Test
    void rejectsUnknownMembershipRole() {
        assertThatThrownBy(() -> CooperativeOfficerRoles.normalize("TREASURER"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void leadershipCanAppointOfficersButNotPresident() {
        UserPrincipal president = principal(Set.of("PRESIDENT"), Set.of());
        assertThat(CooperativeOfficerRoles.canAssign(president, "ACCOUNTANT")).isTrue();
        assertThat(CooperativeOfficerRoles.canAssign(president, "PRESIDENT")).isFalse();

        UserPrincipal secretary =
                principal(Set.of("SECRETARY", "MEMBER"), Set.of("MEMBERSHIP_MANAGE"));
        assertThat(CooperativeOfficerRoles.canAssign(secretary, "MEMBER")).isTrue();
        assertThat(CooperativeOfficerRoles.canAssign(secretary, "ACCOUNTANT")).isFalse();
    }

    @Test
    void fundAuthorizeRequiresPermission() {
        UserPrincipal accountant = principal(Set.of("ACCOUNTANT", "MEMBER"), Set.of("LOAN_WRITE"));
        assertThatThrownBy(() -> CooperativeOfficerRoles.requireFundAuthorize(accountant))
                .isInstanceOf(ForbiddenException.class);

        UserPrincipal president =
                principal(Set.of("PRESIDENT", "MEMBER"), Set.of(CooperativeOfficerRoles.FUND_AUTHORIZE));
        CooperativeOfficerRoles.requireFundAuthorize(president);
    }

    @Test
    void billingManagersIncludePresidentVicePresidentAccountantAndSuperAdmin() {
        assertThat(CooperativeOfficerRoles.canManageBilling(principal(Set.of("PRESIDENT"), Set.of()))).isTrue();
        assertThat(CooperativeOfficerRoles.canManageBilling(principal(Set.of("VICE_PRESIDENT"), Set.of())))
                .isTrue();
        assertThat(CooperativeOfficerRoles.canManageBilling(principal(Set.of("ACCOUNTANT"), Set.of()))).isTrue();
        assertThat(CooperativeOfficerRoles.canManageBilling(principal(Set.of("SUPER_ADMIN"), Set.of())))
                .isTrue();
        assertThat(CooperativeOfficerRoles.canManageBilling(principal(Set.of("MEMBER"), Set.of()))).isFalse();
        assertThat(CooperativeOfficerRoles.canManageBilling(principal(Set.of("SECRETARY"), Set.of()))).isFalse();
        assertThat(CooperativeOfficerRoles.canManageBilling(principal(Set.of("LOAN_OFFICER"), Set.of())))
                .isFalse();
        assertThatThrownBy(() -> CooperativeOfficerRoles.requireBillingManager(principal(Set.of("MEMBER"), Set.of())))
                .isInstanceOf(ForbiddenException.class);
    }

    private static UserPrincipal principal(Set<String> roles, Set<String> permissions) {
        return UserPrincipal.builder()
                .username("tester")
                .roles(roles)
                .permissions(permissions)
                .cooperativeIds(Set.of())
                .accountNonLocked(true)
                .enabled(true)
                .build();
    }
}
