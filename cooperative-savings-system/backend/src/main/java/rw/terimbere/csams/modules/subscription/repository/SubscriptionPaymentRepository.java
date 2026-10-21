package rw.terimbere.csams.modules.subscription.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, UUID> {

    Page<SubscriptionPayment> findByCooperativeId(UUID cooperativeId, Pageable pageable);

    List<SubscriptionPayment> findByCooperativeIdOrderByCreatedAtDesc(UUID cooperativeId);

    List<SubscriptionPayment> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    Optional<SubscriptionPayment> findByIdempotencyKey(String idempotencyKey);

    Optional<SubscriptionPayment> findByProviderAndExternalReference(String provider, String externalReference);

    Optional<SubscriptionPayment> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Optional<SubscriptionPayment>
            findFirstByCooperativeIdAndBillingCycleAndPaymentChannelAndStatusOrderByInitiatedAtDesc(
                    UUID cooperativeId,
                    SubscriptionBillingCycle billingCycle,
                    SubscriptionPaymentChannel paymentChannel,
                    SubscriptionPaymentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from SubscriptionPayment p where p.id = :id")
    Optional<SubscriptionPayment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * PENDING payments eligible for reconciliation: initiated between {@code minInitiated}
     * (abandon floor) and {@code maxInitiated} (min-age ceiling), oldest first.
     * Pass a {@link org.springframework.data.domain.Pageable} to cap the batch size.
     */
    @Query(
            """
            select p.id from SubscriptionPayment p
            where p.status = :status
              and p.initiatedAt >= :minInitiated
              and p.initiatedAt <= :maxInitiated
            order by p.initiatedAt asc
            """)
    List<UUID> findPendingIdsForReconciliation(
            @Param("status") SubscriptionPaymentStatus status,
            @Param("minInitiated") java.time.Instant minInitiated,
            @Param("maxInitiated") java.time.Instant maxInitiated,
            org.springframework.data.domain.Pageable pageable);
}
