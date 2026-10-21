package rw.terimbere.csams.modules.memberpayment.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentReversal;

public interface MemberPaymentReversalRepository extends JpaRepository<MemberPaymentReversal, UUID> {

    Optional<MemberPaymentReversal> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MemberPaymentReversal r where r.id = :id and r.cooperativeId = :cooperativeId")
    Optional<MemberPaymentReversal> findByIdAndCooperativeIdForUpdate(
            @Param("id") UUID id, @Param("cooperativeId") UUID cooperativeId);

    List<MemberPaymentReversal> findByCooperativeIdAndPaymentIdOrderByRequestedAtDesc(UUID cooperativeId, UUID paymentId);

    List<MemberPaymentReversal> findByCooperativeIdOrderByRequestedAtDesc(UUID cooperativeId);
}
