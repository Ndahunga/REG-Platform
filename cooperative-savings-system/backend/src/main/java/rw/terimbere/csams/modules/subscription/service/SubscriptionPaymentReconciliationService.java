package rw.terimbere.csams.modules.subscription.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;

/**
 * Re-verifies PENDING subscription payments when callbacks/webhooks were missed.
 *
 * <p>Strategy: a low-frequency scheduled sweep (default every 5 minutes when enabled)
 * selects PENDING rows that are old enough for providers to have settled, but younger
 * than {@code pending-abandon-hours}. Each row is locked and verified through the same
 * {@link BillingService#synchronizeWithProvider} path used by callbacks, webhooks, and
 * status polling — so activation remains a single idempotent path.
 *
 * <p>Not a busy loop. Multiple app instances may run the job; pessimistic locks +
 * activation idempotency prevent double entitlement extension.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionPaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPaymentReconciliationService.class);

    private final SubscriptionPaymentRepository paymentRepository;
    private final BillingService billingService;
    private final SubscriptionProperties subscriptionProperties;
    private final Clock clock;

    @Transactional
    public int reconcileEligiblePendingPayments() {
        SubscriptionProperties.Payment.Reconciliation cfg =
                subscriptionProperties.getPayment().getReconciliation();
        Instant now = clock.instant();
        Instant maxInitiated = now.minus(Duration.ofMinutes(Math.max(0, cfg.getMinAgeMinutes())));
        Instant minInitiated =
                now.minus(Duration.ofHours(Math.max(1, subscriptionProperties.getPayment().getPendingAbandonHours())));
        int limit = Math.max(1, cfg.getBatchSize());

        List<UUID> ids = paymentRepository.findPendingIdsForReconciliation(
                SubscriptionPaymentStatus.PENDING,
                minInitiated,
                maxInitiated,
                org.springframework.data.domain.PageRequest.of(0, limit));
        if (ids.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (UUID id : ids) {
            try {
                reconcileOne(id);
                processed++;
            } catch (RuntimeException ex) {
                log.warn(
                        "Subscription payment reconciliation failed for {}: {}",
                        id,
                        ex.getClass().getSimpleName());
            }
        }
        if (processed > 0) {
            log.info("Reconciled {} PENDING subscription payment(s)", processed);
        }
        return processed;
    }

    /**
     * Verifies a single PENDING payment. Temporary provider errors leave status PENDING.
     */
    @Transactional
    public SubscriptionPayment reconcileOne(UUID paymentId) {
        SubscriptionPayment locked = paymentRepository
                .findByIdForUpdate(paymentId)
                .orElse(null);
        if (locked == null || locked.getStatus() != SubscriptionPaymentStatus.PENDING) {
            return locked;
        }
        return billingService.synchronizeWithProvider(locked, null, null).payment();
    }
}
