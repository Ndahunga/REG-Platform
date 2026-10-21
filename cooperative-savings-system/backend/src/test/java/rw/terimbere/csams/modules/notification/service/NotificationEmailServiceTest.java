package rw.terimbere.csams.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.mail.MailProperties;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class NotificationEmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @Mock
    private UserRepository userRepository;

    private MailProperties properties;
    private NotificationEmailService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID cooperativeId = UUID.randomUUID();
    private final Executor sync = Runnable::run;

    @BeforeEach
    void setUp() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        properties = new MailProperties();
        service = new NotificationEmailService(mailSenderProvider, properties, userRepository, sync);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void missingMailConfig_skipsSend() {
        service.deliverFromInApp(
                userId,
                cooperativeId,
                NotificationType.LOAN,
                "Loan approved",
                "Your loan request was approved.",
                "Loan",
                UUID.randomUUID());

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(userRepository, never()).findByIdAndDeletedFalse(any());
    }

    @Test
    void configured_sendsToUserEmailAfterCommit() throws Exception {
        enableMail();
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        when(userRepository.findByIdAndDeletedFalse(userId)).thenReturn(Optional.of(userWithEmail("borrower@test.local")));

        beginTransaction();
        service.deliverFromInApp(
                userId,
                cooperativeId,
                NotificationType.LOAN,
                "Loan approved",
                "Your loan request was approved by Jane.",
                "Loan",
                UUID.randomUUID());
        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(userRepository, never()).findByIdAndDeletedFalse(any());

        commitSynchronizations();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getSubject()).isEqualTo("Loan approved");
        assertThat(InternetAddress.toString(sent.getAllRecipients())).contains("borrower@test.local");
        assertThat(sent.getFrom()[0].toString())
                .contains("OuWealth Community")
                .contains("noreply@ouwealth.test")
                .doesNotContain("ousuite.com")
                .doesNotContain("resend");
        String content = rawMessage(sent);
        assertThat(content)
                .contains("Your loan request was approved by Jane.")
                .contains("OuWealth Community")
                .contains("Please do not reply to this email.")
                .doesNotContain(userId.toString())
                .doesNotContain("password")
                .doesNotContain("ChangeMe")
                .doesNotContain("MAIL_PASSWORD")
                .doesNotContain("re_");
    }

    @Test
    void rollback_doesNotSend() {
        enableMail();
        beginTransaction();
        service.deliverFromInApp(
                userId,
                cooperativeId,
                NotificationType.CONTRIBUTION,
                "Contribution approved",
                "Your contribution was approved.",
                "Contribution",
                UUID.randomUUID());
        TransactionSynchronizationManager.clear();

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(userRepository, never()).findByIdAndDeletedFalse(any());
    }

    @Test
    void missingUserOrEmail_skipsSend() {
        enableMail();
        when(userRepository.findByIdAndDeletedFalse(userId)).thenReturn(Optional.empty());

        service.deliverFromInApp(
                userId, cooperativeId, NotificationType.ACCOUNT, "Welcome to OuWealth Community", "Hello", "User", userId);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void smtpFailure_doesNotPropagate() {
        enableMail();
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("smtp down"));
        when(userRepository.findByIdAndDeletedFalse(userId)).thenReturn(Optional.of(userWithEmail("a@test.local")));

        assertThatCode(() -> service.deliverFromInApp(
                        userId,
                        cooperativeId,
                        NotificationType.LOAN,
                        "Loan approved",
                        "Approved",
                        "Loan",
                        UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void sendFailureAfterCreate_isSwallowed() {
        enableMail();
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        when(userRepository.findByIdAndDeletedFalse(userId)).thenReturn(Optional.of(userWithEmail("a@test.local")));
        doThrow(new RuntimeException("timeout")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() -> service.deliverFromInApp(
                        userId,
                        cooperativeId,
                        NotificationType.CONTRIBUTION,
                        "Contribution approved",
                        "Approved",
                        "Contribution",
                        UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    private void enableMail() {
        properties.setEnabled(true);
        properties.setHost("smtp.test.local");
        properties.setFrom("noreply@ouwealth.test");
    }

    private static User userWithEmail(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static String rawMessage(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void commitSynchronizations() {
        List<TransactionSynchronization> syncs =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        for (TransactionSynchronization sync : syncs) {
            sync.afterCommit();
        }
    }
}
