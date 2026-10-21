package rw.terimbere.csams.modules.notification.channel;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationWhatsAppService;

/**
 * Optional WhatsApp channel for existing in-app events. Never throws into financial flows.
 * Does not persist extra notification rows.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppNotificationPublisher implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotificationPublisher.class);

    private final NotificationWhatsAppService notificationWhatsAppService;

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
            notificationWhatsAppService.deliverFromInApp(
                    userId, cooperativeId, type, title, body, entityType, entityId);
        } catch (Exception ex) {
            log.warn(
                    "WhatsApp notification channel failed (ignored): userId={}, type={}, error={}",
                    userId,
                    type,
                    ex.getClass().getSimpleName());
        }
    }
}
