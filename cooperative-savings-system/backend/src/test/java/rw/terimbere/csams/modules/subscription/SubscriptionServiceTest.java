package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.dto.CooperativeSubscriptionResponse;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionInitialization;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.service.SubscriptionEntitlementService;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;
import rw.terimbere.csams.modules.subscription.service.SubscriptionService;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Mock
    private CooperativeRepository cooperativeRepository;

    @Mock
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private Clock clock;

    private SubscriptionService service;
    private UUID cooperativeId;
    private UUID actorId;
    private Instant start;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SubscriptionPricing pricing = new SubscriptionPricing(new SubscriptionProperties());
        SubscriptionEntitlementService entitlementService =
                new SubscriptionEntitlementService(subscriptionRepository, pricing, clock);
        service = new SubscriptionService(
                cooperativeRepository,
                subscriptionRepository,
                pricing,
                entitlementService,
                auditService,
                objectMapper,
                clock);
        cooperativeId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        start = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, KIGALI).toInstant();
        org.mockito.Mockito.lenient().when(clock.instant()).thenReturn(start);
    }

    @Test
    void startTrial_createsTrialForExactlyFourCalendarMonths() {
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId))
                .thenReturn(Optional.of(Cooperative.builder().name("Scheme").build()));
        when(subscriptionRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.empty());
        when(clock.instant()).thenReturn(start);
        when(subscriptionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CooperativeSubscription saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        CooperativeSubscriptionResponse response =
                service.initializeForCooperative(cooperativeId, SubscriptionInitialization.START_TRIAL, actorId);

        Instant expectedEnd = ZonedDateTime.of(2026, 5, 15, 10, 0, 0, 0, KIGALI).toInstant();
        assertThat(response.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(response.getBillingCycle()).isNull();
        assertThat(response.getTrialStartedAt()).isEqualTo(start);
        assertThat(response.getTrialEndsAt()).isEqualTo(expectedEnd);
        assertThat(response.getCurrentPeriodStartedAt()).isNull();
        assertThat(response.getCurrentPeriodEndsAt()).isNull();

        verify(auditService)
                .record(
                        eq(actorId),
                        eq(cooperativeId),
                        eq(AuditableAction.SUBSCRIPTION_INIT),
                        eq("CooperativeSubscription"),
                        any(),
                        isNull(),
                        org.mockito.ArgumentMatchers.contains("START_TRIAL"),
                        isNull(),
                        isNull());
        verify(auditService)
                .record(
                        eq(actorId),
                        eq(cooperativeId),
                        eq(AuditableAction.SUBSCRIPTION_TRIAL_START),
                        eq("CooperativeSubscription"),
                        any(),
                        isNull(),
                        org.mockito.ArgumentMatchers.contains("\"status\":\"TRIAL\""),
                        isNull(),
                        isNull());
    }

    @Test
    void none_createsInactiveSubscriptionWithoutTrial() {
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId))
                .thenReturn(Optional.of(Cooperative.builder().name("Scheme").build()));
        when(subscriptionRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.empty());
        when(subscriptionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CooperativeSubscription saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        CooperativeSubscriptionResponse response =
                service.initializeForCooperative(cooperativeId, SubscriptionInitialization.NONE, actorId);

        assertThat(response.getStatus()).isEqualTo(SubscriptionStatus.NONE);
        assertThat(response.getTrialStartedAt()).isNull();
        assertThat(response.getTrialEndsAt()).isNull();
        assertThat(response.getBillingCycle()).isNull();
        verify(auditService, times(1))
                .record(any(), any(), eq(AuditableAction.SUBSCRIPTION_INIT), any(), any(), any(), any(), any(), any());
        verify(auditService, never())
                .record(
                        any(),
                        any(),
                        eq(AuditableAction.SUBSCRIPTION_TRIAL_START),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void repeatedStartTrial_doesNotRestartOrExtendTrial() {
        Instant originalEnd = ZonedDateTime.of(2026, 5, 15, 10, 0, 0, 0, KIGALI).toInstant();
        CooperativeSubscription existing = CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(SubscriptionStatus.TRIAL)
                .trialStartedAt(start)
                .trialEndsAt(originalEnd)
                .build();
        existing.setId(UUID.randomUUID());
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId))
                .thenReturn(Optional.of(Cooperative.builder().name("Scheme").build()));
        when(subscriptionRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.of(existing));

        CooperativeSubscriptionResponse response =
                service.initializeForCooperative(cooperativeId, SubscriptionInitialization.START_TRIAL, actorId);

        assertThat(response.getTrialStartedAt()).isEqualTo(start);
        assertThat(response.getTrialEndsAt()).isEqualTo(originalEnd);
        verify(subscriptionRepository, never()).saveAndFlush(any());
        verify(auditService, never())
                .record(any(), any(), any(AuditableAction.class), any(), any(), any(), any(), any(), any());
    }

    @Test
    void changingInitializationMode_isRejected() {
        CooperativeSubscription existing = CooperativeSubscription.builder()
                .cooperativeId(cooperativeId)
                .status(SubscriptionStatus.NONE)
                .build();
        existing.setId(UUID.randomUUID());
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId))
                .thenReturn(Optional.of(Cooperative.builder().name("Scheme").build()));
        when(subscriptionRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.initializeForCooperative(
                        cooperativeId, SubscriptionInitialization.START_TRIAL, actorId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already initialized");
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void activeManual_isRejected() {
        assertThatThrownBy(() -> service.initializeForCooperative(
                        cooperativeId, SubscriptionInitialization.ACTIVE_MANUAL, actorId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ACTIVE_MANUAL");
    }

    @Test
    void missingCooperative_isNotFound() {
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.initializeForCooperative(
                        cooperativeId, SubscriptionInitialization.START_TRIAL, actorId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void startTrial_auditPayloadIncludesCooperativeAndWindow() throws Exception {
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId))
                .thenReturn(Optional.of(Cooperative.builder().name("Scheme").build()));
        when(subscriptionRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.empty());
        when(clock.instant()).thenReturn(start);
        when(subscriptionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CooperativeSubscription saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        service.initializeForCooperative(cooperativeId, SubscriptionInitialization.START_TRIAL, actorId);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .record(
                        eq(actorId),
                        eq(cooperativeId),
                        eq(AuditableAction.SUBSCRIPTION_INIT),
                        eq("CooperativeSubscription"),
                        any(),
                        isNull(),
                        payload.capture(),
                        isNull(),
                        isNull());
        assertThat(payload.getValue()).contains(cooperativeId.toString());
        assertThat(payload.getValue()).contains("START_TRIAL");
        assertThat(payload.getValue()).contains("TRIAL");
        assertThat(payload.getValue()).contains("trialStartedAt");
        assertThat(payload.getValue()).contains("trialEndsAt");
    }
}
