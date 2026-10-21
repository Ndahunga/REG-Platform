package rw.terimbere.csams.modules.subscription.job;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPaymentReconciliationService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.subscription.payment.reconciliation.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class SubscriptionPaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPaymentReconciliationJob.class);

    private final SubscriptionPaymentReconciliationService reconciliationService;

    @Scheduled(
            fixedDelayString = "${app.subscription.payment.reconciliation.fixed-delay-ms:300000}",
            initialDelayString = "${app.subscription.payment.reconciliation.initial-delay-ms:60000}")
    public void reconcilePendingPayments() {
        log.debug("Running subscription payment reconciliation sweep");
        reconciliationService.reconcileEligiblePendingPayments();
    }
}
