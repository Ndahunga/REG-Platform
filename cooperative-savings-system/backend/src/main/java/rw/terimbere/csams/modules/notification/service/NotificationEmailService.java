package rw.terimbere.csams.modules.notification.service;

import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.mail.MailProperties;
import rw.terimbere.csams.modules.notification.mail.NotificationEmailCopy;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;

/**
 * Optional SMTP delivery for existing in-app notification events.
 * Sends only after a successful commit. Failures never propagate to financial transactions.
 */
@Service
public class NotificationEmailService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;
    private final UserRepository userRepository;
    private final Executor taskExecutor;

    public NotificationEmailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            MailProperties mailProperties,
            UserRepository userRepository,
            @Qualifier("taskExecutor") Executor taskExecutor) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.mailProperties = mailProperties;
        this.userRepository = userRepository;
        this.taskExecutor = taskExecutor;
    }

    public void deliverFromInApp(
            UUID userId,
            UUID cooperativeId,
            NotificationType type,
            String title,
            String body,
            String entityType,
            UUID entityId) {
        if (userId == null || !isReady()) {
            if (userId != null && !mailProperties.isConfigured()) {
                log.debug("Email notification skipped (mail not configured): type={}, title={}", type, title);
            }
            return;
        }
        if (!StringUtils.hasText(title) && !StringUtils.hasText(body)) {
            return;
        }
        runAfterCommit(() -> sendToUser(userId, title, body));
    }

    boolean isReady() {
        return mailSender != null && mailProperties.isConfigured();
    }

    private void sendToUser(UUID userId, String title, String body) {
        try {
            User user = userRepository.findByIdAndDeletedFalse(userId).orElse(null);
            if (user == null || !StringUtils.hasText(user.getEmail())) {
                log.debug("Email notification skipped: no recipient email for user {}", userId);
                return;
            }
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(NotificationEmailCopy.fromAddress(mailProperties.getFrom()));
            helper.setTo(user.getEmail().trim());
            helper.setSubject(NotificationEmailCopy.subject(title));
            helper.setText(NotificationEmailCopy.plainText(title, body), NotificationEmailCopy.html(title, body));
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn(
                    "Email notification failed for user {}: {}",
                    userId,
                    ex.getClass().getSimpleName());
        }
    }

    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutor.execute(task);
                }
            });
            return;
        }
        taskExecutor.execute(task);
    }
}
