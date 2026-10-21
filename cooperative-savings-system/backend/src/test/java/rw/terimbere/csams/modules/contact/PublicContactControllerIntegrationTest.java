package rw.terimbere.csams.modules.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The public contact endpoint: open without login, validated, and always delivered to the configured mailbox. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "app.mail.enabled=true",
            "app.mail.host=smtp.example.test",
            "app.mail.from=no-reply@ouwealth.test",
            "app.support.email-to=support@ozufy.com",
            "app.support.email-from=support-sender@ouwealth.test",
        })
class PublicContactControllerIntegrationTest {

    private static final String URL = "/api/v1/public/contact";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
    }

    private static String json(String overrides) {
        String base =
                """
                {"firstName":"Alice","lastName":"Uwase","email":"alice@example.com",
                 "countryCode":"+250","phoneNumber":"0781234567","message":"I need help."%s}
                """;
        return base.formatted(overrides.isEmpty() ? "" : "," + overrides);
    }

    private ResultActions submit(String body) throws Exception {
        // no Authorization header on purpose: visitors are not logged in
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void assertRejectedAndNothingSent(String body) throws Exception {
        submit(body).andExpect(status().isBadRequest());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void aValidMessageIsAcceptedWithoutLoggingIn_andEmailedToSupport() throws Exception {
        submit(json(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Your message has been sent to the OuWealth support team."));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        assertThat(((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress())
                .isEqualTo("support@ozufy.com");
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("support-sender@ouwealth.test");
        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress()).isEqualTo("alice@example.com");
        assertThat(message.getSubject()).isEqualTo("OuWealth support request - Alice Uwase");
        assertThat((String) message.getContent()).contains("First name: Alice", "Email: alice@example.com", "I need help.");
    }

    @Test
    void thePhoneIsOptional() throws Exception {
        submit("{\"firstName\":\"Alice\",\"lastName\":\"Uwase\",\"email\":\"alice@example.com\","
                        + "\"countryCode\":\"+250\",\"message\":\"Hello\"}")
                .andExpect(status().isOk());
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void theClientCannotChooseTheRecipient_unknownFieldsAreIgnored() throws Exception {
        submit(json("\"to\":\"attacker@example.com\",\"recipient\":\"attacker@example.com\",\"cc\":\"a@b.co\""))
                .andExpect(status().isOk());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress()).isEqualTo("support@ozufy.com");
    }

    @Test
    void requiredFieldsAreEnforced() throws Exception {
        assertRejectedAndNothingSent("{}");
        assertRejectedAndNothingSent(json("\"firstName\":\"\""));
        assertRejectedAndNothingSent(json("\"lastName\":\"   \""));
        assertRejectedAndNothingSent(json("\"email\":\"\""));
        assertRejectedAndNothingSent(json("\"countryCode\":\"\""));
        assertRejectedAndNothingSent(json("\"message\":\"  \""));
    }

    @Test
    void theEmailMustBeValid() throws Exception {
        assertRejectedAndNothingSent(json("\"email\":\"not-an-email\""));
        assertRejectedAndNothingSent(json("\"email\":\"a b@example.com\""));
        assertRejectedAndNothingSent(json("\"email\":\"a@example.com, b@example.com\""));
    }

    @Test
    void lineBreaksAreRejectedSoNothingCanBecomeAHeader() throws Exception {
        assertRejectedAndNothingSent(json("\"firstName\":\"Alice\\r\\nBcc: attacker@example.com\""));
        assertRejectedAndNothingSent(json("\"lastName\":\"Uwase\\nCc: attacker@example.com\""));
        assertRejectedAndNothingSent(json("\"email\":\"alice@example.com\\r\\nBcc: attacker@example.com\""));
    }

    @Test
    void lengthsAreCapped() throws Exception {
        assertRejectedAndNothingSent(json("\"firstName\":\"" + "a".repeat(81) + "\""));
        assertRejectedAndNothingSent(json("\"lastName\":\"" + "a".repeat(81) + "\""));
        assertRejectedAndNothingSent(json("\"email\":\"" + "a".repeat(250) + "@example.com\""));
        assertRejectedAndNothingSent(json("\"phoneNumber\":\"" + "1".repeat(21) + "\""));
        assertRejectedAndNothingSent(json("\"message\":\"" + "m".repeat(2001) + "\""));
    }

    @Test
    void theLongestAllowedMessageIsAccepted() throws Exception {
        submit(json("\"message\":\"" + "m".repeat(2000) + "\"")).andExpect(status().isOk());
    }

    @Test
    void theCountryCodeAndPhoneMustLookLikeOne() throws Exception {
        assertRejectedAndNothingSent(json("\"countryCode\":\"Rwanda\""));
        assertRejectedAndNothingSent(json("\"countryCode\":\"250\""));
        assertRejectedAndNothingSent(json("\"phoneNumber\":\"call me maybe\""));
    }

    @Test
    void aMailServerFailureIsReportedAsAFriendlyServerError_withoutProviderDetails() throws Exception {
        doThrow(new MailSendException("535 Authentication failed: user smtp-user, password hunter2"))
                .when(mailSender)
                .send(any(MimeMessage.class));

        submit(json(""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CONTACT_DELIVERY_FAILED"))
                .andExpect(jsonPath("$.message")
                        .value("We could not send your message right now. Please try again or contact us by email or WhatsApp."))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("535")
                        .doesNotContain("hunter2")
                        .doesNotContain("smtp-user"));
    }

    @Test
    void aWrongHttpMethodIsA405_neverA500_andNeverSendsMail() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("This operation is not supported for that HTTP method."));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void otherMethodsAndPathsStayClosed() throws Exception {
        // only POST /api/v1/public/contact was opened: other methods and sibling paths still need a login
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/public/contact/anything").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/public/other").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
