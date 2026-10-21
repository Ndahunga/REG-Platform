package rw.terimbere.csams.modules.contact.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.contact.dto.ContactRequest;
import rw.terimbere.csams.modules.notification.mail.MailProperties;

/**
 * Emails a public contact message to the support mailbox. The recipient and sender are server configuration; the
 * visitor's address is only ever the Reply-To, so pressing Reply in the support inbox answers the visitor.
 */
@Service
public class ContactMessageService {

    private static final Logger log = LoggerFactory.getLogger(ContactMessageService.class);

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;
    private final SupportContactProperties supportProperties;

    public ContactMessageService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            MailProperties mailProperties,
            SupportContactProperties supportProperties) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.mailProperties = mailProperties;
        this.supportProperties = supportProperties;
    }

    public void send(ContactRequest request) {
        String from = senderAddress();
        if (mailSender == null
                || !mailProperties.isEnabled()
                || !StringUtils.hasText(mailProperties.getHost())
                || !StringUtils.hasText(from)
                || !StringUtils.hasText(supportProperties.getEmailTo())) {
            log.warn("Contact message not sent: outgoing mail is not configured");
            throw new ContactDeliveryException();
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(supportProperties.getEmailTo().trim());
            // the visitor's address is validated and parsed as a single address, never concatenated into a header
            helper.setReplyTo(new InternetAddress(request.getEmail().trim(), true));
            helper.setSubject(subject(request));
            helper.setText(body(request), false);
            mailSender.send(message);
        } catch (Exception ex) {
            // log the failure type only: never the credentials, the provider's response or the visitor's message
            log.warn("Contact message could not be sent: {}", ex.getClass().getSimpleName());
            throw new ContactDeliveryException(ex);
        }
    }

    private String senderAddress() {
        if (StringUtils.hasText(supportProperties.getEmailFrom())) {
            return supportProperties.getEmailFrom().trim();
        }
        return mailProperties.getFrom() == null ? "" : mailProperties.getFrom().trim();
    }

    static String subject(ContactRequest request) {
        return "OuWealth support request - " + clean(request.getFirstName()) + " " + clean(request.getLastName());
    }

    static String body(ContactRequest request) {
        String phone = StringUtils.hasText(request.getPhoneNumber())
                ? request.getCountryCode().trim() + " " + request.getPhoneNumber().trim()
                : "Not provided";
        return "First name: " + clean(request.getFirstName()) + "\n"
                + "Last name: " + clean(request.getLastName()) + "\n"
                + "Email: " + request.getEmail().trim() + "\n"
                + "Phone: " + phone + "\n"
                + "Message:\n\n"
                + request.getMessage().trim()
                + "\n";
    }

    /** Defence in depth: names are validated to have no control characters, and any that slip through become spaces. */
    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").trim();
    }
}
