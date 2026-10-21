package rw.terimbere.csams.modules.subscription.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.subscription.dto.CooperativeSubscriptionResponse;
import rw.terimbere.csams.modules.subscription.dto.SubscriptionEntitlement;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionInitialization;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final CooperativeRepository cooperativeRepository;
    private final CooperativeSubscriptionRepository subscriptionRepository;
    private final SubscriptionPricing pricing;
    private final SubscriptionEntitlementService entitlementService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates the single current subscription for a cooperative.
     *
     * <p>Idempotent for the same initialization mode: a second call returns the existing
     * row and does not restart or extend a trial. A different mode is rejected.
     */
    @Transactional
    public CooperativeSubscriptionResponse initializeForCooperative(
            UUID cooperativeId, SubscriptionInitialization initialization, UUID actorUserId) {
        if (initialization == null) {
            throw new ValidationException("Subscription initialization is required");
        }
        if (initialization == SubscriptionInitialization.ACTIVE_MANUAL) {
            throw new ValidationException("ACTIVE_MANUAL subscription initialization is not supported yet");
        }
        requireCooperative(cooperativeId);

        return subscriptionRepository
                .findByCooperativeId(cooperativeId)
                .map(existing -> reuseExisting(existing, initialization))
                .orElseGet(() -> createNew(cooperativeId, initialization, actorUserId));
    }

    @Transactional(readOnly = true)
    public Optional<CooperativeSubscriptionResponse> findByCooperativeId(UUID cooperativeId) {
        return subscriptionRepository.findByCooperativeId(cooperativeId).map(this::toResponse);
    }

    private CooperativeSubscriptionResponse createNew(
            UUID cooperativeId, SubscriptionInitialization initialization, UUID actorUserId) {
        Instant now = clock.instant();
        CooperativeSubscription subscription = switch (initialization) {
            case START_TRIAL -> trialSubscription(cooperativeId, now);
            case NONE -> inactiveSubscription(cooperativeId);
            case ACTIVE_MANUAL -> throw new ValidationException(
                    "ACTIVE_MANUAL subscription initialization is not supported yet");
        };

        CooperativeSubscription saved;
        try {
            saved = subscriptionRepository.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException ex) {
            CooperativeSubscription raced = subscriptionRepository
                    .findByCooperativeId(cooperativeId)
                    .orElseThrow(() -> ex);
            return reuseExisting(raced, initialization);
        }

        auditInitialization(saved, initialization, actorUserId);
        return toResponse(saved);
    }

    private CooperativeSubscriptionResponse reuseExisting(
            CooperativeSubscription existing, SubscriptionInitialization requested) {
        SubscriptionInitialization current = inferInitialization(existing);
        if (current == requested) {
            return toResponse(existing);
        }
        throw new ConflictException(
                "Subscription already initialized as " + current + " and cannot be changed via initialize");
    }

    private SubscriptionInitialization inferInitialization(CooperativeSubscription existing) {
        if (existing.getStatus() == SubscriptionStatus.NONE) {
            return SubscriptionInitialization.NONE;
        }
        if (existing.getStatus() == SubscriptionStatus.TRIAL) {
            return SubscriptionInitialization.START_TRIAL;
        }
        throw new ConflictException("Subscription already exists for this cooperative");
    }

    private CooperativeSubscription trialSubscription(UUID cooperativeId, Instant startedAt) {
        Instant endsAt = SubscriptionCalendar.plusCalendarMonths(startedAt, pricing.trialMonths(), pricing.zoneId());
        return CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(SubscriptionStatus.TRIAL)
                .billingCycle(null)
                .trialStartedAt(startedAt)
                .trialEndsAt(endsAt)
                .build();
    }

    private CooperativeSubscription inactiveSubscription(UUID cooperativeId) {
        return CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(SubscriptionStatus.NONE)
                .billingCycle(null)
                .build();
    }

    private void requireCooperative(UUID cooperativeId) {
        cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private void auditInitialization(
            CooperativeSubscription saved, SubscriptionInitialization initialization, UUID actorUserId) {
        String payload = auditPayload(saved, initialization);
        auditService.record(
                actorUserId,
                saved.getCooperativeId(),
                AuditableAction.SUBSCRIPTION_INIT,
                "CooperativeSubscription",
                saved.getId(),
                null,
                payload,
                null,
                null);
        if (initialization == SubscriptionInitialization.START_TRIAL) {
            auditService.record(
                    actorUserId,
                    saved.getCooperativeId(),
                    AuditableAction.SUBSCRIPTION_TRIAL_START,
                    "CooperativeSubscription",
                    saved.getId(),
                    null,
                    payload,
                    null,
                    null);
        }
    }

    private String auditPayload(CooperativeSubscription saved, SubscriptionInitialization initialization) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("cooperativeId", saved.getCooperativeId());
        values.put("initialization", initialization.name());
        values.put("status", saved.getStatus() == null ? null : saved.getStatus().name());
        values.put("trialStartedAt", saved.getTrialStartedAt());
        values.put("trialEndsAt", saved.getTrialEndsAt());
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize subscription audit payload", e);
        }
    }

    private CooperativeSubscriptionResponse toResponse(CooperativeSubscription saved) {
        SubscriptionEntitlement entitlement = entitlementService.evaluate(saved);
        return CooperativeSubscriptionResponse.builder()
                .id(saved.getId())
                .cooperativeId(saved.getCooperativeId())
                .status(saved.getStatus())
                .storedStatus(entitlement.storedStatus())
                .effectiveStatus(entitlement.effectiveStatus())
                .usable(entitlement.usable())
                .writeAllowed(entitlement.writeAllowed())
                .billingCycle(saved.getBillingCycle())
                .trialStartedAt(saved.getTrialStartedAt())
                .trialEndsAt(saved.getTrialEndsAt())
                .currentPeriodStartedAt(saved.getCurrentPeriodStartedAt())
                .currentPeriodEndsAt(saved.getCurrentPeriodEndsAt())
                .pastDueUntil(saved.getPastDueUntil())
                .canceledAt(saved.getCanceledAt())
                .daysRemaining(entitlement.daysRemaining())
                .createdAt(saved.getCreatedAt())
                .updatedAt(saved.getUpdatedAt())
                .build();
    }
}
