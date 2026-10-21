package rw.terimbere.csams.modules.audit.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.dto.ApprovalEventResponse;
import rw.terimbere.csams.modules.audit.entity.ApprovalAction;
import rw.terimbere.csams.modules.audit.entity.ApprovalEvent;
import rw.terimbere.csams.modules.audit.repository.ApprovalEventRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;

@Service
@RequiredArgsConstructor
public class ApprovalTrailService {

    public static final String ENTITY_LOAN = "Loan";
    public static final String ENTITY_CONTRIBUTION = "Contribution";
    public static final String ENTITY_SOCIAL_CONTRIBUTION = "SocialContribution";
    public static final String ENTITY_LOAN_GUARANTOR = "LoanGuarantor";

    private final ApprovalEventRepository approvalEventRepository;
    private final UserRepository userRepository;

    @Transactional
    public ApprovalEventResponse append(
            UUID cooperativeId,
            String entityType,
            UUID entityId,
            UserPrincipal principal,
            ApprovalAction action,
            String previousStatus,
            String newStatus,
            String comment) {
        String actorName = userRepository
                .findByIdAndDeletedFalse(principal.getId())
                .map(User::getFullName)
                .filter(StringUtils::hasText)
                .orElse(principal.getUsername());
        ApprovalEvent event = ApprovalEvent.builder()
                .cooperativeId(cooperativeId)
                .entityType(entityType)
                .entityId(entityId)
                .actorUserId(principal.getId())
                .actorName(actorName)
                .actorRole(CooperativeOfficerRoles.displayRole(principal))
                .action(action)
                .previousStatus(trimToNull(previousStatus))
                .newStatus(trimToNull(newStatus))
                .comment(trimToNull(comment))
                .build();
        event = approvalEventRepository.save(event);
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public List<ApprovalEventResponse> list(UUID cooperativeId, String entityType, UUID entityId) {
        return approvalEventRepository
                .findByCooperativeIdAndEntityTypeAndEntityIdOrderByCreatedAtAsc(
                        cooperativeId, entityType, entityId)
                .stream()
                .map(ApprovalTrailService::toResponse)
                .toList();
    }

    private static ApprovalEventResponse toResponse(ApprovalEvent event) {
        return ApprovalEventResponse.builder()
                .id(event.getId())
                .cooperativeId(event.getCooperativeId())
                .entityType(event.getEntityType())
                .entityId(event.getEntityId())
                .actorUserId(event.getActorUserId())
                .actorName(event.getActorName())
                .actorRole(event.getActorRole())
                .action(event.getAction())
                .previousStatus(event.getPreviousStatus())
                .newStatus(event.getNewStatus())
                .comment(event.getComment())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
