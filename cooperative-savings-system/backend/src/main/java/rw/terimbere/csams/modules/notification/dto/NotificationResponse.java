package rw.terimbere.csams.modules.notification.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.notification.entity.NotificationType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    private UUID id;
    private UUID userId;
    private UUID cooperativeId;
    private NotificationType type;
    private String title;
    private String body;
    private String entityType;
    private UUID entityId;
    private boolean read;
    private Instant readAt;
    private Instant createdAt;
}
