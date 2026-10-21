package rw.terimbere.csams.modules.memberpayment.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;

public interface MemberPaymentRepository
        extends JpaRepository<MemberPayment, UUID>, JpaSpecificationExecutor<MemberPayment> {

    /** Tenant-scoped lookup: another scheme's payment is simply "not found". */
    Optional<MemberPayment> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    /** Row-locking variant used by every status change so concurrent transitions are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MemberPayment p where p.id = :id and p.cooperativeId = :cooperativeId")
    Optional<MemberPayment> findByIdAndCooperativeIdForUpdate(
            @Param("id") UUID id, @Param("cooperativeId") UUID cooperativeId);

    /**
     * Records a failed posting attempt on a still-VERIFIED payment. Run in its own transaction after the failed
     * financial transaction has rolled back; it touches only the attempt metadata, never a financial record.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MemberPayment p set p.postingAttempts = p.postingAttempts + 1,"
            + " p.lastPostingFailureCode = :code, p.lastPostingFailedAt = :at, p.version = p.version + 1"
            + " where p.id = :id and p.cooperativeId = :cooperativeId"
            + " and p.status = rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus.VERIFIED")
    int recordPostingFailure(
            @Param("id") UUID id,
            @Param("cooperativeId") UUID cooperativeId,
            @Param("code") String code,
            @Param("at") java.time.Instant at);

    /** Payments of the scheme that carry this method and normalized reference (any status). */
    java.util.List<MemberPayment> findByCooperativeIdAndPaymentMethodAndNormalizedReference(
            UUID cooperativeId,
            rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod paymentMethod,
            String normalizedReference);

    Optional<MemberPayment> findByCooperativeIdAndReferenceKey(UUID cooperativeId, String referenceKey);

    Optional<MemberPayment> findByCooperativeIdAndSubmittedByAndIdempotencyKey(
            UUID cooperativeId, UUID submittedBy, String idempotencyKey);
}
