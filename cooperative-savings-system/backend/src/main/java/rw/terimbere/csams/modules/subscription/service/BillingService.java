package rw.terimbere.csams.modules.subscription.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppPhone;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.dto.BillingCheckoutRequest;
import rw.terimbere.csams.modules.subscription.dto.BillingCheckoutResponse;
import rw.terimbere.csams.modules.subscription.dto.BillingPlanQuote;
import rw.terimbere.csams.modules.subscription.dto.BillingPlansResponse;
import rw.terimbere.csams.modules.subscription.dto.SubscriptionPaymentResponse;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationCommand;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationResult;
import rw.terimbere.csams.modules.subscription.payment.PaymentVerification;
import rw.terimbere.csams.modules.subscription.payment.ProviderErrorClass;
import rw.terimbere.csams.modules.subscription.payment.ProviderPaymentStatus;
import rw.terimbere.csams.modules.subscription.payment.SubscriptionPaymentProvider;
import rw.terimbere.csams.modules.subscription.payment.SubscriptionPaymentProviderRegistry;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveCardSubscriptionPaymentProvider;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveWebhookPayload;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveWebhookSignatures;
import rw.terimbere.csams.modules.subscription.payment.mtn.MtnMomoSubscriptionPaymentProvider;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.UnauthorizedException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;

@Service
@RequiredArgsConstructor
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    public static final String VERIFY_UNAVAILABLE_MESSAGE =
            "We couldn't verify your payment right now. Please try checking the status again.";
    public static final String PENDING_STATUS_MESSAGE = "Your payment is still being processed.";
    public static final String SUCCESS_STATUS_MESSAGE = "Payment successful. Your subscription is active.";
    public static final String FAILED_STATUS_MESSAGE = "Payment was not completed.";
    public static final String CANCELED_STATUS_MESSAGE = "Payment was canceled.";

    private final CooperativeAuthorizationService authorizationService;
    private final SubscriptionPricing pricing;
    private final SubscriptionPaymentRepository paymentRepository;
    private final SubscriptionPaymentProviderRegistry providerRegistry;
    private final SubscriptionPaymentAttemptService attemptService;
    private final SubscriptionActivationService activationService;
    private final SubscriptionProperties subscriptionProperties;
    private final UserRepository userRepository;
    private final CooperativeRepository cooperativeRepository;

    @Transactional(readOnly = true)
    public BillingPlansResponse getPlans(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        return catalog();
    }

    public BillingPlansResponse catalog() {
        SubscriptionPaymentProvider mtn = providerRegistry.find(SubscriptionPaymentChannel.MTN_MOMO);
        SubscriptionPaymentProvider card = providerRegistry.find(SubscriptionPaymentChannel.CARD);
        return BillingPlansResponse.builder()
                .currency(pricing.currency())
                .trialMonths(pricing.trialMonths())
                .mtnCheckoutAvailable(mtn != null && mtn.available())
                .cardCheckoutAvailable(card != null && card.available())
                .plans(List.of(toQuote(pricing.monthly()), toQuote(pricing.annual())))
                .build();
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionPaymentResponse> listPayments(UUID cooperativeId, Pageable pageable) {
        authorizationService.requireMembership(cooperativeId);
        return paymentRepository.findByCooperativeId(cooperativeId, pageable).map(this::toPayment);
    }

    /**
     * Starts provider checkout. Does not activate the subscription.
     * Amount is always taken from {@link SubscriptionPricing}; client amount fields are ignored.
     */
    public BillingCheckoutResponse checkout(UUID cooperativeId, BillingCheckoutRequest request) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        CooperativeOfficerRoles.requireBillingManager(principal);
        if (request == null || request.getBillingCycle() == null || request.getPaymentChannel() == null) {
            throw new ValidationException("Billing cycle and payment method are required");
        }
        pricing.quote(request.getBillingCycle());

        SubscriptionPaymentChannel channel = request.getPaymentChannel();
        SubscriptionPaymentProvider provider = providerRegistry.requireAvailable(channel);
        String msisdn = requireMtnMsisdn(request);
        CardCustomer customer = channel == SubscriptionPaymentChannel.CARD
                ? requireCardCustomer(cooperativeId, principal)
                : null;

        SubscriptionPayment payment = attemptService.createOrReusePending(
                cooperativeId, request.getBillingCycle(), channel, actorId(principal), msisdn);

        if (alreadyInitiated(payment, channel)) {
            return toCheckout(payment, SubscriptionPaymentAttemptService.pendingMessage(channel));
        }

        PaymentInitiationResult result = provider.initiate(new PaymentInitiationCommand(
                payment.getId(),
                cooperativeId,
                payment.getBillingCycle(),
                payment.getAmount(),
                payment.getCurrency(),
                msisdn,
                customer == null ? null : customer.email(),
                customer == null ? null : customer.name(),
                customer == null ? null : customer.phone()));
        if (!result.accepted()
                || (channel == SubscriptionPaymentChannel.CARD && !StringUtils.hasText(result.checkoutUrl()))) {
            payment = attemptService.markFailed(payment.getId(), actorId(principal));
            return toCheckout(payment, SubscriptionPaymentAttemptService.FAILED_MESSAGE);
        }
        payment = attemptService.markInitiated(
                payment.getId(),
                result.externalReference(),
                result.checkoutUrl(),
                SubscriptionPaymentAttemptService.providerName(channel));
        return toCheckout(payment, SubscriptionPaymentAttemptService.pendingMessage(channel));
    }

    public SubscriptionPaymentResponse getPayment(UUID cooperativeId, UUID paymentId) {
        authorizationService.requireMembership(cooperativeId);
        SubscriptionPayment payment = paymentRepository
                .findByIdAndCooperativeId(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPayment", paymentId));
        SyncOutcome outcome =
                synchronizeWithProvider(payment, actorId(authorizationService.currentPrincipal()), null);
        return toPayment(outcome);
    }

    /**
     * MTN Collection callback. Payload status is ignored; provider status is re-queried.
     * Temporary provider outages leave the payment PENDING.
     */
    public void handleMtnCallback(String referenceId) {
        if (!StringUtils.hasText(referenceId)) {
            return;
        }
        SubscriptionPayment payment = paymentRepository
                .findByProviderAndExternalReference(
                        MtnMomoSubscriptionPaymentProvider.PROVIDER_NAME, referenceId.trim())
                .or(() -> parseUuid(referenceId).flatMap(paymentRepository::findById))
                .orElse(null);
        if (payment == null) {
            log.info("MTN MoMo callback for unknown payment reference");
            return;
        }
        if (payment.getPaymentChannel() != SubscriptionPaymentChannel.MTN_MOMO) {
            log.warn("MTN MoMo callback ignored for non-MTN payment {}", payment.getId());
            return;
        }
        synchronizeWithProvider(payment, null, null);
    }

    /**
     * Flutterwave webhook. Hash is authenticated first; payload amounts/status are never trusted.
     * Invalid signatures never reach provider verification or activation.
     */
    public void handleFlutterwaveWebhook(String verifHash, FlutterwaveWebhookPayload body) {
        String secretHash = subscriptionProperties.getPayment().getFlutterwave().getSecretHash();
        if (!FlutterwaveWebhookSignatures.matches(secretHash, verifHash)) {
            throw new UnauthorizedException("Invalid webhook signature");
        }
        String txRef = body == null || body.data() == null ? null : body.data().txRef();
        String transactionId = body == null || body.data() == null || body.data().id() == null
                ? null
                : String.valueOf(body.data().id());
        SubscriptionPayment payment = findFlutterwavePayment(txRef);
        if (payment == null) {
            log.info("Flutterwave webhook for unknown payment reference");
            return;
        }
        if (payment.getPaymentChannel() != SubscriptionPaymentChannel.CARD) {
            log.warn("Flutterwave webhook ignored for non-CARD payment {}", payment.getId());
            return;
        }
        synchronizeWithProvider(payment, null, transactionId);
    }

    SubscriptionPayment synchronizeWithProvider(SubscriptionPayment payment, UUID actorUserId) {
        return synchronizeWithProvider(payment, actorUserId, null).payment();
    }

    SyncOutcome synchronizeWithProvider(
            SubscriptionPayment payment, UUID actorUserId, String providerTransactionId) {
        if (payment.getStatus() == SubscriptionPaymentStatus.SUCCESS
                || payment.getStatus() == SubscriptionPaymentStatus.CANCELED) {
            return SyncOutcome.of(payment);
        }
        SubscriptionPaymentProvider provider = providerRegistry.find(payment.getPaymentChannel());
        if (provider == null || !provider.available()) {
            return SyncOutcome.temporary(payment);
        }
        try {
            if (payment.getPaymentChannel() == SubscriptionPaymentChannel.CARD) {
                return synchronizeCard(payment, actorUserId, provider, providerTransactionId);
            }
            if (payment.getPaymentChannel() != SubscriptionPaymentChannel.MTN_MOMO) {
                return SyncOutcome.of(payment);
            }
            return synchronizeMtn(payment, actorUserId, provider);
        } catch (RuntimeException ex) {
            log.warn(
                    "Provider verification temporary failure for payment {}: {}",
                    payment.getId(),
                    ex.getClass().getSimpleName());
            return SyncOutcome.temporary(payment);
        }
    }

    private SyncOutcome synchronizeMtn(
            SubscriptionPayment payment, UUID actorUserId, SubscriptionPaymentProvider provider) {
        String reference = StringUtils.hasText(payment.getExternalReference())
                ? payment.getExternalReference()
                : payment.getId().toString();
        ProviderPaymentStatus providerStatus = provider.verify(reference);
        return switch (ProviderErrorClass.of(providerStatus)) {
            case CONFIRMED_SUCCESS -> {
                activationService.applySuccessfulPayment(payment.getId(), actorUserId);
                yield SyncOutcome.of(paymentRepository.findById(payment.getId()).orElse(payment));
            }
            case CONFIRMED_FAILURE -> SyncOutcome.of(
                    providerStatus == ProviderPaymentStatus.CANCELED
                            ? attemptService.markCanceled(payment.getId(), actorUserId)
                            : attemptService.markFailed(payment.getId(), actorUserId));
            case TEMPORARY -> SyncOutcome.temporary(payment);
            case IN_FLIGHT -> SyncOutcome.of(payment);
        };
    }

    private SyncOutcome synchronizeCard(
            SubscriptionPayment payment,
            UUID actorUserId,
            SubscriptionPaymentProvider provider,
            String providerTransactionId) {
        PaymentVerification verification = provider.inspect(payment.getExternalReference(), providerTransactionId);
        if (verification.verifiedSuccess(payment)) {
            activationService.applySuccessfulPayment(payment.getId(), actorUserId);
            return SyncOutcome.of(paymentRepository.findById(payment.getId()).orElse(payment));
        }
        if (verification.status() == ProviderPaymentStatus.SUCCESS) {
            log.warn(
                    "Flutterwave verification rejected for payment {}: {}",
                    payment.getId(),
                    verification.mismatchReason(payment) == null
                            ? "incomplete provider payload"
                            : verification.mismatchReason(payment));
            return SyncOutcome.of(payment);
        }
        return switch (ProviderErrorClass.of(verification.status())) {
            case CONFIRMED_FAILURE -> SyncOutcome.of(
                    verification.status() == ProviderPaymentStatus.CANCELED
                            ? attemptService.markCanceled(payment.getId(), actorUserId)
                            : attemptService.markFailed(payment.getId(), actorUserId));
            case TEMPORARY -> SyncOutcome.temporary(payment);
            case CONFIRMED_SUCCESS, IN_FLIGHT -> SyncOutcome.of(payment);
        };
    }

    private SubscriptionPayment findFlutterwavePayment(String txRef) {
        if (!StringUtils.hasText(txRef)) {
            return null;
        }
        String trimmed = txRef.trim();
        Optional<SubscriptionPayment> byRef = paymentRepository.findByProviderAndExternalReference(
                FlutterwaveCardSubscriptionPaymentProvider.PROVIDER_NAME, trimmed);
        if (byRef.isPresent()) {
            return byRef.get();
        }
        if (!trimmed.startsWith(FlutterwaveCardSubscriptionPaymentProvider.TX_REF_PREFIX)) {
            return null;
        }
        return parseUuid(trimmed.substring(FlutterwaveCardSubscriptionPaymentProvider.TX_REF_PREFIX.length()))
                .flatMap(paymentRepository::findById)
                .filter(payment -> payment.getPaymentChannel() == SubscriptionPaymentChannel.CARD)
                .orElse(null);
    }

    private static boolean alreadyInitiated(SubscriptionPayment payment, SubscriptionPaymentChannel channel) {
        if (payment.getStatus() != SubscriptionPaymentStatus.PENDING
                || !StringUtils.hasText(payment.getExternalReference())) {
            return false;
        }
        if (channel == SubscriptionPaymentChannel.CARD) {
            return StringUtils.hasText(payment.getCheckoutUrl());
        }
        return true;
    }

    private CardCustomer requireCardCustomer(UUID cooperativeId, UserPrincipal principal) {
        User user = principal == null ? null : userRepository.findById(principal.getId()).orElse(null);
        Cooperative cooperative = cooperativeRepository.findById(cooperativeId).orElse(null);
        String email = firstText(
                user == null ? null : user.getEmail(), cooperative == null ? null : cooperative.getContactEmail());
        if (!StringUtils.hasText(email)) {
            throw new ValidationException("A billing email is required for card checkout");
        }
        String name = user == null
                ? null
                : firstText(joinName(user.getFirstName(), user.getLastName()), user.getUsername());
        String phone = firstText(
                user == null ? null : user.getPhone(), cooperative == null ? null : cooperative.getContactPhone());
        return new CardCustomer(email.trim(), name, phone);
    }

    private static String joinName(String first, String last) {
        String combined = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return StringUtils.hasText(combined) ? combined : null;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String requireMtnMsisdn(BillingCheckoutRequest request) {
        if (request.getPaymentChannel() != SubscriptionPaymentChannel.MTN_MOMO) {
            return null;
        }
        if (!StringUtils.hasText(request.getPayerPhoneNumber())
                || !CooperativeFieldRules.isValidRwandanPhone(request.getPayerPhoneNumber())) {
            throw new ValidationException("A valid Rwandan MTN Mobile Money phone number is required");
        }
        String msisdn = WhatsAppPhone.toRecipient(request.getPayerPhoneNumber());
        if (!StringUtils.hasText(msisdn)) {
            throw new ValidationException("A valid Rwandan MTN Mobile Money phone number is required");
        }
        return msisdn;
    }

    private static UUID actorId(UserPrincipal principal) {
        return principal == null ? null : principal.getId();
    }

    private static java.util.Optional<UUID> parseUuid(String value) {
        try {
            return java.util.Optional.of(UUID.fromString(value.trim()));
        } catch (RuntimeException ex) {
            return java.util.Optional.empty();
        }
    }

    private BillingPlanQuote toQuote(SubscriptionPricing.PlanQuote quote) {
        return BillingPlanQuote.builder()
                .billingCycle(quote.billingCycle())
                .listPrice(quote.listPrice())
                .amount(quote.charge())
                .discountPercent(quote.discountPercent())
                .savings(MoneyUtils.subtract(quote.listPrice(), quote.charge()))
                .periodMonths(quote.periodMonths())
                .build();
    }

    private BillingCheckoutResponse toCheckout(SubscriptionPayment payment, String message) {
        return BillingCheckoutResponse.builder()
                .paymentId(payment.getId())
                .status(payment.getStatus())
                .billingCycle(payment.getBillingCycle())
                .paymentChannel(payment.getPaymentChannel())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .checkoutUrl(payment.getCheckoutUrl())
                .message(message)
                .build();
    }

    private SubscriptionPaymentResponse toPayment(SubscriptionPayment payment) {
        return toPayment(SyncOutcome.of(payment));
    }

    private SubscriptionPaymentResponse toPayment(SyncOutcome outcome) {
        SubscriptionPayment payment = outcome.payment();
        return SubscriptionPaymentResponse.builder()
                .id(payment.getId())
                .billingCycle(payment.getBillingCycle())
                .paymentChannel(payment.getPaymentChannel())
                .status(payment.getStatus())
                .currency(payment.getCurrency())
                .amount(payment.getAmount())
                .provider(payment.getProvider())
                .externalReference(payment.getExternalReference())
                .initiatedAt(payment.getInitiatedAt())
                .paidAt(payment.getPaidAt())
                .failedAt(payment.getFailedAt())
                .message(customerMessage(payment.getStatus(), outcome.verificationUnavailable()))
                .verificationUnavailable(outcome.verificationUnavailable())
                .build();
    }

    static String customerMessage(SubscriptionPaymentStatus status, boolean verificationUnavailable) {
        if (verificationUnavailable && status == SubscriptionPaymentStatus.PENDING) {
            return VERIFY_UNAVAILABLE_MESSAGE;
        }
        if (status == null) {
            return PENDING_STATUS_MESSAGE;
        }
        return switch (status) {
            case PENDING -> PENDING_STATUS_MESSAGE;
            case SUCCESS -> SUCCESS_STATUS_MESSAGE;
            case FAILED -> FAILED_STATUS_MESSAGE;
            case CANCELED -> CANCELED_STATUS_MESSAGE;
        };
    }

    private record CardCustomer(String email, String name, String phone) {}

    record SyncOutcome(SubscriptionPayment payment, boolean verificationUnavailable) {
        static SyncOutcome of(SubscriptionPayment payment) {
            return new SyncOutcome(payment, false);
        }

        static SyncOutcome temporary(SubscriptionPayment payment) {
            return new SyncOutcome(payment, true);
        }
    }
}
