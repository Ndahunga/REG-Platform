package rw.terimbere.csams.modules.memberpayment.verification;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;

public interface TrustedTransactionRepository extends JpaRepository<TrustedTransaction, UUID> {

    Optional<TrustedTransaction> findByCooperativeIdAndChannelAndNormalizedReference(
            UUID cooperativeId, PaymentMethod channel, String normalizedReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TrustedTransaction t where t.cooperativeId = :coop and t.channel = :channel"
            + " and t.normalizedReference = :ref")
    Optional<TrustedTransaction> findForUpdate(
            @Param("coop") UUID cooperativeId, @Param("channel") PaymentMethod channel, @Param("ref") String ref);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TrustedTransaction t where t.id = :id")
    Optional<TrustedTransaction> findByIdForUpdate(@Param("id") UUID id);

    Optional<TrustedTransaction> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Optional<TrustedTransaction> findByCooperativeIdAndSourceAndEventId(
            UUID cooperativeId, VerificationSource source, String eventId);

    List<TrustedTransaction> findByCooperativeIdOrderByRecordedAtDesc(UUID cooperativeId, Pageable pageable);
}
