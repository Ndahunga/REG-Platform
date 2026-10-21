package rw.terimbere.csams.modules.notification.channel;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationEmailService;

/**
 * Email channel for {@link rw.terimbere.csams.modules.notification.service.NotificationFacade}.
 *
 * <p>Delivers after commit via {@link NotificationEmailService}. Missing SMTP config is a
 * safe skip. Must never throw into financial flows.
 */
@Component
@RequiredArgsConstructor
public class EmailNotificationPublisher implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationPublisher.class);

    private final NotificationEmailService notificationEmailService;

    @Override
    public void publish(
            UUID userId,
            UUID cooperativeId,
            NotificationType type,
            String title,
            String body,
            String entityType,
            UUID entityId) {
        try {
            notificationEmailService.deliverFromInApp(
                    userId, cooperativeId, type, title, body, entityType, entityId);
        } catch (Exception ex) {
            log.warn(
                    "Email notification channel failed (ignored): userId={}, type={}, error={}",
                    userId,
                    type,
                    ex.getClass().getSimpleName());
        }
    }
}
