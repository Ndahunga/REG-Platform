package rw.terimbere.csams.modules.notification.channel;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationService;

/**
 * Default channel: persists an in-app notification for the recipient.
 */
@Component
@RequiredArgsConstructor
public class InAppNotificationPublisher implements NotificationChannel {

    private final NotificationService notificationService;

    @Override
    public void publish(
            UUID userId,
            UUID cooperativeId,
            NotificationType type,
            String title,
            String body,
            String entityType,
            UUID entityId) {
        notificationService.create(userId, cooperativeId, type, title, body, entityType, entityId);
    }
}
