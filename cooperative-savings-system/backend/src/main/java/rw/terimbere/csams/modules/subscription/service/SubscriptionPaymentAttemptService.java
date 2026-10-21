package rw.terimbere.csams.modules.subscription.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveCardSubscriptionPaymentProvider;
import rw.terimbere.csams.modules.subscription.payment.mtn.MtnMomoSubscriptionPaymentProvider;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Transactional writes for checkout attempts. HTTP calls to providers stay outside this bean.
 *
 * <p>Pending reuse: same cooperative + billing cycle + channel + payer identity may be reused
 * only inside {@code app.subscription.payment.pending-reuse-minutes} (default 15). That window
 * absorbs double-clicks without blocking a later retry. MTN identity is the normalized MSISDN;
 * CARD has no extra payer identity. Stale or different-phone PENDING rows are canceled first.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionPaymentAttemptService {

    static final String PENDING_MESSAGE = "Payment request sent. Approve the payment on your phone.";
    static final String CARD_PENDING_MESSAGE = "Continue to secure card payment.";
    static final String FAILED_MESSAGE = "Payment was not completed.";

    private final CooperativeSubscriptionRepository subscriptionRepository;
    private final SubscriptionPaymentRepository paymentRepository;
    private final SubscriptionPricing pricing;
    private final SubscriptionProperties subscriptionProperties;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public SubscriptionPayment createOrReusePending(
            UUID cooperativeId,
            SubscriptionBillingCycle billingCycle,
            SubscriptionPaymentChannel paymentChannel,
            UUID actorUserId,
            String payerMsisdn) {
        CooperativeSubscription subscription = subscriptionRepository
                .findByCooperativeIdForUpdate(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("CooperativeSubscription", cooperativeId));

        Instant now = clock.instant();
        SubscriptionPayment existing = paymentRepository
                .findFirstByCooperativeIdAndBillingCycleAndPaymentChannelAndStatusOrderByInitiatedAtDesc(
                        cooperativeId, billingCycle, paymentChannel, SubscriptionPaymentStatus.PENDING)
                .orElse(null);
        if (existing != null) {
            if (canReuse(existing, paymentChannel, payerMsisdn, now)) {
                return existing;
            }
            markTerminal(existing.getId(), SubscriptionPaymentStatus.CANCELED, actorUserId);
        }

        var quote = pricing.quote(billingCycle);
        String provider = providerName(paymentChannel);
        SubscriptionPayment payment = SubscriptionPayment.builder()
                .subscriptionId(subscription.getId())
                .cooperativeId(cooperativeId)
                .billingCycle(billingCycle)
                .paymentChannel(paymentChannel)
                .status(SubscriptionPaymentStatus.PENDING)
                .currency(pricing.currency())
                .amount(quote.charge())
                .provider(provider)
                .payerMsisdn(paymentChannel == SubscriptionPaymentChannel.MTN_MOMO ? payerMsisdn : null)
                .initiatedAt(now)
                .build();
        SubscriptionPayment saved = paymentRepository.saveAndFlush(payment);
        saved.setIdempotencyKey(idempotencyKey(paymentChannel, saved.getId()));
        saved = paymentRepository.saveAndFlush(saved);

        auditService.record(
                actorUserId,
                cooperativeId,
                AuditableAction.SUBSCRIPTION_PAYMENT_INITIATED,
                "SubscriptionPayment",
                saved.getId(),
                null,
                json(initiatedSnapshot(saved)),
                null,
                null);
        return saved;
    }

    @Transactional
    public SubscriptionPayment markInitiated(
            UUID paymentId, String externalReference, String checkoutUrl, String provider) {
        SubscriptionPayment payment = paymentRepository
                .findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPayment", paymentId));
        if (payment.getStatus() != SubscriptionPaymentStatus.PENDING) {
            return payment;
        }
        if (StringUtils.hasText(externalReference)) {
            payment.setExternalReference(externalReference);
        } else if (!StringUtils.hasText(payment.getExternalReference())) {
            payment.setExternalReference(payment.getId().toString());
        }
        if (StringUtils.hasText(provider)) {
            payment.setProvider(provider);
        }
        if (StringUtils.hasText(checkoutUrl)) {
            payment.setCheckoutUrl(checkoutUrl);
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public SubscriptionPayment markInitiated(UUID paymentId, String externalReference) {
        return markInitiated(paymentId, externalReference, null, MtnMomoSubscriptionPaymentProvider.PROVIDER_NAME);
    }

    @Transactional
    public SubscriptionPayment markFailed(UUID paymentId, UUID actorUserId) {
        return markTerminal(paymentId, SubscriptionPaymentStatus.FAILED, actorUserId);
    }

    @Transactional
    public SubscriptionPayment markCanceled(UUID paymentId, UUID actorUserId) {
        return markTerminal(paymentId, SubscriptionPaymentStatus.CANCELED, actorUserId);
    }

    @Transactional
    public SubscriptionPayment markTerminal(
            UUID paymentId, SubscriptionPaymentStatus terminal, UUID actorUserId) {
        SubscriptionPayment payment = paymentRepository
                .findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPayment", paymentId));
        if (payment.getStatus() == SubscriptionPaymentStatus.SUCCESS
                || payment.getStatus() == SubscriptionPaymentStatus.CANCELED
                || payment.getStatus() == SubscriptionPaymentStatus.FAILED) {
            return payment;
        }
        Instant now = clock.instant();
        payment.setStatus(terminal);
        payment.setFailedAt(now);
        SubscriptionPayment saved = paymentRepository.save(payment);
        auditService.record(
                actorUserId,
                saved.getCooperativeId(),
                AuditableAction.SUBSCRIPTION_PAYMENT_FAILED,
                "SubscriptionPayment",
                saved.getId(),
                null,
                json(initiatedSnapshot(saved)),
                null,
                null);
        return saved;
    }

    boolean canReuse(
            SubscriptionPayment existing,
            SubscriptionPaymentChannel paymentChannel,
            String payerMsisdn,
            Instant now) {
        if (existing == null || existing.getStatus() != SubscriptionPaymentStatus.PENDING) {
            return false;
        }
        if (existing.getInitiatedAt() == null || now == null) {
            return false;
        }
        Duration window = pendingReuseWindow();
        if (existing.getInitiatedAt().isBefore(now.minus(window))) {
            return false;
        }
        if (paymentChannel == SubscriptionPaymentChannel.MTN_MOMO) {
            return StringUtils.hasText(payerMsisdn) && Objects.equals(existing.getPayerMsisdn(), payerMsisdn);
        }
        return paymentChannel == SubscriptionPaymentChannel.CARD;
    }

    Duration pendingReuseWindow() {
        int minutes = subscriptionProperties.getPayment().getPendingReuseMinutes();
        return Duration.ofMinutes(Math.max(1, minutes));
    }

    static String providerName(SubscriptionPaymentChannel channel) {
        if (channel == SubscriptionPaymentChannel.CARD) {
            return FlutterwaveCardSubscriptionPaymentProvider.PROVIDER_NAME;
        }
        return MtnMomoSubscriptionPaymentProvider.PROVIDER_NAME;
    }

    static String idempotencyKey(SubscriptionPaymentChannel channel, UUID paymentId) {
        if (channel == SubscriptionPaymentChannel.CARD) {
            return "flutterwave:" + paymentId;
        }
        return "mtn-momo:" + paymentId;
    }

    static String pendingMessage(SubscriptionPaymentChannel channel) {
        return channel == SubscriptionPaymentChannel.CARD ? CARD_PENDING_MESSAGE : PENDING_MESSAGE;
    }

    private Map<String, Object> initiatedSnapshot(SubscriptionPayment payment) {
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
}
