package rw.terimbere.csams.modules.notification.channel;

import java.util.UUID;
import rw.terimbere.csams.modules.notification.entity.NotificationType;

/**
 * Provider-agnostic notification delivery channel. Implementations may persist in-app,
 * send email, push, etc. without coupling domain services to a specific transport.
 */
public interface NotificationChannel {

    void publish(
            UUID userId,
            UUID cooperativeId,
            NotificationType type,
            String title,
            String body,
            String entityType,
            UUID entityId);
}
