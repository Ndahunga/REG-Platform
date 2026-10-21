package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.dto.BillingCheckoutRequest;
import rw.terimbere.csams.modules.subscription.dto.BillingCheckoutResponse;
import rw.terimbere.csams.modules.subscription.dto.BillingPlanQuote;
import rw.terimbere.csams.modules.subscription.dto.BillingPlansResponse;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationResult;
import rw.terimbere.csams.modules.subscription.payment.SubscriptionPaymentProvider;
import rw.terimbere.csams.modules.subscription.payment.SubscriptionPaymentProviderRegistry;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.modules.subscription.service.BillingService;
import rw.terimbere.csams.modules.subscription.service.SubscriptionActivationService;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPaymentAttemptService;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.PaymentIntegrationUnavailableException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    private CooperativeAuthorizationService authorizationService;

    @Mock
    private SubscriptionPaymentRepository paymentRepository;

    @Mock
    private SubscriptionPaymentProviderRegistry providerRegistry;

    @Mock
    private SubscriptionPaymentAttemptService attemptService;

    @Mock
    private SubscriptionActivationService activationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CooperativeRepository cooperativeRepository;

    @Mock
    private SubscriptionPaymentProvider mtnProvider;

    @Mock
    private SubscriptionPaymentProvider cardProvider;

    private BillingService billingService;
    private SubscriptionPricing pricing;
    private SubscriptionProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SubscriptionProperties();
        pricing = new SubscriptionPricing(properties);
        pricing.validateCatalog();
        billingService = new BillingService(
                authorizationService,
                pricing,
                paymentRepository,
                providerRegistry,
                attemptService,
                activationService,
                properties,
                userRepository,
                cooperativeRepository);
    }

    @Test
    void catalogIsSourcedFromSubscriptionPricing() {
        BillingPlansResponse catalog = billingService.catalog();
        assertThat(catalog.getCurrency()).isEqualTo(pricing.currency());
        assertThat(catalog.getTrialMonths()).isEqualTo(pricing.trialMonths());
        assertThat(catalog.isCardCheckoutAvailable()).isFalse();

        BillingPlanQuote monthly = catalog.getPlans().get(0);
        assertThat(monthly.getBillingCycle()).isEqualTo(SubscriptionBillingCycle.MONTHLY);
        assertThat(monthly.getAmount()).isEqualByComparingTo(pricing.monthly().charge());
        assertThat(monthly.getListPrice()).isEqualByComparingTo(pricing.monthly().listPrice());
        assertThat(monthly.getDiscountPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(monthly.getSavings()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(monthly.getPeriodMonths()).isEqualTo(1);

        BillingPlanQuote annual = catalog.getPlans().get(1);
        assertThat(annual.getBillingCycle()).isEqualTo(SubscriptionBillingCycle.ANNUAL);
        assertThat(annual.getListPrice()).isEqualByComparingTo(pricing.annual().listPrice());
        assertThat(annual.getAmount()).isEqualByComparingTo(pricing.annual().charge());
        assertThat(annual.getDiscountPercent()).isEqualByComparingTo(pricing.annual().discountPercent());
        assertThat(annual.getSavings()).isEqualByComparingTo("6000.0000");
        assertThat(annual.getPeriodMonths()).isEqualTo(12);
    }

    @Test
    void catalogMarksCardAvailableWhenFlutterwaveIsConfigured() {
        when(providerRegistry.find(SubscriptionPaymentChannel.MTN_MOMO)).thenReturn(null);
        when(providerRegistry.find(SubscriptionPaymentChannel.CARD)).thenReturn(cardProvider);
        when(cardProvider.available()).thenReturn(true);
        BillingPlansResponse catalog = billingService.catalog();
        assertThat(catalog.isCardCheckoutAvailable()).isTrue();
        assertThat(catalog.isMtnCheckoutAvailable()).isFalse();
    }

    @Test
    void catalogMarksMtnAvailableWhenConfigured() {
        when(providerRegistry.find(SubscriptionPaymentChannel.MTN_MOMO)).thenReturn(mtnProvider);
        when(providerRegistry.find(SubscriptionPaymentChannel.CARD)).thenReturn(null);
        when(mtnProvider.available()).thenReturn(true);
        BillingPlansResponse catalog = billingService.catalog();
        assertThat(catalog.isMtnCheckoutAvailable()).isTrue();
        assertThat(catalog.isCardCheckoutAvailable()).isFalse();
    }

    @Test
    void getPlansRequiresMembership() {
        UUID cooperativeId = UUID.randomUUID();
        billingService.getPlans(cooperativeId);
        verify(authorizationService).requireMembership(cooperativeId);
    }

    @Test
    void checkoutRequiresBillingManager() {
        UUID cooperativeId = UUID.randomUUID();
        when(authorizationService.currentPrincipal()).thenReturn(principal("MEMBER"));
        assertThatThrownBy(() -> billingService.checkout(cooperativeId, checkoutRequest()))
                .isInstanceOf(ForbiddenException.class);
        verify(attemptService, never()).createOrReusePending(any(), any(), any(), any(), any());
    }

    @Test
    void checkoutReturnsControlledErrorWhenMtnIsUnavailable() {
        UUID cooperativeId = UUID.randomUUID();
        when(authorizationService.currentPrincipal()).thenReturn(principal("PRESIDENT"));
        when(providerRegistry.requireAvailable(SubscriptionPaymentChannel.MTN_MOMO))
                .thenThrow(new PaymentIntegrationUnavailableException());
        assertThatThrownBy(() -> billingService.checkout(cooperativeId, checkoutRequest()))
                .isInstanceOf(PaymentIntegrationUnavailableException.class)
                .hasMessageContaining("was not charged");
        verify(attemptService, never()).createOrReusePending(any(), any(), any(), any(), any());
        verify(activationService, never()).applySuccessfulPayment(any(UUID.class), any());
    }

    @Test
    void checkoutRejectsCardWithoutCreatingAPaymentWhenUnavailable() {
        UUID cooperativeId = UUID.randomUUID();
        when(authorizationService.currentPrincipal()).thenReturn(principal("PRESIDENT"));
        when(providerRegistry.requireAvailable(SubscriptionPaymentChannel.CARD))
                .thenThrow(new PaymentIntegrationUnavailableException());
        BillingCheckoutRequest request = BillingCheckoutRequest.builder()
                .billingCycle(SubscriptionBillingCycle.MONTHLY)
                .paymentChannel(SubscriptionPaymentChannel.CARD)
                .build();
        assertThatThrownBy(() -> billingService.checkout(cooperativeId, request))
                .isInstanceOf(PaymentIntegrationUnavailableException.class);
        verify(attemptService, never()).createOrReusePending(any(), any(), any(), any(), any());
    }

    @Test
    void checkoutRequiresValidRwandanPhoneForMtn() {
        UUID cooperativeId = UUID.randomUUID();
        when(authorizationService.currentPrincipal()).thenReturn(principal("PRESIDENT"));
        when(providerRegistry.requireAvailable(SubscriptionPaymentChannel.MTN_MOMO)).thenReturn(mtnProvider);
        BillingCheckoutRequest request = BillingCheckoutRequest.builder()
                .billingCycle(SubscriptionBillingCycle.MONTHLY)
                .paymentChannel(SubscriptionPaymentChannel.MTN_MOMO)
                .payerPhoneNumber("123")
                .build();
        assertThatThrownBy(() -> billingService.checkout(cooperativeId, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("phone");
        verify(attemptService, never()).createOrReusePending(any(), any(), any(), any(), any());
    }

    @Test
    void checkoutCreatesPendingPaymentWithoutActivating() {
        UUID cooperativeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(authorizationService.currentPrincipal()).thenReturn(principal("PRESIDENT"));
        when(providerRegistry.requireAvailable(SubscriptionPaymentChannel.MTN_MOMO)).thenReturn(mtnProvider);
        SubscriptionPayment pending = SubscriptionPayment.builder()
                .subscriptionId(UUID.randomUUID())
                .cooperativeId(cooperativeId)
                .billingCycle(SubscriptionBillingCycle.MONTHLY)
                .paymentChannel(SubscriptionPaymentChannel.MTN_MOMO)
                .status(SubscriptionPaymentStatus.PENDING)
                .currency("RWF")
                .amount(pricing.monthly().charge())
                .initiatedAt(java.time.Instant.parse("2026-09-01T10:00:00Z"))
                .build();
        pending.setId(paymentId);
        when(attemptService.createOrReusePending(
                        eq(cooperativeId),
                        eq(SubscriptionBillingCycle.MONTHLY),
                        eq(SubscriptionPaymentChannel.MTN_MOMO),
                        any(),
                        eq("250781234567")))
                .thenReturn(pending);
        when(mtnProvider.initiate(any())).thenReturn(PaymentInitiationResult.accepted(paymentId.toString()));
        when(attemptService.markInitiated(eq(paymentId), eq(paymentId.toString()), any(), any()))
                .thenAnswer(invocation -> {
                    pending.setExternalReference(paymentId.toString());
                    pending.setProvider("MTN_MOMO");
                    return pending;
                });

        BillingCheckoutResponse response = billingService.checkout(cooperativeId, checkoutRequest());
        assertThat(response.getPaymentId()).isEqualTo(paymentId);
        assertThat(response.getStatus()).isEqualTo(SubscriptionPaymentStatus.PENDING);
        assertThat(response.getAmount()).isEqualByComparingTo("2000.0000");
        assertThat(response.getCurrency()).isEqualTo("RWF");
        assertThat(response.getMessage()).contains("Approve the payment on your phone");
        verify(activationService, never()).applySuccessfulPayment(any(UUID.class), any());
    }

    @Test
    void cardCheckoutReturnsHostedUrlWithoutActivating() {
        UUID cooperativeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UserPrincipal principal = principal("PRESIDENT");
        when(authorizationService.currentPrincipal()).thenReturn(principal);
        when(providerRegistry.requireAvailable(SubscriptionPaymentChannel.CARD)).thenReturn(cardProvider);
        when(userRepository.findById(principal.getId())).thenReturn(Optional.of(User.builder()
                .email("pat@test.local")
                .firstName("Pat")
                .lastName("President")
                .build()));
        when(cooperativeRepository.findById(cooperativeId)).thenReturn(Optional.of(Cooperative.builder()
                .contactEmail("scheme@test.local")
                .build()));
        SubscriptionPayment pending = SubscriptionPayment.builder()
                .subscriptionId(UUID.randomUUID())
                .cooperativeId(cooperativeId)
                .billingCycle(SubscriptionBillingCycle.ANNUAL)
                .paymentChannel(SubscriptionPaymentChannel.CARD)
                .status(SubscriptionPaymentStatus.PENDING)
                .currency("RWF")
                .amount(pricing.annual().charge())
                .initiatedAt(java.time.Instant.parse("2026-09-01T10:00:00Z"))
                .build();
        pending.setId(paymentId);
        when(attemptService.createOrReusePending(
                        eq(cooperativeId),
                        eq(SubscriptionBillingCycle.ANNUAL),
                        eq(SubscriptionPaymentChannel.CARD),
                        any(),
                        eq(null)))
                .thenReturn(pending);
        when(cardProvider.initiate(any()))
                .thenReturn(PaymentInitiationResult.hosted("ouwealth-sub-" + paymentId, "https://checkout.flutterwave.com/pay/test"));
        when(attemptService.markInitiated(eq(paymentId), any(), any(), any())).thenAnswer(invocation -> {
            pending.setExternalReference(invocation.getArgument(1));
            pending.setCheckoutUrl(invocation.getArgument(2));
            pending.setProvider("FLUTTERWAVE");
            return pending;
        });

        BillingCheckoutResponse response = billingService.checkout(
                cooperativeId,
                BillingCheckoutRequest.builder()
                        .billingCycle(SubscriptionBillingCycle.ANNUAL)
                        .paymentChannel(SubscriptionPaymentChannel.CARD)
                        .build());
        assertThat(response.getStatus()).isEqualTo(SubscriptionPaymentStatus.PENDING);
        assertThat(response.getAmount()).isEqualByComparingTo("18000.0000");
        assertThat(response.getCheckoutUrl()).isEqualTo("https://checkout.flutterwave.com/pay/test");
        assertThat(response.getMessage()).contains("secure card payment");
        verify(activationService, never()).applySuccessfulPayment(any(UUID.class), any());
    }

    private static BillingCheckoutRequest checkoutRequest() {
        return BillingCheckoutRequest.builder()
                .billingCycle(SubscriptionBillingCycle.MONTHLY)
                .paymentChannel(SubscriptionPaymentChannel.MTN_MOMO)
                .payerPhoneNumber("0781234567")
                .build();
    }

    private static UserPrincipal principal(String role) {
        return UserPrincipal.builder()
                .id(UUID.randomUUID())
                .username("tester")
                .roles(Set.of(role))
                .permissions(Set.of())
                .cooperativeIds(Set.of())
                .accountNonLocked(true)
                .enabled(true)
                .build();
    }
}
