package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.modules.subscription.service.SubscriptionActivationService;
import rw.terimbere.csams.modules.subscription.service.SubscriptionCalendar;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;
import rw.terimbere.csams.shared.auditing.AuditableAction;

@ExtendWith(MockitoExtension.class)
class SubscriptionActivationServiceTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Mock
    private SubscriptionPaymentRepository paymentRepository;

    @Mock
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private Clock clock;

    private SubscriptionActivationService service;
    private SubscriptionPricing pricing;
    private UUID cooperativeId;
    private UUID paymentId;
    private Instant now;

    @BeforeEach
    void setUp() {
        pricing = new SubscriptionPricing(new SubscriptionProperties());
        pricing.validateCatalog();
        service = new SubscriptionActivationService(
                paymentRepository,
                subscriptionRepository,
                pricing,
                auditService,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                clock);
        cooperativeId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
        now = Instant.parse("2026-03-01T08:00:00Z");
        when(clock.instant()).thenReturn(now);
    }

    @Test
    void monthlyAddsOneCalendarMonthFromPaymentTimeWhenExpired() {
        CooperativeSubscription subscription = expired();
        SubscriptionPayment payment = pending(SubscriptionBillingCycle.MONTHLY);
        stubLocks(subscription, payment);

        service.applySuccessfulPayment(paymentId, UUID.randomUUID());

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getBillingCycle()).isEqualTo(SubscriptionBillingCycle.MONTHLY);
        assertThat(subscription.getCurrentPeriodStartedAt()).isEqualTo(now);
        assertThat(subscription.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(now, 1, KIGALI));
        assertThat(payment.getStatus()).isEqualTo(SubscriptionPaymentStatus.SUCCESS);
        verify(auditService)
                .record(any(), eq(cooperativeId), eq(AuditableAction.SUBSCRIPTION_ACTIVATED), any(), any(), any(), any(), isNull(), isNull());
    }

    @Test
    void annualAddsTwelveCalendarMonthsFromPaymentTimeWhenExpired() {
        CooperativeSubscription subscription = expired();
        SubscriptionPayment payment = pending(SubscriptionBillingCycle.ANNUAL);
        stubLocks(subscription, payment);

        service.applySuccessfulPayment(paymentId, null);

        assertThat(subscription.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(now, 12, KIGALI));
        assertThat(subscription.getBillingCycle()).isEqualTo(SubscriptionBillingCycle.ANNUAL);
    }

    @Test
    void paymentDuringActiveTrialStartsPaidPeriodAtTrialEnd() {
        Instant trialEnd = Instant.parse("2026-01-16T00:00:00Z");
        Instant paidAt = Instant.parse("2025-12-01T00:00:00Z");
        when(clock.instant()).thenReturn(paidAt);
        CooperativeSubscription subscription = CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(SubscriptionStatus.TRIAL)
                .trialStartedAt(Instant.parse("2025-09-16T00:00:00Z"))
                .trialEndsAt(trialEnd)
                .build();
        SubscriptionPayment payment = pending(SubscriptionBillingCycle.ANNUAL);
        stubLocks(subscription, payment);

        service.applySuccessfulPayment(paymentId, null);

        assertThat(subscription.getCurrentPeriodStartedAt()).isEqualTo(trialEnd);
        assertThat(subscription.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(trialEnd, 12, KIGALI));
        assertThat(subscription.getTrialEndsAt()).isEqualTo(trialEnd);
        verify(auditService)
                .record(any(), eq(cooperativeId), eq(AuditableAction.SUBSCRIPTION_ACTIVATED), any(), any(), any(), any(), isNull(), isNull());
    }

    @Test
    void earlyRenewalExtendsFromCurrentPeriodEnd() {
        Instant periodEnd = Instant.parse("2026-03-20T00:00:00Z");
        Instant started = Instant.parse("2025-03-20T00:00:00Z");
        CooperativeSubscription subscription = CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(SubscriptionStatus.ACTIVE)
                .billingCycle(SubscriptionBillingCycle.ANNUAL)
                .currentPeriodStartedAt(started)
                .currentPeriodEndsAt(periodEnd)
                .build();
        SubscriptionPayment payment = pending(SubscriptionBillingCycle.ANNUAL);
        stubLocks(subscription, payment);

        service.applySuccessfulPayment(paymentId, null);

        assertThat(subscription.getCurrentPeriodStartedAt()).isEqualTo(started);
        assertThat(subscription.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(periodEnd, 12, KIGALI));
        verify(auditService)
                .record(any(), eq(cooperativeId), eq(AuditableAction.SUBSCRIPTION_RENEWED), any(), any(), any(), any(), isNull(), isNull());
    }

    @Test
    void pastDueStartsFromPaymentTimeWhenPaidPeriodAlreadyEnded() {
        Instant ended = Instant.parse("2026-02-01T00:00:00Z");
        CooperativeSubscription subscription = CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(SubscriptionStatus.PAST_DUE)
                .billingCycle(SubscriptionBillingCycle.MONTHLY)
                .currentPeriodStartedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .currentPeriodEndsAt(ended)
                .pastDueUntil(Instant.parse("2026-03-08T00:00:00Z"))
                .build();
        SubscriptionPayment payment = pending(SubscriptionBillingCycle.MONTHLY);
        stubLocks(subscription, payment);

        service.applySuccessfulPayment(paymentId, null);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getCurrentPeriodStartedAt()).isEqualTo(now);
        assertThat(subscription.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(now, 1, KIGALI));
        assertThat(subscription.getPastDueUntil()).isNull();
    }

    @Test
    void duplicateSuccessDoesNotExtendAgain() {
        CooperativeSubscription subscription = expired();
        SubscriptionPayment payment = pending(SubscriptionBillingCycle.MONTHLY);
        stubLocks(subscription, payment);
        when(subscriptionRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.of(subscription));

        service.applySuccessfulPayment(paymentId, null);
        Instant endsAfterFirst = subscription.getCurrentPeriodEndsAt();
        assertThat(payment.getStatus()).isEqualTo(SubscriptionPaymentStatus.SUCCESS);

        service.applySuccessfulPayment(paymentId, null);

        assertThat(subscription.getCurrentPeriodEndsAt()).isEqualTo(endsAfterFirst);
        verify(subscriptionRepository, times(1)).save(subscription);
        verify(auditService, times(1))
                .record(any(), eq(cooperativeId), eq(AuditableAction.SUBSCRIPTION_ACTIVATED), any(), any(), any(), any(), isNull(), isNull());
    }

    private void stubLocks(CooperativeSubscription subscription, SubscriptionPayment payment) {
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(subscriptionRepository.findByCooperativeIdForUpdate(cooperativeId)).thenReturn(Optional.of(subscription));
    }

    private CooperativeSubscription expired() {
        return CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(SubscriptionStatus.EXPIRED)
                .trialStartedAt(Instant.parse("2025-09-01T00:00:00Z"))
                .trialEndsAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private SubscriptionPayment pending(SubscriptionBillingCycle cycle) {
        SubscriptionPayment payment = SubscriptionPayment.builder()
                .subscriptionId(UUID.randomUUID())
                .cooperativeId(cooperativeId)
                .billingCycle(cycle)
                .paymentChannel(SubscriptionPaymentChannel.MTN_MOMO)
                .status(SubscriptionPaymentStatus.PENDING)
                .currency("RWF")
                .amount(cycle == SubscriptionBillingCycle.ANNUAL
                        ? new BigDecimal("18000.0000")
                        : new BigDecimal("2000.0000"))
                .provider("MTN_MOMO")
                .externalReference(paymentId.toString())
                .initiatedAt(now)
                .build();
        payment.setId(paymentId);
        return payment;
    }
}
