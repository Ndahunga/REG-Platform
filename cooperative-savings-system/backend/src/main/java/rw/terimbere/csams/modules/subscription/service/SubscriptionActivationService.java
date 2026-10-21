package rw.terimbere.csams.modules.subscription.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Sole writer of paid subscription dates after a verified provider SUCCESS.
 *
 * <p>Renewal base (paid period start):
 * <ul>
 *   <li>Open TRIAL ({@code now < trialEndsAt}): paid period starts at {@code trialEndsAt}
 *       so remaining free trial is not shortened.</li>
 *   <li>Otherwise if {@code currentPeriodEndsAt} is still in the future: extend from that
 *       instant (early renewal while ACTIVE, or remaining paid access while CANCELED).</li>
 *   <li>Otherwise start from payment-success time (NONE, EXPIRED, ended trial,
 *       PAST_DUE after the paid period already ended). Remaining PAST_DUE grace is
 *       replaced by a new ACTIVE window that is longer than leftover grace, so
 *       already-earned access is not shortened and a second month is not granted.</li>
 * </ul>
 *
 * <p>MONTHLY adds 1 calendar month; ANNUAL adds 12 calendar months in Africa/Kigali.
 * Calling this twice for the same SUCCESS payment is a no-op.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionActivationService {

    private final SubscriptionPaymentRepository paymentRepository;
    private final CooperativeSubscriptionRepository subscriptionRepository;
    private final SubscriptionPricing pricing;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public CooperativeSubscription applySuccessfulPayment(UUID paymentId, UUID actorUserId) {
        SubscriptionPayment payment = paymentRepository
                .findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPayment", paymentId));
        return applySuccessfulPayment(payment, actorUserId);
    }

    @Transactional
    public CooperativeSubscription applySuccessfulPayment(SubscriptionPayment lockedPayment, UUID actorUserId) {
        if (lockedPayment == null || lockedPayment.getId() == null) {
            throw new ValidationException("Payment is required");
        }
        if (lockedPayment.getStatus() == SubscriptionPaymentStatus.SUCCESS) {
            return subscriptionRepository
                    .findByCooperativeId(lockedPayment.getCooperativeId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "CooperativeSubscription", lockedPayment.getCooperativeId()));
        }
        if (lockedPayment.getStatus() == SubscriptionPaymentStatus.CANCELED) {
            return subscriptionRepository
                    .findByCooperativeId(lockedPayment.getCooperativeId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "CooperativeSubscription", lockedPayment.getCooperativeId()));
        }

        Instant now = clock.instant();
        CooperativeSubscription subscription = subscriptionRepository
                .findByCooperativeIdForUpdate(lockedPayment.getCooperativeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CooperativeSubscription", lockedPayment.getCooperativeId()));

        Map<String, Object> previous = subscriptionSnapshot(subscription);
        Renewal renewal = computeRenewal(subscription, lockedPayment.getBillingCycle(), now);

        boolean renewed = renewal.kind() == RenewalKind.RENEWED;
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setBillingCycle(lockedPayment.getBillingCycle());
        subscription.setCurrentPeriodStartedAt(renewal.periodStartedAt());
        subscription.setCurrentPeriodEndsAt(renewal.periodEndsAt());
        subscription.setPastDueUntil(null);
        subscription.setCanceledAt(null);
        subscriptionRepository.save(subscription);

        lockedPayment.setStatus(SubscriptionPaymentStatus.SUCCESS);
        lockedPayment.setPaidAt(now);
        lockedPayment.setFailedAt(null);
        paymentRepository.save(lockedPayment);

        Map<String, Object> next = paymentSnapshot(lockedPayment);
        next.putAll(subscriptionSnapshot(subscription));
        auditService.record(
                actorUserId,
                lockedPayment.getCooperativeId(),
                AuditableAction.SUBSCRIPTION_PAYMENT_SUCCESS,
                "SubscriptionPayment",
                lockedPayment.getId(),
                json(previous),
                json(next),
                null,
                null);
        auditService.record(
                actorUserId,
                lockedPayment.getCooperativeId(),
                renewed ? AuditableAction.SUBSCRIPTION_RENEWED : AuditableAction.SUBSCRIPTION_ACTIVATED,
                "CooperativeSubscription",
                subscription.getId(),
                json(previous),
                json(subscriptionSnapshot(subscription)),
                null,
                null);
        return subscription;
    }

    Renewal computeRenewal(CooperativeSubscription subscription, SubscriptionBillingCycle cycle, Instant now) {
        int months = cycle == SubscriptionBillingCycle.ANNUAL ? 12 : 1;
        Instant base;
        Instant periodStartedAt;
        RenewalKind kind;
        if (openTrial(subscription, now)) {
            base = subscription.getTrialEndsAt();
            periodStartedAt = base;
            kind = RenewalKind.ACTIVATED;
        } else if (futurePeriodEnd(subscription, now)) {
            base = subscription.getCurrentPeriodEndsAt();
            Instant existingStart = subscription.getCurrentPeriodStartedAt();
            periodStartedAt = existingStart != null ? existingStart : now;
            kind = RenewalKind.RENEWED;
        } else {
            base = now;
            periodStartedAt = now;
            kind = RenewalKind.ACTIVATED;
        }
        Instant periodEndsAt = SubscriptionCalendar.plusCalendarMonths(base, months, pricing.zoneId());
        return new Renewal(kind, periodStartedAt, periodEndsAt);
    }

    static boolean openTrial(CooperativeSubscription subscription, Instant now) {
        return subscription != null
                && subscription.getStatus() == SubscriptionStatus.TRIAL
                && subscription.getTrialEndsAt() != null
                && now != null
                && now.isBefore(subscription.getTrialEndsAt());
    }

    static boolean futurePeriodEnd(CooperativeSubscription subscription, Instant now) {
        return subscription != null
                && subscription.getCurrentPeriodEndsAt() != null
                && now != null
                && subscription.getCurrentPeriodEndsAt().isAfter(now);
    }

    private Map<String, Object> subscriptionSnapshot(CooperativeSubscription subscription) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("cooperativeId", subscription.getCooperativeId());
        values.put("status", subscription.getStatus() == null ? null : subscription.getStatus().name());
        values.put(
                "billingCycle",
                subscription.getBillingCycle() == null ? null : subscription.getBillingCycle().name());
        values.put("trialStartedAt", subscription.getTrialStartedAt());
        values.put("trialEndsAt", subscription.getTrialEndsAt());
        values.put("currentPeriodStartedAt", subscription.getCurrentPeriodStartedAt());
        values.put("currentPeriodEndsAt", subscription.getCurrentPeriodEndsAt());
        values.put("pastDueUntil", subscription.getPastDueUntil());
        values.put("canceledAt", subscription.getCanceledAt());
        return values;
    }

    private Map<String, Object> paymentSnapshot(SubscriptionPayment payment) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paymentId", payment.getId());
        values.put("cooperativeId", payment.getCooperativeId());
        values.put("billingCycle", payment.getBillingCycle() == null ? null : payment.getBillingCycle().name());
        values.put(
                "paymentChannel",
                payment.getPaymentChannel() == null ? null : payment.getPaymentChannel().name());
        values.put("amount", payment.getAmount());
        values.put("currency", payment.getCurrency());
        values.put("status", payment.getStatus() == null ? null : payment.getStatus().name());
        values.put("provider", payment.getProvider());
        values.put("externalReference", payment.getExternalReference());
        return values;
    }

    private String json(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize subscription payment audit payload", e);
        }
    }

    enum RenewalKind {
        ACTIVATED,
        RENEWED
    }

    record Renewal(RenewalKind kind, Instant periodStartedAt, Instant periodEndsAt) {}
}
