package rw.terimbere.csams.modules.rotation.payout;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RotationPayoutRepository extends JpaRepository<RotationPayout, UUID> {

    Optional<RotationPayout> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RotationPayout p where p.id = :id and p.cooperativeId = :cooperativeId")
    Optional<RotationPayout> findByIdAndCooperativeIdForUpdate(
            @Param("id") UUID id, @Param("cooperativeId") UUID cooperativeId);

    Optional<RotationPayout> findByCooperativeIdAndRequestedByAndIdempotencyKey(
            UUID cooperativeId, UUID requestedBy, String idempotencyKey);

    List<RotationPayout> findByCooperativeIdOrderByRequestedAtDesc(UUID cooperativeId);

    List<RotationPayout> findByCooperativeIdAndRotationPeriodIdOrderByRequestedAtAsc(UUID cooperativeId, UUID periodId);

    List<RotationPayout> findByCooperativeIdAndRecipientUserIdOrderByRequestedAtDesc(UUID cooperativeId, UUID recipientUserId);

    @Query("select coalesce(sum(p.amount), 0) from RotationPayout p where p.cooperativeId = :cooperativeId"
            + " and p.rotationPeriodId = :periodId and p.status in :statuses")
    BigDecimal sumAmount(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("periodId") UUID periodId,
            @Param("statuses") Collection<RotationPayoutStatus> statuses);

    @Query("select coalesce(sum(p.amount), 0) from RotationPayout p where p.cooperativeId = :cooperativeId"
            + " and p.status in :statuses")
    BigDecimal sumAmountForScheme(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<RotationPayoutStatus> statuses);

    /** Amount per rotation period of the payouts in the given statuses (period id, total). */
    @Query("select p.rotationPeriodId, coalesce(sum(p.amount), 0) from RotationPayout p where p.cooperativeId = :cooperativeId"
            + " and p.status in :statuses group by p.rotationPeriodId")
    java.util.List<Object[]> sumAmountByPeriod(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<RotationPayoutStatus> statuses);

    /** Amount per recipient of the payouts in the given statuses (recipient user id, total). */
    @Query("select p.recipientUserId, coalesce(sum(p.amount), 0) from RotationPayout p where p.cooperativeId = :cooperativeId"
            + " and p.status in :statuses group by p.recipientUserId")
    java.util.List<Object[]> sumAmountByRecipient(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<RotationPayoutStatus> statuses);
}
