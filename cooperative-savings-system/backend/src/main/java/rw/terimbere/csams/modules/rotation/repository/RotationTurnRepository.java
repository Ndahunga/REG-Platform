package rw.terimbere.csams.modules.rotation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.rotation.entity.RotationTurn;

public interface RotationTurnRepository extends JpaRepository<RotationTurn, UUID> {

    Optional<RotationTurn> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<RotationTurn> findByCooperativeIdAndCycleId(UUID cooperativeId, UUID cycleId);

    List<RotationTurn> findByCooperativeIdAndCycleIdIn(UUID cooperativeId, Collection<UUID> cycleIds);

    List<RotationTurn> findByCooperativeIdAndRotationPeriodId(UUID cooperativeId, UUID periodId);

    List<RotationTurn> findByCooperativeId(UUID cooperativeId);

    List<RotationTurn> findByCooperativeIdAndMemberUserId(UUID cooperativeId, UUID memberUserId);
}
