package rw.terimbere.csams.modules.rotation.reconciliation;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegacyReconciliationRepository extends JpaRepository<LegacyRotatingReconciliation, UUID> {

    Optional<LegacyRotatingReconciliation> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LegacyRotatingReconciliation r where r.id = :id and r.cooperativeId = :cooperativeId")
    Optional<LegacyRotatingReconciliation> findByIdForUpdate(
            @Param("id") UUID id, @Param("cooperativeId") UUID cooperativeId);

    List<LegacyRotatingReconciliation> findByCooperativeIdOrderByRequestedAtDesc(UUID cooperativeId);

    List<LegacyRotatingReconciliation> findByCooperativeIdAndStatus(UUID cooperativeId, LegacyReconciliationStatus status);

    List<LegacyRotatingReconciliation> findByCooperativeIdAndRotationPeriodIdAndStatus(
            UUID cooperativeId, UUID rotationPeriodId, LegacyReconciliationStatus status);

    Optional<LegacyRotatingReconciliation> findByCooperativeIdAndReferenceKey(UUID cooperativeId, String referenceKey);
}
