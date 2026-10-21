package rw.terimbere.csams.modules.rotation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;

public interface RotationPeriodRepository extends JpaRepository<RotationPeriod, UUID> {

    Optional<RotationPeriod> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<RotationPeriod> findByCycleIdAndCooperativeIdOrderBySequenceNumberAsc(UUID cycleId, UUID cooperativeId);

    List<RotationPeriod> findByCooperativeIdAndCycleIdInOrderByCycleIdAscSequenceNumberAsc(
            UUID cooperativeId, Collection<UUID> cycleIds);

    /** Periods of the scheme that currently hold one of the given months (live slots only). */
    List<RotationPeriod> findByCooperativeIdAndSlotKeyIn(UUID cooperativeId, Collection<String> slotKeys);

    boolean existsByCycleIdAndScheduledRecipientUserId(UUID cycleId, UUID scheduledRecipientUserId);

    List<RotationPeriod> findByCooperativeIdOrderByPeriodYearAscPeriodMonthAsc(UUID cooperativeId);

    List<RotationPeriod> findByCooperativeIdAndScheduledRecipientUserIdOrderByPeriodYearAscPeriodMonthAsc(
            UUID cooperativeId, UUID scheduledRecipientUserId);
}
