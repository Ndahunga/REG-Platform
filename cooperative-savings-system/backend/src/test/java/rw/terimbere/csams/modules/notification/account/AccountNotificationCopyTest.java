package rw.terimbere.csams.modules.notification.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountNotificationCopyTest {

    @Test
    void welcomeAndRoleCopyNeverIncludePasswordsOrEnumTokens() {
        String officerWelcome = AccountNotificationCopy.welcomeNewMemberBody("ABC Saving Scheme", "ACCOUNTANT");
        assertThat(officerWelcome)
                .contains("ABC Saving Scheme")
                .contains("Treasurer")
                .contains("provided separately")
                .doesNotContain("ACCOUNTANT")
                .doesNotContain("password")
                .doesNotContain("Temporary");

        String adminWelcome = AccountNotificationCopy.welcomeNewAdministratorBody("ABC Saving Scheme");
        assertThat(adminWelcome).contains("President").doesNotContain("PRESIDENT");

        String membership = AccountNotificationCopy.membershipAddedBody("ABC Saving Scheme", "PRESIDENT");
        assertThat(membership)
                .startsWith("You have been added to ABC Saving Scheme as President.")
                .doesNotContain("Welcome to OuWealth");

        String roleChange = AccountNotificationCopy.roleChangedBody("ABC Saving Scheme", "MEMBER", "ACCOUNTANT");
        assertThat(roleChange)
                .isEqualTo("Your role in ABC Saving Scheme changed from Member to Treasurer.")
                .doesNotContain("ACCOUNTANT")
                .doesNotContain("MEMBER");
    }
}
