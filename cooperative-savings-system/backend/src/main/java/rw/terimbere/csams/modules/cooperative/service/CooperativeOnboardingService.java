package rw.terimbere.csams.modules.cooperative.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Explicit onboarding-state transitions. Operational {@code CooperativeStatus} is never changed here.
 */
@Service
@RequiredArgsConstructor
public class CooperativeOnboardingService {

    private final CooperativeRepository cooperativeRepository;
    private final AuditService auditService;

    @Transactional
    public CooperativeOnboardingState completeIfAwaitingPresident(
            UUID cooperativeId, UUID actorUserId, HttpServletRequest httpRequest) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        CooperativeOnboardingState previous = cooperative.getOnboardingState() == null
                ? CooperativeOnboardingState.AWAITING_PRESIDENT
                : cooperative.getOnboardingState();
        if (previous == CooperativeOnboardingState.COMPLETE) {
            return previous;
        }
        cooperative.setOnboardingState(CooperativeOnboardingState.COMPLETE);
        cooperativeRepository.save(cooperative);
        auditService.record(
                actorUserId,
                cooperativeId,
                AuditableAction.COOPERATIVE_ONBOARDING_CHANGE,
                "Cooperative",
                cooperativeId,
                "{\"onboardingState\":\"" + previous + "\"}",
                "{\"onboardingState\":\"COMPLETE\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return CooperativeOnboardingState.COMPLETE;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
