package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import rw.terimbere.csams.modules.audit.entity.AuditLog;
import rw.terimbere.csams.modules.audit.repository.AuditLogRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.subscription.dto.CooperativeSubscriptionResponse;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionInitialization;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;
import rw.terimbere.csams.modules.subscription.service.SubscriptionService;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@SpringBootTest
@ActiveProfiles("test")
class SubscriptionInitializationIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionPaymentRepository subscriptionPaymentRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CooperativeRepository cooperativeRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionPricing pricing;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        actorId = userRepository.findByUsernameIgnoreCaseAndDeletedFalse("superadmin").orElseThrow().getId();
    }

    @Test
    void startTrial_persistsSingleTrialAndAudit() {
        UUID cooperativeId = persistCooperative("Trial Scheme");
        Instant before = Instant.now().minusSeconds(5);

        CooperativeSubscriptionResponse created = subscriptionService.initializeForCooperative(
                cooperativeId, SubscriptionInitialization.START_TRIAL, actorId);

        assertThat(created.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(created.getTrialStartedAt()).isAfter(before);
        Instant expectedEnd = created.getTrialStartedAt()
                .atZone(KIGALI)
                .plusMonths(4)
                .toInstant();
        assertThat(created.getTrialEndsAt()).isEqualTo(expectedEnd);
        assertThat(subscriptionRepository.countByCooperativeId(cooperativeId)).isEqualTo(1);

        Cooperative cooperative = cooperativeRepository.findByIdAndDeletedFalse(cooperativeId).orElseThrow();
        assertThat(cooperative.getOnboardingState()).isEqualTo(CooperativeOnboardingState.AWAITING_PRESIDENT);
        assertThat(cooperative.getStatus().name()).isNotEqualTo("AWAITING_PRESIDENT");

        List<AuditLog> initLogs = auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                cooperativeId, AuditableAction.SUBSCRIPTION_INIT.name());
        List<AuditLog> trialLogs = auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                cooperativeId, AuditableAction.SUBSCRIPTION_TRIAL_START.name());
        assertThat(initLogs).hasSize(1);
        assertThat(trialLogs).hasSize(1);
        assertThat(initLogs.get(0).getUserId()).isEqualTo(actorId);
        assertThat(initLogs.get(0).getNewValues()).contains(cooperativeId.toString());
        assertThat(initLogs.get(0).getNewValues()).contains("START_TRIAL");
    }

    @Test
    void none_persistsInactiveState() {
        UUID cooperativeId = persistCooperative("None Scheme");
        CooperativeSubscriptionResponse created = subscriptionService.initializeForCooperative(
                cooperativeId, SubscriptionInitialization.NONE, actorId);

        assertThat(created.getStatus()).isEqualTo(SubscriptionStatus.NONE);
        assertThat(created.getTrialStartedAt()).isNull();
        assertThat(created.getTrialEndsAt()).isNull();
        assertThat(created.getBillingCycle()).isNull();
        assertThat(auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                        cooperativeId, AuditableAction.SUBSCRIPTION_TRIAL_START.name()))
                .isEmpty();
        assertThat(auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                        cooperativeId, AuditableAction.SUBSCRIPTION_INIT.name()))
                .hasSize(1);
    }

    @Test
    void repeatedInitialization_isIdempotentAndDoesNotExtendTrial() {
        UUID cooperativeId = persistCooperative("Idempotent Scheme");
        CooperativeSubscriptionResponse first = subscriptionService.initializeForCooperative(
                cooperativeId, SubscriptionInitialization.START_TRIAL, actorId);
        CooperativeSubscription storedAfterFirst =
                subscriptionRepository.findByCooperativeId(cooperativeId).orElseThrow();

        CooperativeSubscriptionResponse second = subscriptionService.initializeForCooperative(
                cooperativeId, SubscriptionInitialization.START_TRIAL, actorId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getTrialStartedAt()).isEqualTo(storedAfterFirst.getTrialStartedAt());
        assertThat(second.getTrialEndsAt()).isEqualTo(storedAfterFirst.getTrialEndsAt());
        assertThat(second.getTrialEndsAt())
                .isEqualTo(storedAfterFirst.getTrialStartedAt().atZone(KIGALI).plusMonths(4).toInstant());
        assertThat(subscriptionRepository.countByCooperativeId(cooperativeId)).isEqualTo(1);
        assertThat(auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                        cooperativeId, AuditableAction.SUBSCRIPTION_INIT.name()))
                .hasSize(1);
    }

    @Test
    void differentInitialization_isRejectedWithoutMutating() {
        UUID cooperativeId = persistCooperative("Conflict Scheme");
        CooperativeSubscriptionResponse first = subscriptionService.initializeForCooperative(
                cooperativeId, SubscriptionInitialization.NONE, actorId);

        assertThatThrownBy(() -> subscriptionService.initializeForCooperative(
                        cooperativeId, SubscriptionInitialization.START_TRIAL, actorId))
                .isInstanceOf(ConflictException.class);

        CooperativeSubscription persisted =
                subscriptionRepository.findByCooperativeId(cooperativeId).orElseThrow();
        assertThat(persisted.getId()).isEqualTo(first.getId());
        assertThat(persisted.getStatus()).isEqualTo(SubscriptionStatus.NONE);
        assertThat(persisted.getTrialStartedAt()).isNull();
        assertThat(subscriptionRepository.countByCooperativeId(cooperativeId)).isEqualTo(1);
    }

    @Test
    void subscriptionPayment_doesNotWriteFinancialLedger() {
        UUID cooperativeId = persistCooperative("Payment Scheme");
        CooperativeSubscriptionResponse subscription = subscriptionService.initializeForCooperative(
                cooperativeId, SubscriptionInitialization.NONE, actorId);
        long ledgerBefore = ledgerEntryRepository.count();

        SubscriptionPayment payment = SubscriptionPayment.builder()
                .subscriptionId(subscription.getId())
                .cooperativeId(cooperativeId)
                .billingCycle(SubscriptionBillingCycle.MONTHLY)
                .paymentChannel(SubscriptionPaymentChannel.MTN_MOMO)
                .status(SubscriptionPaymentStatus.PENDING)
                .currency("RWF")
                .amount(MoneyUtils.scaleForStorage(pricing.monthly().charge()))
                .provider("placeholder")
                .externalReference("ext-" + UUID.randomUUID())
                .idempotencyKey("subpay-" + UUID.randomUUID())
                .initiatedAt(Instant.now())
                .build();
        subscriptionPaymentRepository.saveAndFlush(payment);

        assertThat(subscriptionPaymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .hasSize(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);
        assertThat(ledgerEntryRepository.findByIdempotencyKey(payment.getIdempotencyKey())).isEmpty();
        assertThat(payment.getAmount()).isEqualByComparingTo("2000.0000");
    }

    @Test
    void pricingCatalog_isLoadedFromConfiguration() {
        assertThat(pricing.monthly().charge()).isEqualByComparingTo("2000.0000");
        assertThat(pricing.annual().listPrice()).isEqualByComparingTo("24000.0000");
        assertThat(pricing.annual().charge()).isEqualByComparingTo("18000.0000");
        assertThat(pricing.annual().discountPercent()).isEqualByComparingTo("25");
    }

    @Test
    void databaseRejectsInvalidStatusAndDuplicateCurrentSubscription() {
        UUID cooperativeId = persistCooperative("Constraint Scheme");
        subscriptionService.initializeForCooperative(
                cooperativeId, SubscriptionInitialization.START_TRIAL, actorId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO cooperative_subscriptions
                            (id, cooperative_id, status, created_at, updated_at, version)
                        VALUES (?, ?, 'NONE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                        """,
                        UUID.randomUUID(),
                        cooperativeId))
                .isInstanceOf(DataAccessException.class);

        UUID otherCoop = persistCooperative("Invalid Status Scheme");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO cooperative_subscriptions
                            (id, cooperative_id, status, created_at, updated_at, version)
                        VALUES (?, ?, 'NOT_A_STATUS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                        """,
                        UUID.randomUUID(),
                        otherCoop))
                .isInstanceOf(DataAccessException.class);
    }

    /** Persists a cooperative without going through Super Admin create (which now initializes a subscription). */
    private UUID persistCooperative(String namePrefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Cooperative saved = cooperativeRepository.saveAndFlush(Cooperative.builder()
                .name(namePrefix + " " + suffix)
                .registrationNumber("RCA/SVC/" + suffix.toUpperCase())
                .contactEmail("svc-" + suffix.toLowerCase() + "@test.local")
                .contactPhone("0781234567")
                .currency("RWF")
                .financialYearStartMonth(1)
                .monthlyContributionAmount(new BigDecimal("1000.0000"))
                .contributionDueDay(1)
                .registrationDate(LocalDate.of(2024, 1, 15))
                .status(CooperativeStatus.ACTIVE)
                .onboardingState(CooperativeOnboardingState.AWAITING_PRESIDENT)
                .operatingModel(rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel.ACCUMULATING)
                .createdBy(actorId)
                .build());
        return saved.getId();
    }
}
