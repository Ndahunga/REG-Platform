package rw.terimbere.csams.modules.rotation.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.rotation.entity.RotationRecipientChange;

public interface RotationRecipientChangeRepository extends JpaRepository<RotationRecipientChange, UUID> {

    List<RotationRecipientChange> findByRotationPeriodIdAndCooperativeIdOrderByChangedAtAsc(
            UUID rotationPeriodId, UUID cooperativeId);
}
