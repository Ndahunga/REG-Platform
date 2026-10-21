package rw.terimbere.csams.modules.audit.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.audit.entity.ApprovalAction;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalEventResponse {

    private UUID id;
    private UUID cooperativeId;
    private String entityType;
    private UUID entityId;
    private UUID actorUserId;
    private String actorName;
    private String actorRole;
    private ApprovalAction action;
    private String previousStatus;
    private String newStatus;
    private String comment;
    private Instant createdAt;
}
