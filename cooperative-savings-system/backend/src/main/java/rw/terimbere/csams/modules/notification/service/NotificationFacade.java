package rw.terimbere.csams.modules.notification.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.modules.notification.channel.NotificationChannel;
import rw.terimbere.csams.modules.notification.entity.NotificationType;

/**
 * Facade that fans out a notification to every registered {@link NotificationChannel}.
 */
@Service
@RequiredArgsConstructor
public class NotificationFacade {

    private static final Logger log = LoggerFactory.getLogger(NotificationFacade.class);

    private final List<NotificationChannel> channels;

    public void notifyUser(
            UUID userId,
            UUID cooperativeId,
            NotificationType type,
            String title,
            String body,
            String entityType,
            UUID entityId) {
        if (userId == null) {
            return;
        }
        for (NotificationChannel channel : channels) {
            try {
                channel.publish(userId, cooperativeId, type, title, body, entityType, entityId);
            } catch (Exception ex) {
                log.warn(
                        "Notification channel {} failed for user {}: {}",
                        channel.getClass().getSimpleName(),
                        userId,
                        ex.getMessage());
            }
        }
    }
}
