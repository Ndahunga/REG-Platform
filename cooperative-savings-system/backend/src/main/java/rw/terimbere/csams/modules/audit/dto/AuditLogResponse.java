package rw.terimbere.csams.modules.audit.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private UUID id;
    private UUID userId;
    /** Actor display name (full name, falling back to username). */
    private String userName;
    private UUID cooperativeId;
    private String action;
    private String entityType;
    private UUID entityId;
    /** Human-readable label for the affected entity (member name, coop name, file name, …). */
    private String entityLabel;
    private String previousValues;
    private String newValues;
    private String ipAddress;
    private String userAgent;
    private Instant createdAt;
}
