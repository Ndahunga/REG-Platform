package rw.terimbere.csams.modules.notification.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationWhatsAppCopyTest {

    @Test
    void formatsContributionAndLoanMemberMessages() {
        assertThat(NotificationWhatsAppCopy.fromInApp(
                        "Contribution submitted",
                        "Your contribution for 2026-05 was submitted for Accountant review."))
                .isEqualTo("TERIMBERE CSAMS\nYour contribution for May 2026 was submitted for approval.");

        assertThat(NotificationWhatsAppCopy.fromInApp(
                        "Contribution approved",
                        "Your contribution for 2026-05 was approved by Jane Doe (ACCOUNTANT)."))
                .isEqualTo(
                        "TERIMBERE CSAMS\nYour contribution for May 2026 was approved by Jane Doe (ACCOUNTANT).");

        assertThat(NotificationWhatsAppCopy.fromInApp(
                        "Contribution rejected",
                        "Your contribution for 2026-05 was rejected by Jane Doe (ACCOUNTANT): Unclear proof"))
                .isEqualTo(
                        "TERIMBERE CSAMS\nYour contribution for May 2026 was rejected by Jane Doe (ACCOUNTANT).\nReason: Unclear proof");

        assertThat(NotificationWhatsAppCopy.fromInApp(
                        "Loan approved", "Your loan request was approved by Jane Doe (PRESIDENT)."))
                .isEqualTo("TERIMBERE CSAMS\nYour loan request has been fully approved.");

        assertThat(NotificationWhatsAppCopy.fromInApp(
                        "Loan rejected",
                        "Your loan request was rejected by Jane Doe (LOAN_OFFICER): Incomplete form"))
                .isEqualTo(
                        "TERIMBERE CSAMS\nYour loan request was rejected by Jane Doe (LOAN_OFFICER).\nReason: Incomplete form");

        assertThat(NotificationWhatsAppCopy.fromInApp(
                        "Loan disbursed", "Your loan of 100000.0000 has been disbursed."))
                .isEqualTo("TERIMBERE CSAMS\nYour loan has been disbursed.");
    }

    @Test
    void skipsEventsThatMustNotGoToWhatsApp() {
        assertThat(NotificationWhatsAppCopy.fromInApp("Guarantor request", "Please guarantee")).isNull();
        assertThat(NotificationWhatsAppCopy.fromInApp("Loan awaiting second approval", "body")).isNull();
        assertThat(NotificationWhatsAppCopy.fromInApp(null, "body")).isNull();
    }

    @Test
    void officerPendingCopy() {
        assertThat(NotificationWhatsAppCopy.contributionPending(1))
                .isEqualTo("TERIMBERE CSAMS\nYou have 1 contribution request pending approval.");
        assertThat(NotificationWhatsAppCopy.contributionPending(3))
                .isEqualTo("TERIMBERE CSAMS\nYou have 3 contribution requests pending approval.");
        assertThat(NotificationWhatsAppCopy.loanPending())
                .isEqualTo("TERIMBERE CSAMS\nYou have a loan request pending your approval.");
    }
}
