package rw.terimbere.csams.modules.subscription.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;

public interface CooperativeSubscriptionRepository extends JpaRepository<CooperativeSubscription, UUID> {

    Optional<CooperativeSubscription> findByCooperativeId(UUID cooperativeId);

    boolean existsByCooperativeId(UUID cooperativeId);

    long countByCooperativeId(UUID cooperativeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select s from CooperativeSubscription s where s.cooperativeId = :cooperativeId")
    Optional<CooperativeSubscription> findByCooperativeIdForUpdate(@Param("cooperativeId") UUID cooperativeId);
}
