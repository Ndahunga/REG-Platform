package rw.terimbere.csams.modules.rotation.reconciliation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegacyReconciliationEventRepository extends JpaRepository<LegacyReconciliationEvent, UUID> {

    List<LegacyReconciliationEvent> findByCooperativeIdAndReconciliationIdOrderByCreatedAtAsc(
            UUID cooperativeId, UUID reconciliationId);
}
