package rw.terimbere.csams.modules.rotation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.rotation.entity.RotationCycle;

public interface RotationCycleRepository extends JpaRepository<RotationCycle, UUID> {

    Optional<RotationCycle> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<RotationCycle> findByCooperativeIdOrderByCycleNumberDesc(UUID cooperativeId);

    Optional<RotationCycle> findByCooperativeIdAndIdempotencyKey(UUID cooperativeId, String idempotencyKey);

    @Query("SELECT COALESCE(MAX(c.cycleNumber), 0) FROM RotationCycle c WHERE c.cooperativeId = :cooperativeId")
    int maxCycleNumber(@Param("cooperativeId") UUID cooperativeId);
}
