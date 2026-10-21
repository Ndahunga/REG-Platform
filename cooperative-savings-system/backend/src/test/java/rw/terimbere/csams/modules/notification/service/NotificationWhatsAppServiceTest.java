package rw.terimbere.csams.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppCloudClient;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppProperties;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAccessResolver;

@ExtendWith(MockitoExtension.class)
class NotificationWhatsAppServiceTest {

    @Mock
    private WhatsAppCloudClient whatsAppCloudClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CooperativeMembershipRepository membershipRepository;

    @Mock
    private CooperativeAccessResolver accessResolver;

    private WhatsAppProperties properties;
    private NotificationWhatsAppService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID cooperativeId = UUID.randomUUID();
    private final Executor sync = Runnable::run;

    @BeforeEach
    void setUp() {
        properties = new WhatsAppProperties();
        service = new NotificationWhatsAppService(
                whatsAppCloudClient, properties, userRepository, membershipRepository, accessResolver, sync);
    }

    @Test
    void disabled_doesNotCallClientOrLoadUsers() {
        service.deliverFromInApp(
                userId,
                cooperativeId,
                NotificationType.CONTRIBUTION,
                "Contribution rejected",
                "Your contribution for 2026-05 was rejected by Jane (ACCOUNTANT): Unclear proof",
                "Contribution",
                UUID.randomUUID());
        service.notifyOfficers(cooperativeId, "CONTRIBUTION_WRITE", "msg", null);

        verify(whatsAppCloudClient, never()).sendText(any(), any());
        verify(whatsAppCloudClient, never()).sendDocument(any(), any(), any(), any());
        verify(userRepository, never()).findByIdAndDeletedFalse(any());
        verify(membershipRepository, never()).findActiveByCooperativeId(any());
    }

    @Test
    void configured_sendsEligibleNotification() {
        enableWhatsApp();
        when(userRepository.findByIdAndDeletedFalse(userId)).thenReturn(Optional.of(userWithPhone("0788123456")));

        service.deliverFromInApp(
                userId,
                cooperativeId,
                NotificationType.CONTRIBUTION,
                "Contribution rejected",
                "Your contribution for 2026-05 was rejected by Jane (ACCOUNTANT): Unclear proof",
                "Contribution",
                UUID.randomUUID());

        verify(whatsAppCloudClient)
                .sendText(
                        eq("250788123456"),
                        eq(
                                "TERIMBERE CSAMS\nYour contribution for May 2026 was rejected by Jane (ACCOUNTANT).\nReason: Unclear proof"));
    }

    @Test
    void missingOrInvalidPhone_skipsSend() {
        enableWhatsApp();
        UUID noPhone = UUID.randomUUID();
        UUID badPhone = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedFalse(noPhone))
                .thenReturn(Optional.of(userWithPhone(null)));
        when(userRepository.findByIdAndDeletedFalse(badPhone))
                .thenReturn(Optional.of(userWithPhone("12345")));

        service.deliverFromInApp(
                noPhone,
                cooperativeId,
                NotificationType.LOAN,
                "Loan rejected",
                "Your loan request was rejected by Jane (LOAN_OFFICER): Incomplete",
                "Loan",
                UUID.randomUUID());
        service.deliverFromInApp(
                badPhone,
                cooperativeId,
                NotificationType.LOAN,
                "Loan rejected",
                "Your loan request was rejected by Jane (LOAN_OFFICER): Incomplete",
                "Loan",
                UUID.randomUUID());

        verify(whatsAppCloudClient, never()).sendText(any(), any());
    }

    @Test
    void clientFailure_doesNotPropagate() {
        enableWhatsApp();
        when(userRepository.findByIdAndDeletedFalse(userId)).thenReturn(Optional.of(userWithPhone("0788123456")));
        doThrow(new RuntimeException("graph down")).when(whatsAppCloudClient).sendText(any(), any());

        assertThatCode(() -> service.deliverFromInApp(
                        userId,
                        cooperativeId,
                        NotificationType.LOAN,
                        "Loan disbursed",
                        "Your loan of 100.0000 has been disbursed.",
                        "Loan",
                        UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void firstStageTitle_isNotSent() {
        enableWhatsApp();
        service.deliverFromInApp(
                userId,
                cooperativeId,
                NotificationType.LOAN,
                "Loan awaiting second approval",
                "ignored",
                "Loan",
                UUID.randomUUID());
        verify(whatsAppCloudClient, never()).sendText(any(), any());
        verify(userRepository, never()).findByIdAndDeletedFalse(any());
    }

    @Test
    void finalApprovalAndDisbursement_sendOnceEach() {
        enableWhatsApp();
        when(userRepository.findByIdAndDeletedFalse(userId)).thenReturn(Optional.of(userWithPhone("0788123456")));

        service.deliverFromInApp(
                userId, cooperativeId, NotificationType.LOAN, "Loan approved", "approved by X.", "Loan", UUID.randomUUID());
        service.deliverFromInApp(
                userId,
                cooperativeId,
                NotificationType.LOAN,
                "Loan disbursed",
                "Your loan of 50 has been disbursed.",
                "Loan",
                UUID.randomUUID());

        verify(whatsAppCloudClient)
                .sendText(eq("250788123456"), eq("TERIMBERE CSAMS\nYour loan request has been fully approved."));
        verify(whatsAppCloudClient)
                .sendText(eq("250788123456"), eq("TERIMBERE CSAMS\nYour loan has been disbursed."));
    }

    @Test
    void notifyOfficers_sendsToValidPhonesOnly() {
        enableWhatsApp();
        UUID officerId = UUID.randomUUID();
        UUID excluded = UUID.randomUUID();
        User officer = userWithPhone("0788123456");
        officer.setId(officerId);
        when(membershipRepository.findActiveByCooperativeId(cooperativeId))
                .thenReturn(List.of(
                        membership(officerId, "LOAN_OFFICER"), membership(excluded, "LOAN_OFFICER")));
        when(accessResolver.permissionsForMembershipRole("LOAN_OFFICER"))
                .thenReturn(Set.of("LOAN_READ", "LOAN_APPROVE"));
        when(userRepository.findByIdAndDeletedFalse(officerId)).thenReturn(Optional.of(officer));

        service.notifyOfficers(
                cooperativeId, "LOAN_APPROVE", "TERIMBERE CSAMS\nYou have a loan request pending your approval.", excluded);

        verify(whatsAppCloudClient)
                .sendText(eq("250788123456"), eq("TERIMBERE CSAMS\nYou have a loan request pending your approval."));
        verify(whatsAppCloudClient, never()).sendText(eq("250788000000"), any());
    }

    @Test
    void notifyOfficers_usesRoleInThisCooperative_notGlobalRoles() {
        enableWhatsApp();
        UUID officerInThisCoop = UUID.randomUUID();
        UUID memberHere = UUID.randomUUID(); // may be an accountant in ANOTHER cooperative
        User officer = userWithPhone("0788123456");
        officer.setId(officerInThisCoop);
        when(membershipRepository.findActiveByCooperativeId(cooperativeId))
                .thenReturn(List.of(membership(officerInThisCoop, "ACCOUNTANT"), membership(memberHere, "MEMBER")));
        when(accessResolver.permissionsForMembershipRole("ACCOUNTANT"))
                .thenReturn(Set.of("CONTRIBUTION_READ", "CONTRIBUTION_WRITE"));
        when(accessResolver.permissionsForMembershipRole("MEMBER")).thenReturn(Set.of("CONTRIBUTION_READ"));
        when(userRepository.findByIdAndDeletedFalse(officerInThisCoop)).thenReturn(Optional.of(officer));

        assertThat(service.officerRecipientIds(cooperativeId, "CONTRIBUTION_WRITE"))
                .containsExactly(officerInThisCoop);
        service.notifyOfficers(cooperativeId, "CONTRIBUTION_WRITE", "pending", null);

        verify(userRepository, never()).findByIdAndDeletedFalse(memberHere);
        verify(whatsAppCloudClient).sendText(eq("250788123456"), eq("pending"));
    }

    @Test
    void officerRecipients_includeActiveSuperAdminMembers() {
        UUID superAdminMember = UUID.randomUUID();
        UUID plainMember = UUID.randomUUID();
        when(membershipRepository.findActiveByCooperativeId(cooperativeId))
                .thenReturn(List.of(membership(superAdminMember, "MEMBER"), membership(plainMember, "MEMBER")));
        when(accessResolver.permissionsForMembershipRole("MEMBER")).thenReturn(Set.of("CONTRIBUTION_READ"));
        when(userRepository.findSuperAdminIdsAmong(any())).thenReturn(List.of(superAdminMember));

        assertThat(service.officerRecipientIds(cooperativeId, "CONTRIBUTION_WRITE"))
                .containsExactly(superAdminMember);
    }

    private static CooperativeMembership membership(UUID userId, String roleInCooperative) {
        return CooperativeMembership.builder()
                .userId(userId)
                .membershipStatus("ACTIVE")
                .roleInCooperative(roleInCooperative)
                .build();
    }

    private void enableWhatsApp() {
        properties.setEnabled(true);
        properties.setAccessToken("super-secret-token");
        properties.setPhoneNumberId("1234567890");
    }

    private static User userWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        return user;
    }
}
