package rw.terimbere.csams.modules.contact.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import rw.terimbere.csams.modules.contact.dto.ContactRequest;
import rw.terimbere.csams.modules.notification.mail.MailProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactMessageServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    private MailProperties mailProperties;
    private SupportContactProperties supportProperties;
    private ContactMessageService service;

    @BeforeEach
    void setUp() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
        mailProperties = new MailProperties();
        mailProperties.setEnabled(true);
        mailProperties.setHost("smtp.example.test");
        mailProperties.setFrom("no-reply@ouwealth.test");
        supportProperties = new SupportContactProperties();
        supportProperties.setEmailTo("support@ozufy.com");
        supportProperties.setEmailFrom("support-sender@ouwealth.test");
        service = new ContactMessageService(mailSenderProvider, mailProperties, supportProperties);
    }

    private static ContactRequest request() {
        return ContactRequest.builder()
                .firstName("Alice")
                .lastName("Uwase")
                .email("alice@example.com")
                .countryCode("+250")
                .phoneNumber("0781234567")
                .message("I need help setting up my scheme.")
                .build();
    }

    private MimeMessage sent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    private static String addresses(Address[] list) {
        return Arrays.stream(list).map(a -> ((InternetAddress) a).getAddress()).toList().toString();
    }

    @Test
    void sendsToTheConfiguredSupportMailbox_fromTheConfiguredSender_withReplyToTheVisitor() throws Exception {
        service.send(request());

        MimeMessage message = sent();
        assertThat(addresses(message.getRecipients(Message.RecipientType.TO))).isEqualTo("[support@ozufy.com]");
        assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(addresses(message.getFrom())).isEqualTo("[support-sender@ouwealth.test]");
        assertThat(addresses(message.getReplyTo())).isEqualTo("[alice@example.com]");
    }

    @Test
    void subjectAndBodyCarryTheVisitorsDetails() throws Exception {
        service.send(request());

        MimeMessage message = sent();
        assertThat(message.getSubject()).isEqualTo("OuWealth support request - Alice Uwase");
        assertThat((String) message.getContent())
                .isEqualTo("First name: Alice\n"
                        + "Last name: Uwase\n"
                        + "Email: alice@example.com\n"
                        + "Phone: +250 0781234567\n"
                        + "Message:\n\n"
                        + "I need help setting up my scheme.\n");
    }

    @Test
    void aMissingPhoneIsReportedAsNotProvided() throws Exception {
        ContactRequest noPhone = request();
        noPhone.setPhoneNumber("  ");
        service.send(noPhone);

        assertThat((String) sent().getContent()).contains("Phone: Not provided");
    }

    @Test
    void theSenderFallsBackToTheSharedMailFromWhenNoSupportSenderIsConfigured() throws Exception {
        supportProperties.setEmailFrom("");
        service.send(request());

        assertThat(addresses(sent().getFrom())).isEqualTo("[no-reply@ouwealth.test]");
    }

    @Test
    void theRecipientCanOnlyComeFromServerConfiguration() {
        // the request type has no destination field at all, so a client has nothing to set
        for (Field field : ContactRequest.class.getDeclaredFields()) {
            assertThat(field.getName()).doesNotContainIgnoringCase("to").doesNotContainIgnoringCase("recipient");
        }
        supportProperties.setEmailTo("configured@ozufy.com");
        service.send(request());
        assertThat(addresses(recipients(sent()))).isEqualTo("[configured@ozufy.com]");
    }

    private static Address[] recipients(MimeMessage message) {
        try {
            return message.getRecipients(Message.RecipientType.TO);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void controlCharactersInNamesCannotReachTheSubjectHeader() throws Exception {
        ContactRequest sneaky = request();
        sneaky.setFirstName("Alice\r\nBcc: attacker@example.com");
        service.send(sneaky);

        MimeMessage message = sent();
        assertThat(message.getSubject()).doesNotContain("\r").doesNotContain("\n");
        assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(message.getHeader("Bcc")).isNull();
    }

    @Test
    void anAddressWithAnInjectedHeaderIsRejectedAndNothingIsSent() {
        ContactRequest sneaky = request();
        sneaky.setEmail("alice@example.com\r\nBcc: attacker@example.com");

        assertThatThrownBy(() -> service.send(sneaky)).isInstanceOf(ContactDeliveryException.class);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void whenMailIsNotConfiguredNothingIsSentAndTheErrorIsFriendly() {
        mailProperties.setHost("");

        assertThatThrownBy(() -> service.send(request()))
                .isInstanceOf(ContactDeliveryException.class)
                .hasMessageContaining("try again")
                .hasMessageNotContaining("smtp")
                .hasMessageNotContaining("password");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void whenThereIsNoSenderAtAllNothingIsSent() {
        supportProperties.setEmailFrom("");
        mailProperties.setFrom("");

        assertThatThrownBy(() -> service.send(request())).isInstanceOf(ContactDeliveryException.class);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void whenTheMailSwitchIsOffNothingIsSent() {
        mailProperties.setEnabled(false);

        assertThatThrownBy(() -> service.send(request())).isInstanceOf(ContactDeliveryException.class);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void whenThereIsNoMailSenderBeanNothingIsSent() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);
        ContactMessageService noSender = new ContactMessageService(mailSenderProvider, mailProperties, supportProperties);

        assertThatThrownBy(() -> noSender.send(request())).isInstanceOf(ContactDeliveryException.class);
    }

    @Test
    void aMailServerFailureBecomesAFriendlyErrorWithNoProviderDetails() {
        doThrow(new MailSendException("535 5.7.8 Authentication failed for user smtp-user password hunter2"))
                .when(mailSender)
                .send(any(MimeMessage.class));

        assertThatThrownBy(() -> service.send(request()))
                .isInstanceOf(ContactDeliveryException.class)
                .hasMessageNotContaining("535")
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("smtp-user");
    }
}
