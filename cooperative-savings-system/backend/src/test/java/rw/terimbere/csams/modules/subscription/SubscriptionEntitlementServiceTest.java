package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.dto.SubscriptionEntitlement;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.service.SubscriptionEntitlementService;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;
import rw.terimbere.csams.shared.exceptions.SubscriptionInactiveException;

@ExtendWith(MockitoExtension.class)
class SubscriptionEntitlementServiceTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Mock
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Mock
    private Clock clock;

    private SubscriptionEntitlementService service;
    private UUID cooperativeId;
    private Instant now;

    @BeforeEach
    void setUp() {
        service = new SubscriptionEntitlementService(
                subscriptionRepository, new SubscriptionPricing(new SubscriptionProperties()), clock);
        cooperativeId = UUID.randomUUID();
        now = ZonedDateTime.of(2026, 9, 16, 12, 0, 0, 0, KIGALI).toInstant();
        org.mockito.Mockito.lenient().when(clock.instant()).thenReturn(now);
    }

    @Test
    void trialBeforeEnd_isUsable() {
        Instant end = now.plusSeconds(3600);
        SubscriptionEntitlement entitlement = service.evaluate(trial(end), now);
        assertThat(entitlement.storedStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(entitlement.writeAllowed()).isTrue();
        assertThat(entitlement.usable()).isTrue();
        assertThat(entitlement.daysRemaining()).isNotNull();
    }

    @Test
    void trialAfterEnd_isExpired() {
        SubscriptionEntitlement entitlement = service.evaluate(trial(now.minusSeconds(1)), now);
        assertThat(entitlement.storedStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(entitlement.writeAllowed()).isFalse();
    }

    @Test
    void trialMissingEnd_failsClosed() {
        SubscriptionEntitlement entitlement = service.evaluate(trial(null), now);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(entitlement.writeAllowed()).isFalse();
    }

    @Test
    void activeWithinPeriod_isUsable() {
        CooperativeSubscription subscription = base(SubscriptionStatus.ACTIVE);
        subscription.setBillingCycle(SubscriptionBillingCycle.MONTHLY);
        subscription.setCurrentPeriodEndsAt(now.plusSeconds(86_400));
        SubscriptionEntitlement entitlement = service.evaluate(subscription, now);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(entitlement.writeAllowed()).isTrue();
        assertThat(entitlement.billingCycle()).isEqualTo(SubscriptionBillingCycle.MONTHLY);
    }

    @Test
    void activeAfterPeriodEnd_cannotRemainUsable() {
        CooperativeSubscription subscription = base(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodEndsAt(now.minusSeconds(1));
        SubscriptionEntitlement entitlement = service.evaluate(subscription, now);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(entitlement.writeAllowed()).isFalse();
    }

    @Test
    void activeMissingPeriodEnd_failsClosed() {
        SubscriptionEntitlement entitlement = service.evaluate(base(SubscriptionStatus.ACTIVE), now);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(entitlement.writeAllowed()).isFalse();
    }

    @Test
    void pastDueBeforeGraceEnd_isUsable() {
        CooperativeSubscription subscription = base(SubscriptionStatus.PAST_DUE);
        subscription.setPastDueUntil(now.plusSeconds(86_400));
        SubscriptionEntitlement entitlement = service.evaluate(subscription, now);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(entitlement.writeAllowed()).isTrue();
    }

    @Test
    void pastDueAfterGraceEnd_isExpired() {
        CooperativeSubscription subscription = base(SubscriptionStatus.PAST_DUE);
        subscription.setPastDueUntil(now.minusSeconds(1));
        SubscriptionEntitlement entitlement = service.evaluate(subscription, now);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(entitlement.writeAllowed()).isFalse();
    }

    @Test
    void canceledBeforePeriodEnd_isUsable() {
        CooperativeSubscription subscription = base(SubscriptionStatus.CANCELED);
        subscription.setCurrentPeriodEndsAt(now.plusSeconds(86_400));
        subscription.setCanceledAt(now.minusSeconds(60));
        SubscriptionEntitlement entitlement = service.evaluate(subscription, now);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(entitlement.writeAllowed()).isTrue();
    }

    @Test
    void canceledAfterPeriodEnd_isExpired() {
        CooperativeSubscription subscription = base(SubscriptionStatus.CANCELED);
        subscription.setCurrentPeriodEndsAt(now.minusSeconds(1));
        SubscriptionEntitlement entitlement = service.evaluate(subscription, now);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(entitlement.writeAllowed()).isFalse();
    }

    @Test
    void storedExpiredAndNone_areRestricted() {
        assertThat(service.evaluate(base(SubscriptionStatus.EXPIRED), now).writeAllowed()).isFalse();
        assertThat(service.evaluate(base(SubscriptionStatus.NONE), now).effectiveStatus())
                .isEqualTo(SubscriptionStatus.NONE);
        assertThat(service.evaluate(base(SubscriptionStatus.NONE), now).writeAllowed()).isFalse();
    }

    @Test
    void missingRow_isNone() {
        when(subscriptionRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.empty());
        SubscriptionEntitlement entitlement = service.getEntitlement(cooperativeId);
        assertThat(entitlement.effectiveStatus()).isEqualTo(SubscriptionStatus.NONE);
        assertThat(entitlement.writeAllowed()).isFalse();
    }

    @Test
    void requireWriteAllowed_throwsInactive() {
        CooperativeSubscription subscription = trial(now.minusSeconds(1));
        when(subscriptionRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.of(subscription));
        assertThatThrownBy(() -> service.requireWriteAllowed(cooperativeId))
                .isInstanceOf(SubscriptionInactiveException.class)
                .extracting(ex -> ((SubscriptionInactiveException) ex).getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.EXPIRED);
    }

    private CooperativeSubscription trial(Instant trialEndsAt) {
        CooperativeSubscription subscription = base(SubscriptionStatus.TRIAL);
        subscription.setTrialStartedAt(now.minusSeconds(86_400));
        subscription.setTrialEndsAt(trialEndsAt);
        return subscription;
    }

    private CooperativeSubscription base(SubscriptionStatus status) {
        CooperativeSubscription subscription = CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(status)
                .build();
        subscription.setId(UUID.randomUUID());
        return subscription;
    }
}
